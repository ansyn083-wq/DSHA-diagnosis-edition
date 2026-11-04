package com.deepseekharness.app.util;

import java.util.Set;
import java.util.IdentityHashMap;

/** 只输出固定阶段、异常类型、已知错误码与代码位置，不记录消息原文、URL或令牌。 */
public final class SessionDiagnostic {
  private SessionDiagnostic() {}

  private static final Set<String> CODES =
      Set.of(
          "WEB_SESSION_BOOT_UNREADABLE",
          "WEB_SESSION_BOOT_LIMIT",
          "WEB_SESSION_CAPTURE_UNCONFIRMED",
          "WEB_SESSION_PROCESS_UNREADABLE",
          "WEB_SESSION_PROCESS_CHANGED",
          "WEB_SESSION_ENV_DUPLICATE",
          "WEB_SESSION_LAUNCHER_MISSING",
          "WEB_SESSION_PRIVATE_DIRECTORY",
          "WEB_SESSION_APK_IDENTITY",
          "WEB_SESSION_ENCRYPTION_UNAVAILABLE",
          "WEB_SESSION_WRITE",
          "WEB_SESSION_UNREADABLE",
          "WEB_SESSION_SCHEMA",
          "WEB_SESSION_IDENTITY",
          "WEB_SESSION_CREDENTIAL",
          "WEB_SESSION_RECORD_MISSING_FOR_EXISTING_PID");

  public static String failure(String stage, Throwable failure) {
    String safeStage = stage != null && stage.matches("[A-Z_]{1,48}") ? stage : "UNKNOWN";
    StringBuilder out = new StringBuilder("stage=").append(safeStage);
    var seen = new IdentityHashMap<Throwable, Boolean>();
    for (int n = 0;
        failure != null && n < 4 && seen.put(failure, true) == null;
        n++, failure = failure.getCause()) {
      out.append(n == 0 ? " type=" : " cause=").append(failure.getClass().getSimpleName());
      String message = failure.getMessage();
      if (message != null) {
        for (String code : CODES)
          if (message.matches("(?s).*\\b" + code + "\\b.*")) {
            out.append(" code=").append(code);
            break;
          }
        if (message.contains("EACCES") || message.contains("Permission denied"))
          out.append(" errno=EACCES");
        else if (message.contains("EPERM") || message.contains("Operation not permitted"))
          out.append(" errno=EPERM");
        else if (message.contains("ENOSPC") || message.contains("No space left"))
          out.append(" errno=ENOSPC");
        else if (message.contains("EROFS") || message.contains("Read-only file system"))
          out.append(" errno=EROFS");
      }
      for (StackTraceElement frame : failure.getStackTrace()) {
        if (frame.getClassName().startsWith("com.deepseekharness.app.")) {
          out.append(" at=")
              .append(frame.getClassName())
              .append('.')
              .append(frame.getMethodName())
              .append(':')
              .append(frame.getLineNumber());
          break;
        }
      }
    }
    return out.length() > 1300 ? out.substring(0, 1300) : out.toString();
  }
}
