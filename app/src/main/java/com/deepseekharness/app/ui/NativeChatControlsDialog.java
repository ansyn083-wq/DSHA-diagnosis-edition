package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatControlsRpc;
import com.deepseekharness.app.util.NativeChatControlsRpc.Command;
import com.deepseekharness.app.util.NativeChatControlsRpc.Feedback;
import com.deepseekharness.app.util.NativeChatControlsRpc.Goal;
import com.deepseekharness.app.util.NativeChatControlsRpc.Permissions;
import com.deepseekharness.app.util.NativeChatControlsRpc.Preset;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** 会话绑定的原生控制窗口；列表没有副作用，写操作均由明确的人工确认发起。 */
final class NativeChatControlsDialog implements DefaultLifecycleObserver {
  private static final Map<ChatHomeActivity, NativeChatControlsDialog> OPEN = new WeakHashMap<>();
  private final ChatHomeActivity host;
  private final String sessionId;
  private final String messageId;
  private final BooleanSupplier current;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final NativeChatControlsRpc.RequestGate gate = new NativeChatControlsRpc.RequestGate();
  private final List<View> controls = new ArrayList<>();
  private final List<View> mutationControls = new ArrayList<>();
  private final String fixedAuthUrl;
  private final String fixedCookie;
  private final LinearLayout content;
  private final TextView notice;
  private final AlertDialog dialog;
  private AlertDialog confirmation;
  private boolean busy, stale, expired;
  private String screen = "commands";
  private final Runnable identityCheck =
      new Runnable() {
        @Override
        public void run() {
          if (!dialog.isShowing()) return;
          if (!current.getAsBoolean()) {
            expire();
            return;
          }
          main.postDelayed(this, 500);
        }
      };

  static void show(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      BooleanSupplier current) {
    open(host, authUrl, cookie, sessionId, null, current);
  }

  /** messageId 必须来自 assistant 消息的持久 ID，不能传事件 seq。 */
  static void feedback(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      String messageId,
      BooleanSupplier current) {
    if (messageId == null || messageId.isBlank()) return;
    open(host, authUrl, cookie, sessionId, messageId, current);
  }

  private static void open(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      String messageId,
      BooleanSupplier current) {
    if (host.isFinishing()
        || host.isDestroyed()
        || sessionId == null
        || sessionId.isBlank()
        || !current.getAsBoolean()
        || OPEN.containsKey(host)) return;
    new NativeChatControlsDialog(host, authUrl, cookie, sessionId, messageId, current);
  }

  private NativeChatControlsDialog(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      String messageId,
      BooleanSupplier current) {
    this.host = host;
    this.sessionId = sessionId;
    this.messageId = messageId;
    this.current = current;
    fixedAuthUrl = authUrl;
    fixedCookie = cookie;
    LinearLayout body = vertical();
    body.setPadding(dp(20), dp(8), dp(20), dp(16));
    text(body, t("仅操作当前会话：", "Current conversation only: ") + sessionId, 12, false);
    notice = text(body, "", 14, false);
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    notice.setVisibility(View.GONE);
    content = vertical();
    body.addView(content, row());
    ScrollView scroll = new ScrollView(host);
    scroll.setFillViewport(true);
    scroll.addView(body);
    dialog =
        new DshaDialogBuilder(host)
            .setTitle(
                messageId == null ? t("会话工具", "Conversation tools") : t("消息反馈", "Message feedback"))
            .setView(scroll)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    dialog.setOnDismissListener(
        ignored -> {
          gate.close();
          if (confirmation != null) confirmation.dismiss();
          main.removeCallbacksAndMessages(null);
          worker.shutdownNow();
          host.getLifecycle().removeObserver(this);
          if (OPEN.get(host) == this) OPEN.remove(host);
        });
    OPEN.put(host, this);
    dialog.show();
    secure(dialog);
    host.getLifecycle().addObserver(this);
    if (messageId != null) screen = "feedback";
    refresh();
    main.post(identityCheck);
  }

