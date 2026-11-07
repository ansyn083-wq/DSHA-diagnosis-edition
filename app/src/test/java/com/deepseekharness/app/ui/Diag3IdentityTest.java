package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import com.deepseekharness.app.BuildConfig;
import org.junit.Test;

public class Diag3IdentityTest {
  @Test
  public void nativeChatBuildCanUpgradePreviousDiagnostics() {
    assertEquals("dsh.android.client.diagnostics", BuildConfig.APPLICATION_ID);
    assertEquals(216, BuildConfig.VERSION_CODE);
    assertEquals(BuildConfig.LOW_ANDROID ? "1.0.0low" : "1.0.0", BuildConfig.VERSION_NAME);
  }
}
