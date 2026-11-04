package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 使用真实应用宿主和设置，验证备份只取得配置目录，不接管设置或启动运行时。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = BackupHostPortsTest.App.class)
public final class BackupHostPortsTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private App app;
  private ConfigStore config;

  @Before
  public void prepare() {
    app = (App) RuntimeEnvironment.getApplication();
    app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    config = new ConfigStore(app);
  }

  @Test
  public void actualApplicationPortReadsCurrentConfiguredWorkspaceWithoutCaching() {
    BackupHostPorts ports = BackupHostPorts.fromOwner(app);
    assertEquals(Constants.DEFAULT_WORKDIR, ports.configuredWorkdir());
    config.setWorkdir("/root/project with spaces");
    assertEquals("/root/project with spaces", ports.configuredWorkdir());
    config.setWorkdir("/sdcard/user-chosen-project");
    assertEquals("/sdcard/user-chosen-project", ports.configuredWorkdir());
  }

  @Test
  public void legacyScalarWorkspacePreferenceKeepsExistingConversionAndOtherSettings() {
    var preferences = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    preferences
        .edit()
        .putInt(Constants.KEY_WORKDIR, 42)
        .putString("unrelated-setting", "keep")
        .commit();
    var before = preferences.getAll();
    assertEquals("42", BackupHostPorts.fromOwner(app).configuredWorkdir());
    assertEquals(before, preferences.getAll());
  }

  @Test
  public void malformedWorkspacePreferenceKeepsExistingFallbackAndOtherSettings() {
    var preferences = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    preferences
        .edit()
        .putStringSet(Constants.KEY_WORKDIR, java.util.Set.of("invalid", "workspace"))
        .putString("unrelated-setting", "keep")
        .commit();
    var before = preferences.getAll();
    assertEquals(Constants.DEFAULT_WORKDIR, BackupHostPorts.fromOwner(app).configuredWorkdir());
    assertEquals(before, preferences.getAll());
  }

  @Test
  public void missingCompositionOwnerFailsClosed() {
    assertThrows(IllegalStateException.class, () -> BackupHostPorts.fromOwner(new Object()));
    assertThrows(IllegalStateException.class, () -> BackupHostPorts.fromOwner(null));
  }
}
