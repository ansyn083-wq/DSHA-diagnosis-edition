package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** A single authenticated session follower on the DSH gateway's multiplexed socket. */
public final class NativeChatStream implements AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();
  private final WebSocket socket;
  private final BooleanSupplier current;
  private volatile boolean questionEventsAccepted;
  private final CountDownLatch questionEventsReady = new CountDownLatch(1);

  public static NativeChatStream open(
      OkHttpClient client,
      String authUrl,
      String cookie,
      String sessionId,
      BooleanSupplier current,
      Consumer<JsonObject> items,
      Consumer<String> errors) {
    return open(client, authUrl, cookie, sessionId, current, items, null, errors);
  }

  public static NativeChatStream open(
      OkHttpClient client,
      String authUrl,
      String cookie,
      String sessionId,
      BooleanSupplier current,
      Consumer<JsonObject> items,
      Consumer<JsonObject> events,
      Consumer<String> errors) {
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (base == null || cookie == null || !cookie.matches("dsh-auth-[^=;\\s]+=[^;\\s]+")) {
      throw new IllegalArgumentException(
          "A local DSH origin and authentication cookie are required");
    }
    if (!current.getAsBoolean()) throw new IllegalStateException("DSH service changed");
    String origin = base.substring(0, base.length() - 1);
    Request request =
        new Request.Builder()
            .url(origin + "/api/remote.mux")
            .header("Origin", origin)
            .header("Cookie", cookie)
            .build();
    return new NativeChatStream(client, request, sessionId, current, items, events, errors);
  }

  private NativeChatStream(
      OkHttpClient client,
      Request request,
      String sessionId,
      BooleanSupplier current,
      Consumer<JsonObject> items,
      Consumer<JsonObject> events,
      Consumer<String> errors) {
    this.current = current;
    String streamId = UUID.randomUUID().toString();
    String eventsId = UUID.randomUUID().toString();
    JsonObject address = new JsonObject();
    address.addProperty("kind", "session");
    address.addProperty("sessionId", sessionId);
    JsonObject args = new JsonObject();
    args.add("address", address);
    args.addProperty("assistantStream", true);
    args.addProperty("maxMessages", 100);
    JsonObject wireArgs = new JsonObject();
    wireArgs.add("request", args);
    JsonObject open = openFrame(streamId, "session/follow", wireArgs);
    JsonObject openEvents = openFrame(eventsId, "$events", new JsonObject());
    socket =
        client
            .newBuilder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
            .newWebSocket(
                request,
                new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket ws, Response response) {
                    if (!active(ws)) return;
                    if (events != null && !ws.send(openEvents.toString())
                        || !ws.send(open.toString())) {
                      fail(ws, "stream-open");
                    }
                  }

                  @Override
                  public void onMessage(WebSocket ws, String message) {
                    if (!active(ws)) return;
                    try {
                      if (message.length() > 4 * 1024 * 1024) {
                        fail(ws, "stream-size");
                        return;
                      }
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      if (!frame.has("streamId")) return;
                      String id = frame.get("streamId").getAsString();
                      if (events != null && eventsId.equals(id)) {
                        String type = frame.get("type").getAsString();
                        if ("error".equals(type) || "end".equals(type)) {
                          fail(ws, "stream-ended");
                          return;
                        }
                        if (!"item".equals(type)) return;
                        JsonObject value = frame.getAsJsonObject("value");
                        if (value == null) {
                          fail(ws, "stream-frame");
                          return;
                        }
                        boolean ready = "ready".equals(value.get("type").getAsString());
                        if (ready
                            && (questionEventsAccepted
                                || !value.has("clientId")
                                || !value.get("clientId").isJsonPrimitive()
                                || !value.get("clientId").getAsJsonPrimitive().isString()
                                || value.get("clientId").getAsString().isBlank()
                                || value.get("clientId").getAsString().length() > 4096)) {
                          fail(ws, "stream-frame");
                          return;
                        }
                        events.accept(value);
                        if (ready) {
                          questionEventsAccepted = true;
                          questionEventsReady.countDown();
                        }
                        return;
                      }
                      if (!streamId.equals(id)) return;
                      switch (frame.get("type").getAsString()) {
                        case "item" -> items.accept(frame.getAsJsonObject("value"));
                        case "error", "end" -> fail(ws, "stream-ended");
                        default -> {}
                      }
                    } catch (RuntimeException invalid) {
                      fail(ws, "stream-frame");
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    fail(ws, "stream-closed");
                  }

                  @Override
                  public void onFailure(WebSocket ws, Throwable error, Response response) {
                    fail(ws, "stream-connection");
                  }

                  private boolean active(WebSocket ws) {
                    if (!closed.get() && current.getAsBoolean()) return true;
                    ws.cancel();
                    return false;
                  }

                  private void fail(WebSocket ws, String code) {
                    boolean notify = closed.compareAndSet(false, true) && current.getAsBoolean();
                    questionEventsReady.countDown();
                    ws.cancel();
                    if (notify) errors.accept(code);
                  }
                });
  }

  private static JsonObject openFrame(String id, String endpoint, JsonObject args) {
    JsonObject payload = new JsonObject();
    payload.add("args", args);
    JsonObject open = new JsonObject();
    open.addProperty("type", "open");
    open.addProperty("streamId", id);
    open.addProperty("endpoint", endpoint);
    open.add("payload", payload);
    return open;
  }

  public void awaitQuestionEvents() throws InterruptedException, IOException {
    boolean completed = questionEventsReady.await(2, TimeUnit.SECONDS);
    if (!completed || !questionEventsAccepted || closed.get() || !current.getAsBoolean()) {
      throw new IOException("Question event stream is not ready");
    }
  }

  @Override
  public void close() {
    boolean cancel = closed.compareAndSet(false, true);
    questionEventsReady.countDown();
    if (cancel) socket.cancel();
  }
}
