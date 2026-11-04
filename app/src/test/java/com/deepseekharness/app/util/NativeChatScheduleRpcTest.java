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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real loopback gateway tests against the locked schedule remote contract. */
public final class NativeChatScheduleRpcTest {
  private static final String TASK =
      """
      {"id":"task-1","kind":"weekly","title":"Weekly review","prompt":"Review the week",
       "time":"09:30:00.000","timeZone":"Asia/Shanghai","weekdays":[1,5],
       "scheduledAt":"2026-11-06T01:30:00.000Z","sessionId":"session-1","status":"active",
       "lastDelivery":{"scheduledAt":"2026-11-02T01:30:00.000Z",
        "deliveredAt":"2026-11-02T01:30:01.000Z","messageId":"delivery-1"}}
      """;
  private MockWebServer server;
  private NativeChatScheduleRpc client;
  private final AtomicReference<JsonObject> sent = new AtomicReference<>();
  private final AtomicReference<String> reply = new AtomicReference<>("[" + TASK + "]");
  private final AtomicReference<String> cookie = new AtomicReference<>();
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final AtomicBoolean replaceDuringCall = new AtomicBoolean(false);

  @Before
  public void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            cookie.set(incoming.getHeader("Cookie"));
            JsonObject request =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            sent.set(request);
            JsonObject response = new JsonObject();
            response.addProperty("type", "server-response");
            response.add("rpcId", request.get("rpcId"));
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("value", JsonParser.parseString(reply.get()));
            response.add("result", result);
            if (replaceDuringCall.get()) current.set(false);
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(response.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    client =
        new NativeChatScheduleRpc(
            new NativeChatRpc(
                "http://127.0.0.1:" + server.getPort(), "dsh-auth-test=secret", current::get));
  }

  @After
  public void stop() throws IOException {
    if (server != null) server.shutdown();
  }

  @Test
  public void catalogUsesNoRequestWrapperAndKeepsOriginalSessionBinding() throws Exception {
    var tasks = client.catalog();
    assertEquals(1, tasks.size());
    assertEquals("session-1", tasks.get(0).sessionId());
    assertEquals("task-1", tasks.get(0).id());
    assertEquals("Weekly review", tasks.get(0).title());
    assertTrue(tasks.get(0).active());
    assertEquals("schedule/catalog", sent.get().get("method").getAsString());
    assertEquals(0, args().size());
    assertEquals("dsh-auth-test=secret", cookie.get());
  }

  @Test
  public void titleEditSendsCompleteExpectedRuleWithoutCatalogMetadataOrNewTiming()
      throws Exception {
    var task = client.catalog().get(0);
    reply.set("{\"id\":\"task-1\",\"updated\":true,\"record\":" + stored() + "}");
    var result = client.update(task, " Revised name ", " New instruction ", null);
    assertTrue(result.success());
    assertTrue(result.changed());
    assertEquals("schedule/update", sent.get().get("method").getAsString());
    JsonObject request = args().getAsJsonObject("request");
    assertEquals("session-1", request.get("sessionId").getAsString());
    assertEquals("Revised name", request.get("title").getAsString());
    assertEquals("New instruction", request.get("prompt").getAsString());
    assertEquals(stored(), request.getAsJsonObject("expected"));
    assertFalse(request.has("change"));
    task.record().addProperty("scheduledAt", "corrupt-copy");
    assertEquals("2026-11-06T01:30:00.000Z", task.scheduledAt());
  }

  @Test
  public void noOpWithRecordIsSuccessButConflictIsNeverSuccess() throws Exception {
    var task = client.catalog().get(0);
    reply.set("{\"id\":\"task-1\",\"updated\":false,\"record\":" + stored() + "}");
    assertTrue(client.update(task, task.title(), task.prompt(), null).success());
    reply.set("{\"id\":\"task-1\",\"updated\":false,\"code\":\"schedule_conflict\"}");
    var conflict = client.update(task, task.title(), task.prompt(), null);
    assertFalse(conflict.success());
    assertEquals("schedule_conflict", conflict.code());
  }

  @Test
  public void updateRejectsEmptyContentAndInactiveTaskWithoutSending() throws Exception {
    var task = client.catalog().get(0);
    sent.set(null);
    assertThrows(IllegalArgumentException.class, () -> client.update(task, " ", "ok", null));
    assertThrows(IllegalArgumentException.class, () -> client.update(task, "ok", " ", null));
    assertThrows(
        IllegalArgumentException.class, () -> client.update(task, "x".repeat(121), "ok", null));
    assertNull(sent.get());
    reply.set("[" + TASK.replace("\"active\"", "\"inactive\"") + "]");
    var inactive = client.catalog().get(0);
    sent.set(null);
    assertThrows(IllegalArgumentException.class, () -> client.update(inactive, "ok", "ok", null));
    assertNull(sent.get());
  }

  @Test
  public void deleteIsBoundToExactTaskAndSessionAndDoesNotInventRevision() throws Exception {
    var task = client.catalog().get(0);
    reply.set("{\"id\":\"task-1\",\"deleted\":true}");
    assertTrue(client.delete(task));
    assertEquals("schedule/delete", sent.get().get("method").getAsString());
    assertEquals(
        JsonParser.parseString("{\"sessionId\":\"session-1\",\"id\":\"task-1\"}"),
        args().get("request"));
    reply.set("{\"id\":\"task-1\",\"deleted\":false,\"code\":\"schedule_not_found\"}");
    assertFalse(client.delete(task));
  }