  private void chrome() {
    content.removeAllViews();
    controls.clear();
    mutationControls.clear();
    if (messageId == null) {
      button(content, t("斜杠命令", "Slash commands"), false, () -> navigate("commands"));
      button(content, t("目标面板", "Goal panel"), false, () -> navigate("goal"));
      button(content, t("权限预设", "Permission presets"), false, () -> navigate("permissions"));
    }
    button(content, t("刷新核对", "Refresh and check"), false, this::refresh);
  }

  private void navigate(String target) {
    if (busy || confirmation != null || expired) return;
    screen = target;
    refresh();
  }

  private void refresh() {
    run(
        false,
        rpc ->
            switch (screen) {
              case "goal" -> {
                Goal goal = rpc.goal();
                yield () -> showGoal(goal);
              }
              case "permissions" -> {
                Permissions permissions = rpc.permissions();
                yield () -> showPermissions(permissions);
              }
              case "feedback" -> {
                Feedback feedback = rpc.feedback(messageId);
                yield () -> showFeedback(feedback);
              }
              default -> {
                List<Command> commands = rpc.commands();
                yield () -> showCommands(commands);
              }
            });
  }

  private void showCommands(List<Command> commands) {
    chrome();
    text(
        content,
        t(
            "选择命令后可检查参数，再确认执行。命令可能修改会话、文件或权限。",
            "Choose a command, review its arguments, then confirm. Commands can change the conversation, files, or permissions."),
        14,
        false);
    if (commands.isEmpty())
      text(
          content, t("当前会话没有可用命令。", "No commands are available for this conversation."), 15, false);
    for (Command command : commands) {
      text(content, "/" + command.name() + "\n" + command.description(), 15, true);
      button(
          content,
          t("查看命令", "Review command"),
          false,
          () -> {
            if (NativeChatControlsRpc.PERMISSION_COMMAND.equals(command.definitionId()))
              navigate("permissions");
            else commandEditor(command);
          });
    }
  }

  private void commandEditor(Command command) {
    if (busy || expired) return;
    chrome();
    text(content, "/" + command.name(), 18, true);
    text(content, command.description(), 14, false);
    EditText input = command.hasInput() ? field(content, command.hint(), "", false) : null;
    text(
        content,
        t(
            "此入口不附加聊天输入框中的文件。命令执行结果会显示在这里。",
            "Files in the chat composer are not attached here. The command result appears in this panel."),
        13,
        false);
    button(
        content,
        t("检查并执行", "Review and run"),
        true,
        () -> {
          String args = input == null ? "" : input.getText().toString();
          String line = "/" + command.name() + (args.isEmpty() ? "" : " " + args);
          confirm(
              t("执行此命令？", "Run this command?"),
              command.description()
                  + "\n\n"
                  + line
                  + "\n\n"
                  + t(
                      "会在上方指定会话中执行，可能修改数据或触发工作。只执行一次；结果不明时请先核对。",
                      "This runs in the conversation shown above and may change data or start work. It is sent once; check the outcome before retrying."),
              () ->
                  run(
                      true,
                      rpc -> {
                        var result = rpc.execute(command, args);
                        return () ->
                            showNotice(
                                (result.success()
                                        ? t("命令已返回：", "Command returned: ")
                                        : t("命令未成功：", "Command did not succeed: "))
                                    + (result.text().isBlank()
                                        ? t("已完成。", "Completed.")
                                        : result.text()));
                      }));
        });
    updateControls();
  }

