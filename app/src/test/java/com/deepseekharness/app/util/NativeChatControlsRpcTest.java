package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.Dispatcher;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 固定后端契约的真实本机 HTTP 检查，不能把事件序号当作消息 ID。 */
public final class NativeChatControlsRpcTest {
  private static final String COMMANDS =
      "[{\"name\":\"compact\",\"description\":\"Compact history\",\"input\":{\"hint\":\"Instructions\"}},"
          + "{\"name\":\"permission\",\"definitionId\":\"@deepseek-ai/dsh-permission-presets\",\"description\":\"Switch permissions\",\"input\":{\"hint\":\"<preset>\"}}]";
  private static final String GOAL =
      "{\"id\":\"goal-1\",\"revision\":3,\"objective\":\"Finish tests\",\"phase\":\"paused\","
          + "\"maxGoalRounds\":8,\"roundsStarted\":2,\"createdAt\":10,\"updatedAt\":20,\"activation\":\"disarmed\"}";
  private static final String FEEDBACK =
      "{\"messageId\":\"message-durable\",\"rating\":\"negative\",\"note\":\"Missing details\","
          + "\"category\":\"other\",\"version\":\"opaque-v3\",\"createdAt\":1,\"updatedAt\":2}";
  private MockWebServer server;
  private NativeChatControlsRpc client;
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final AtomicBoolean replaceDuringCall = new AtomicBoolean();
  private final List<JsonObject> sent = new ArrayList<>();
  private String commands = COMMANDS;
  private String goal = GOAL;
  private String permission = "standard";
  private String feedback = "{\"ok\":true,\"value\":{\"items\":[" + FEEDBACK + "]}}";
  private String mutation =
      "{\"commandId\":\"command-1\",\"result\":{\"kind\":\"success\",\"text\":\"Done\"}}";

  @Before
  public void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            JsonObject request =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            assertEquals("dsh-auth-test=secret", incoming.getHeader("Cookie"));
            assertEquals("/api/" + request.get("method").getAsString(), incoming.getPath());
            synchronized (sent) {
              sent.add(request);
            }
            String method = request.get("method").getAsString();
            String value =
                switch (method) {
                  case "commands/list" -> commands;
                  case "goals/get" -> goal;
                  case "permissionPresets/catalog" ->
                      "{\"options\":[{\"value\":\"standard\",\"name\":\"Standard\",\"description\":\"Ask before writes\"},{\"value\":\"full\",\"name\":\"Full\",\"description\":\"No sandbox or prompts\"}],\"defaultOptions\":[],\"defaultPreset\":\"standard\"}";
                  case "session/projections" ->
                      "{\"asOfSeq\":8,\"values\":{\"permissions\":{\"currentValue\":\""
                          + permission
                          + "\"}}}";
                  case "messageFeedback/list" -> feedback;
                  default -> mutation;
                };
            JsonObject response =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            response.add("rpcId", request.get("rpcId"));
            if (replaceDuringCall.get()) current.set(false);
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(response.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    client =
        new NativeChatControlsRpc(
            new NativeChatRpc(
                "http://127.0.0.1:" + server.getPort(), "dsh-auth-test=secret", current::get),
            "session-1");
  }

  @After
  public void stop() throws IOException {
    if (server != null) server.shutdown();
  }

  @Test
  public void discoveryOnlyListsCommandsForExplicitSession() throws Exception {
    var result = client.commands();
    assertEquals("compact", result.get(0).name());
    assertEquals("Instructions", result.get(0).hint());
    assertEquals("commands/list", last().get("method").getAsString());
    assertEquals(JsonParser.parseString("{\"agentId\":\"session-1\"}"), args());
    assertEquals(1, sent.size());
  }

  @Test
  public void executionRevalidatesDescriptorAndSendsExactLineWithoutRequestWrapper()
      throws Exception {
    var command = client.commands().get(0);
    var result = client.execute(command, "focus on tests");
    assertTrue(result.success());
    assertEquals("Done", result.text());
    assertEquals(
        JsonParser.parseString(
            "{\"agentId\":\"session-1\",\"line\":\"/compact focus on tests\",\"submittedAttachments\":[]}"),
        args());
    assertEquals(3, sent.size());
  }

  @Test
  public void changedCommandIsNotExecuted() throws Exception {
    var command = client.commands().get(0);
    commands = "[{\"name\":\"compact\",\"description\":\"Delete history\"}]";
    assertThrows(IOException.class, () -> client.execute(command, ""));
    assertEquals("commands/list", last().get("method").getAsString());
  }