  @Test
  public void malformedCatalogAndMismatchedMutationResponsesAreRejected() throws Exception {
    reply.set("[{\"id\":\"incomplete\"}]");
    assertThrows(IOException.class, client::catalog);
    reply.set("[" + TASK + "]");
    var task = client.catalog().get(0);
    reply.set("{\"id\":\"other-task\",\"deleted\":true}");
    assertThrows(IOException.class, () -> client.delete(task));
    reply.set("{\"id\":\"task-1\",\"updated\":true}");
    assertThrows(IOException.class, () -> client.update(task, "ok", "ok", null));
  }

  @Test
  public void serviceOrDialogInvalidationPreventsRequestsAndDropsResponses() throws Exception {
    current.set(false);
    assertThrows(IOException.class, client::catalog);
    assertNull(sent.get());
    current.set(true);
    replaceDuringCall.set(true);
    assertThrows(IOException.class, client::catalog);
  }

  @Test
  public void timingChangesUseExactSelectorsAndValidateBasicInput() {
    assertEquals(
        JsonParser.parseString("{\"kind\":\"at\",\"at\":\"2026-12-01T08:00:00Z\"}"),
        NativeChatScheduleRpc.timing("at", "2026-12-01T08:00:00Z", "", ""));
    assertEquals(
        JsonParser.parseString("{\"kind\":\"every\",\"every_seconds\":3600}"),
        NativeChatScheduleRpc.timing("every", "3600", "", ""));
    assertEquals(
        JsonParser.parseString(
            "{\"kind\":\"daily\",\"daily\":{\"time\":\"09:30:00\",\"time_zone\":\"Asia/Shanghai\"}}"),
        NativeChatScheduleRpc.timing("daily", "09:30", "Asia/Shanghai", ""));
    assertEquals(
        JsonParser.parseString(
            "{\"kind\":\"weekly\",\"weekly\":{\"time\":\"09:30:00\",\"time_zone\":\"Asia/Shanghai\",\"weekdays\":[1,5]}}"),
        NativeChatScheduleRpc.timing("weekly", "09:30", "Asia/Shanghai", "5,1"));
    assertEquals(
        JsonParser.parseString(
            "{\"kind\":\"cron\",\"cron\":{\"expression\":\"0 9 * * 1-5\",\"time_zone\":\"UTC\"}}"),
        NativeChatScheduleRpc.timing("cron", "0 9 * * 1-5", "UTC", ""));
    for (String seconds : new String[] {"59", "1.5", "-1", "9007199254740992"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> NativeChatScheduleRpc.timing("every", seconds, "", ""));
    }
    assertThrows(
        IllegalArgumentException.class, () -> NativeChatScheduleRpc.timing("after", "60", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("at", "2026-12-01T08:00", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("daily", "25:00", "UTC", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("daily", "09:00", "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("weekly", "09:00", "UTC", "0,8"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("weekly", "09:00", "UTC", "1,1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeChatScheduleRpc.timing("cron", "0 0 9 * * *", "UTC", ""));
  }

  @Test
  public void changedTimingReachesTheGatewayWithoutReanchoringExpectedRecord() throws Exception {
    var task = client.catalog().get(0);
    reply.set("{\"id\":\"task-1\",\"updated\":false,\"record\":" + stored() + "}");
    JsonObject change = NativeChatScheduleRpc.timing("every", "600", "", "");
    var result = client.update(task, task.title(), task.prompt(), change);
    assertTrue(result.success());
    assertFalse(result.changed());
    JsonObject request = args().getAsJsonObject("request");
    assertEquals(
        JsonParser.parseString("{\"kind\":\"every\",\"every_seconds\":600}"),
        request.get("change"));
    assertEquals("weekly", request.getAsJsonObject("expected").get("kind").getAsString());
    assertEquals(
        "2026-11-06T01:30:00.000Z",
        request.getAsJsonObject("expected").get("scheduledAt").getAsString());
  }

  @Test
  public void catalogAcceptsAllSixStoredKindsIncludingEndedTasks() throws Exception {
    String common =
        "\"id\":\"task\",\"title\":\"Task\",\"prompt\":\"Do something\","
            + "\"scheduledAt\":\"2026-12-01T00:00:00.000Z\",\"sessionId\":\"owner\",\"status\":\"inactive\"";
    for (String rule :
        new String[] {
          "\"kind\":\"at\"",
          "\"kind\":\"after\",\"afterSeconds\":30",
          "\"kind\":\"every\",\"everySeconds\":60",
          "\"kind\":\"daily\",\"time\":\"09:00:00.000\",\"timeZone\":\"UTC\"",
          "\"kind\":\"weekly\",\"time\":\"09:00:00.000\",\"timeZone\":\"UTC\",\"weekdays\":[1,7]",
          "\"kind\":\"cron\",\"expression\":\"0 9 * * *\",\"timeZone\":\"UTC\""
        }) {
      reply.set("[{" + common + "," + rule + "}]");
      assertFalse(client.catalog().get(0).active());
    }
  }

  @Test
  public void duplicateSubmitsAndStaleCompletionsCannotOwnTheEditor() {
    var gate = new NativeChatScheduleRpc.RequestGate();
    long first = gate.begin();
    assertTrue(first > 0);
    assertEquals(0, gate.begin());
    assertTrue(gate.current(first));
    assertTrue(gate.finish(first));
    assertFalse(gate.current(first));
    long second = gate.begin();
    assertTrue(second > first);
    assertFalse(gate.finish(first));
    assertTrue(gate.current(second));
    gate.close();
    assertFalse(gate.current(second));
    assertFalse(gate.finish(second));
    assertEquals(0, gate.begin());
  }

  private JsonObject args() {
    return sent.get().getAsJsonObject("payload").getAsJsonObject("args");
  }

  private static JsonObject stored() {
    JsonObject value = JsonParser.parseString(TASK).getAsJsonObject();
    value.remove("sessionId");
    value.remove("status");
    value.remove("lastDelivery");
    return value;
  }
}
