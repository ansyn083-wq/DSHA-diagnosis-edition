package com.deepseekharness.app.ui;

import com.deepseekharness.app.util.ChatEntryPolicy;
import com.deepseekharness.app.util.UiText;

/** Stable copy for the conversation-entry state machine. */
public final class GptStatusPanel {
  private GptStatusPanel() {}

  public static String title(ChatEntryPolicy.Action action, boolean checking) {
    return switch (action) {
      case READY -> UiText.choose("对话已就绪", "Chat is ready");
      case PREPARE, WAIT -> UiText.choose("正在准备对话", "Preparing chat");
      case START -> UiText.choose("正在启动对话", "Starting chat");
      case CHECK ->
          checking
              ? UiText.choose("正在核验已有服务", "Verifying existing service")
              : UiText.choose("旧服务恢复待确认", "Existing service needs confirmation");
      case STOPPED -> UiText.choose("对话服务已停止", "Chat service is stopped");
      case RECOVER -> UiText.choose("需要完成上次维护", "Previous maintenance needs attention");
      case ERROR -> UiText.choose("对话暂时不可用", "Chat is temporarily unavailable");
    };
  }
}