  @Test
  public void unknownExecutionIsNeverReportedAsSuccess() throws Exception {
    var command = client.commands().get(0);
    mutation = "null";
    assertThrows(IOException.class, () -> client.execute(command, ""));
    mutation =
        "{\"commandId\":\"command-2\",\"result\":{\"kind\":\"error\",\"text\":\"Rejected\"}}";
    assertFalse(client.execute(command, "").success());
  }

  @Test
  public void goalsSendExactObservedRevisionAndNeverReplacementViewFields() throws Exception {
    var observed = client.goal();
    assertEquals("paused", observed.phase());
    observed.record().addProperty("revision", 999);
    mutation = GOAL.replace("\"revision\":3", "\"revision\":4");
    client.changeGoal(observed, "resume");
    assertEquals("goals/resume", last().get("method").getAsString());
    assertEquals(
        JsonParser.parseString(
            "{\"agentId\":\"session-1\",\"ref\":{\"id\":\"goal-1\",\"revision\":3}}"),
        args());
    client.editGoal(observed, "Revised goal", 12);
    assertEquals(
        JsonParser.parseString(
            "{\"agentId\":\"session-1\",\"ref\":{\"id\":\"goal-1\",\"revision\":3},\"request\":{\"objective\":\"Revised goal\",\"maxGoalRounds\":12}}"),
        args());
  }

  @Test
  public void creationUsesExplicitRoundBudgetAndClearRetainsExactReference() throws Exception {
    goal = "null";
    assertNull(client.goal());
    mutation = "{\"ref\":{\"id\":\"new-goal\",\"revision\":1}}";
    client.createGoal("Finish safely", 5);
    assertEquals(
        JsonParser.parseString(
            "{\"agentId\":\"session-1\",\"request\":{\"objective\":\"Finish safely\",\"maxGoalRounds\":5}}"),
        args());
    goal = GOAL;
    var observed = client.goal();
    mutation = "{\"id\":\"goal-1\",\"revision\":4}";
    client.changeGoal(observed, "clear");
    assertEquals("goals/clear", last().get("method").getAsString());
    assertEquals(3, args().getAsJsonObject("ref").get("revision").getAsInt());
  }

  @Test
  public void invalidGoalInputsDoNotContactGateway() {
    assertThrows(IllegalArgumentException.class, () -> client.createGoal(" ", 5));
    assertThrows(IllegalArgumentException.class, () -> client.createGoal("Goal", 0));
    assertThrows(
        IllegalArgumentException.class, () -> client.createGoal("Goal", 9007199254740992L));
    assertEquals(0, sent.size());
  }

  @Test
  public void permissionSelectionUsesOfficialCommandAndDoesNotChangeDefault() throws Exception {
    var snapshot = client.permissions();
    assertEquals("standard", snapshot.currentValue());
    assertEquals("No sandbox or prompts", snapshot.options().get(1).description());
    client.selectPermission(snapshot, snapshot.options().get(1));
    assertEquals("commands/execute", last().get("method").getAsString());
    assertEquals("/permission full", args().get("line").getAsString());
    assertEquals("session-1", args().get("agentId").getAsString());
    assertFalse(
        sent.stream().anyMatch(row -> row.get("method").getAsString().startsWith("settings/")));
  }

  @Test
  public void changedPermissionSelectionFailsClosedBeforeExecution() throws Exception {
    var snapshot = client.permissions();
    permission = "full";
    assertThrows(
        IOException.class, () -> client.selectPermission(snapshot, snapshot.options().get(1)));
    assertFalse(
        sent.stream().anyMatch(row -> "commands/execute".equals(row.get("method").getAsString())));
  }

  @Test
  public void feedbackReadsDurableTargetAndUpdatesWithOpaqueObservedVersion() throws Exception {
    var snapshot = client.feedback("message-durable");
    assertEquals("negative", snapshot.rating());
    assertEquals(JsonParser.parseString("{\"request\":{\"sessionId\":\"session-1\"}}"), args());
    mutation = "{\"ok\":true,\"value\":" + FEEDBACK + "}";
    assertTrue(client.putFeedback(snapshot, "positive", "Useful").success());
    assertEquals(
        JsonParser.parseString(
            "{\"request\":{\"sessionId\":\"session-1\",\"messageId\":\"message-durable\",\"rating\":\"positive\",\"note\":\"Useful\",\"category\":\"other\",\"ifVersion\":\"opaque-v3\"}}"),
        args());
  }

