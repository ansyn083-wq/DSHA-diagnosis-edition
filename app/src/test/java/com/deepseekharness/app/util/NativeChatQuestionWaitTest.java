package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

public final class NativeChatQuestionWaitTest {
  @Test
  public void claimsExactCallOnAuthenticatedSocketAndDetachOnlyCancelsClaim() throws Exception {
    try (Peer peer = new Peer()) {
      NativeChatQuestionWait wait = peer.open();
      JsonObject open = peer.openings.poll(3, TimeUnit.SECONDS);
      assertNotNull(open);
      assertEquals("userQuestions/attachWait", open.get("endpoint").getAsString());
      JsonObject args = open.getAsJsonObject("payload").getAsJsonObject("args");
      assertEquals(2, args.size());
      assertEquals("session-1", args.get("agentId").getAsString());
      assertEquals("call-1", args.get("callId").getAsString());
      var request = peer.host.takeRequest(3, TimeUnit.SECONDS);
      assertNotNull(request);
      assertEquals("/api/remote.mux", request.getPath());
      assertEquals("dsh-auth-test=secret", request.getHeader("Cookie"));
      assertEquals(peer.origin(), request.getHeader("Origin"));
      peer.send("item", "{\"remainingMs\":5000}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      wait.close();
      JsonObject cancel = peer.messages.poll(3, TimeUnit.SECONDS);
      assertNotNull(cancel);
      assertEquals("cancel", cancel.get("type").getAsString());
      assertEquals(open.get("streamId"), cancel.get("streamId"));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void waitsForValidatedRemainingDurationBeforeStartingCountdown() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      peer.send("item", "{\"remainingMs\":0}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertEquals("timeout", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      assertNull(peer.messages.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void unresponsiveClaimOpeningEndsWithTransportFailureNotUserTimeout() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      assertEquals("question-wait-opening-timeout", peer.notifications.poll(12, TimeUnit.SECONDS));
      assertNotNull(peer.messages.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void validOpeningDisarmsHandshakeWatchdogWhileEditedAnswerWaits() throws Exception {
    try (Peer peer = new Peer()) {
      NativeChatQuestionWait wait = peer.open();
      wait.engage();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":0}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(11, TimeUnit.SECONDS));
      assertNull(peer.messages.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void ignoresFramesForOtherLogicalStreams() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.server.send("{\"type\":\"item\",\"streamId\":\"other\",\"value\":{\"remainingMs\":0}}");
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      peer.send("item", "{\"remainingMs\":5000}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
    }
  }

  @Test
  public void rejectsMalformedOpeningWithoutAnswerOrTimeout() throws Exception {
    for (String value : new String[] {"-1", "1.5", "\"100\"", "2147483648", "null"}) {
      try (Peer peer = new Peer()) {
        peer.open();
        assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
        peer.send("item", "{\"remainingMs\":" + value + "}");
        assertEquals("question-wait-frame", peer.notifications.poll(3, TimeUnit.SECONDS));
        assertNull(peer.notifications.poll(30, TimeUnit.MILLISECONDS));
      }
    }
  }

  @Test
  public void duplicateOpeningCannotExtendCountdown() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":5000}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":9000}");
      assertEquals("question-wait-frame", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void hostEndAndErrorDoNotPretendUserCancelled() throws Exception {
    for (String type : new String[] {"end", "error"}) {
      try (Peer peer = new Peer()) {
        peer.open();
        assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
        peer.send(type, null);
        assertEquals(
            "end".equals(type) ? "question-wait-ended" : "question-wait-error",
            peer.notifications.poll(3, TimeUnit.SECONDS));
        assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      }
    }
  }

  @Test
  public void staleIdentityDetachesWithoutReadyOrTimeout() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.current.set(false);
      peer.send("item", "{\"remainingMs\":0}");
      assertNotNull(peer.messages.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void staleIdentityAtDeadlineSuppressesTimeout() throws Exception {
    try (Peer peer = new Peer()) {
      peer.open();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":80}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      peer.current.set(false);
      assertNotNull(peer.messages.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void initialFocusAndEditBeforeOpeningPreventExpiry() throws Exception {
    try (Peer peer = new Peer()) {
      NativeChatQuestionWait wait = peer.open();
      wait.holdFocus();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":1}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      wait.engage();
      wait.releaseFocus();
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void leavingPristineFocusResumesTheClientTimer() throws Exception {
    try (Peer peer = new Peer()) {
      NativeChatQuestionWait wait = peer.open();
      wait.holdFocus();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":40}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      wait.releaseFocus();
      assertEquals("timeout", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void explicitTakeTimeBeforeOpeningHoldsTheClaim() throws Exception {
    try (Peer peer = new Peer()) {
      NativeChatQuestionWait wait = peer.open();
      wait.takeTime();
      assertNotNull(peer.openings.poll(3, TimeUnit.SECONDS));
      peer.send("item", "{\"remainingMs\":0}");
      assertEquals("ready", peer.notifications.poll(3, TimeUnit.SECONDS));
      assertNull(peer.notifications.poll(80, TimeUnit.MILLISECONDS));
      assertNull(peer.messages.poll(80, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void pristineFocusResumesOnlyUnusedMonotonicDuration() {
    var countdown = new NativeChatQuestionWait.Countdown();
    countdown.start(100, 1_000_000_000L);
    countdown.holdFocus(1_040_000_000L);
    assertEquals(-1, countdown.delayNanos(9_000_000_000L));
    countdown.releaseFocus(9_000_000_000L);
    assertEquals(60_000_000L, countdown.delayNanos(9_000_000_000L));
    assertEquals(1L, countdown.delayNanos(9_059_999_999L));
    assertEquals(0L, countdown.delayNanos(9_060_000_000L));
  }

  @Test
  public void editingAndExplicitTakeTimeHoldAfterFocusLeaves() {
    for (boolean edit : new boolean[] {true, false}) {
      var countdown = new NativeChatQuestionWait.Countdown();
      countdown.start(100, 1_000L);
      if (edit) countdown.engage();
      else countdown.takeTime();
      countdown.releaseFocus(200_000_000L);
      assertEquals(-1L, countdown.delayNanos(500_000_000L));
    }
  }

  @Test
  public void focusBeforeClaimPreservesFullInitialDuration() {
    var countdown = new NativeChatQuestionWait.Countdown();
    countdown.holdFocus(1L);
    countdown.start(200, 1_000_000_000L);
    assertEquals(-1L, countdown.delayNanos(4_000_000_000L));
    countdown.releaseFocus(4_000_000_000L);
    assertEquals(200_000_000L, countdown.delayNanos(4_000_000_000L));
  }

  @Test
  public void neverAcceptsExternalOriginsOrMissingIdentity() {
    OkHttpClient client = new OkHttpClient();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              NativeChatQuestionWait.open(
                  client,
                  "https://example.com",
                  "dsh-auth-test=secret",
                  "s",
                  "c",
                  () -> true,
                  () -> {},
                  () -> {},
                  code -> {}));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              NativeChatQuestionWait.open(
                  client,
                  "http://127.0.0.1:8080",
                  "dsh-auth-test=secret",
                  "s",
                  "",
                  () -> true,
                  () -> {},
                  () -> {},
                  code -> {}));
      assertThrows(
          IllegalStateException.class,
          () ->
              NativeChatQuestionWait.open(
                  client,
                  "http://127.0.0.1:8080",
                  "dsh-auth-test=secret",
                  "s",
                  "c",
                  () -> false,
                  () -> {},
                  () -> {},
                  code -> {}));
    } finally {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  private static final class Peer implements AutoCloseable {
    final MockWebServer host = new MockWebServer();
    final OkHttpClient client = new OkHttpClient();
    final AtomicBoolean current = new AtomicBoolean(true);
    final LinkedBlockingQueue<JsonObject> openings = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<String> notifications = new LinkedBlockingQueue<>();
    volatile WebSocket server;
    volatile String streamId;
    NativeChatQuestionWait wait;

    Peer() throws Exception {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, okhttp3.Response response) {
                      server = socket;
                    }

                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      if ("open".equals(frame.get("type").getAsString())) {
                        streamId = frame.get("streamId").getAsString();
                        openings.add(frame);
                      } else messages.add(frame);
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                      socket.close(code, reason);
                    }
                  }));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
    }

    String origin() {
      return "http://127.0.0.1:" + host.getPort();
    }

    NativeChatQuestionWait open() {
      wait =
          NativeChatQuestionWait.open(
              client,
              origin(),
              "dsh-auth-test=secret",
              "session-1",
              "call-1",
              current::get,
              () -> notifications.add("ready"),
              () -> notifications.add("timeout"),
              notifications::add);
      return wait;
    }

    void send(String type, String value) {
      var frame = new JsonObject();
      frame.addProperty("type", type);
      frame.addProperty("streamId", streamId);
      if (value != null) frame.add("value", JsonParser.parseString(value));
      assertTrue(server.send(frame.toString()));
    }

    @Override
    public void close() throws Exception {
      if (wait != null) wait.close();
      host.close();
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }
}