  private void showGoal(Goal goal) {
    chrome();
    if (goal == null) {
      text(content, t("当前会话尚无目标。", "This conversation has no goal."), 15, false);
      button(content, t("创建目标", "Create goal"), false, () -> goalEditor(null));
      return;
    }
    text(content, goal.objective(), 18, true);
    text(
        content,
        t("状态：", "Status: ")
            + phase(goal.phase())
            + " · "
            + (goal.activation().equals("armed")
                ? t("允许自动继续", "Automatic continuation armed")
                : t("自动继续未启用", "Automatic continuation disarmed")),
        14,
        false);
    text(
        content,
        t("已启动轮次 / 总上限：", "Rounds started / total limit: ")
            + goal.roundsStarted()
            + " / "
            + goal.maxRounds(),
        14,
        false);
    if (!goal.blockedReason().isBlank()) text(content, goal.blockedReason(), 14, false);
    text(
        content,
        t(
            "暂停、完成或清除会阻止后续目标自动续跑，不会撤回已开始的工作。",
            "Pausing, completing, or clearing prevents future automatic goal rounds. It does not recall work already started."),
        13,
        false);
    button(content, t("编辑目标", "Edit goal"), false, () -> goalEditor(goal));
    if (goal.phase().equals("active"))
      goalButton(
          goal,
          "pause",
          t("暂停目标", "Pause goal"),
          t(
              "停止后续自动续跑。正在执行的工作不会撤回。",
              "Disarm future automatic continuation. Work already running is not recalled."));
    if (!goal.phase().equals("complete")
        && goal.roundsStarted() < goal.maxRounds()
        && !(goal.phase().equals("active") && goal.activation().equals("armed"))) {
      goalButton(
          goal,
          "resume",
          t("继续目标", "Resume goal"),
          t(
              "重新启用自动续跑，可能继续调用模型及工具并产生费用，受总轮数上限约束。",
              "Rearm automatic continuation. It may call models and tools and incur costs, within the total round limit."));
    }
    if (!goal.phase().equals("complete"))
      goalButton(
          goal,
          "complete",
          t("标记完成", "Mark complete"),
          t(
              "把目标标记为完成并停止后续自动续跑。此目标之后不能直接恢复。",
              "Mark this goal complete and stop future automatic continuation. A completed goal cannot be resumed."));
    else button(content, t("创建新目标", "Create another goal"), false, () -> goalEditor(null));
    goalButton(
        goal,
        "clear",
        t("清除当前目标", "Clear current goal"),
        t(
            "移除当前目标并停止后续自动续跑。历史记录会保留，已开始的工作不会撤回。",
            "Remove the current goal and disarm future automatic continuation. History remains, and work already started is not recalled."));
  }

  private void goalButton(Goal goal, String action, String label, String consequence) {
    button(
        content,
        label,
        true,
        () ->
            confirm(
                label + "？",
                goal.objective() + "\n\n" + consequence,
                () ->
                    run(
                        true,
                        rpc -> {
                          rpc.changeGoal(goal, action);
                          Goal updated = rpc.goal();
                          return () -> {
                            showGoal(updated);
                            showNotice(t("目标已更新。", "Goal updated."));
                          };
                        })));
  }

  private void goalEditor(Goal goal) {
    if (busy || expired) return;
    chrome();
    text(content, goal == null ? t("创建目标", "Create goal") : t("编辑目标", "Edit goal"), 18, true);
    EditText objective =
        field(content, t("目标内容", "Objective"), goal == null ? "" : goal.objective(), false);
    EditText rounds =
        field(
            content,
            t("自动续跑总轮数上限", "Total automatic continuation round limit"),
            goal == null ? "10" : Long.toString(goal.maxRounds()),
            true);
    text(
        content,
        t(
            "创建目标会启用自动续跑，可能调用模型和工具并产生费用。编辑会保留当前状态。",
            "Creating a goal arms automatic continuation and may call models and tools and incur costs. Editing keeps its current phase."),
        14,
        false);
    button(
        content,
        t("检查并保存", "Review and save"),
        true,
        () -> {
          String value = objective.getText().toString().strip();
          long limit;
          if (value.isBlank()) {
            objective.setError(t("请输入目标。", "Enter an objective."));
            return;
          }
          try {
            limit = Long.parseLong(rounds.getText().toString().strip());
            if (limit < 1 || limit > 9007199254740991L) throw new NumberFormatException();
          } catch (NumberFormatException invalid) {
            rounds.setError(t("请输入有效的正整数。", "Enter a valid positive integer."));
            return;
          }
          String consequence =
              goal == null
                  ? t("这会创建并启用目标自动续跑。", "This creates and arms automatic goal continuation.")
                  : t(
                      "这会修改目标内容与轮数上限，保留当前运行状态。",
                      "This changes the objective and round limit while keeping its current phase.");
          confirm(
              t("保存此目标？", "Save this goal?"),
              value + "\n\n" + t("总轮数上限：", "Total round limit: ") + limit + "\n\n" + consequence,
              () ->
                  run(
                      true,
                      rpc -> {
                        if (goal == null) rpc.createGoal(value, limit);
                        else rpc.editGoal(goal, value, limit);
                        Goal updated = rpc.goal();
                        return () -> {
                          showGoal(updated);
                          showNotice(t("目标已保存。", "Goal saved."));
                        };
                      }));
        });
    updateControls();
  }

