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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 固定 rc.2 的会话与工作区协议，走真实本机 HTTP 门禁。 */
public final class NativeChatSessionsRpcTest {
  private MockWebServer server;
  private NativeChatSessionsRpc client;
  private final AtomicReference<JsonObject> sent = new AtomicReference<>();
  private final AtomicReference<String> value = new AtomicReference<>("{}");
  private final AtomicReference<String> failure = new AtomicReference<>();
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final AtomicBoolean invalidate = new AtomicBoolean(false);
  private final AtomicInteger calls = new AtomicInteger();

  @Before
  public void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            calls.incrementAndGet();
            JsonObject request =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            sent.set(request);
            assertEquals("dsh-auth-test=secret", incoming.getHeader("Cookie"));
            assertEquals("/api/" + request.get("method").getAsString(), incoming.getPath());
            JsonObject response =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":"
                            + (failure.get() == null
                                ? "{\"ok\":true,\"value\":" + value.get() + "}"
                                : "{\"ok\":false,\"error\":{\"code\":\"" + failure.get() + "\"}}")
                            + "}")
                    .getAsJsonObject();
            response.add("rpcId", request.get("rpcId"));
            if (invalidate.get()) current.set(false);
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(response.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    client =
        new NativeChatSessionsRpc(
            new NativeChatRpc(
                "http://127.0.0.1:" + server.getPort(), "dsh-auth-test=secret", current::get));
  }

  @After
  public void stop() throws IOException {
    if (server != null) server.shutdown();
  }

  @Test
  public void searchUsesMessageContentEndpointAndKeepsSnippetAndTruncation() throws Exception {
    value.set(
        "{\"items\":[{\"sessionId\":\"chat-1\",\"snippet\":\"matching message content\"}],\"hasMore\":true}");
    var result = client.search(" message content ");
    assertEquals("session/search", sent.get().get("method").getAsString());
    assertEquals(JsonParser.parseString("{\"request\":{\"query\":\"message content\"}}"), args());
    assertEquals("chat-1", result.items().get(0).sessionId());
    assertEquals("matching message content", result.items().get(0).snippet());
    assertTrue(result.hasMore());
  }

  @Test
  public void renameUsesAcceptedTitleAndRequiresDurableSequence() throws Exception {
    value.set("{\"title\":\"Accepted title\",\"seq\":32}");
    assertEquals("Accepted title", client.rename("chat-1", " New title "));
    assertEquals("session/rename", sent.get().get("method").getAsString());
    assertEquals(
        JsonParser.parseString("{\"request\":{\"sessionId\":\"chat-1\",\"title\":\"New title\"}}"),
        args());
    value.set("{\"title\":\"uncommitted\"}");
    assertThrows(IOException.class, () -> client.rename("chat-1", "new"));
  }

  @Test
  public void archiveNeverRequestsStoppingActivityAndReturnsAuthoritativeSet() throws Exception {
    value.set("{\"archivedSessionIds\":[\"other\",\"chat-1\"]}");
    assertEquals(Set.of("other", "chat-1"), client.archive("chat-1"));
    assertEquals("workspace/archiveSession", sent.get().get("method").getAsString());
    assertEquals(JsonParser.parseString("{\"request\":{\"sessionId\":\"chat-1\"}}"), args());
  }

  @Test
  public void restoreUsesWorkspaceUnarchiveAndVerifiesMembership() throws Exception {
    value.set("{\"archivedSessionIds\":[\"other\"]}");
    assertEquals(Set.of("other"), client.restore("chat-1"));
    assertEquals("workspace/unarchiveSession", sent.get().get("method").getAsString());
    value.set("{\"archivedSessionIds\":[\"chat-1\"]}");
    assertThrows(IOException.class, () -> client.restore("chat-1"));
    value.set("{\"archivedSessionIds\":[]}");
    assertThrows(IOException.class, () -> client.archive("chat-1"));
  }

  @Test
  public void forkPreservesExactInclusiveEventSeqAndRejectsSameSessionResponse() throws Exception {
    value.set("{\"sessionId\":\"forked\"}");
    assertEquals("forked", client.fork("chat-1", 27L));
    assertEquals("session/fork", sent.get().get("method").getAsString());
    assertEquals(
        JsonParser.parseString(
            "{\"request\":{\"sessionId\":\"chat-1\",\"atSeq\":27,\"allowMigration\":false}}"),
        args());
    value.set("{\"sessionId\":\"chat-1\"}");
    assertThrows(IOException.class, () -> client.fork("chat-1", 27L));
  }

  @Test
  public void latestCompletedForkOmitsBoundaryRatherThanGuessingIt() throws Exception {
    value.set("{\"sessionId\":\"forked\"}");
    assertEquals("forked", client.forkLatestCompleted("chat-1"));
    assertEquals(
        JsonParser.parseString("{\"request\":{\"sessionId\":\"chat-1\",\"allowMigration\":false}}"),
        args());
  }

  @Test
  public void invalidInputsNeverSendNetworkRequests() {
    assertThrows(IllegalArgumentException.class, () -> client.search(" "));
    assertThrows(IllegalArgumentException.class, () -> client.rename("chat-1", " "));
    assertThrows(IllegalArgumentException.class, () -> client.archive(""));
    assertThrows(IllegalArgumentException.class, () -> client.restore(" "));
    assertThrows(IllegalArgumentException.class, () -> client.fork("chat-1", -1L));
    assertThrows(IllegalArgumentException.class, () -> client.fork("chat-1", 9007199254740992L));
    assertEquals(0, calls.get());
  }

  @Test
  public void searchEnforcesLockedFiveHundredUtf16LimitAndRejectsNul() throws Exception {
    value.set("{\"items\":[],\"hasMore\":false}");
    assertThrows(IllegalArgumentException.class, () -> client.search("x".repeat(501)));
    assertThrows(IllegalArgumentException.class, () -> client.search("a\u0000b"));
    assertEquals(0, calls.get());
    assertTrue(client.search("x".repeat(500)).items().isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  public void malformedSearchAndArchiveDataFailClosed() {
    value.set("{\"items\":[{\"sessionId\":\"x\"}],\"hasMore\":false}");
    assertThrows(IOException.class, () -> client.search("message"));
    value.set("{\"items\":[],\"hasMore\":\"false\"}");
    assertThrows(IOException.class, () -> client.search("message"));
    value.set("{\"archivedSessionIds\":[42]}");
    assertThrows(IOException.class, () -> client.archive("chat-1"));
  }

  @Test
  public void serviceInvalidationPreventsWritesAndDropsStaleResponses() {
    current.set(false);
    assertThrows(IOException.class, () -> client.rename("chat-1", "new"));
    assertEquals(0, calls.get());
    current.set(true);
    invalidate.set(true);
    value.set("{\"sessionId\":\"forked\"}");
    assertThrows(IOException.class, () -> client.fork("chat-1", 27L));
    assertEquals(1, calls.get());
  }

  @Test
  public void activeArchiveRejectionDoesNotCancelOrRetry() {
    failure.set("workspace/session-active");
    IOException error = assertThrows(IOException.class, () -> client.archive("chat-1"));
    assertTrue(error.getMessage().contains("workspace/session-active"));
    assertEquals(1, calls.get());
    assertEquals("workspace/archiveSession", sent.get().get("method").getAsString());
    assertFalse(args().getAsJsonObject("request").has("stopActivity"));
  }

  @Test
  public void newerSearchInvalidatesOlderLoadAndCompletedTicketCannotRunTwice() {
    var gate = new NativeChatSessionsRpc.RequestGate();
    long first = gate.beginRead();
    long second = gate.beginRead();
    assertFalse(gate.current(first));
    assertFalse(gate.finish(first));
    assertTrue(gate.current(second));
    assertTrue(gate.finish(second));
    assertFalse(gate.finish(second));
  }

  @Test
  public void mutationSuppressesDuplicateSubmitAndSupersedesStaleReads() {
    var gate = new NativeChatSessionsRpc.RequestGate();
    long read = gate.beginRead();
    long write = gate.beginMutation();
    assertTrue(write > read);
    assertFalse(gate.current(read));
    assertEquals(0, gate.beginMutation());
    assertEquals(0, gate.beginRead());
    assertTrue(gate.finish(write));
    assertTrue(gate.beginRead() > write);
  }

  @Test
  public void dismissalRetiresPendingWorkWithoutReopeningTheGate() {
    var gate = new NativeChatSessionsRpc.RequestGate();
    long pending = gate.beginMutation();
    gate.close();
    assertFalse(gate.current(pending));
    assertFalse(gate.finish(pending));
    assertEquals(0, gate.beginRead());
    assertEquals(0, gate.beginMutation());
  }

  private JsonObject args() {
    return sent.get().getAsJsonObject("payload").getAsJsonObject("args");
  }
}
