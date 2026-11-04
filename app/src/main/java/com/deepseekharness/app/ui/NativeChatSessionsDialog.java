package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.NativeChatRpc.Session;
import com.deepseekharness.app.util.NativeChatSessionsRpc;
import com.deepseekharness.app.util.NativeChatSessionsRpc.SearchResult;
import com.deepseekharness.app.util.NativeChatWorkspaceSnapshot;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;

/** 原生全文搜索、重命名、归档/恢复及分支入口，归档从不隐式停止任务。 */
final class NativeChatSessionsDialog implements DefaultLifecycleObserver {
  private static final OkHttpClient SOCKETS = new OkHttpClient();
  private final ChatHomeActivity host;
  private final String authUrl;
  private final String cookie;
  private final String selected;
  private final BooleanSupplier current;
  private final Consumer<String> select;
  private final Runnable onChanged;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newFixedThreadPool(2);
  private final NativeChatSessionsRpc.RequestGate gate = new NativeChatSessionsRpc.RequestGate();
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AlertDialog dialog;
  private final EditText query;
  private final CheckBox archivedOnly;
  private final Button refresh;
  private final TextView notice;
  private final LinearLayout rows;
  private final List<View> rowControls = new ArrayList<>();
  private final Runnable searchLater = this::load;
  private AlertDialog child;
  private List<Session> sessions = List.of();
  private Set<String> archived = Set.of();
  private SearchResult matches;
  private boolean loaded;
  private boolean writing;
  private boolean uncertain;
  private int shown = 40;

  static void show(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String selected,
      BooleanSupplier current,
      Consumer<String> select,
      Runnable onChanged) {
    new NativeChatSessionsDialog(host, authUrl, cookie, selected, current, select, onChanged);
  }

