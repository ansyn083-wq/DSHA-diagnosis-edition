package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.util.Log;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/** 验证真实 Android 资产读取边界上的兼容空值、脱敏和有界诊断。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class PackagedAssetReaderTest {
  @Before
  public void clearLogs() {
    ShadowLog.clear();
  }

  @Test
  public void missingPackagedAssetReturnsEmptyWithoutLeakingRequestedToken() {
    assertEquals(
        "",
        PackagedAssetReader.read(
            RuntimeEnvironment.getApplication(), "missing-asset?token=fixture-private-token"));
    var logs = ShadowLog.getLogsForTag("DSHA");
    assertEquals(1, logs.size());
    assertEquals(Log.WARN, logs.get(0).type);
    assertFalse(logs.get(0).msg.contains("fixture-private-token"));
    assertTrue(logs.get(0).msg.contains("Packaged asset read failed"));
  }

  @Test
  public void unreadableAssetsPreserveFailureTypeAndBoundRedactedReason() {
    var context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public AssetManager getAssets() {
            throw new IllegalStateException(
                "token=fixture-secret " + "x".repeat(300) + "tail-marker");
          }
        };
    assertEquals("", PackagedAssetReader.read(context, "backup-engine.py"));
    String message = ShadowLog.getLogsForTag("DSHA").get(0).msg;
    assertTrue(message.contains("IllegalStateException"));
    assertFalse(message.contains("fixture-secret"));
    assertFalse(message.contains("tail-marker"));
    assertTrue(message.length() < 300);
  }
}