  @Test
  public void absentFeedbackUsesNullCASAndBlankNoteIsOmitted() throws Exception {
    feedback = "{\"ok\":true,\"value\":{\"items\":[]}}";
    var snapshot = client.feedback("message-durable");
    assertFalse(snapshot.exists());
    mutation = "{\"ok\":true,\"value\":" + FEEDBACK + "}";
    client.putFeedback(snapshot, "positive", " ");
    JsonObject request = args().getAsJsonObject("request");
    assertTrue(request.get("ifVersion").isJsonNull());
    assertFalse(request.has("note"));
    assertFalse(request.has("category"));
  }

  @Test
  public void feedbackConflictDoesNotOverwriteOrRetry() throws Exception {
    var snapshot = client.feedback("message-durable");
    mutation = "{\"ok\":false,\"error\":{\"code\":\"version-conflict\",\"current\":null}}";
    var result = client.putFeedback(snapshot, "positive", "Good");
    assertFalse(result.success());
    assertEquals("version-conflict", result.code());
    assertEquals(2, sent.size());
  }

  @Test
  public void feedbackDeleteRequiresExistingSnapshotVersion() throws Exception {
    var snapshot = client.feedback("message-durable");
    mutation = "{\"ok\":true,\"value\":{\"absent\":true}}";
    assertTrue(client.deleteFeedback(snapshot).success());
    assertEquals(
        JsonParser.parseString(
            "{\"request\":{\"sessionId\":\"session-1\",\"messageId\":\"message-durable\",\"ifVersion\":\"opaque-v3\"}}"),
        args());
  }

  @Test
  public void malformedSnapshotsAndWrongSessionCannotReachMutation() throws Exception {
    var observed = client.goal();
    var other =
        new NativeChatControlsRpc(
            new NativeChatRpc(
                "http://127.0.0.1:" + server.getPort(), "dsh-auth-test=secret", current::get),
            "other");
    assertThrows(IllegalArgumentException.class, () -> other.changeGoal(observed, "pause"));
    goal = "{\"id\":\"goal-1\",\"revision\":1.5}";
    assertThrows(IOException.class, client::goal);
    feedback =
        "{\"ok\":false,\"error\":{\"code\":\"session-not-found\",\"sessionId\":\"session-1\"}}";
    assertThrows(IOException.class, () -> client.feedback("message-durable"));
  }

  @Test
  public void serviceReplacementBeforeOrDuringReadDiscardsResult() {
    current.set(false);
    assertThrows(IOException.class, client::commands);
    assertEquals(0, sent.size());
    current.set(true);
    replaceDuringCall.set(true);
    assertThrows(IOException.class, client::commands);
  }

  @Test
  public void gateBlocksDuplicateAndUncertainMutationsUntilConfirmedRead() {
    var gate = new NativeChatControlsRpc.RequestGate();
    long first = gate.begin(true);
    assertTrue(first > 0);
    assertEquals(0, gate.begin(true));
    assertTrue(gate.finish(first, false));
    assertEquals(0, gate.begin(true));
    long read = gate.begin(false);
    assertTrue(read > first);
    assertFalse(gate.finish(first, true));
    assertTrue(gate.finish(read, true));
    long last = gate.begin(true);
    assertTrue(last > read);
    gate.close();
    assertFalse(gate.current(last));
    assertFalse(gate.finish(last, true));
    assertEquals(0, gate.begin(false));
  }

  @Test
  public void unrelatedGoalMutationAcknowledgementIsNotAccepted() throws Exception {
    var observed = client.goal();
    mutation = GOAL.replace("goal-1", "another-goal").replace("\"revision\":3", "\"revision\":4");
    assertThrows(IOException.class, () -> client.changeGoal(observed, "pause"));
    mutation = "{\"id\":\"goal-1\",\"revision\":3}";
    assertThrows(IOException.class, () -> client.changeGoal(observed, "clear"));
  }

  @Test
  public void unrelatedFeedbackAcknowledgementIsNotAccepted() throws Exception {
    var snapshot = client.feedback("message-durable");
    mutation =
        "{\"ok\":true,\"value\":" + FEEDBACK.replace("message-durable", "different-message") + "}";
    assertThrows(
        IOException.class, () -> client.putFeedback(snapshot, "negative", "Missing details"));
  }

  private JsonObject last() {
    return sent.get(sent.size() - 1);
  }

  private JsonObject args() {
    return last().getAsJsonObject("payload").getAsJsonObject("args");
  }
}
