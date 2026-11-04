package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.NativeChatDetails;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.NativeChatStream;
import com.deepseekharness.app.util.NativeChatTranscript;
import com.deepseekharness.app.util.PreviewPageSession.Identity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** 实际 Panel + 弹窗 + RPC 回归；不运行 Activity.onCreate、DSH、真实模型或设备服务。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = NativeChatPanelQuestionTest.TestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeChatPanelQuestionTest {
  public static final class TestApplication extends DshaApp {
    Identity ready;
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
            };
      return controller;
    }
  }

  private ChatHomeActivity host;
  private NativeChatPanel panel;
  private MockWebServer server;
  private String origin;
  private final List<JsonObject> results = new CopyOnWriteArrayList<>();
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private final List<AlertDialog> dialogs = new CopyOnWriteArrayList<>();
  private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
  private final Map<String, CountDownLatch> arrivals = new ConcurrentHashMap<>();
  private final OkHttpClient sockets = new OkHttpClient();
  private volatile boolean terminalEvents;
  private volatile boolean failClaim;
  private final CountDownLatch streamFailed = new CountDownLatch(1);
  private final CountDownLatch waitOpened = new CountDownLatch(1);
  private final List<JsonObject> waitOpenings = new CopyOnWriteArrayList<>();

  @Before
  public void prepare() throws Exception {
    TestApplication app = (TestApplication) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) throws InterruptedException {
            if ("/api/remote.mux".equals(incoming.getPath())) return socketResponse();
            JsonObject envelope =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            String method = envelope.get("method").getAsString();
            methods.add(method);
            String value = "null";
            if ("$events/result".equals(method)) {
              JsonObject args = envelope.getAsJsonObject("payload").getAsJsonObject("args");
              results.add(args.deepCopy());
              String event = args.get("eventId").getAsString();
              arrivals.computeIfAbsent(event, ignored -> new CountDownLatch(1)).countDown();
              CountDownLatch hold = holds.get(event);
              if (hold != null) hold.await(5, TimeUnit.SECONDS);
            } else if ("session/projections".equals(method)) {
              value =
                  "{\"values\":{\"userQuestions\":{\"active\":[]},\"modelSelection\":{\"next\":{\"provider\":\"deepseek-official\",\"model\":\"fixture\"}}}}";
            } else if ("session/prompt".equals(method)) value = "{\"accepted\":true}";
            else if ("session/list".equals(method)) value = "{\"items\":[]}";
            JsonObject response =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            response.add("rpcId", envelope.get("rpcId"));
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(response.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    origin = "http://127.0.0.1:" + server.getPort();
    app.ready = new Identity(1, "fixture-run", origin);
    host = Robolectric.buildActivity(ChatHomeActivity.class).get();
    host.setTheme(R.style.Theme_DeepseekHarness);
    host.setContentView(LayoutInflater.from(host).inflate(R.layout.activity_diagnostic_home, null));
    panel = new NativeChatPanel(host, host.findViewById(R.id.diagnostic_draft), null);
    set("sessionId", "fixture-session");
    set("identity", app.ready);
    set("cookie", "dsh-auth-test=synthetic");
    long epoch = (Long) get("epoch");
    set("rpc", new NativeChatRpc(origin, "dsh-auth-test=synthetic", () -> currentEpoch() == epoch));
    event(
        JsonParser.parseString("{\"type\":\"ready\",\"clientId\":\"fixture-client\"}")
            .getAsJsonObject());
  }

  @After
  public void cleanup() throws Exception {
    holds.values().forEach(CountDownLatch::countDown);
    if (panel != null) {
      try {
        drain();
      } finally {
        panel.destroy();
      }
    }
    for (AlertDialog dialog : dialogs) if (dialog.isShowing()) dialog.dismiss();
    idle();
    sockets.dispatcher().executorService().shutdownNow();
    sockets.connectionPool().evictAll();
    if (server != null) server.close();
  }

  @Test
  public void twoQuestionsWaitInOrderWithoutPrematureDelegation() throws Exception {
    event(question("first", "Question A"));
    event(question("second", "Question B"));
    drain();
    assertTrue("A busy question must queue rather than send next", results.isEmpty());
    AlertDialog first = openPending();
    assertText(first, "Question A");
    submit(first);
    drain();
    assertResult(0, "first", "result");
    AlertDialog second = openPending();
    assertText(second, "Question B");
    submit(second);
    drain();
    assertResult(1, "second", "result");
    assertEquals(2, results.size());
  }

  @Test
  public void historicalFailedToolCardStaysHiddenWithoutDiscardingItsDetails() throws Exception {
    applyTranscript(
        """
        {"type":"snapshot","cursor":4,"records":[
          {"event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"Visible request"}]}}},
          {"event":{"seq":2,"type":"tool/call","data":{"callId":"old-call","name":"hidden_old_tool","arguments":"old arguments"}}},
          {"event":{"seq":3,"type":"tool/result","data":{"message":{"toolCallId":"old-call","isError":true,"content":[{"type":"text","text":"old error details"}]}}}},
          {"event":{"seq":4,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"Visible explanation"}]}}}}
        ]}
        """);
    List<NativeChatDetails.Tool> tools = ((NativeChatDetails) get("details")).tools();
    assertEquals(1, tools.size());
    assertEquals("old-call", tools.get(0).id());
    assertEquals("old arguments", tools.get(0).arguments());
    assertEquals("old error details", tools.get(0).result());
    assertEquals("error", tools.get(0).state());
    View messages = host.findViewById(R.id.native_chat_messages);
    assertTrue(hasText(messages, "Visible request"));
    assertTrue(hasText(messages, "Visible explanation"));
    assertFalse(
        "Historical failed tool cards must not appear in the conversation",
        hasText(messages, "hidden_old_tool"));
    assertFalse(hasText(messages, "old error details"));
  }

  @Test
  public void newSuccessfulToolCardStaysHiddenWhileReplyAndAttachmentRemainVisible()
      throws Exception {
    applyTranscript(
        """
        {"type":"snapshot","cursor":0,"records":[]}
        """);
    applyTranscript(
        """
        {"type":"event","event":{"seq":1,"type":"tool/call","data":{"callId":"new-call","name":"hidden_new_tool","arguments":"new arguments"}}}
        """);
    applyTranscript(
        """
        {"type":"event","event":{"seq":2,"type":"tool/result","data":{"message":{"toolCallId":"new-call","content":[
          {"type":"text","text":"new success details"},
          {"type":"file","attachment":{"attachmentId":"output-file","name":"visible-output.txt","bytes":12}}
        ]}}}}
        """);
    applyTranscript(
        """
        {"type":"event","event":{"seq":3,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"Visible final reply"}]}}}}
        """);
    NativeChatDetails details = (NativeChatDetails) get("details");
    assertEquals(1, details.tools().size());
    assertEquals("new-call", details.tools().get(0).id());
    assertEquals("new arguments", details.tools().get(0).arguments());
    assertEquals("new success details", details.tools().get(0).result());
    assertEquals("completed", details.tools().get(0).state());
    assertEquals("output-file", details.attachments().get(0).id());
    View messages = host.findViewById(R.id.native_chat_messages);
    assertTrue(hasText(messages, "Visible final reply"));
    assertTrue(hasText(messages, "visible-output.txt"));
    assertFalse(
        "New successful tool cards must not appear in the conversation",
        hasText(messages, "hidden_new_tool"));
    assertFalse(hasText(messages, "new success details"));
  }

  @Test
  public void questionThenApprovalPreservesArrivalOrder() throws Exception {
    event(question("first", "Question A"));
    event(approval("second", "Approval B"));
    AlertDialog first = openPending();
    assertText(first, "Question A");
    submit(first);
    drain();
    AlertDialog second = openPending();
    assertText(second, "Approval B");
    second.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    drain();
    assertResult(0, "first", "result");
    assertResult(1, "second", "result");
    assertEquals("rejected", results.get(1).getAsJsonObject("outcome").get("value").getAsString());
  }

  @Test
  public void approvalThenQuestionPreservesArrivalOrder() throws Exception {
    event(approval("first", "Approval A"));
    event(question("second", "Question B"));
    AlertDialog first = openPending();
    assertText(first, "Approval A");
    first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    drain();
    AlertDialog second = openPending();
    assertText(second, "Question B");
    submit(second);
    drain();
    assertResult(0, "first", "result");
    assertResult(1, "second", "result");
  }

  @Test
  public void duplicateEventNeverDelegatesOrAnswersTwice() throws Exception {
    JsonObject frame = question("same", "Question A");
    event(frame);
    event(frame.deepCopy());
    drain();
    assertTrue(results.isEmpty());
    submit(openPending());
    drain();
    event(frame.deepCopy());
    drain();
    assertEquals(1, results.size());
    assertResult(0, "same", "result");
  }

  @Test
  public void queuedRemoteCancelRemovesOnlyItsMatchingEvent() throws Exception {
    event(question("first", "Question A"));
    event(question("removed", "Question B"));
    event(question("last", "Question C"));
    cancel("removed");
    drain();
    assertTrue(results.isEmpty());
    submit(openPending());
    drain();
    AlertDialog last = openPending();
    assertText(last, "Question C");
    submit(last);
    drain();
    assertEquals(2, results.size());
    assertResult(1, "last", "result");
  }

  @Test
  public void unsupportedAndOtherSessionEventsDelegateOnlyOnce() throws Exception {
    JsonObject other = question("other-session-event", "Not this conversation");
    other.addProperty("agentId", "another-session");
    event(other);
    event(other.deepCopy());
    JsonObject unsupported = base("unsupported", "unknown/request");
    unsupported.add("request", new JsonObject());
    event(unsupported);
    event(unsupported.deepCopy());
    drain();
    assertEquals(2, results.size());
    assertResult(0, "other-session-event", "next");
    assertResult(1, "unsupported", "next");
    assertEquals(View.GONE, host.findViewById(R.id.native_chat_question).getVisibility());
  }

  @Test
  public void boundedQueueOverflowDelegatesOnlyOverflowAndPreservesHead() throws Exception {
    for (int i = 0; i < 33; i++) event(question("queued-" + i, "Question " + i));
    event(question("queued-32", "Question 32"));
    drain();
    assertEquals(1, results.size());
    assertResult(0, "queued-32", "next");
    assertText(openPending(), "Question 0");
  }

  @Test
  public void passiveBackRetainsHeadAndExplicitButtonCanReopen() throws Exception {
    event(question("first", "Question A"));
    AlertDialog first = openPending();
    first.onBackPressed();
    idle();
    drain();
    assertTrue(results.isEmpty());
    AlertDialog reopened = openPending();
    assertText(reopened, "Question A");
    submit(reopened);
    drain();
    assertEquals(1, results.size());
    assertResult(0, "first", "result");
  }

  @Test
  public void suspendedOldDialogButtonsCannotSubmitOrReject() throws Exception {
    event(question("first", "Question A"));
    AlertDialog old = openPending();
    firstInput(old.getWindow().getDecorView()).setText("synthetic answer");
    panel.suspend();
    old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    old.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    drain();
    assertTrue(results.isEmpty());
    assertFalse(old.isShowing());
  }

  @Test
  public void oldApprovalDismissCannotClearReplacementDialogOwner() throws Exception {
    event(approval("old", "Approval A"));
    AlertDialog old = openPending();
    cancel("old");
    event(approval("new", "Approval B"));
    host.findViewById(R.id.native_chat_question).performClick();
    AlertDialog replacement = (AlertDialog) get("approvalDialog");
    assertNotNull(replacement);
    dialogs.add(replacement);
    assertNotSame(old, replacement);
    assertTrue(replacement.isShowing());
    idle();
    assertSame(
        "Old queued dismiss must not clear the new owner", replacement, get("approvalDialog"));
    cancel("new");
    idle();
    assertFalse(replacement.isShowing());
    assertTrue(results.isEmpty());
  }

  @Test
  public void oldAnswerCompletionCannotClearNewHeadsAnsweringState() throws Exception {
    holds.put("blocked-a", new CountDownLatch(1));
    holds.put("blocked-b", new CountDownLatch(1));
    arrivals.put("blocked-a", new CountDownLatch(1));
    arrivals.put("blocked-b", new CountDownLatch(1));
    event(question("blocked-a", "Question A"));
    submit(openPending());
    assertTrue(arrivals.get("blocked-a").await(3, TimeUnit.SECONDS));
    event(question("blocked-b", "Question B"));
    cancel("blocked-a");
    AlertDialog second = openPending();
    assertText(second, "Question B");
    submit(second);
    holds.get("blocked-a").countDown();
    assertTrue(arrivals.get("blocked-b").await(3, TimeUnit.SECONDS));
    idle();
    assertFalse(
        "B must remain pending while B's RPC is in flight",
        host.findViewById(R.id.native_chat_question).isEnabled());
    holds.get("blocked-b").countDown();
    drain();
    assertEquals(2, results.size());
  }

  @Test
  public void terminalEventChannelPreventsPanelPromptAndKeepsDraft() throws Exception {
    terminalEvents = true;
    NativeChatStream stream = createStream();
    set("stream", stream);
    assertTrue(streamFailed.await(3, TimeUnit.SECONDS));
    ((EditText) host.findViewById(R.id.diagnostic_draft)).setText("synthetic prompt");
    host.findViewById(R.id.native_chat_send).performClick();
    drain();
    assertTrue(
        "The model-preflight request proves submit reached the readiness gate",
        methods.contains("session/projections"));
    assertFalse(methods.contains("session/prompt"));
    assertEquals(
        "synthetic prompt",
        ((EditText) host.findViewById(R.id.diagnostic_draft)).getText().toString());
  }

  @Test
  public void validEventReadyAllowsPanelPromptAsPositiveControl() throws Exception {
    NativeChatStream stream = createStream();
    set("stream", stream);
    stream.awaitQuestionEvents();
    ((EditText) host.findViewById(R.id.diagnostic_draft)).setText("synthetic prompt");
    host.findViewById(R.id.native_chat_send).performClick();
    drain();
    assertEquals(1, methods.stream().filter("session/prompt"::equals).count());
  }

  @Test
  public void submittedTimedAnswerWinsOverAlreadyQueuedTimeoutCallback() throws Exception {
    event(timedQuestion("timed", "Timed question"));
    awaitWaitReady();
    AlertDialog dialog = openPending();
    Runnable timeout = capturedTimeout();
    timeout.run();
    submit(dialog);
    drain();
    assertEquals(1, results.size());
    assertResult(0, "timed", "result");
    assertEquals(
        "fixture-session",
        waitOpenings
            .get(0)
            .getAsJsonObject("payload")
            .getAsJsonObject("args")
            .get("agentId")
            .getAsString());
    assertEquals(
        "call-timed",
        waitOpenings
            .get(0)
            .getAsJsonObject("payload")
            .getAsJsonObject("args")
            .get("callId")
            .getAsString());
  }

  @Test
  public void timedOutHeadRejectsOnceAndIgnoresItsStaleSubmitButton() throws Exception {
    event(timedQuestion("timed", "Timed question"));
    awaitWaitReady();
    AlertDialog dialog = openPending();
    capturedTimeout().run();
    idle();
    submit(dialog);
    drain();
    assertEquals(1, results.size());
    assertResult(0, "timed", "rejected");
    assertEquals(
        "ASK_TIMED_OUT",
        results
            .get(0)
            .getAsJsonObject("outcome")
            .getAsJsonObject("error")
            .get("code")
            .getAsString());
  }

  @Test
  public void suspendedTimedClaimCannotRejectFromLateTimeoutCallback() throws Exception {
    event(timedQuestion("timed", "Timed question"));
    awaitWaitReady();
    Runnable late = capturedTimeout();
    panel.suspend();
    late.run();
    drain();
    assertTrue(results.isEmpty());
  }

  @Test
  public void canceledQueuedTimedQuestionNeverClaimsOrReplies() throws Exception {
    event(question("first", "Question A"));
    event(timedQuestion("queued-timed", "Timed question"));
    assertNull("Only the FIFO head may open a timed claim", get("questionWait"));
    cancel("queued-timed");
    drain();
    assertTrue(results.isEmpty());
    assertTrue(waitOpenings.isEmpty());
    submit(openPending());
    drain();
    assertEquals(1, results.size());
    assertResult(0, "first", "result");
    assertEquals(View.GONE, host.findViewById(R.id.native_chat_question).getVisibility());
  }

  @Test
  public void sessionSwitchRetiresTimedClaimAndStaleCallbacks() throws Exception {
    event(timedQuestion("timed", "Timed question"));
    awaitWaitReady();
    Runnable late = capturedTimeout();
    AlertDialog old = openPending();
    Field layout = ChatHomeActivity.class.getDeclaredField("homeLayout");
    layout.setAccessible(true);
    layout.set(host, host.findViewById(R.id.native_chat_home));
    Method select = NativeChatPanel.class.getDeclaredMethod("selectConversation", String.class);
    select.setAccessible(true);
    select.invoke(panel, "");
    late.run();
    submit(old);
    drain();
    assertTrue(results.isEmpty());
    assertFalse(old.isShowing());
    assertNull(get("questionWait"));
    assertEquals(View.GONE, host.findViewById(R.id.native_chat_question).getVisibility());
  }

  @Test
  public void malformedTimedQuestionDelegatesBeforeQueueOrClaim() throws Exception {
    JsonObject invalid = timedQuestion("malformed", "Invalid");
    JsonArray broken = new JsonArray();
    broken.add(com.google.gson.JsonNull.INSTANCE);
    invalid.getAsJsonObject("request").add("questions", broken);
    event(invalid);
    drain();
    assertNull("An unrenderable request must not take a timed claim", get("questionWait"));
    assertTrue(waitOpenings.isEmpty());
    assertEquals(1, results.size());
    assertResult(0, "malformed", "next");
    assertEquals(View.GONE, host.findViewById(R.id.native_chat_question).getVisibility());
  }

  @Test
  public void timedClaimTransportErrorDisconnectsWithoutDelegatingOrRejecting() throws Exception {
    failClaim = true;
    event(timedQuestion("timed", "Timed question"));
    assertTrue(waitOpened.await(3, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (get("rpc") != null && results.isEmpty() && System.nanoTime() < deadline) {
      idle();
      Thread.sleep(5);
    }
    drain();
    assertTrue("Transport loss must not become a business next/rejection", results.isEmpty());
    assertNull("Reconnect is required to reestablish the delivery identity", get("rpc"));
    assertNull(get("questionWait"));
  }

  private void awaitWaitReady() throws Exception {
    assertTrue("Timed head must open its exact Host claim", waitOpened.await(3, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!(Boolean) get("questionWaitReady") && System.nanoTime() < deadline) {
      idle();
      Thread.sleep(5);
    }
    assertEquals(true, get("questionWaitReady"));
  }

  private Runnable capturedTimeout() throws Exception {
    Object wait = get("questionWait");
    assertNotNull(wait);
    Field callback = wait.getClass().getDeclaredField("timedOut");
    callback.setAccessible(true);
    return (Runnable) callback.get(wait);
  }

  private static JsonObject timedQuestion(String id, String text) {
    JsonObject frame = question(id, text), wait = new JsonObject();
    wait.addProperty("timed", true);
    wait.addProperty("callId", "call-" + id);
    frame.getAsJsonObject("request").add("wait", wait);
    return frame;
  }

  private NativeChatStream createStream() {
    return NativeChatStream.open(
        sockets,
        origin,
        "dsh-auth-test=synthetic",
        "fixture-session",
        () -> true,
        value -> {},
        value -> {},
        error -> streamFailed.countDown());
  }

  private MockResponse socketResponse() {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onMessage(WebSocket socket, String message) {
                JsonObject open = JsonParser.parseString(message).getAsJsonObject();
                if ("cancel".equals(open.get("type").getAsString())) return;
                String endpoint = open.get("endpoint").getAsString();
                JsonObject frame = new JsonObject();
                frame.add("streamId", open.get("streamId"));
                if ("userQuestions/attachWait".equals(endpoint)) {
                  waitOpenings.add(open.deepCopy());
                  waitOpened.countDown();
                  if (failClaim) frame.addProperty("type", "error");
                  else {
                    frame.addProperty("type", "item");
                    frame.add("value", JsonParser.parseString("{\"remainingMs\":60000}"));
                  }
                } else if ("$events".equals(endpoint) && !terminalEvents) {
                  frame.addProperty("type", "item");
                  frame.add(
                      "value",
                      JsonParser.parseString(
                          "{\"type\":\"ready\",\"clientId\":\"stream-client\"}"));
                } else frame.addProperty("type", "end");
                if (!"session/follow".equals(endpoint)) socket.send(frame.toString());
              }

              @Override
              public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
              }
            });
  }

  private void drain() throws Exception {
    for (int i = 0; i < 3; i++) {
      for (String field : List.of("io", "questionIo")) {
        ExecutorService executor = (ExecutorService) get(field);
        if (!executor.isShutdown()) executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      }
      idle();
    }
  }

  private AlertDialog openPending() throws Exception {
    assertEquals(View.VISIBLE, host.findViewById(R.id.native_chat_question).getVisibility());
    assertTrue(
        "Current head must be available for interaction",
        host.findViewById(R.id.native_chat_question).isEnabled());
    host.findViewById(R.id.native_chat_question).performClick();
    idle();
    AlertDialog question = (AlertDialog) get("questionDialog"),
        approval = (AlertDialog) get("approvalDialog");
    AlertDialog shown = question != null && question.isShowing() ? question : approval;
    assertNotNull("A pending head must have an actual dialog", shown);
    assertTrue(shown.isShowing());
    dialogs.add(shown);
    return shown;
  }

  private void submit(AlertDialog dialog) {
    EditText field = firstInput(dialog.getWindow().getDecorView());
    assertNotNull(field);
    field.setText("synthetic answer");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  }

  private static EditText firstInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        EditText result = firstInput(((ViewGroup) view).getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private static boolean hasText(View view, String expected) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected))
      return true;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (hasText(((ViewGroup) view).getChildAt(i), expected)) return true;
    return false;
  }

  private static void assertText(AlertDialog dialog, String expected) {
    assertTrue(
        "Expected the head's own content", hasText(dialog.getWindow().getDecorView(), expected));
  }

  private void assertResult(int index, String eventId, String kind) {
    assertTrue(results.size() > index);
    JsonObject result = results.get(index);
    assertEquals(eventId, result.get("eventId").getAsString());
    assertEquals("fixture-client", result.get("clientId").getAsString());
    assertEquals(kind, result.getAsJsonObject("outcome").get("kind").getAsString());
  }

  private static JsonObject question(String id, String text) {
    JsonObject frame = base(id, "user-questions/request");
    JsonObject q = new JsonObject();
    q.addProperty("id", "question");
    q.addProperty("question", text);
    JsonArray questions = new JsonArray();
    questions.add(q);
    JsonObject request = new JsonObject();
    request.add("questions", questions);
    frame.add("request", request);
    return frame;
  }

  private static JsonObject approval(String id, String reason) {
    JsonObject frame = base(id, "approval/request"), request = new JsonObject();
    request.addProperty("toolName", "fixture-tool");
    request.addProperty("callId", "call-" + id);
    request.addProperty("reason", reason);
    frame.add("request", request);
    return frame;
  }

  private static JsonObject base(String id, String event) {
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "waterfall");
    frame.addProperty("event", event);
    frame.addProperty("eventId", id);
    frame.addProperty("agentId", "fixture-session");
    return frame;
  }

  private void cancel(String id) throws Exception {
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "cancel");
    frame.addProperty("eventId", id);
    event(frame);
  }

  private void event(JsonObject frame) throws Exception {
    Method method =
        NativeChatPanel.class.getDeclaredMethod("handleQuestionEvent", JsonObject.class);
    method.setAccessible(true);
    method.invoke(panel, frame);
  }

  private void applyTranscript(String json) throws Exception {
    JsonObject frame = JsonParser.parseString(json).getAsJsonObject();
    ((NativeChatTranscript) get("transcript")).apply(frame);
    ((NativeChatDetails) get("details")).apply(frame);
    Method render = NativeChatPanel.class.getDeclaredMethod("renderTranscript");
    render.setAccessible(true);
    render.invoke(panel);
  }

  private Object get(String name) throws Exception {
    Field f = NativeChatPanel.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(panel);
  }

  private void set(String name, Object value) throws Exception {
    Field f = NativeChatPanel.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(panel, value);
  }

  private long currentEpoch() {
    try {
      return (Long) get("epoch");
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static void idle() {
    ShadowLooper.shadowMainLooper().idle();
  }
}