  private NativeChatSessionsDialog(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String selected,
      BooleanSupplier current,
      Consumer<String> select,
      Runnable onChanged) {
    this.host = host;
    this.authUrl = authUrl;
    this.cookie = cookie;
    this.selected = selected;
    this.current = current;
    this.select = select;
    this.onChanged = onChanged;
    LinearLayout body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(16));
    text(
        body,
        t(
            "搜索消息正文。归档不会删除记录，也不会停止正在运行的任务。",
            "Search message content. Archiving keeps the history and never stops running work."),
        false);
    query = new EditText(host);
    query.setSingleLine(true);
    query.setHint(t("搜索消息正文", "Search message content"));
    query.setTextColor(host.getColor(R.color.gpt_text));
    query.setHintTextColor(host.getColor(R.color.gpt_muted));
    query.setMinHeight(dp(48));
    query.setSaveEnabled(false);
    query.setFilters(new InputFilter[] {new InputFilter.LengthFilter(500)});
    body.addView(query, row());
    archivedOnly = new CheckBox(host);
    archivedOnly.setText(t("仅显示已归档会话", "Show archived chats"));
    archivedOnly.setMinHeight(dp(48));
    archivedOnly.setTextColor(host.getColor(R.color.gpt_text));
    body.addView(archivedOnly, row());
    refresh = button(body, t("刷新", "Refresh"), this::load);
    notice = text(body, "", false);
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    rows = column();
    body.addView(rows, row());
    ScrollView scroll = new ScrollView(host);
    scroll.setFillViewport(true);
    scroll.addView(body);
    dialog =
        new DshaDialogBuilder(host)
            .setTitle(t("会话管理", "Manage chats"))
            .setView(scroll)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    dialog.setOnDismissListener(
        ignored -> {
          open.set(false);
          gate.close();
          if (child != null) child.dismiss();
          main.removeCallbacksAndMessages(null);
          worker.shutdownNow();
          host.getLifecycle().removeObserver(this);
        });
    query.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence value, int start, int before, int count) {
            if (writing) return;
            gate.beginRead(); // 输入一改变就使旧结果失效，而非等防抖结束。
            loaded = false;
            matches = null;
            shown = 40;
            render();
            main.removeCallbacks(searchLater);
            main.postDelayed(searchLater, 300);
          }

          @Override
          public void afterTextChanged(Editable value) {}
        });
    archivedOnly.setOnCheckedChangeListener(
        (button, checked) -> {
          shown = 40;
          render();
        });
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    host.getLifecycle().addObserver(this);
    load();
  }

  private boolean active() {
    return open.get() && current.getAsBoolean();
  }

  private boolean valid(long ticket) {
    return active() && gate.current(ticket);
  }

  private void load() {
    main.removeCallbacks(searchLater);
    if (!active() || writing) return;
    long ticket = gate.beginRead();
    if (ticket == 0) return;
    String term = query.getText().toString().strip();
    loaded = false;
    render();
    notice.setText(t("正在读取会话…", "Reading chats…"));
    worker.execute(
        () -> {
          try {
            NativeChatRpc rpc = new NativeChatRpc(authUrl, cookie, () -> valid(ticket));
            List<Session> listed = rpc.listSessions();
            Set<String> archiveSet =
                NativeChatWorkspaceSnapshot.read(SOCKETS, authUrl, cookie, () -> valid(ticket));
            SearchResult result =
                term.isEmpty() ? null : new NativeChatSessionsRpc(rpc).search(term);
            main.post(
                () -> {
                  if (!active() || !gate.finish(ticket)) return;
                  sessions = listed;
                  archived = archiveSet;
                  matches = result;
                  loaded = true;
                  uncertain = false;
                  render();
                  notice.setText(
                      result != null && result.hasMore()
                          ? t(
                              "仅显示部分结果，请输入更具体的关键词。",
                              "Showing limited results. Refine your search for more.")
                          : rows.getChildCount() == 0 ? t("没有匹配的会话", "No matching chats") : "");
                });
          } catch (IOException | RuntimeException failure) {
            main.post(
                () -> {
                  if (!active() || !gate.finish(ticket)) return;
                  loaded = false;
                  render();
                  notice.setText(
                      t(
                          "暂时无法读取会话和归档状态，请刷新重试。",
                          "Could not read chats and archive state. Refresh to try again."));
                });
          }
        });
  }

  private void render() {
    rows.removeAllViews();
    rowControls.clear();
    refresh.setEnabled(!writing);
    query.setEnabled(!writing);
    archivedOnly.setEnabled(!writing);
    if (!loaded && !writing) return;
    Map<String, Session> known = new HashMap<>();
    for (Session session : sessions) known.put(session.id(), session);
    List<Session> display = sessions;
    Map<String, String> snippets = new HashMap<>();
    if (matches != null) {
      display = new ArrayList<>();
      for (var item : matches.items()) {
        Session session = known.get(item.sessionId());
        if (session == null) session = new Session(item.sessionId(), "", 0);
        display.add(session);
        snippets.put(item.sessionId(), item.snippet());
      }
    }
    int count = 0;
    for (Session session : display) {
      if (archived.contains(session.id()) != archivedOnly.isChecked()) continue;
      if (++count > shown) continue;
      LinearLayout card = column();
      var params = row();
      params.topMargin = dp(12);
      rows.addView(card, params);
      String title = title(session);
      Button openChat =
          button(
              card,
              session.id().equals(selected) ? title + t("（当前）", " (current)") : title,
              () -> choose(session.id()));
      rowControls.add(openChat);
      String snippet = snippets.get(session.id());
      if (snippet != null && !snippet.isEmpty()) {
        TextView preview = text(card, snippet, false);
        preview.setMaxLines(4);
      }
      Button actions = button(card, t("管理此会话", "Chat actions"), () -> actions(session));
      rowControls.add(actions);
    }
    if (count > shown) {
      Button more =
          button(
              rows,
              t("显示更多", "Show more"),
              () -> {
                shown += 40;
                render();
              });
      rowControls.add(more);
    }
    for (View control : rowControls) control.setEnabled(!writing && !uncertain && active());
  }

  private String title(Session session) {
    return session.title().isBlank()
        ? t("未命名会话", "Untitled chat") + " · " + session.id()
        : session.title();
  }

  private void choose(String id) {
    if (!active() || writing || uncertain) return;
    dialog.dismiss();
    select.accept(id);
  }

  private void actions(Session session) {
    if (!active() || writing || uncertain) return;
    if (child != null) child.dismiss();
    boolean isArchived = archived.contains(session.id());
    String[] choices = {
      t("重命名", "Rename"),
      isArchived ? t("恢复会话", "Restore chat") : t("归档会话", "Archive chat"),
      t("从最近完成的回合分支", "Branch from the last completed turn")
    };
    child =
        new DshaDialogBuilder(host)
            .setTitle(title(session))
            .setItems(
                choices,
                (ignored, which) -> {
                  if (!active() || writing || uncertain) return;
                  switch (which) {
                    case 0 -> rename(session);
                    case 1 -> archive(session, isArchived);
                    case 2 -> fork(session);
                    default -> {}
                  }
                })
            .setNegativeButton(t("取消", "Cancel"), null)
            .create();
    child.show();
  }

  private void rename(Session session) {
    LinearLayout body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(8));
    EditText name = new EditText(host);
    name.setSingleLine(true);
    name.setMinHeight(dp(48));
    name.setText(session.title());
    name.setHint(t("会话名称", "Chat title"));
    name.setSaveEnabled(false);
    body.addView(name, row());
    ScrollView scroll = new ScrollView(host);
    scroll.addView(body);
    AlertDialog editor =
        new DshaDialogBuilder(host)
            .setTitle(t("重命名会话", "Rename chat"))
            .setView(scroll)
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(t("保存", "Save"), null)
            .create();
    child = editor;
    editor.show();
    editor
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            view -> {
              String proposed = name.getText().toString().strip();
              if (proposed.isEmpty()) {
                name.setError(t("请输入名称", "Enter a title"));
                return;
              }
              if (!active() || writing || uncertain) return;
              editor.dismiss();
              List<Session> before = sessions;
              mutate(
                  () -> replaceTitle(session.id(), proposed),
                  () -> sessions = before,
                  rpc -> {
                    String accepted = rpc.rename(session.id(), proposed);
                    return () -> replaceTitle(session.id(), accepted);
                  },
                  t("会话名称已更新", "Chat renamed"));
            });
  }

  private void replaceTitle(String id, String title) {
    List<Session> changed = new ArrayList<>();
    boolean found = false;
    for (Session session : sessions) {
      if (session.id().equals(id)) {
        changed.add(new Session(id, title, session.updatedAt()));
        found = true;
      } else changed.add(session);
    }
    if (!found) changed.add(new Session(id, title, 0));
    sessions = List.copyOf(changed);
  }

  private void archive(Session session, boolean restore) {
    Set<String> before = archived;
    mutate(
        () -> {
          Set<String> provisional = new LinkedHashSet<>(archived);
          if (restore) provisional.remove(session.id());
          else provisional.add(session.id());
          archived = Set.copyOf(provisional);
        },
        () -> archived = before,
        rpc -> {
          Set<String> result = restore ? rpc.restore(session.id()) : rpc.archive(session.id());
          return () -> archived = result;
        },
        restore ? t("会话已恢复", "Chat restored") : t("会话已归档", "Chat archived"));
  }

  private void fork(Session session) {
    mutate(
        () -> {},
        () -> {},
        rpc -> {
          String id = rpc.forkLatestCompleted(session.id());
          return () -> {
            dialog.dismiss();
            select.accept(id);
          };
        },
        t("已创建会话分支", "Chat branch created"));
  }

  private interface Mutation {
    Runnable call(NativeChatSessionsRpc rpc) throws IOException;
  }

  private void mutate(Runnable optimistic, Runnable rollback, Mutation request, String success) {
    if (!active() || writing || uncertain || !loaded) return;
    long ticket = gate.beginMutation();
    if (ticket == 0) return;
    main.removeCallbacks(searchLater);
    writing = true;
    optimistic.run();
    render();
    notice.setText(t("正在保存…", "Saving…"));
    worker.execute(
        () -> {
          try {
            NativeChatRpc guarded = new NativeChatRpc(authUrl, cookie, () -> valid(ticket));
            Runnable result = request.call(new NativeChatSessionsRpc(guarded));
            main.post(
                () -> {
                  if (!active() || !gate.finish(ticket)) return;
                  writing = false;
                  result.run();
                  onChanged.run();
                  if (!open.get()) return;
                  render();
                  notice.setText(success);
                });
          } catch (IOException | RuntimeException failure) {
            String code = failure.getMessage() == null ? "" : failure.getMessage();
            main.post(
                () -> {
                  if (!active() || !gate.finish(ticket)) return;
                  writing = false;
                  rollback.run();
                  // 明确服务端拒绝可直接纠正；网络/格式错误不能证明写请求没有执行。
                  uncertain = !code.startsWith("DSH gateway rejected request:");
                  render();
                  notice.setText(
                      code.contains("workspace/session-active")
                          ? t(
                              "该会话仍有运行中的任务，未归档。请先处理任务后重试。",
                              "This chat has active work and was not archived. Finish that work first.")
                          : code.contains("session/fork-unavailable")
                              ? t(
                                  "此会话还没有可用于分支的已完成回合。",
                                  "This chat has no completed turn to branch from.")
                              : code.contains("session/migration-required")
                                  ? t(
                                      "请先打开此会话完成升级，再创建分支。",
                                      "Open this chat to finish migration before branching.")
                                  : uncertain
                                      ? t(
                                          "操作结果未确认，未自动重试。请先刷新核对。",
                                          "The result is unconfirmed. No retry was sent. Refresh before trying again.")
                                      : t(
                                          "服务拒绝了此操作，请刷新后重试。",
                                          "The service rejected this change. Refresh and try again."));
                  onChanged.run();
                });
          }
        });
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    dialog.dismiss();
  }

  private LinearLayout column() {
    LinearLayout result = new LinearLayout(host);
    result.setOrientation(LinearLayout.VERTICAL);
    return result;
  }

  private TextView text(LinearLayout parent, String value, boolean bold) {
    TextView result = new TextView(host);
    result.setText(value);
    result.setTextSize(14);
    result.setTextColor(host.getColor(bold ? R.color.gpt_text : R.color.gpt_muted));
    if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    parent.addView(result, row());
    return result;
  }

  private Button button(LinearLayout parent, String title, Runnable action) {
    MaterialButton result = new MaterialButton(host);
    result.setText(title);
    result.setAllCaps(false);
    result.setMinHeight(dp(48));
    result.setCornerRadius(dp(12));
    result.setBackgroundTintList(ColorStateList.valueOf(host.getColor(R.color.gpt_raised)));
    result.setTextColor(host.getColor(R.color.gpt_text));
    result.setOnClickListener(view -> action.run());
    var params = row();
    params.topMargin = dp(6);
    parent.addView(result, params);
    return result;
  }

  private LinearLayout.LayoutParams row() {
    return new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
