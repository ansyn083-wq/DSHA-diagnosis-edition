package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ClipboardManager;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatDetails;
import com.deepseekharness.app.util.NativeChatTranscript;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.PreviewPageSession.Identity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowDialog;

/** 实际消息渲染、点击及布局回归；不启动后端、网络或设备。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "mdpi",
    application = NativeChatMessageActionsTest.TestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeChatMessageActionsTest {
  public static final class TestApplication extends DshaApp {
    @Override
    public void onCreate() {
      /* 本测试只使用原生视图与应用作用域，不启动运行环境。 */
    }
  }

  private ChatHomeActivity host;
  private ChatHomeLayout root;
  private NativeChatPanel panel;
  private LinearLayout messages;

  @Before
  public void prepare() {
    RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir =
        RuntimeEnvironment.getApplication().getCacheDir().getAbsolutePath();
    host = Robolectric.buildActivity(ChatHomeActivity.class).get();
    host.setTheme(R.style.Theme_DeepseekHarness);
    root =
        (ChatHomeLayout) LayoutInflater.from(host).inflate(R.layout.activity_diagnostic_home, null);
    host.setContentView(root);
    panel = new NativeChatPanel(host, host.findViewById(R.id.diagnostic_draft), null);
    messages = root.findViewById(R.id.native_chat_messages);
  }

  @After
  public void cleanup() {
    if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
    if (panel != null) panel.destroy();
    ShadowLooper.shadowMainLooper().idle();
  }

  @Test
  public void reasoningArrowKeepsTouchTargetAndReflectsExpansion() throws Exception {
    NativeChatReasoningView reasoning = new NativeChatReasoningView(host);
    messages.addView(reasoning);
    reasoning.showReasoning("Visible reasoning.");
    layout(360, 640);
    LinearLayout header = (LinearLayout) reasoning.getChildAt(0);
    View arrow = header.getChildAt(0);
    assertTrue(header.isClickable());
    assertTrue(header.getHeight() >= 48);
    assertNotNull(header.getContentDescription());
    assertEquals(0f, arrow.getRotation(), 0f);
    assertEquals(View.GONE, reasoning.getChildAt(1).getVisibility());
    header.performClick();
    assertEquals(90f, arrow.getRotation(), 0f);
    assertEquals(View.VISIBLE, reasoning.getChildAt(1).getVisibility());
    reasoning.showReasoning("Visible reasoning. Updated.");
    assertTrue(reasoning.isExpanded());
    header.performClick();
    assertEquals(View.GONE, reasoning.getChildAt(1).getVisibility());
  }

  @Test
  public void appendingReplyKeepsHistoryViewsAndReasoningExpansion() throws Exception {
    setReasoningTranscript(
        """
        {"type":"snapshot","cursor":1,"records":[{"event":{"type":"assistant/message","seq":1,
        "data":{"message":{"content":[{"type":"reasoning","text":"Compare options."},
        {"type":"text","text":"First reply."}]}}}}]}
        """);
    render();
    NativeChatReasoningView reasoning = (NativeChatReasoningView) messages.getChildAt(0);
    reasoning.getChildAt(0).performClick();
    View body = messages.getChildAt(1);
    NativeChatTranscript transcript = (NativeChatTranscript) field("transcript");
    transcript.apply(
        JsonParser.parseString(
                """
        {"type":"event","event":{"type":"assistant/message","seq":2,
        "data":{"message":{"content":[{"type":"text","text":"Second reply."}]}}}}
        """)
            .getAsJsonObject());
    render();
    assertSame(reasoning, messages.getChildAt(0));
    assertSame(body, messages.getChildAt(1));
    assertEquals(View.VISIBLE, reasoning.getChildAt(1).getVisibility());
  }

  @Test
  public void messageActionsAreIconsWithAccessibleLabelsAndRealTouchTargets() throws Exception {
    appendActions(new NativeChatTranscript.Message(true, "Reply to copy"), 7);
    layout(360, 640);
    LinearLayout buttons = buttons(messages.getChildAt(0));
    assertEquals(2, buttons.getChildCount());
    for (int i = 0; i < buttons.getChildCount(); i++) {
      View view = buttons.getChildAt(i);
      assertTrue("Message actions must be icon buttons", view instanceof ImageButton);
      ImageButton icon = (ImageButton) view;
      assertNotNull(icon.getDrawable());
      assertNotNull(icon.getContentDescription());
      assertFalse(icon.getContentDescription().toString().isBlank());
      assertEquals(icon.getContentDescription(), icon.getTooltipText());
      assertTrue(icon.isFocusable());
      assertTrue(icon.getWidth() >= 48);
      assertTrue(icon.getHeight() >= 48);
    }
    buttons.getChildAt(0).performClick();
    assertEquals(
        "Reply to copy",
        host.getSystemService(ClipboardManager.class)
            .getPrimaryClip()
            .getItemAt(0)
            .getText()
            .toString());
  }

  @Test
  public void eachRowStaysImmediatelyBelowItsMessageAndOnItsOwnSide() throws Exception {
    renderConversation("Question one", "Answer one", "Question two");
    layout(1000, 720);
    assertEquals(6, messages.getChildCount());
    for (int message = 0; message < 3; message++) {
      View body = messages.getChildAt(message * 2);
      View actions = messages.getChildAt(message * 2 + 1);
      assertTrue(actions.getTop() >= body.getBottom());
      int gravity = ((LinearLayout.LayoutParams) actions.getLayoutParams()).gravity;
      assertEquals(message == 1 ? Gravity.START : Gravity.END, gravity);
      assertEquals(message == 1 ? 3 : 2, buttons(actions).getChildCount());
      if (message == 1) assertEquals(0, actions.getLeft());
      else assertEquals(messages.getWidth(), actions.getRight());
      buttons(actions).getChildAt(0).performClick();
      assertEquals(
          new String[] {"Question one", "Answer one", "Question two"}[message],
          host.getSystemService(ClipboardManager.class)
              .getPrimaryClip()
              .getItemAt(0)
              .getText()
              .toString());
    }
  }

  @Test
  public void unavailableForkAndFeedbackStayHidden() throws Exception {
    appendActions(new NativeChatTranscript.Message(false, "Pending"), -1);
    appendActions(new NativeChatTranscript.Message(true, "No durable message id"), 2);
    assertEquals(1, buttons(messages.getChildAt(0)).getChildCount());
    assertEquals(2, buttons(messages.getChildAt(1)).getChildCount());
  }

  @Test
  public void finalActionsRemainReachableAboveComposerOnPhoneAndTablet() throws Exception {
    renderConversation("Question", "Long reply\n".repeat(70), "Final message");
    for (int width : new int[] {320, 360, 411, 1000}) {
      layout(width, 640);
      ScrollView scroll = root.findViewById(R.id.native_chat_scroll);
      scroll.fullScroll(View.FOCUS_DOWN);
      View row = messages.getChildAt(messages.getChildCount() - 1);
      View composerFrame = ((ViewGroup) root.findViewById(R.id.native_chat_content)).getChildAt(2);
      Rect rowBounds = inRoot(row);
      Rect composerBounds = inRoot(composerFrame);
      assertTrue(
          "Last actions must be fully visible at width " + width,
          rowBounds.bottom <= composerBounds.top - 8);
      assertTrue(rowBounds.top >= inRoot(scroll).top);
      assertTrue(row.getWidth() <= messages.getWidth());
      assertFalse(((HorizontalScrollView) row).canScrollHorizontally(1));
    }
  }

  @Test
  @Config(qualifiers = "night-mdpi")
  public void largeFontAndNightThemeKeepIconTargetsInsideCompactRows() throws Exception {
    RuntimeEnvironment.setFontScale(1.5f);
    renderConversation("Question", "Answer", "Final");
    layout(320, 480);
    LinearLayout controls = buttons(messages.getChildAt(3));
    assertEquals(3, controls.getChildCount());
    assertEquals(144, controls.getWidth());
    for (int i = 0; i < controls.getChildCount(); i++) {
      assertEquals(48, controls.getChildAt(i).getWidth());
      assertEquals(48, controls.getChildAt(i).getHeight());
    }
  }

  @Test
  public void finalReplyFollowsBottomAfterTheNewContentHasBeenMeasured() throws Exception {
    renderConversation("Question", "Short reply", "Follow up");
    layout(360, 640);
    ShadowLooper.shadowMainLooper().idle();
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();

    renderConversation("Question", "Final reply\n".repeat(70), "Final message");
    // 普通消息队列可先于下一次绘制遍历执行，不能用旧内容高度滚动。
    ShadowLooper.shadowMainLooper().idle();
    layout(360, 640);
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    assertFinalActionsAboveComposer();
  }

  @Test
  public void composerGrowthKeepsTheFollowedFinalActionsVisible() throws Exception {
    renderConversation("Question", "Long reply\n".repeat(70), "Final message");
    layout(360, 640);
    ShadowLooper.shadowMainLooper().idle();
    ScrollView scroll = root.findViewById(R.id.native_chat_scroll);
    scroll.fullScroll(View.FOCUS_DOWN);
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();

    ((android.widget.EditText) root.findViewById(R.id.diagnostic_draft))
        .setText("One\nTwo\nThree\nFour");
    layout(360, 640);
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    assertFinalActionsAboveComposer();
  }

  @Test
  public void newReplyDoesNotMoveAReaderWhoScrolledAway() throws Exception {
    renderConversation("Question", "Long reply\n".repeat(70), "Final message");
    layout(360, 640);
    ShadowLooper.shadowMainLooper().idle();
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    ScrollView scroll = root.findViewById(R.id.native_chat_scroll);
    scroll.scrollTo(0, 20);
    assertEquals(20, scroll.getScrollY());

    renderConversation("Question", "Longer reply\n".repeat(90), "Final message");
    ShadowLooper.shadowMainLooper().idle();
    layout(360, 640);
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    assertEquals(20, scroll.getScrollY());
  }

  @Test
  public void scrollAwayBeforeTheNextFrameCancelsPendingBottomFollow() throws Exception {
    renderConversation("Question", "Long reply\n".repeat(70), "Final message");
    layout(360, 640);
    ShadowLooper.shadowMainLooper().idle();
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    ScrollView scroll = root.findViewById(R.id.native_chat_scroll);
    scroll.fullScroll(View.FOCUS_DOWN);

    renderConversation("Question", "Longer reply\n".repeat(90), "Final message");
    scroll.scrollTo(0, 20);
    assertEquals(20, scroll.getScrollY());
    ShadowLooper.shadowMainLooper().idle();
    layout(360, 640);
    root.findViewById(R.id.native_chat_scroll).getViewTreeObserver().dispatchOnPreDraw();
    assertEquals(20, scroll.getScrollY());
  }

  private void assertFinalActionsAboveComposer() {
    View row = messages.getChildAt(messages.getChildCount() - 1);
    View composerFrame = ((ViewGroup) root.findViewById(R.id.native_chat_content)).getChildAt(2);
    assertTrue(
        "Final actions must follow above composer without manual scrolling",
        inRoot(row).bottom <= inRoot(composerFrame).top - 8);
    assertTrue(inRoot(row).top >= inRoot(root.findViewById(R.id.native_chat_scroll)).top);
  }

  @Test
  public void retainedForkCannotTargetAnotherSession() throws Exception {
    assertRetainedForkRejected("sessionId", "session-two");
  }

  @Test
  public void retainedForkCannotTargetAnotherClient() throws Exception {
    assertRetainedForkRejected("rpc", rpc());
  }

  @Test
  public void retainedForkCannotTargetAnotherRuntimeIdentity() throws Exception {
    assertRetainedForkRejected(
        "identity", new Identity(2, "runtime-two", "http://127.0.0.1:19876/"));
  }

  @Test
  public void retainedForkCannotCrossPanelEpochs() throws Exception {
    assertRetainedForkRejected("epoch", 2L);
  }

  @Test
  public void unchangedTranscriptRebindsActionsAfterReconnect() throws Exception {
    bindActions();
    renderConversation("Question", "Answer", "Final");
    View oldFork = buttons(messages.getChildAt(1)).getChildAt(1);
    setField("rpc", rpc());
    setField("epoch", 2L);
    renderConversation("Question", "Answer", "Final");
    View newFork = buttons(messages.getChildAt(1)).getChildAt(1);
    assertNotSame(oldFork, newFork);
    newFork.performClick();
    android.app.Dialog current = ShadowDialog.getLatestDialog();
    assertNotNull(current);
    assertTrue(current.isShowing());
    current.dismiss();
    oldFork.performClick();
    assertSame(current, ShadowDialog.getLatestDialog());
    assertFalse(current.isShowing());
  }

  private void assertRetainedForkRejected(String changedField, Object changedValue)
      throws Exception {
    bindActions();
    appendActions(new NativeChatTranscript.Message(false, "Original message"), 7);
    LinearLayout actions = buttons(messages.getChildAt(0));
    View fork = actions.getChildAt(1);
    fork.performClick();
    android.app.Dialog current = ShadowDialog.getLatestDialog();
    assertNotNull(current);
    assertTrue(current.isShowing());
    current.dismiss();
    setField(changedField, changedValue);
    fork.performClick();
    assertSame(
        "Retained action must not open for " + changedField,
        current,
        ShadowDialog.getLatestDialog());
    assertFalse(current.isShowing());
    actions.getChildAt(0).performClick();
    assertEquals(
        "Original message",
        host.getSystemService(ClipboardManager.class)
            .getPrimaryClip()
            .getItemAt(0)
            .getText()
            .toString());
  }

  private void bindActions() throws Exception {
    setField("identity", new Identity(1, "runtime-one", "http://127.0.0.1:19876/"));
    setField("rpc", rpc());
    setField("sessionId", "session-one");
    setField("cookie", "dsh-auth-test=synthetic");
    setField("epoch", 1L);
  }

  private static NativeChatRpc rpc() {
    return new NativeChatRpc("http://127.0.0.1:19876/", "dsh-auth-test=synthetic", () -> false);
  }

  private void setReasoningTranscript(String source) throws Exception {
    JsonObject snapshot = JsonParser.parseString(source).getAsJsonObject();
    ((NativeChatTranscript) field("transcript")).apply(snapshot);
    ((NativeChatDetails) field("details")).apply(snapshot);
  }

  private void render() throws Exception {
    Method method = NativeChatPanel.class.getDeclaredMethod("renderTranscript");
    method.setAccessible(true);
    method.invoke(panel);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = NativeChatPanel.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(panel, value);
  }

  private void appendActions(NativeChatTranscript.Message message, long sequence) throws Exception {
    Method add =
        NativeChatPanel.class.getDeclaredMethod(
            "addMessageActions", NativeChatTranscript.Message.class, long.class);
    add.setAccessible(true);
    add.invoke(panel, message, sequence);
  }

  private void renderConversation(String first, String reply, String last) throws Exception {
    JsonObject snapshot =
        JsonParser.parseString(
                "{\"type\":\"snapshot\",\"cursor\":2,\"records\":["
                    + "{\"event\":{\"seq\":0,\"type\":\"user/message\",\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}}},"
                    + "{\"event\":{\"seq\":1,\"type\":\"assistant/message\",\"data\":{\"message\":{\"id\":\"reply-1\",\"content\":[{\"type\":\"text\",\"text\":\"\"}]}}}},"
                    + "{\"event\":{\"seq\":2,\"type\":\"user/message\",\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}}}]}")
            .getAsJsonObject();
    String[] values = {first, reply, last};
    for (int i = 0; i < values.length; i++) {
      JsonObject data =
          snapshot
              .getAsJsonArray("records")
              .get(i)
              .getAsJsonObject()
              .getAsJsonObject("event")
              .getAsJsonObject("data");
      if (i == 1) data = data.getAsJsonObject("message");
      data.getAsJsonArray("content").get(0).getAsJsonObject().addProperty("text", values[i]);
    }
    ((NativeChatTranscript) field("transcript")).apply(snapshot);
    ((NativeChatDetails) field("details")).apply(snapshot);
    Method render = NativeChatPanel.class.getDeclaredMethod("renderTranscript");
    render.setAccessible(true);
    render.invoke(panel);
  }

  private Object field(String name) throws Exception {
    Field field = NativeChatPanel.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(panel);
  }

  private static LinearLayout buttons(View row) {
    assertTrue(row instanceof HorizontalScrollView);
    return (LinearLayout) ((HorizontalScrollView) row).getChildAt(0);
  }

  private Rect inRoot(View view) {
    Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
    root.offsetDescendantRectToMyCoords(view, bounds);
    return bounds;
  }

  private void layout(int width, int height) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
  }
}
