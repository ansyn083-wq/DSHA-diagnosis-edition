package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class SessionDiagnosticTest {
  @Test
  public void capturesStageAndKnownCodeWithoutSecrets() {
    String result =
        SessionDiagnostic.failure(
            "BOOT_ID",
            new IOException(
                "WEB_SESSION_BOOT_UNREADABLE token=private-token http://127.0.0.1:1234/?token=secret"));
    assertTrue(result.contains("BOOT_ID"));
    assertTrue(result.contains("WEB_SESSION_BOOT_UNREADABLE"));
    assertFalse(result.contains("private-token"));
    assertFalse(result.contains("127.0.0.1"));
    assertFalse(result.contains("secret"));
  }

  @Test
  public void nestedPermissionsAreDiagnosableWithoutFilePaths() {
    String result =
        SessionDiagnostic.failure(
            "ATOMIC_WRITE",
            new IOException(
                "/private/user-data", new IOException("open failed: EACCES (Permission denied)")));
    assertTrue(result.contains("EACCES"));
    assertFalse(result.contains("user-data"));
  }

  @Test
  public void boundedChainNeverCopiesArbitraryMessages() {
    Throwable cause = new IOException("secret-session-token");
    for (int i = 0; i < 10; i++) cause = new IOException("another-secret", cause);
    String result = SessionDiagnostic.failure("CAPTURE", cause);
    assertTrue(result.length() < 1400);
    assertFalse(result.contains("secret"));
    assertTrue(result.contains("IOException"));
  }

  @Test
  public void untrustedStageCannotInjectText() {
    assertTrue(
        SessionDiagnostic.failure("token=bad\n", new IOException()).startsWith("stage=UNKNOWN"));
  }
}