  private void showPermissions(Permissions snapshot) {
    chrome();
    text(
        content,
        t(
            "权限预设只影响当前会话，不修改新会话默认值。可能放宽沙箱和确认要求。",
            "A preset affects only this conversation, not defaults for future chats. It may relax sandbox and approval requirements."),
        14,
        false);
    if (snapshot.currentValue().isBlank() || snapshot.command() == null) {
      text(
          content,
          t("当前服务没有可用的权限设置入口。", "The current service has no available permission control."),
          15,
          false);
      return;
    }
    text(content, t("当前预设：", "Current preset: ") + snapshot.currentValue(), 16, true);
    for (Preset preset : snapshot.options()) {
      text(content, preset.name() + " (" + preset.value() + ")", 16, true);
      text(
          content,
          preset.description().isBlank()
              ? t(
                  "服务端未提供权限说明，为避免意外授权，此选项不可在这里应用。",
                  "The server provided no permission description. This option cannot be applied here safely.")
              : preset.description(),
          14,
          false);
      if (preset.value().equals(snapshot.currentValue())) continue;
      if (preset.description().isBlank()) continue;
      button(
          content,
          t("检查并选择", "Review and select"),
          true,
          () ->
              confirm(
                  t("更改当前会话权限？", "Change this conversation's permissions?"),
                  preset.name()
                      + " ("
                      + preset.value()
                      + ")\n\n"
                      + preset.description()
                      + "\n\n"
                      + t(
                          "此操作可能减少确认提示或放宽执行权限。请仅在理解以上服务端说明后确认。",
                          "This may reduce approval prompts or relax execution permissions. Confirm only after reviewing the server's description above."),
                  () ->
                      run(
                          true,
                          rpc -> {
                            var result = rpc.selectPermission(snapshot, preset);
                            if (!result.success())
                              return () -> {
                                stale = true;
                                showNotice(result.text());
                              };
                            Permissions updated = rpc.permissions();
                            return () -> {
                              showPermissions(updated);
                              showNotice(t("权限预设已更新。", "Permission preset updated."));
                            };
                          })));
    }
  }

  private void showFeedback(Feedback snapshot) {
    chrome();
    text(
        content,
        t(
            "反馈仅对应这条助手消息。备注会保存在此会话的反馈记录中，不作为模型对话输入。",
            "Feedback targets this assistant message only. Notes are saved in this conversation's feedback log, not sent as model conversation input."),
        14,
        false);
    RadioGroup ratings = new RadioGroup(host);
    RadioButton positive = rating(ratings, t("有帮助", "Helpful"));
    RadioButton negative = rating(ratings, t("没有帮助", "Not helpful"));
    content.addView(ratings, row());
    if (snapshot.rating().equals("positive")) ratings.check(positive.getId());
    else if (snapshot.rating().equals("negative")) ratings.check(negative.getId());
    EditText note = field(content, t("备注（可选）", "Note (optional)"), snapshot.note(), false);
    button(
        content,
        t("保存反馈", "Save feedback"),
        true,
        () -> {
          int checked = ratings.getCheckedRadioButtonId();
          if (checked != positive.getId() && checked != negative.getId()) {
            showNotice(t("请先选择评价。", "Choose a rating first."));
            return;
          }
          String rating = checked == positive.getId() ? "positive" : "negative";
          String noteText = note.getText().toString();
          confirm(
              t("保存这条消息的反馈？", "Save feedback for this message?"),
              (rating.equals("positive") ? t("有帮助", "Helpful") : t("没有帮助", "Not helpful"))
                  + (noteText.isBlank() ? "" : "\n\n" + noteText),
              () ->
                  run(
                      true,
                      rpc -> {
                        var result = rpc.putFeedback(snapshot, rating, noteText);
                        if (!result.success())
                          return () -> {
                            stale = true;
                            showNotice(feedbackError(result.code()));
                          };
                        Feedback updated = rpc.feedback(messageId);
                        return () -> {
                          showFeedback(updated);
                          showNotice(t("反馈已保存。", "Feedback saved."));
                        };
                      }));
        });
    if (snapshot.exists())
      button(
          content,
          t("移除当前反馈", "Remove current feedback"),
          true,
          () ->
              confirm(
                  t("移除此反馈？", "Remove this feedback?"),
                  t(
                      "这会移除当前评价和备注。早先的评价与备注仍保留在会话日志中。",
                      "This removes the current rating and note. Earlier ratings and notes remain in the conversation log."),
                  () ->
                      run(
                          true,
                          rpc -> {
                            var result = rpc.deleteFeedback(snapshot);
                            if (!result.success())
                              return () -> {
                                stale = true;
                                showNotice(feedbackError(result.code()));
                              };
                            Feedback updated = rpc.feedback(messageId);
                            return () -> {
                              showFeedback(updated);
                              showNotice(t("当前反馈已移除。", "Current feedback removed."));
                            };
                          })));
  }

