package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.PreviewPageSession.Identity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 实际头像、菜单、综合配置弹窗与首次进入回归；只使用虚构本机 HTTP 服务。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "mdpi",
    application = NativeChatAccountEntryTest.TestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeChatAccountEntryTest {
  public static final class TestApplication extends DshaApp {
    volatile Identity ready;
    HarnessController controller;

    @Override
    public void onCreate() {}

    @Override
    public HarnessController harnessController() {
      if (controller == null)
        controller =
            new HarnessController(this) {
              @Override
              public Identity getReadyWebPageIdentity() {
                return ready;
              }

              @Override
              public boolean hasUnresolvedWebSession() {
                return false;
              }

              @Override
              public boolean isStopping() {
                return false;
              }

              @Override
              public boolean isUserStopped() {
                return false;
              }

              @Override
              public String exchangeDshAuthCookie() {
                return "dsh-auth-test=synthetic";
              }
            };
      return controller;
    }
  }

  private TestApplication app;
  private ChatHomeActivity host;
  private final List<ChatHomeActivity> hosts = new ArrayList<>();
  private MockWebServer server;
  private NativeChatRpc rpc;
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private volatile boolean configured;
  private volatile boolean signedIn;
  private volatile boolean failRead;
  private volatile CountDownLatch held;
  private final CountDownLatch arrived = new CountDownLatch(1);

  @Before
  public void prepare() throws Exception {
    app = (TestApplication) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    app.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) throws InterruptedException {
            JsonObject request =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            String method = request.get("method").getAsString();
            methods.add(method);
            if ("account/getState".equals(method)) {
              arrived.countDown();
              CountDownLatch hold = held;
              if (hold != null) hold.await(5, TimeUnit.SECONDS);
            }
            if (failRead) return new MockResponse().setResponseCode(503);
            String value =
                switch (method) {
                  case "account/getState" ->
                      "{\"status\":\""
                          + (signedIn ? "credential-stored" : "signed-out")
                          + "\",\"attempt\":null}";
                  case "settings/describe" ->
                      """
              {"writable":true,"hasDocument":true,"namespaces":[
                {"ns":"llm-deepseek-api-key","value":{"apiKeyEnv":"FIXTURE_KEY"}},
                {"ns":"llm-pi-ai","revision":0,"value":{"providers":{}},"user":{},"base":{}}]}
              """;
                  case "credentials/describe" ->
                      "{\"FIXTURE_KEY\":{\"configured\":" + configured + ",\"writable\":true}}";
                  default -> "null";
                };
            JsonObject reply =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            reply.add("rpcId", request.get("rpcId"));
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(reply.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    host = newHost();
  }

  private ChatHomeActivity newHost() throws Exception {
    ChatHomeActivity next = Robolectric.buildActivity(ChatHomeActivity.class).get();
    next.setTheme(R.style.Theme_DeepseekHarness);
    Field savedState =
        androidx.activity.ComponentActivity.class.getDeclaredField("mSavedStateRegistryController");
    savedState.setAccessible(true);
    ((androidx.savedstate.SavedStateRegistryController) savedState.get(next)).performRestore(null);
    Field model = ChatHomeActivity.class.getDeclaredField("model");
    model.setAccessible(true);
    model.set(next, new ChatSetupCoordinator(app));
    Method build = ChatHomeActivity.class.getDeclaredMethod("buildUi", Bundle.class);
    build.setAccessible(true);
    build.invoke(next, (Bundle) null);
    hosts.add(next);
    return next;
  }

  @After
  public void cleanup() throws Exception {
    if (held != null) held.countDown();
    for (ChatHomeActivity page : hosts) {
      if (page.getLifecycle().getCurrentState() == Lifecycle.State.INITIALIZED)
        ((LifecycleRegistry) page.getLifecycle()).handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
      ((LifecycleRegistry) page.getLifecycle()).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
      Field chat = ChatHomeActivity.class.getDeclaredField("chat");
      chat.setAccessible(true);
      ((NativeChatPanel) chat.get(page)).destroy();
      Field model = ChatHomeActivity.class.getDeclaredField("model");
      model.setAccessible(true);
      ((ChatSetupCoordinator) model.get(page)).onCleared();
    }
    if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
    ShadowLooper.shadowMainLooper().idle();
    server.close();
  }

  @Test
  public void avatarOpensIntegratedAccountAndApiInsteadOfSettings() {
    ChatHomeLayout root = host.findViewById(R.id.native_chat_home);
    root.toggleSidebar();
    assertEquals(View.VISIBLE, host.findViewById(R.id.native_chat_scrim).getVisibility());
    host.findViewById(R.id.native_chat_avatar).performClick();
    assertEquals(View.GONE, host.findViewById(R.id.native_chat_scrim).getVisibility());
    assertNull(
        "Avatar must not navigate to general settings",
        Shadow.<ShadowActivity>extract(host).getNextStartedActivity());
    Dialog dialog = ShadowDialog.getLatestDialog();
    assertNotNull("Avatar must open account/API management", dialog);
    assertTrue(dialog.isShowing());
    assertTrue(hasText(dialog.getWindow().getDecorView(), "API Key"));
    assertTrue(
        hasText(dialog.getWindow().getDecorView(), "Sign in to DeepSeek")
            || hasText(dialog.getWindow().getDecorView(), "登录 DeepSeek"));
    assertTrue(
        host.findViewById(R.id.native_chat_avatar)
            .getContentDescription()
            .toString()
            .contains("API"));
  }

  @Test
  public void sidebarRemovesSeparateAccountEntryButKeepsSettingsReachable() {
    ViewGroup rows = host.findViewById(R.id.native_chat_feature_rows);
    assertFalse(hasText(rows, "Account and API"));
    assertFalse(hasText(rows, "账号与 API"));
    Button settings = findButton(rows, "Settings", "设置");
    assertNotNull("General settings must remain reachable when setup card is hidden", settings);
    settings.performClick();
    Intent started = Shadow.<ShadowActivity>extract(host).getNextStartedActivity();
    assertEquals(ChatManagementActivity.class.getName(), started.getComponent().getClassName());
  }

  @Test
  public void repeatedAvatarTapKeepsOneDialogAndCloseAllowsReopeningWithoutDataLoss() {
    var prefs = app.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE);
    prefs
        .edit()
        .putString("draft", "kept draft")
        .putString("native_session", "kept-session")
        .putString("native_provider:kept-session", "dsha-custom-kept")
        .commit();
    host.findViewById(R.id.native_chat_avatar).performClick();
    Dialog first = ShadowDialog.getLatestDialog();
    assertNotNull(first);
    host.findViewById(R.id.native_chat_avatar).performClick();
    assertSame(first, ShadowDialog.getLatestDialog());
    first.onBackPressed();
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(first.isShowing());
    host.findViewById(R.id.native_chat_avatar).performClick();
    assertNotSame(first, ShadowDialog.getLatestDialog());
    assertEquals("kept draft", prefs.getString("draft", ""));
    assertEquals("kept-session", prefs.getString("native_session", ""));
    assertEquals("dsha-custom-kept", prefs.getString("native_provider:kept-session", ""));
  }

  @Test
  public void firstReadyEntryOffersBothFieldsOnceAndDoesNotForceEither() throws Exception {
    ready(1);
    resume(host);
    check();
    until(() -> showing());
    Dialog first = ShadowDialog.getLatestDialog();
    assertTrue(hasText(first.getWindow().getDecorView(), "API Key"));
    List<EditText> fields = fields(first.getWindow().getDecorView());
    assertTrue(fields.size() >= 4);
    for (EditText field : fields) assertEquals("", field.getText().toString());
    first.cancel();
    ShadowLooper.shadowMainLooper().idle();
    check();
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(showing());
    host = newHost();
    resume(host);
    check();
    ShadowLooper.shadowMainLooper().idle();
    assertFalse("Rotation/recreation must not force setup again", showing());
    assertTrue(
        methods.stream()
            .noneMatch(m -> m.equals("credentials/set") || m.equals("account/startSignIn")));
  }

  @Test
  public void existingApiSkipsFirstEntryPrompt() throws Exception {
    configured = true;
    ready(1);
    resume(host);
    check();
    until(() -> methods.contains("credentials/describe"));
    settle();
    assertFalse(showing());
  }

  @Test
  public void existingAccountSkipsPromptWithoutRequiringAnApiKey() throws Exception {
    signedIn = true;
    ready(1);
    resume(host);
    check();
    until(() -> methods.contains("account/getState"));
    settle();
    assertFalse(showing());
    assertFalse(methods.contains("credentials/set"));
  }

  @Test
  public void returningNativeSessionDoesNotGetNewOnboarding() throws Exception {
    app.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("native_session", "existing-session")
        .commit();
    ready(1);
    resume(host);
    check();
    settle();
    assertFalse(showing());
    assertTrue(methods.isEmpty());
  }

  @Test
  public void unavailableReadDoesNotConsumeFirstEntryAndCanRetry() throws Exception {
    failRead = true;
    ready(1);
    resume(host);
    check();
    until(() -> methods.contains("account/getState"));
    settle();
    assertFalse(showing());
    failRead = false;
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(6));
    check();
    until(() -> showing());
  }

  @Test
  public void staleServiceAndRepeatedChecksDoNotOpenOldSetup() throws Exception {
    held = new CountDownLatch(1);
    ready(1);
    resume(host);
    check();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    check();
    check();
    assertEquals(1, methods.stream().filter("account/getState"::equals).count());
    ready(2);
    held.countDown();
    held = null;
    settle();
    assertFalse(showing());
    check();
    until(() -> showing());
  }

  @Test
  public void backgroundReadWaitsForResumeBeforeShowingAndRecordingPrompt() throws Exception {
    ready(1);
    check();
    until(() -> methods.contains("credentials/describe"));
    settle();
    assertFalse(showing());
    resume(host);
    until(() -> showing());
  }

  @Test
  public void dismissedManualDialogIsNotReopenedByLateFirstEntryCheck() throws Exception {
    held = new CountDownLatch(1);
    ready(1);
    resume(host);
    check();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    host.findViewById(R.id.native_chat_avatar).performClick();
    Dialog manual = ShadowDialog.getLatestDialog();
    assertNotNull(manual);
    assertTrue(manual.isShowing());
    manual.cancel();
    held.countDown();
    held = null;
    settle();
    assertFalse(showing());
    check();
    settle();
    assertFalse(showing());
  }

  @Test
  public void destroyedActivityCannotConsumeTheFirstEntryOffer() throws Exception {
    held = new CountDownLatch(1);
    ready(1);
    resume(host);
    check();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    ((LifecycleRegistry) host.getLifecycle()).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    held.countDown();
    held = null;
    settle();
    assertFalse(showing());
    host = newHost();
    resume(host);
    check();
    until(() -> showing());
  }

  @Test
  public void emptyModelSetupStillUsesTheSameAvatarDialog() {
    NativeChatModelPicker.show(
        host,
        new NativeChatRpc.ModelCatalog(new NativeChatRpc.ModelSelection("", "", ""), List.of()),
        null,
        choice -> fail("No model should be selected"));
    androidx.appcompat.app.AlertDialog empty =
        (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
    empty.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(empty.isShowing());
    Dialog account = ShadowDialog.getLatestDialog();
    assertNotSame(empty, account);
    assertTrue(account.isShowing());
    assertTrue(hasText(account.getWindow().getDecorView(), "API Key"));
    assertNull(Shadow.<ShadowActivity>extract(host).getNextStartedActivity());
  }

  @Test
  public void emptyLegacySessionPreferenceStillOffersFirstEntrySetup() throws Exception {
    app.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("native_session", "")
        .commit();
    ready(1);
    resume(host);
    check();
    until(() -> showing());
  }

  @Test
  public void lateDismissCallbackCannotForgetNewlyReopenedDialog() {
    host.findViewById(R.id.native_chat_avatar).performClick();
    Dialog first = ShadowDialog.getLatestDialog();
    assertNotNull(first);
    first.dismiss();
    host.findViewById(R.id.native_chat_avatar).performClick();
    Dialog second = ShadowDialog.getLatestDialog();
    assertNotSame(first, second);
    ShadowLooper.shadowMainLooper().idle();
    host.findViewById(R.id.native_chat_avatar).performClick();
    assertSame(
        "The old onDismiss callback must not clear the newer dialog",
        second,
        ShadowDialog.getLatestDialog());
  }

  @Test
  public void existingConversationDraftStillSkipsSetupAfterChoosingNewChat() throws Exception {
    app.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("native_session", "")
        .putString("native_draft:prior-conversation", "kept draft")
        .commit();
    ready(1);
    resume(host);
    check();
    settle();
    assertFalse(showing());
    assertTrue(methods.isEmpty());
  }

  private void ready(long generation) {
    app.ready =
        new Identity(
            generation, "fixture-run-" + generation, "http://127.0.0.1:" + server.getPort());
    Identity identity = app.ready;
    rpc =
        new NativeChatRpc(
            identity.authUrl(), "dsh-auth-test=synthetic", () -> identity.equals(app.ready));
  }

  private void check() throws Exception {
    try {
      Method check =
          ChatHomeActivity.class.getDeclaredMethod(
              "checkChatAccountSetup", NativeChatRpc.class, Identity.class);
      check.setAccessible(true);
      check.invoke(host, rpc, app.ready);
    } catch (NoSuchMethodException absent) {
      throw new AssertionError("First ready entry needs bounded native account/API setup", absent);
    }
  }

  private void resume(ChatHomeActivity page) {
    ((LifecycleRegistry) page.getLifecycle()).handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
  }

  private boolean showing() {
    return ShadowDialog.getLatestDialog() != null && ShadowDialog.getLatestDialog().isShowing();
  }

  private void until(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      ShadowLooper.shadowMainLooper().idle();
      Thread.sleep(10);
    }
    ShadowLooper.shadowMainLooper().idle();
    assertTrue("Expected UI state before timeout", condition.getAsBoolean());
  }

  private void settle() throws Exception {
    for (int i = 0; i < 25; i++) {
      ShadowLooper.shadowMainLooper().idle();
      Thread.sleep(10);
    }
  }

  private static boolean hasText(View view, String text) {
    if (view instanceof TextView label && label.getText().toString().equals(text)) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasText(group.getChildAt(i), text)) return true;
    return false;
  }

  private static Button findButton(View view, String en, String zh) {
    if (view instanceof Button button
        && (button.getText().toString().equals(en) || button.getText().toString().equals(zh)))
      return button;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i), en, zh);
        if (found != null) return found;
      }
    return null;
  }

  private static List<EditText> fields(View view) {
    List<EditText> result = new ArrayList<>();
    if (view instanceof EditText field) result.add(field);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) result.addAll(fields(group.getChildAt(i)));
    return result;
  }
}
