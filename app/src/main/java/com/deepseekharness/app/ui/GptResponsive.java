package com.deepseekharness.app.ui;

/** Width policy shared by phone, tablet drawer and desktop-style sidebar layouts. */
public final class GptResponsive {
  public enum Mode {
    PHONE,
    DRAWER,
    SIDEBAR
  }

  private GptResponsive() {}

  public static Mode classify(int widthDp) {
    if (widthDp <= 760) return Mode.PHONE;
    if (widthDp <= 1023) return Mode.DRAWER;
    return Mode.SIDEBAR;
  }
}
