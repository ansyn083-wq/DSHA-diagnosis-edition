package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatButton;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatRpc.Session;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class NativeChatSidebar {
  private final ChatHomeActivity host;
  private final EditText filter;
  private final LinearLayout history;
  private final TextView status;
  private List<Session> sessions = List.of();
  private String selected = "";
  private String notice = "";
  private boolean canSwitch;
  private final Consumer<String> selectSession;

  NativeChatSidebar(
      ChatHomeActivity host,
      Consumer<String> selectSession,
      Runnable manageSessions,
      Runnable manageSchedules,
      Runnable controls,
      Runnable files) {
    this.host = host;
    this.selectSession = selectSession;
    filter = host.findViewById(R.id.native_chat_filter);
    history = host.findViewById(R.id.native_chat_history_rows);
    status = host.findViewById(R.id.native_chat_history_status);
    ((TextView) host.findViewById(R.id.native_chat_history_label))
        .setText(UiText.choose("历史会话", "Chats"));
    View search = host.findViewById(R.id.native_chat_search);
    search.setContentDescription(UiText.choose("搜索会话", "Search chats"));
    search.setOnClickListener(v -> toggleSearch());
    filter.setHint(search.getContentDescription());
    filter.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            renderHistory();
          }

          @Override
          public void afterTextChanged(Editable value) {}
        });
    ((Button) host.findViewById(R.id.native_chat_new))
        .setText(UiText.choose("＋ 新对话", "＋ New chat"));
    View close = host.findViewById(R.id.native_chat_sidebar_close);
    close.setContentDescription(UiText.choose("DSHA，关闭侧栏", "DSHA, close sidebar"));
    LinearLayout features = host.findViewById(R.id.native_chat_feature_rows);
    Button settings = row(UiText.choose("设置", "Settings"), false);
    settings.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
    settings.setCompoundDrawableTintList(ColorStateList.valueOf(host.getColor(R.color.gpt_text)));
    settings.setCompoundDrawablePadding(dp(12));
    settings.setOnClickListener(
        v -> {
          host.closeChatOverlay();
          host.settings();
        });
    features.addView(settings);
    Button sessions = row(UiText.choose("管理与全文搜索会话", "Manage and search chats"), false);
    sessions.setOnClickListener(
        v -> {
          host.closeChatOverlay();
          manageSessions.run();
        });
    features.addView(sessions);
    Button schedules = row(UiText.choose("定时任务", "Scheduled tasks"), false);
    schedules.setOnClickListener(
        v -> {
          host.closeChatOverlay();
          manageSchedules.run();
        });
    features.addView(schedules);
    Button advanced = row(UiText.choose("／命令、目标与权限", "/ Commands, goals and permissions"), false);
    advanced.setOnClickListener(
        v -> {
          host.closeChatOverlay();
          controls.run();
        });
    features.addView(advanced);
    Button fileTree = row(UiText.choose("对话文件与预览", "Chat files and previews"), false);
    fileTree.setOnClickListener(
        v -> {
          host.closeChatOverlay();
          files.run();
        });
    features.addView(fileTree);
    for (HomeFeatureMenu.Feature feature : HomeFeatureMenu.Feature.values()) {
      Button row = row(feature.title(), false);
      int icon =
          switch (feature) {
            case WORKSPACE -> R.drawable.ic_ui2_folder;
            case PLUGINS -> R.drawable.ic_plugins;
            case TERMINAL -> R.drawable.ic_terminal;
            case BACKGROUND_TASKS -> R.drawable.ic_recovery_clock;
            case AGENT_TASKS -> R.drawable.ic_ui_tasks;
            case RUNTIME -> R.drawable.ic_ui2_box;
            case DIAGNOSTICS -> R.drawable.ic_recovery_wrench;
            case STARTUP -> R.drawable.ic_recovery_document;
          };
      row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
      row.setCompoundDrawableTintList(ColorStateList.valueOf(host.getColor(R.color.gpt_text)));
      row.setCompoundDrawablePadding(dp(12));
      row.setOnClickListener(
          v -> {
            host.closeChatOverlay();
            host.startActivity(feature.intent(host));
          });
      features.addView(row);
    }
  }

  void update(List<Session> sessions, String selected, boolean enabled, String notice) {
    if (this.sessions.equals(sessions)
        && this.selected.equals(selected)
        && canSwitch == enabled
        && this.notice.equals(notice)) return;
    this.sessions = sessions;
    this.selected = selected;
    canSwitch = enabled;
    this.notice = notice;
    renderHistory();
  }

  private void toggleSearch() {
    if (filter.getVisibility() == View.VISIBLE) {
      dismissSearch();
      return;
    }
    filter.setVisibility(View.VISIBLE);
    filter.requestFocus();
    filter.post(
        () -> {
          if (filter.isShown() && filter.hasFocus()) {
            host.getSystemService(InputMethodManager.class)
                .showSoftInput(filter, InputMethodManager.SHOW_IMPLICIT);
          }
        });
  }

  void dismissSearch() {
    if (filter.getVisibility() != View.VISIBLE) return;
    host.getSystemService(InputMethodManager.class)
        .hideSoftInputFromWindow(filter.getWindowToken(), 0);
    filter.clearFocus();
    filter.setVisibility(View.GONE);
    filter.setText("");
  }

  private void renderHistory() {
    history.removeAllViews();
    String query = filter.getText().toString().strip().toLowerCase(Locale.ROOT);
    for (Session session : sessions) {
      String title =
          session.title().isBlank()
              ? DateUtils.formatDateTime(
                  host,
                  session.updatedAt(),
                  DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)
              : session.title();
      if (!title.toLowerCase(Locale.ROOT).contains(query)) continue;
      boolean active = session.id().equals(selected);
      Button row = row(title, active);
      row.setSelected(active);
      row.setEnabled(canSwitch || active);
      row.setOnClickListener(v -> selectSession.accept(session.id()));
      history.addView(row);
      if (history.getChildCount() == 100) break;
    }
    String label =
        !notice.isEmpty()
            ? notice
            : query.isEmpty()
                ? UiText.choose("还没有历史会话", "No conversations yet")
                : UiText.choose("没有匹配的会话", "No matching conversations");
    status.setText(label);
    status.setVisibility(
        history.getChildCount() == 0 || !notice.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private Button row(String label, boolean selected) {
    Button button = new AppCompatButton(host);
    button.setText(label);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setTextSize(15);
    button.setTextColor(host.getColor(R.color.gpt_text));
    button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    GradientDrawable background = new GradientDrawable();
    background.setColor(selected ? host.getColor(R.color.gpt_line) : Color.TRANSPARENT);
    background.setCornerRadius(dp(10));
    GradientDrawable mask = new GradientDrawable();
    mask.setColor(Color.WHITE);
    mask.setCornerRadius(dp(10));
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(host.getColor(R.color.gpt_line)), background, mask));
    button.setPaddingRelative(dp(12), 0, dp(12), 0);
    button.setLayoutParams(
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
    return button;
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }
}
