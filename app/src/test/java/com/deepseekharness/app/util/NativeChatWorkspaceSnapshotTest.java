package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

/** 归档集只能由工作区流的完整 baseline 获得。 */
public final class NativeChatWorkspaceSnapshotTest {
  @Test
  public void readsOneAuthenticatedWorkspaceBaselineWithoutRequestWrapper() throws Exception {
    OkHttpClient client = new OkHttpClient();
    var openings = new LinkedBlockingQueue<JsonObject>();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      JsonObject open = JsonParser.parseString(message).getAsJsonObject();
                      openings.add(open);
                      socket.send("{\"type\":\"item\",\"streamId\":\"unrelated\",\"value\":{}}");
                      socket.send(
                          frame(
                              open,
                              "{\"type\":\"baseline\",\"value\":{\"items\":[],"
                                  + "\"archivedSessionIds\":[\"archived-1\"],\"pinnedSessionIds\":[]}}"));
                    }
                  }));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
      Set<String> result =
          NativeChatWorkspaceSnapshot.read(
              client, origin(host), "dsh-auth-test=secret", () -> true);
      assertEquals(Set.of("archived-1"), result);
      var request = host.takeRequest(2, TimeUnit.SECONDS);
      assertNotNull(request);
      assertEquals("/api/remote.mux", request.getPath());
      assertEquals(origin(host), request.getHeader("Origin"));
      assertEquals("dsh-auth-test=secret", request.getHeader("Cookie"));
      JsonObject open = openings.poll(2, TimeUnit.SECONDS);
      assertNotNull(open);
      assertEquals("open", open.get("type").getAsString());
      assertEquals("workspace/follow", open.get("endpoint").getAsString());
      assertEquals(0, open.getAsJsonObject("payload").getAsJsonObject("args").size());
      assertEquals(1, host.getRequestCount());
    } finally {
      shutdown(client);
    }
  }

  @Test
  public void incompleteOrIncrementalArchiveFramesNeverBecomeBaseline() throws Exception {
    expectFailure(
        "{\"type\":\"archived\",\"archivedSessionIds\":[\"x\"]}", new AtomicBoolean(true));
    expectFailure(
        "{\"type\":\"baseline\",\"value\":{\"items\":[],\"pinnedSessionIds\":[]}}",
        new AtomicBoolean(true));
  }

  @Test
  public void replacedServiceDropsBaselineRatherThanReturningStaleMembership() throws Exception {
    AtomicBoolean current = new AtomicBoolean(true);
    OkHttpClient client = new OkHttpClient();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      JsonObject open = JsonParser.parseString(message).getAsJsonObject();
                      current.set(false);
                      socket.send(
                          frame(
                              open,
                              "{\"type\":\"baseline\",\"value\":{\"items\":[],\"archivedSessionIds\":[],\"pinnedSessionIds\":[]}}"));
                    }
                  }));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
      assertThrows(
          IOException.class,
          () ->
              NativeChatWorkspaceSnapshot.read(
                  client, origin(host), "dsh-auth-test=secret", current::get));
    } finally {
      shutdown(client);
    }
  }

  @Test
  public void invalidOriginAndRetiredDialogAreRejectedBeforeConnecting() throws Exception {
    OkHttpClient client = new OkHttpClient();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              NativeChatWorkspaceSnapshot.read(
                  client, "https://outside.example", "dsh-auth-test=secret", () -> true));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              NativeChatWorkspaceSnapshot.read(
                  client, "http://127.0.0.1:1234", "unrelated=secret", () -> true));
      assertThrows(
          IOException.class,
          () ->
              NativeChatWorkspaceSnapshot.read(
                  client, "http://127.0.0.1:1234", "dsh-auth-test=secret", () -> false));
    } finally {
      shutdown(client);
    }
  }

  @Test
  public void missingBaselineTimesOutAndNeverReconnects() throws Exception {
    OkHttpClient client = new OkHttpClient();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
      long start = System.nanoTime();
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  NativeChatWorkspaceSnapshot.read(
                      client, origin(host), "dsh-auth-test=secret", () -> true));
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(failure.getMessage().contains("timed out"));
      assertTrue("bounded wait was " + elapsed + " ms", elapsed < 9000);
      assertEquals(1, host.getRequestCount());
    } finally {
      shutdown(client);
    }
  }

  @Test
  public void dismissalCancelsAnOpenSocketWithoutWaitingForServerData() throws Exception {
    OkHttpClient client = new OkHttpClient();
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch requested = new CountDownLatch(1);
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      requested.countDown();
                    }
                  }));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
      Thread reader =
          new Thread(
              () -> {
                try {
                  NativeChatWorkspaceSnapshot.read(
                      client, origin(host), "dsh-auth-test=secret", current::get);
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      reader.start();
      try {
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        current.set(false);
        reader.join(1500);
        assertFalse("retired dialog left a pending socket read", reader.isAlive());
        assertTrue(failure.get() instanceof IOException);
        assertEquals(1, host.getRequestCount());
      } finally {
        current.set(false);
        reader.interrupt();
        reader.join(2000);
      }
    } finally {
      shutdown(client);
    }
  }

  private static void expectFailure(String value, AtomicBoolean current) throws Exception {
    OkHttpClient client = new OkHttpClient();
    try (MockWebServer host = new MockWebServer()) {
      host.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String message) {
                      socket.send(frame(JsonParser.parseString(message).getAsJsonObject(), value));
                    }
                  }));
      host.start(InetAddress.getByName("127.0.0.1"), 0);
      assertThrows(
          IOException.class,
          () ->
              NativeChatWorkspaceSnapshot.read(
                  client, origin(host), "dsh-auth-test=secret", current::get));
    } finally {
      shutdown(client);
    }
  }

  private static String frame(JsonObject open, String value) {
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "item");
    frame.add("streamId", open.get("streamId"));
    frame.add("value", JsonParser.parseString(value));
    return frame.toString();
  }

  private static String origin(MockWebServer host) {
    return "http://127.0.0.1:" + host.getPort();
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
  }
}
