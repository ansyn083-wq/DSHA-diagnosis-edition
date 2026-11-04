package com.deepseekharness.app.ui;

/** Single source of truth for the responsive GPT-style native surface. */
public final class GptUiTokens {
  public enum PageKind {
    CHAT,
    FORM,
    TERMINAL
  }

  public static final int FULL_WIDTH = -1;

  private GptUiTokens() {}

  public static int maxWidthDp(PageKind kind) {
    if (kind == null) throw new IllegalArgumentException("Page kind is required");
    return switch (kind) {
      case CHAT -> 800;
      case FORM -> 760;
      case TERMINAL -> FULL_WIDTH;
    };
  }
}
