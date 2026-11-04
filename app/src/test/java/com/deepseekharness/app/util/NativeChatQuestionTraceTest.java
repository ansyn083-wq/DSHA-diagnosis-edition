package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import static com.deepseekharness.app.util.NativeChatQuestionTrace.Phase.*;
import static com.deepseekharness.app.util.NativeChatQuestionTrace.Reason.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NativeChatQuestionTraceTest {
  private final List<String> lines = new ArrayList<>();
  private final NativeChatQuestionTrace trace = new NativeChatQuestionTrace(lines::add);

  private static JsonObject json(String value) {
    return JsonParser.parseString(value).getAsJsonObject();
  }

  private String field(String line, String key) {
    for (String part : line.split(" "))
      if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
    fail("Missing field " + key + " in " + line);
    return "";
  }

  private void call(String name, String id) {
    trace.observe(
        "session-private",
        json(
            "{\"type\":\"event\",\"event\":{\"type\":\"tool/call\",\"seq\":1,\"data\":{\"name\":\""
                + name
                + "\",\"callId\":\""
                + id
                + "\",\"arguments\":\"private question\"}}}"));
  }

  private void result(String code) {
    trace.observe(
        "session-private",
        json(
            "{\"type\":\"event\",\"event\":{\"type\":\"tool/result\",\"seq\":2,\"data\":{\"error\":{\"code\":\""
                + code
                + "\",\"reason\":\"secret reason\"},\"message\":{\"toolCallId\":\"call-private\",\"content\":[{\"type\":\"text\",\"text\":\"secret answer\"}]}}}}"));
  }

  @Test
  public void onlyHashesIdentifiersAndNeverRetainsInjectedLines() {
    trace.mark(
        EVENT_RECEIVED,
        trace.ref("session-private", "call\nCookie=secret", "https://secret.invalid/?token=secret"),
        LIVE);
    assertEquals(1, lines.size());
    String line = lines.get(0);
    assertTrue(field(line, "s").matches("[a-f0-9]{32}"));
    assertTrue(field(line, "c").matches("[a-f0-9]{32}"));
    assertTrue(field(line, "e").matches("[a-f0-9]{32}"));
    assertFalse(line.contains("secret"));
    assertFalse(line.contains("private"));
    assertFalse(line.contains("\n"));
    assertTrue(line.length() < 300);
  }

  @Test
  public void correlatesOnlyMatchingIdentifierDomains() {
    trace.mark(TOOL_CALL, trace.ref("same", "same", "same"), LIVE);
    trace.mark(
        EVENT_RECEIVED,
        trace.eventRef(
            "same", json("{\"eventId\":\"same\",\"request\":{\"wait\":{\"callId\":\"same\"}}}")),
        LIVE);
    assertEquals(field(lines.get(0), "c"), field(lines.get(1), "c"));
    assertEquals(field(lines.get(0), "e"), field(lines.get(1), "e"));
    assertNotEquals(field(lines.get(0), "s"), field(lines.get(0), "c"));
  }

  @Test
  public void legacyEventDoesNotInventCallCorrelation() {
    trace.mark(
        EVENT_RECEIVED,
        trace.eventRef(
            "s", json("{\"eventId\":\"e\",\"request\":{\"questions\":[{\"id\":\"not-a-call\"}]}}")),
        LIVE);
    assertEquals("-", field(lines.get(0), "c"));
  }

  @Test
  public void toolFailuresAreVisibleWithoutAnyUiReceipt() {
    call("ask_user_question", "call-private");
    result("ASK_ABORTED");
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("phase=TOOL_CALL"));
    assertTrue(lines.get(1).contains("phase=TOOL_RESULT"));
    assertTrue(lines.get(1).contains("reason=ASK_ABORTED"));
    assertEquals(field(lines.get(0), "c"), field(lines.get(1), "c"));
    assertFalse(String.join("", lines).contains("secret"));
    assertFalse(String.join("", lines).contains("private"));
  }

  @Test
  public void distinguishesAllAllowlistedErrorsAndNeverUsesUntrustedCodes() {
    for (String code : List.of("ASK_ABORTED", "ASK_CANCELLED", "ASK_TIMED_OUT", "Cookie=secret")) {
      lines.clear();
      call("ask_user_question", "call-private");
      result(code);
      assertEquals(
          code.equals("Cookie=secret") ? "OTHER" : code,
          field(lines.get(lines.size() - 1), "reason"));
    }
  }

  @Test
  public void ignoresUnrelatedToolContentsEvenWhenTheyContainAskErrorText() {
    call("bash", "call-private");
    result("UNRELATED_ERROR");
    assertTrue(lines.isEmpty());
  }

  @Test
  public void labelsUnmatchedKnownErrorsWithoutClaimingKnownToolCall() {
    result("ASK_CANCELLED");
    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("phase=UNMATCHED_TOOL_RESULT"));
  }

  @Test
  public void snapshotHistoryIsDistinctFromLiveUiAndTurnEnd() {
    trace.observe(
        "s",
        json(
            "{\"type\":\"snapshot\",\"records\":[{\"event\":{\"type\":\"turn/start\",\"data\":{}}},{\"event\":{\"type\":\"tool/call\",\"data\":{\"name\":\"ask_user_question\",\"callId\":\"call-private\"}}},{\"event\":{\"type\":\"turn/end\",\"data\":{\"reason\":{\"kind\":\"cancelled\",\"secret\":\"private\"}}}}]}"));
    assertEquals(3, lines.size());
    for (String line : lines) assertEquals("SNAPSHOT", field(line, "source"));
    assertEquals("CANCELLED", field(lines.get(2), "reason"));
  }

  @Test
  public void repeatedSuppressionIsBoundedButTransitionsRemainDistinct() {
    var ref = trace.ref("s", "c", "e");
    for (int i = 0; i < 1000; i++) trace.once(SUPPRESSED, ref, HOST_NO_FOCUS);
    trace.once(SUPPRESSED, ref, HOST_NOT_RESUMED);
    trace.mark(CANCEL, ref, CANCEL_BUTTON);
    trace.mark(CANCEL, ref, DIALOG_CANCEL);
    assertEquals(4, lines.size());
    assertEquals("CANCEL_BUTTON", field(lines.get(2), "reason"));
    assertEquals("DIALOG_CANCEL", field(lines.get(3), "reason"));
  }

  @Test
  public void dialogSignalsReportObservedStateWithoutClaimingUserSawIt() {
    var ref = trace.ref("s", "", "e");
    trace.dialog(ref, SHOW_CALLBACK, true, false, false);
    trace.dialog(ref, DIALOG_CANCEL, true, true, false);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("showing=1 attached=0 focused=0"));
    assertEquals("DIALOG_CANCEL", field(lines.get(1), "reason"));
  }

  @Test
  public void invalidAndOversizedIdentifiersNeverLeakOrBreakObservation() {
    trace.mark(EVENT_RECEIVED, trace.ref(null, "x".repeat(4097), ""), LIVE);
    assertEquals("-", field(lines.get(0), "s"));
    assertEquals("invalid", field(lines.get(0), "c"));
    for (String malformed :
        List.of(
            "{}",
            "{\"type\":\"snapshot\",\"records\":[null,2,{},[]]}",
            "{\"type\":\"event\",\"event\":5}")) trace.observe("s", json(malformed));
    assertEquals(1, lines.size());
  }

  @Test
  public void failedLoggingNeverChangesQuestionControlFlow() {
    NativeChatQuestionTrace broken =
        new NativeChatQuestionTrace(
            line -> {
              throw new IllegalStateException("secret");
            });
    broken.mark(SUBMIT, broken.ref("s", "c", "e"), ANSWER);
    broken.observe(
        "s", json("{\"type\":\"event\",\"event\":{\"type\":\"turn/start\",\"data\":{}}}"));
  }

  @Test
  public void exactTurnCancellationSchemaReportsOnlyAllowlistedCause() {
    trace.observe(
        "s",
        json(
            "{\"type\":\"event\",\"event\":{\"type\":\"turn/end\",\"data\":{\"reason\":{\"kind\":\"aborted\",\"reason\":{\"kind\":\"parent\",\"reason\":\"secret hook reason\"}}}}}"));
    assertEquals("ABORTED", field(lines.get(0), "reason"));
    assertEquals("PARENT", field(lines.get(0), "cause"));
    assertFalse(lines.get(0).contains("secret"));
    trace.observe(
        "s",
        json(
            "{\"type\":\"event\",\"event\":{\"type\":\"turn/end\",\"data\":{\"reason\":{\"kind\":\"completed\"}}}}"));
    assertEquals("COMPLETE", field(lines.get(1), "reason"));
  }

  @Test
  public void untrustedTurnCauseAndStreamErrorNeverAppearInTrace() {
    trace.observe(
        "s",
        json(
            "{\"type\":\"event\",\"event\":{\"type\":\"turn/end\",\"data\":{\"reason\":{\"kind\":\"aborted\",\"reason\":{\"kind\":\"Cookie=secret\"}}}}}"));
    assertEquals("OTHER", field(lines.get(0), "cause"));
    assertEquals(OTHER, NativeChatQuestionTrace.streamFailure("Cookie=secret"));
    assertEquals(STREAM_FRAME, NativeChatQuestionTrace.streamFailure("stream-frame"));
  }

  @Test
  public void asynchronousWriterNeverWaitsForFileIoAndBoundsPendingLines() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var drained = new java.util.concurrent.CountDownLatch(257);
    var output = new java.util.concurrent.CopyOnWriteArrayList<String>();
    NativeChatQuestionTrace async =
        NativeChatQuestionTrace.asynchronous(
            line -> {
              entered.countDown();
              try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              output.add(line);
              drained.countDown();
            });
    try {
      async.mark(EVENT_RECEIVED, async.ref("s", "c", "e"), LIVE);
      assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue("A slow writer must not block the question thread", output.isEmpty());
      for (int i = 0; i < 500; i++) async.mark(SUPPRESSED, async.ref("s", "c", "e"), HOST_NO_FOCUS);
      assertTrue(output.isEmpty());
    } finally {
      release.countDown();
    }
    assertTrue(drained.await(3, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(257, output.size());
    assertEquals("EVENT_RECEIVED", field(output.get(0), "phase"));
  }
}
