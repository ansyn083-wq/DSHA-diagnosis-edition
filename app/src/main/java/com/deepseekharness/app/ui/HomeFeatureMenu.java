package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.util.UiText;

/** First-level shortcuts to DSHA's existing native feature pages. */
final class HomeFeatureMenu {
  enum Feature {
    WORKSPACE("工作区", "Workspace"),
    PLUGINS("插件", "Plugins"),
    TERMINAL("终端", "Terminal"),
    BACKGROUND_TASKS("后台任务", "Background tasks"),
    AGENT_TASKS("智能体任务", "Agent tasks"),
    RUNTIME("运行环境与重试", "Runtime and retry"),
    DIAGNOSTICS("诊断", "Diagnostics"),
    STARTUP("启动控制与日志", "Startup controls and logs");

    private final String chinese;
    private final String english;

    Feature(String chinese, String english) {
      this.chinese = chinese;
      this.english = english;
    }

    String title() {
      return UiText.choose(chinese, english);
    }

    Intent intent(Context context) {
      return switch (this) {
        case WORKSPACE ->
            ChatManagementActivity.pageIntent(context, ChatManagementActivity.Page.WORKSPACE);
        case PLUGINS ->
            ChatManagementActivity.pageIntent(context, ChatManagementActivity.Page.PLUGINS);
        case TERMINAL ->
            new Intent(context, MainActivity.class)
                .putExtra("open_terminal", true)
                .putExtra("limited_entry", true);
        case BACKGROUND_TASKS -> new Intent(context, BackgroundTasksActivity.class);
        case AGENT_TASKS -> new Intent(context, AgentTasksActivity.class);
        case RUNTIME -> new Intent(context, ExtractActivity.class).putExtra("review_only", true);
        case DIAGNOSTICS -> new Intent(context, DiagnosticActivity.class);
        case STARTUP ->
            new Intent(context, MainActivity.class)
                .putExtra("open_launch", true)
                .putExtra("limited_entry", true);
      };
    }
  }

  private HomeFeatureMenu() {}
}