  private void confirm(String title, String explanation, Runnable action) {
    if (busy
        || stale
        || expired
        || gate.uncertain()
        || confirmation != null
        || !current.getAsBoolean()) return;
    // 不启用 safetyConfirmation，权限预设和命令不能由全局自动确认代答。
    confirmation =
        new DshaDialogBuilder(host)
            .setTitle(title)
            .setMessage(t("会话：", "Conversation: ") + sessionId + "\n\n" + explanation)
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(
                t("确认", "Confirm"),
                (ignored, which) -> {
                  if (!expired && current.getAsBoolean()) action.run();
                  else expire();
                })
            .create();
    confirmation.setOnDismissListener(
        ignored -> {
          confirmation = null;
          updateControls();
        });
    confirmation.show();
    secure(confirmation);
    updateControls();
  }

  private interface Request {
    Runnable call(NativeChatControlsRpc rpc) throws IOException;
  }

  private void run(boolean mutation, Request request) {
    if (!dialog.isShowing() || expired || mutation && stale) return;
    if (!current.getAsBoolean()) {
      expire();
      return;
    }
    long ticket = gate.begin(mutation);
    if (ticket == 0) return;
    busy = true;
    notice.setVisibility(View.GONE);
    updateControls();
    worker.execute(
        () -> {
          Runnable result = null;
          try {
            if (!gate.current(ticket) || !current.getAsBoolean())
              throw new IOException("Session changed");
            // 每条 RPC 同时核对关闭票据和父级会话/服务身份，不允许对关闭窗口继续派发。
            NativeChatRpc guarded =
                new NativeChatRpc(
                    authUrl(), cookie(), () -> gate.current(ticket) && current.getAsBoolean());
            result = request.call(new NativeChatControlsRpc(guarded, sessionId));
          } catch (IOException | RuntimeException unavailable) {
            // 不重试不明的写请求，也不把鉴权信息或任意后端异常写入日志。
          }
          Runnable apply = result;
          main.post(
              () -> {
                boolean confirmed = apply != null && current.getAsBoolean();
                if (!gate.finish(ticket, confirmed) || !dialog.isShowing()) return;
                busy = false;
                if (!current.getAsBoolean()) {
                  expire();
                  return;
                }
                if (confirmed) {
                  if (!mutation) stale = false;
                  apply.run();
                } else {
                  if (mutation) stale = true;
                  if (controls.isEmpty()) chrome();
                  showNotice(
                      mutation
                          ? t(
                              "未能确认操作结果。请先刷新核对，再决定是否重新操作；不会自动重发。",
                              "Could not confirm the outcome. Refresh and check before deciding whether to try again. Nothing is retried automatically.")
                          : t(
                              "暂时无法读取。请刷新重试；若会话已切换，请关闭并重新打开。",
                              "Could not load this view. Refresh to try again, or reopen it if the conversation changed."));
                }
                updateControls();
              });
        });
  }

