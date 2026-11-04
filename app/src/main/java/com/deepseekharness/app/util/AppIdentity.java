package com.deepseekharness.app.util;

/** Product identity for the production application. */
public final class AppIdentity {
  private AppIdentity() {}

  public static String applicationId() {
    return "dsh.android.client.diagnostics";
  }

  public static String displayName() {
    return "DSHA-Diagnostics";
  }

  public static String publicDirectoryName() {
    return "DSHA-Diagnostics";
  }

  public static String defaultUpdateFeed() {
    return "";
  }

  public static boolean isExperimental() {
    return false;
  }
}
