package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.StoredWebSession;

/** 有界只读识别；未知证据不启动、不停止，也不擦除任何现场。 */
final class WebSessionRecovery {
  enum Result {
    NONE,
    RESTORED,
    EXITED,
    UNKNOWN,
    STALE
  }

  interface Ports {
    default void diagnostic(String value) {}

    StoredWebSession.Record read() throws Exception;

    boolean current();

    boolean compatible(StoredWebSession.Record record) throws Exception;

    boolean verify(StoredWebSession.Record record) throws Exception;

    boolean authenticate(StoredWebSession.Record record) throws Exception;

    default boolean confirmExited(StoredWebSession.Record record) throws Exception {
      return false;
    }

    boolean adopt(StoredWebSession.Record record) throws Exception;
  }

  static Result recover(Ports ports) {
    String stage = "READ_RECORD";
    StoredWebSession.Record saved = null;
    try {
      saved = ports.read();
      if (!ports.current()) return Result.STALE;
      if (saved == null) {
        ports.diagnostic("NO_SAVED_RECORD");
        return Result.NONE;
      }
      stage = "COMPATIBILITY";
      if (!ports.compatible(saved)) {
        ports.diagnostic("COMPATIBILITY_MISMATCH");
        return unavailable(ports, saved);
      }
      stage = "FIRST_KERNEL_PROOF";
      if (!ports.verify(saved)) {
        ports.diagnostic("FIRST_KERNEL_PROOF_REJECTED");
        return unavailable(ports, saved);
      }
      if (!ports.current()) return Result.STALE;
      stage = "HTTP_AUTHENTICATION";
      if (!ports.authenticate(saved)) {
        ports.diagnostic("AUTHENTICATION_NOT_READY");
        return unavailable(ports, saved);
      }
      if (!ports.current()) return Result.STALE;
      stage = "SECOND_KERNEL_PROOF";
      if (!ports.verify(saved)) {
        ports.diagnostic("SECOND_KERNEL_PROOF_REJECTED");
        return unavailable(ports, saved);
      }
      stage = "ADOPT_RECORD";
      boolean adopted = ports.current() && ports.adopt(saved);
      ports.diagnostic(adopted ? "RESTORED" : "ADOPTION_STALE");
      return adopted ? Result.RESTORED : Result.STALE;
    } catch (Exception unavailable) {
      ports.diagnostic(com.deepseekharness.app.util.SessionDiagnostic.failure(stage, unavailable));
      if (stage.equals("FIRST_KERNEL_PROOF") || stage.equals("SECOND_KERNEL_PROOF"))
        return unavailable(ports, saved);
      return Result.UNKNOWN;
    }
  }

  private static Result unavailable(Ports ports, StoredWebSession.Record saved) {
    if (!ports.current()) return Result.STALE;
    try {
      boolean exited = ports.confirmExited(saved);
      if (!ports.current()) return Result.STALE;
      if (exited) {
        ports.diagnostic("GUEST_AND_LAUNCHER_EXIT_CONFIRMED");
        return Result.EXITED;
      }
    } catch (Exception failure) {
      ports.diagnostic(
          com.deepseekharness.app.util.SessionDiagnostic.failure("EXIT_PROOF", failure));
    }
    return Result.UNKNOWN;
  }
}
