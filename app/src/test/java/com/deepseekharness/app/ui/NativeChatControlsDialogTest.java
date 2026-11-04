package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/** 实际原生对话框和本机 HTTP；不启动宿主环境、模型或真实设备。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeChatControlsDialogTest {
  private ChatHomeActivity host;
  private MockWebServer server;
  private String url;
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private AlertDialog owner;

  @Before
  public void prepare() throws Exception {
    host = Robolectric.buildActivity(ChatHomeActivity.class).get();
    host.setTheme(R.style.Theme_DeepseekHarness);
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            JsonObject request =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            String method = request.get("method").getAsString();
            methods.add(method);
            String value =
                switch (method) {
                  case "commands/list" ->
                      "[{\"name\":\"compact\",\"description\":\"Compact this conversation\",\"input\":{\"hint\":\"Focus\"}}]";
                  case "commands/execute" ->
                      "{\"commandId\":\"execution-1\",\"result\":{\"kind\":\"success\",\"text\":\"Done\"}}";
                  case "messageFeedback/list" -> "{\"ok\":true,\"value\":{\"items\":[]}}";
                  default -> "null";
                };
            JsonObject reply =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            reply.add("rpcId", request.get("rpcId"));
            return new MockResponse().setBody(reply.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    url = "http://127.0.0.1:" + server.getPort();
  }

  @After
  public void cleanup() throws Exception {
    if (owner != null) owner.dismiss();
    android.app.Dialog latest = ShadowDialog.getLatestDialog();
    if (latest != null && latest.isShowing()) latest.dismiss();
    server.shutdown();
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  @Test
  public void openingAndSelectingCommandNeverExecutesBeforeConfirmation() throws Exception {
    open();
    await(() -> find(owner, "Review command", "查看命令") != null);
    assertEquals(List.of("commands/list"), methods);
    find(owner, "Review command", "查看命令").performClick();
    find(owner, "Review and run", "检查并执行").performClick();
    AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotSame(owner, confirmation);
    assertEquals(0, count("commands/execute"));
    confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, count("commands/execute"));
    assertTrue(owner.isShowing());
  }

  @Test
  public void repeatedOpenAndConfirmSubmitOnce() throws Exception {
    open();
    NativeChatControlsDialog.show(host, url, "dsh-auth-test=secret", "session-1", current::get);
    assertSame(owner, ShadowDialog.getLatestDialog());
    await(() -> find(owner, "Review command", "查看命令") != null);
    find(owner, "Review command", "查看命令").performClick();
    find(owner, "Review and run", "检查并执行").performClick();
    AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
    Button yes = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
    yes.performClick();
    yes.performClick();
    await(() -> count("commands/execute") == 1);
    await(() -> find(owner, "Command returned: Done", "命令已返回：Done") != null);
    assertEquals(1, count("commands/execute"));
  }

  @Test
  public void switchedSessionCannotConfirmOldCommand() throws Exception {
    open();
    await(() -> find(owner, "Review command", "查看命令") != null);
    find(owner, "Review command", "查看命令").performClick();
    find(owner, "Review and run", "检查并执行").performClick();
    AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
    current.set(false);
    confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, count("commands/execute"));
    assertFalse(find(owner, "Review and run", "检查并执行").isEnabled());
  }

  @Test
  public void dismissalInvalidatesRetainedConfirmationCallback() throws Exception {
    open();
    await(() -> find(owner, "Review command", "查看命令") != null);
    find(owner, "Review command", "查看命令").performClick();
    find(owner, "Review and run", "检查并执行").performClick();
    AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
    Button yes = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
    owner.dismiss();
    yes.performClick();
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
    assertEquals(0, count("commands/execute"));
    assertFalse(confirm.isShowing());
  }

  @Test
  public void newFeedbackRequiresHumanRatingInsteadOfDefaultApproval() throws Exception {
    NativeChatControlsDialog.feedback(
        host, url, "dsh-auth-test=secret", "session-1", "durable-message-7", current::get);
    owner = (AlertDialog) ShadowDialog.getLatestDialog();
    await(() -> find(owner, "Save feedback", "保存反馈") != null);
    RadioButton positive = (RadioButton) find(owner, "Helpful", "有帮助");
    RadioButton negative = (RadioButton) find(owner, "Not helpful", "没有帮助");
    assertFalse(positive.isChecked());
    assertFalse(negative.isChecked());
    find(owner, "Save feedback", "保存反馈").performClick();
    assertEquals(0, count("messageFeedback/put"));
    assertSame(owner, ShadowDialog.getLatestDialog());
  }

  private void open() {
    NativeChatControlsDialog.show(host, url, "dsh-auth-test=secret", "session-1", current::get);
    owner = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(owner);
  }

  private long count(String method) {
    return methods.stream().filter(method::equals).count();
  }

  private static View find(AlertDialog dialog, String en, String zh) {
    return find(dialog.getWindow().getDecorView(), en, zh);
  }

  private static View find(View view, String en, String zh) {
    if (view instanceof TextView text
        && (text.getText().toString().equals(en) || text.getText().toString().equals(zh)))
      return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), en, zh);
        if (found != null) return found;
      }
    return null;
  }

  private static void await(BooleanSupplier done) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    do {
      ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
      if (done.getAsBoolean()) return;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    fail("Native dialog did not reach the expected state");
  }
}
