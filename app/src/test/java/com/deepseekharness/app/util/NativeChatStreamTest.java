package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

public final class NativeChatStreamTest {
  @Test
  public void receivesOnlyItemsForItsAuthenticatedSessionStream() throws Exception {
    var openings = new LinkedBlockingQueue<JsonObject>();
    var items = new LinkedBlockingQueue<JsonObject>();
    var errors = new LinkedBlockingQueue<String>();
    OkHttpClient client = new OkHttpClient();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      openings.add(frame);
                      socket.send(
                          "{\"type\":\"item\",\"streamId\":\"another\",\"value\":{\"type\":\"ignored\"}}");
                      JsonObject reply =
                          JsonParser.parseString(
                                  "{\"type\":\"item\",\"value\":{\"type\":\"snapshot\",\"records\":[]}}")
                              .getAsJsonObject();
                      reply.add("streamId", frame.get("streamId"));
                      socket.send(reply.toString());
                    }
                  }));
      host.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      String origin = "http://127.0.0.1:" + host.getPort();
      try (NativeChatStream stream =
          NativeChatStream.open(
              client,
              origin,
              "dsh-auth-test=secret",
              "test-session",
              () -> true,
              items::add,
              errors::add)) {
        var upgrade = host.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(upgrade);
        assertEquals("/api/remote.mux", upgrade.getPath());
        assertEquals("dsh-auth-test=secret", upgrade.getHeader("Cookie"));
        JsonObject open = openings.poll(3, TimeUnit.SECONDS);
        assertNotNull(open);
        assertEquals("session/follow", open.get("endpoint").getAsString());
        var args =
            open.getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request");
        assertNotNull(args);
        assertTrue(args.get("assistantStream").getAsBoolean());
        assertEquals(
            "test-session", args.getAsJsonObject("address").get("sessionId").getAsString());
        JsonObject item = items.poll(3, TimeUnit.SECONDS);
        assertNotNull(item);
        assertEquals("snapshot", item.get("type").getAsString());
        assertTrue(errors.isEmpty());
      }
    } finally {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  @Test
  public void followsQuestionsAndTranscriptOnSeparateLogicalStreams() throws Exception {
    var items = new LinkedBlockingQueue<JsonObject>();
    var events = new LinkedBlockingQueue<JsonObject>();
    var errors = new LinkedBlockingQueue<String>();
    OkHttpClient client = new OkHttpClient();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      JsonObject open = JsonParser.parseString(message).getAsJsonObject();
                      boolean events = "$events".equals(open.get("endpoint").getAsString());
                      String[] values =
                          events
                              ? new String[] {
                                "{\"type\":\"ready\",\"clientId\":\"c1\"}",
                                "{\"type\":\"waterfall\",\"event\":\"user-questions/request\",\"eventId\":\"e1\"}",
                                "{\"type\":\"cancel\",\"eventId\":\"e1\"}"
                              }
                              : new String[] {"{\"type\":\"snapshot\"}"};
                      for (String value : values) {
                        JsonObject frame = new JsonObject();
                        frame.addProperty("type", "item");
                        frame.add("streamId", open.get("streamId"));
                        frame.add("value", JsonParser.parseString(value));
                        socket.send(frame.toString());
                      }
                    }
                  }));
      host.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      try (NativeChatStream stream =
          NativeChatStream.open(
              client,
              "http://127.0.0.1:" + host.getPort(),
              "dsh-auth-test=secret",
              "test-session",
              () -> true,
              items::add,
              events::add,
              errors::add)) {
        assertEquals("ready", events.poll(3, TimeUnit.SECONDS).get("type").getAsString());
        assertEquals("waterfall", events.poll(3, TimeUnit.SECONDS).get("type").getAsString());
        assertEquals("cancel", events.poll(3, TimeUnit.SECONDS).get("type").getAsString());
        assertEquals("snapshot", items.poll(3, TimeUnit.SECONDS).get("type").getAsString());
        assertTrue(errors.isEmpty());
      }
    } finally {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  @Test
  public void eventStreamErrorFailsEvenWhenTranscriptSocketIsAlive() throws Exception {
    assertEventStreamTerminal("error");
  }

  @Test
  public void eventStreamEndFailsEvenWhenTranscriptSocketIsAlive() throws Exception {
    assertEventStreamTerminal("end");
  }

  private void assertEventStreamTerminal(String type) throws Exception {
    try (EventPeer peer = new EventPeer()) {
      try (NativeChatStream stream = peer.open()) {
        peer.sendEventFrame(type, null);
        assertEquals("stream-ended", peer.errors.poll(3, TimeUnit.SECONDS));
        assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
      }
    }
  }

  @Test
  public void timeoutWithoutReadyNeverAuthorizesPromptDispatch() throws Exception {
    try (EventPeer peer = new EventPeer();
        NativeChatStream stream = peer.open()) {
      peer.awaitEventsOpen();
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
    }
  }

  @Test
  public void closeBeforeReadyReleasesWaitWithFailure() throws Exception {
    try (EventPeer peer = new EventPeer();
        NativeChatStream stream = peer.open()) {
      peer.awaitEventsOpen();
      stream.close();
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
    }
  }

  @Test
  public void missingReadyClientIdentityFailsInsteadOfOpeningGate() throws Exception {
    try (EventPeer peer = new EventPeer();
        NativeChatStream stream = peer.open()) {
      JsonObject value = new JsonObject();
      value.addProperty("type", "ready");
      peer.sendEventFrame("item", value);
      assertEquals("stream-frame", peer.errors.poll(3, TimeUnit.SECONDS));
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
    }
  }

  @Test
  public void readyIsNotReusableAfterCloseOrServiceChange() throws Exception {
    try (EventPeer peer = new EventPeer();
        NativeChatStream stream = peer.open()) {
      JsonObject value = new JsonObject();
      value.addProperty("type", "ready");
      value.addProperty("clientId", "client");
      peer.sendEventFrame("item", value);
      stream.awaitQuestionEvents();
      peer.current.set(false);
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
      peer.current.set(true);
      stream.close();
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
    }
  }

  @Test
  public void duplicateReadyCannotResetCurrentClientOrPendingQueue() throws Exception {
    try (EventPeer peer = new EventPeer();
        NativeChatStream stream = peer.open()) {
      JsonObject value = new JsonObject();
      value.addProperty("type", "ready");
      value.addProperty("clientId", "client");
      peer.sendEventFrame("item", value);
      stream.awaitQuestionEvents();
      value.addProperty("clientId", "replacement-client");
      peer.sendEventFrame("item", value);
      assertEquals("stream-frame", peer.errors.poll(3, TimeUnit.SECONDS));
      assertThrows(java.io.IOException.class, stream::awaitQuestionEvents);
    }
  }

  /** 单个测试内的回环传输夹具，不启动 DSH 或调用任何模型/外部服务。 */
  private static final class EventPeer implements AutoCloseable {
    final OkHttpClient client = new OkHttpClient();
    final MockWebServer host = new MockWebServer();
    final java.util.concurrent.atomic.AtomicBoolean current =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    final LinkedBlockingQueue<String> errors = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<JsonObject> eventsOpen = new LinkedBlockingQueue<>();
    volatile WebSocket socket;
    JsonObject opening;

    EventPeer() throws Exception {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String message) {
                      socket = ws;
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      if ("$events".equals(frame.get("endpoint").getAsString()))
                        eventsOpen.add(frame);
                    }
                  }));
      host.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
    }

    NativeChatStream open() {
      return NativeChatStream.open(
          client,
          "http://127.0.0.1:" + host.getPort(),
          "dsh-auth-test=synthetic",
          "session",
          current::get,
          value -> {},
          value -> {},
          errors::add);
    }

    void awaitEventsOpen() throws Exception {
      if (opening == null) opening = eventsOpen.poll(3, TimeUnit.SECONDS);
      assertNotNull(opening);
    }

    void sendEventFrame(String type, JsonObject value) throws Exception {
      awaitEventsOpen();
      JsonObject frame = new JsonObject();
      frame.addProperty("type", type);
      frame.add("streamId", opening.get("streamId"));
      if (value != null) frame.add("value", value);
      assertTrue(socket.send(frame.toString()));
    }

    @Override
    public void close() throws Exception {
      host.close();
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }
}
