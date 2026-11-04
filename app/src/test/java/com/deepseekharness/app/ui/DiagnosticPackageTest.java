package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.PackageManager;
import android.view.*;
import android.widget.TextView;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi", application = Application.class)
public class DiagnosticPackageTest {
  @Test
  public void applicationAndProvidersHaveSeparateIdentity() throws Exception {
    assertEquals("dsh.android.client.diagnostics", BuildConfig.APPLICATION_ID);
    assertEquals(
        BuildConfig.APPLICATION_ID, com.deepseekharness.app.util.AppIdentity.applicationId());
    var app = RuntimeEnvironment.getApplication();
    var info =
        app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PROVIDERS);
    assertNotNull(info.providers);
    for (var provider : info.providers) {
      assertFalse(provider.authority.equals("dsh.android.client.updates"));
      assertFalse(provider.authority.equals("dsh.android.client.documents"));
      assertFalse(provider.authority.equals("dsh.android.client.shizuku"));
    }
    assertTrue(app.getString(R.string.app_name).contains("诊断版"));
  }

  @Test
  public void diagnosticHomeKeepsExportAndControlReachableOnPhone() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View v = LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    View setup = v.findViewById(R.id.native_chat_setup_card);
    assertEquals(View.GONE, setup.getVisibility());
    setup.setVisibility(View.VISIBLE);
    v.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, 360, 640);
    assertNotNull(v.findViewById(R.id.diagnostic_export));
    assertNotNull(v.findViewById(R.id.diagnostic_details));
    assertTrue(v.findViewById(R.id.diagnostic_primary).getMeasuredHeight() >= 48);
    assertEquals(
        context.getString(R.string.native_chat_export_diagnostics),
        ((TextView) v.findViewById(R.id.diagnostic_export)).getText().toString());
  }

  @Test
  @Config(qualifiers = "en-rUS-mdpi")
  public void nativeHomeStaticLabelsAreEnglishWhenEnglishIsSelected() {
    assertLocalizedHome(
        "Message DSHA",
        "Export diagnostic logs",
        "Show details",
        "Profile picture, account and API",
        "Preparing");
  }

  @Test
  @Config(qualifiers = "zh-rCN-mdpi")
  public void nativeHomeStaticLabelsKeepChineseWhenChineseIsSelected() {
    assertLocalizedHome("给 DSHA 发消息", "导出诊断日志", "查看详情", "头像，账号与 API", "正在准备");
  }

  private void assertLocalizedHome(
      String hint, String export, String details, String avatar, String preparing) {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View home = LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    assertEquals(hint, ((TextView) home.findViewById(R.id.diagnostic_draft)).getHint().toString());
    assertEquals(
        export, ((TextView) home.findViewById(R.id.diagnostic_export)).getText().toString());
    assertEquals(
        details, ((TextView) home.findViewById(R.id.diagnostic_details)).getText().toString());
    assertEquals(
        avatar, home.findViewById(R.id.native_chat_avatar).getContentDescription().toString());
    assertEquals(
        preparing, new GptLoadingIndicator(context, null).getContentDescription().toString());
  }

  @Test
  public void homeHasNativeComposerAndNoEmbeddedBrowser() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View root = LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    assertNotNull(root.findViewById(R.id.native_chat_messages));
    assertEquals(View.GONE, root.findViewById(R.id.native_chat_send).getVisibility());
    assertNotNull(root.findViewById(R.id.diagnostic_draft));
    assertTrue(root.findViewById(R.id.native_chat_add).isClickable());
    assertTrue(root.findViewById(R.id.native_chat_model).isClickable());
    assertNull(root.findViewById(R.id.web_container));
    assertNotNull(root.findViewById(R.id.diagnostic_settings));
    TextView sidebarToggle = root.findViewById(R.id.native_chat_features);
    assertTrue(sidebarToggle.isClickable());
    assertEquals("DSHA", sidebarToggle.getText().toString());
    assertFalse(WebPreviewActivity.class.isAssignableFrom(ChatHomeActivity.class));
  }

  @Test
  public void nativeSidebarDocksOnTabletAndOverlaysOnPhone() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    var root =
        (ChatHomeLayout)
            LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    View side = root.findViewById(R.id.native_chat_sidebar);
    View content = root.findViewById(R.id.native_chat_content);
    View scrim = root.findViewById(R.id.native_chat_scrim);

    layout(root, 1000);
    assertEquals(View.VISIBLE, side.getVisibility());
    assertEquals(View.INVISIBLE, root.findViewById(R.id.native_chat_features).getVisibility());
    assertEquals(
        "DSHA",
        ((TextView) root.findViewById(R.id.native_chat_sidebar_close)).getText().toString());
    assertEquals(side.getRight(), content.getLeft());
    assertEquals(View.GONE, scrim.getVisibility());
    assertFalse(root.closeOverlay());

    layout(root, 360);
    assertEquals(View.GONE, side.getVisibility());
    assertEquals(0, content.getLeft());
    root.toggleSidebar();
    layout(root, 360);
    assertEquals(View.VISIBLE, side.getVisibility());
    assertEquals(View.VISIBLE, scrim.getVisibility());
    assertEquals(0, content.getLeft());
    assertTrue(root.closeOverlay());
    assertEquals(View.GONE, side.getVisibility());
  }

  @Test
  public void chatHomePlacesTheReasoningSliderDirectlyBelowTheModel() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View root = LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    View model = root.findViewById(R.id.native_chat_model);
    View slider = root.findViewById(R.id.native_chat_effort_slider);
    assertNotNull(slider);
    assertNotNull(root.findViewById(R.id.native_chat_avatar));
    View inlineEffort = (View) slider.getParent();
    assertSame(model.getParent(), inlineEffort.getParent());
    var controls = (android.widget.LinearLayout) model.getParent();
    assertEquals(controls.indexOfChild(model) + 1, controls.indexOfChild(inlineEffort));
  }

  @Test
  public void reasoningSliderSnapsToModelEffortsAndShowsTheSelection() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    View root = LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    View panel = root.findViewById(R.id.native_chat_effort);
    var slider =
        NativeChatModelPicker.configureEffortSlider(
            panel, new String[] {"Default", "Normal", "High"}, 1, level -> {});
    TextView value = panel.findViewById(R.id.native_chat_effort_value);
    assertEquals(1f, slider.getStepSize(), 0f);
    assertTrue(slider.isTickVisible());
    assertEquals(android.graphics.Color.WHITE, slider.getThumbTintList().getDefaultColor());
    assertEquals(2f, slider.getValueTo(), 0f);
    assertEquals("Normal", value.getText().toString());
    slider.setValue(2);
    assertEquals("High", value.getText().toString());
  }

  @Test
  public void reopeningDockedSidebarRelayoutsTheComposerAndTitle() {
    var context =
        new android.view.ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    var root =
        (ChatHomeLayout)
            LayoutInflater.from(context).inflate(R.layout.activity_diagnostic_home, null);
    View side = root.findViewById(R.id.native_chat_sidebar);
    View content = root.findViewById(R.id.native_chat_content);
    View draft = root.findViewById(R.id.diagnostic_draft);
    View title = root.findViewById(R.id.native_chat_title);
    root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    layout(root, 1117);
    root.findViewById(R.id.native_chat_sidebar_close).performClick();
    layout(root, 1117);
    assertEquals(0, content.getLeft());
    int closedDraftStart = leftInRoot(draft, root);
    assertTrue(closedDraftStart < side.getWidth());

    root.findViewById(R.id.native_chat_features).performClick();
    layout(root, 1117);
    assertEquals(View.VISIBLE, side.getVisibility());
    assertEquals(side.getRight(), content.getLeft());
    assertEquals(root.getWidth(), content.getRight());
    assertTrue(leftInRoot(draft, root) >= side.getRight());
    assertEquals(
        (side.getRight() + root.getWidth()) / 2, leftInRoot(title, root) + title.getWidth() / 2, 1);
  }

  private static int leftInRoot(View view, ViewGroup root) {
    var bounds = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
    root.offsetDescendantRectToMyCoords(view, bounds);
    return bounds.left;
  }

  private static void layout(View root, int width) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, 640);
  }

  @Test
  public void homeFeaturesPreserveTheExistingEntryRestrictions() {
    var context = RuntimeEnvironment.getApplication();
    var terminal = HomeFeatureMenu.Feature.TERMINAL.intent(context);
    assertEquals(MainActivity.class.getName(), terminal.getComponent().getClassName());
    assertTrue(terminal.getBooleanExtra("open_terminal", false));
    assertTrue(terminal.getBooleanExtra("limited_entry", false));
    var runtime = HomeFeatureMenu.Feature.RUNTIME.intent(context);
    assertEquals(ExtractActivity.class.getName(), runtime.getComponent().getClassName());
    assertTrue(runtime.getBooleanExtra("review_only", false));
    var plugins = HomeFeatureMenu.Feature.PLUGINS.intent(context);
    assertEquals(ChatManagementActivity.class.getName(), plugins.getComponent().getClassName());
    assertEquals(ChatManagementActivity.Page.PLUGINS, ChatManagementActivity.initialPage(plugins));
    var workspace = HomeFeatureMenu.Feature.WORKSPACE.intent(context);
    assertEquals(
        ChatManagementActivity.Page.WORKSPACE, ChatManagementActivity.initialPage(workspace));
    var diagnostics = HomeFeatureMenu.Feature.DIAGNOSTICS.intent(context);
    assertEquals(DiagnosticActivity.class.getName(), diagnostics.getComponent().getClassName());
    assertFalse(diagnostics.getBooleanExtra("download_error_logs", false));
  }
}
