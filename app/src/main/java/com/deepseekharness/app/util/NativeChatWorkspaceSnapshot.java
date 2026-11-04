package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** 独立、有界、只读的工作区 baseline；不会挤占当前会话的实时流。 */
public final class NativeChatWorkspaceSnapshot {
  private NativeChatWorkspaceSnapshot() {}

  public static Set<String> read(
      OkHttpClient client, String authUrl, String cookie, BooleanSupplier current)
      throws IOException {
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (base == null || cookie == null || !cookie.matches("dsh-auth-[^=;\\s]+=[^;\\s]+")) {
      throw new IllegalArgumentException(
          "A local DSH origin and authentication cookie are required");
    }
    if (!current.getAsBoolean()) throw new IOException("DSH service changed");
    String origin = base.substring(0, base.length() - 1);
    String id = UUID.randomUUID().toString();
    JsonObject payload = new JsonObject();
    payload.add("args", new JsonObject());
    JsonObject open = new JsonObject();
    open.addProperty("type", "open");
    open.addProperty("streamId", id);
    open.addProperty("endpoint", "workspace/follow");
    open.add("payload", payload);
    Request request =
        new Request.Builder()
            .url(origin + "/api/remote.mux")
            .header("Origin", origin)
            .header("Cookie", cookie)
            .build();
    CountDownLatch ready = new CountDownLatch(1);
    AtomicBoolean done = new AtomicBoolean();
    AtomicReference<Set<String>> result = new AtomicReference<>();
    AtomicReference<IOException> error = new AtomicReference<>();
    WebSocket socket =
        client
            .newBuilder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
            .newWebSocket(
                request,
                new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket ws, Response response) {
                    if (!active(ws)) return;
                    if (!ws.send(open.toString()))
                      fail(ws, new IOException("Workspace stream could not open"));
                  }

                  @Override
                  public void onMessage(WebSocket ws, String message) {
                    if (!active(ws)) return;
                    try {
                      if (message.length() > 4 * 1024 * 1024)
                        throw new IOException("Workspace frame too large");
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      if (!frame.has("streamId") || !id.equals(frame.get("streamId").getAsString()))
                        return;
                      String type = frame.get("type").getAsString();
                      if ("error".equals(type) || "end".equals(type)) {
                        throw new IOException("Workspace baseline unavailable");
                      }
                      if (!"item".equals(type)) return;
                      JsonObject item = frame.getAsJsonObject("value");
                      if (!"baseline".equals(item.get("type").getAsString())) {
                        throw new IOException("Workspace baseline required");
                      }
                      JsonObject value = item.getAsJsonObject("value");
                      if (!value.has("items")
                          || !value.get("items").isJsonArray()
                          || !value.has("pinnedSessionIds")
                          || !value.get("pinnedSessionIds").isJsonArray()) {
                        throw new IOException("Incomplete workspace baseline");
                      }
                      Set<String> archived = NativeChatSessionsRpc.archivedIds(value);
                      if (!current.getAsBoolean()) throw new IOException("DSH service changed");
                      if (done.compareAndSet(false, true)) {
                        result.set(archived);
                        ready.countDown();
                        ws.cancel();
                      }
                    } catch (IOException invalid) {
                      fail(ws, invalid);
                    } catch (RuntimeException invalid) {
                      fail(ws, new IOException("Invalid workspace baseline", invalid));
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    fail(ws, new IOException("Workspace stream closed before baseline"));
                  }

                  @Override
                  public void onFailure(WebSocket ws, Throwable cause, Response response) {
                    fail(ws, new IOException("Workspace connection unavailable", cause));
                  }

                  private boolean active(WebSocket ws) {
                    if (done.get()) {
                      ws.cancel();
                      return false;
                    }
                    if (current.getAsBoolean()) return true;
                    fail(ws, new IOException("DSH service changed"));
                    return false;
                  }

                  private void fail(WebSocket ws, IOException cause) {
                    if (done.compareAndSet(false, true)) {
                      error.set(cause);
                      ready.countDown();
                    }
                    ws.cancel();
                  }
                });
    try {
      // 短间隔仅检查关闭/身份变化；不发重试，不让弹窗关闭后再等完整超时。
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while (!ready.await(100, TimeUnit.MILLISECONDS)) {
        if (!current.getAsBoolean()) throw new IOException("DSH service changed");
        if (System.nanoTime() >= deadline) throw new IOException("Workspace baseline timed out");
      }
      if (!current.getAsBoolean()) throw new IOException("DSH service changed");
      if (error.get() != null) throw error.get();
      Set<String> archived = result.get();
      if (archived == null) throw new IOException("Workspace baseline unavailable");
      return archived;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Workspace baseline cancelled", interrupted);
    } finally {
      done.set(true);
      socket.cancel();
    }
  }
}