  // 构造时固定的鉴权地址和 cookie 不允许跨代刷新。仅内存存活，不持久化。
  private String authUrl() {
    return fixedAuthUrl;
  }

  private String cookie() {
    return fixedCookie;
  }

  private void expire() {
    if (expired) return;
    expired = true;
    busy = false;
    gate.close();
    if (confirmation != null) confirmation.dismiss();
    worker.shutdownNow();
    showNotice(
        t(
            "会话或服务已变化。此窗口已失效，请关闭后重新打开。",
            "The conversation or service changed. Close this window and reopen it."));
    updateControls();
  }

  private void updateControls() {
    boolean enabled = !busy && !expired && confirmation == null;
    for (View view : controls) view.setEnabled(enabled);
    for (View view : mutationControls) view.setEnabled(enabled && !stale && !gate.uncertain());
  }

  private void showNotice(String message) {
    notice.setText(message);
    notice.setVisibility(View.VISIBLE);
  }

  private RadioButton rating(RadioGroup group, String label) {
    RadioButton button = new RadioButton(host);
    button.setId(View.generateViewId());
    button.setText(label);
    button.setTextColor(host.getColor(R.color.gpt_text));
    button.setMinHeight(dp(48));
    group.addView(button);
    controls.add(button);
    return button;
  }

  private EditText field(LinearLayout parent, String label, String initial, boolean number) {
    TextView name = text(parent, label, 14, true);
    EditText field = new EditText(host);
    field.setId(View.generateViewId());
    name.setLabelFor(field.getId());
    field.setTextColor(host.getColor(R.color.gpt_text));
    field.setTextSize(15);
    field.setInputType(
        number
            ? InputType.TYPE_CLASS_NUMBER
            : InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setMinHeight(dp(48));
    field.setFilters(
        new InputFilter[] {
          new InputFilter.LengthFilter(number ? 16 : Math.max(65536, initial.length()))
        });
    field.setText(initial);
    parent.addView(field, row());
    controls.add(field);
    return field;
  }

  private Button button(LinearLayout parent, String label, boolean mutation, Runnable clicked) {
    MaterialButton button =
        new MaterialButton(
            host, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setTextColor(host.getColor(R.color.gpt_text));
    button.setStrokeColor(ColorStateList.valueOf(host.getColor(R.color.gpt_line)));
    button.setMinHeight(dp(48));
    button.setOnClickListener(
        ignored -> {
          if (!busy && !expired) clicked.run();
        });
    parent.addView(button, row());
    controls.add(button);
    if (mutation) mutationControls.add(button);
    return button;
  }

  private TextView text(LinearLayout parent, String value, int size, boolean bold) {
    TextView text = new TextView(host);
    text.setText(value);
    text.setTextColor(host.getColor(R.color.gpt_text));
    text.setTextSize(size);
    text.setPadding(0, dp(8), 0, dp(4));
    if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    text.setTextIsSelectable(true);
    parent.addView(text, row());
    return text;
  }

  private LinearLayout vertical() {
    LinearLayout layout = new LinearLayout(host);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private LinearLayout.LayoutParams row() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private static void secure(AlertDialog dialog) {
    if (dialog.getWindow() != null) {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
  }

  private static String feedbackError(String code) {
    return switch (code) {
      case "version-conflict" ->
          t(
              "反馈已在其他位置更改。请刷新核对，不会覆盖新内容。",
              "Feedback changed elsewhere. Refresh and review; newer content was not overwritten.");
      case "target-not-found" ->
          t(
              "此消息不属于当前会话，或不是可评价的助手消息。",
              "This is not an eligible assistant message owned by this conversation.");
      case "note-too-large" ->
          t(
              "备注超过服务端限制。请缩短后刷新核对。",
              "The note exceeds the server limit. Shorten it and refresh to review.");
      default ->
          t("反馈未保存。请刷新核对后再操作。", "Feedback was not saved. Refresh and check before trying again.");
    };
  }

  private static String phase(String value) {
    return switch (value) {
      case "active" -> t("进行中", "Active");
      case "paused" -> t("已暂停", "Paused");
      case "blocked" -> t("受阻", "Blocked");
      default -> t("已完成", "Complete");
    };
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    dialog.dismiss();
  }
}
