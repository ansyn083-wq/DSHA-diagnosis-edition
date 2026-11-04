package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.Proxy;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Holds one timed question's Host claim and runs its Client-owned countdown.
 * Callbacks run off the UI thread. The owner must marshal them to its current
 * question generation and close this claim after settlement or detachment.
 * This class never sends an answer or a user-question rejection.
 */
public final class NativeChatQuestionWait implements AutoCloseable {
  private static final long OPENING_TIMEOUT_MS = 10_000;
  private static final ScheduledThreadPoolExecutor TIMERS = timers();
  private final BooleanSupplier current;
  private final Runnable ready;
  private final Runnable timedOut;
  private final Consumer<String> ended;
  private final String streamId = UUID.randomUUID().toString();
  private final Countdown countdown = new Countdown();
  private volatile WebSocket socket;
  private ScheduledFuture<?> timer;
  private ScheduledFuture<?> openingTimer;
  private boolean closed;
  private boolean opened;
  private boolean expired;

  /** Open only for a request whose wait.timed is true and whose callId is present. */
  public static NativeChatQuestionWait open(
      OkHttpClient client,
      String authUrl,
      String cookie,
      String agentId,
      String callId,
      BooleanSupplier current,
      Runnable ready,
      Runnable timedOut,
      Consumer<String> ended) {
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (base == null
        || cookie == null
        || !cookie.matches("dsh-auth-[^=;\\s]+=[^;\\s]+")
        || agentId == null
        || agentId.isBlank()
        || callId == null
        || callId.isBlank()) {
      throw new IllegalArgumentException(
          "A local DSH question identity and authentication cookie are required");
    }
    Objects.requireNonNull(client);
    Objects.requireNonNull(current);
    Objects.requireNonNull(ready);
    Objects.requireNonNull(timedOut);
    Objects.requireNonNull(ended);
    if (!current.getAsBoolean()) throw new IllegalStateException("DSH question changed");
    String origin = base.substring(0, base.length() - 1);
    Request request =
        new Request.Builder()
            .url(origin + "/api/remote.mux")
            .header("Origin", origin)
            .header("Cookie", cookie)
            .build();
    return new NativeChatQuestionWait(
        client, request, agentId, callId, current, ready, timedOut, ended);
  }

  private NativeChatQuestionWait(
      OkHttpClient client,
      Request request,
      String agentId,
      String callId,
      BooleanSupplier current,
      Runnable ready,
      Runnable timedOut,
      Consumer<String> ended) {
    this.current = current;
    this.ready = ready;
    this.timedOut = timedOut;
    this.ended = ended;
    JsonObject args = new JsonObject();
    args.addProperty("agentId", agentId);
    args.addProperty("callId", callId);
    JsonObject payload = new JsonObject();
    payload.add("args", args);
    JsonObject open = new JsonObject();
    open.addProperty("type", "open");
    open.addProperty("streamId", streamId);
    open.addProperty("endpoint", "userQuestions/attachWait");
    open.add("payload", payload);
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
                    if (!active()) {
                      ws.cancel();
                      return;
                    }
                    if (!ws.send(open.toString())) finish("question-wait-open");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String message) {
                    if (!active()) return;
                    try {
                      if (message.length() > 64 * 1024)
                        throw new IllegalArgumentException("Frame too large");
                      JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
                      if (!frame.has("streamId")
                          || !streamId.equals(frame.get("streamId").getAsString())) return;
                      switch (frame.get("type").getAsString()) {
                        case "item" -> receiveOpening(frame.getAsJsonObject("value"));
                        case "end" -> finish("question-wait-ended");
                        case "error" -> finish("question-wait-error");
                        default -> finish("question-wait-frame");
                      }
                    } catch (RuntimeException invalid) {
                      finish("question-wait-frame");
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    finish("question-wait-closed");
                  }

                  @Override
                  public void onClosed(WebSocket ws, int code, String reason) {
                    finish("question-wait-closed");
                  }

                  @Override
                  public void onFailure(WebSocket ws, Throwable error, Response response) {
                    finish("question-wait-connection");
                  }
                });
    // Listener callbacks may run before newWebSocket returns. If one detached
    // the claim before the field was assigned, do not leave its socket alive.
    synchronized (this) {
      if (closed) socket.cancel();
      else if (!opened)
        openingTimer =
            TIMERS.schedule(
                () -> finish("question-wait-opening-timeout", true),
                OPENING_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }
  }

  private void receiveOpening(JsonObject value) {
    if (value == null
        || !value.has("remainingMs")
        || !value.get("remainingMs").isJsonPrimitive()
        || !value.getAsJsonPrimitive("remainingMs").isNumber()) {
      throw new IllegalArgumentException("Missing wait duration");
    }
    long remainingMs = value.get("remainingMs").getAsBigDecimal().longValueExact();
    if (remainingMs < 0 || remainingMs > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid wait duration");
    synchronized (this) {
      if (closed) return;
      if (opened) throw new IllegalArgumentException("Duplicate wait opening");
      opened = true;
      if (openingTimer != null) openingTimer.cancel(false);
      openingTimer = null;
      countdown.start(remainingMs, System.nanoTime());
    }
    if (!active()) return;
    ready.run();
    reschedule();
  }

  /** Pause a pristine countdown while the answer surface has focus. */
  public void holdFocus() {
    if (!active()) return;
    synchronized (this) {
      countdown.holdFocus(System.nanoTime());
    }
    reschedule();
  }

  /** Resume only its unused duration when pristine focus leaves. */
  public void releaseFocus() {
    if (!active()) return;
    synchronized (this) {
      countdown.releaseFocus(System.nanoTime());
    }
    reschedule();
  }

  /** First actual answer edit or option selection holds the wait until settlement. */
  public void engage() {
    if (!active()) return;
    synchronized (this) {
      countdown.engage();
    }
    reschedule();
  }

  /** Explicitly asking for more time holds the wait until settlement. */
  public void takeTime() {
    if (!active()) return;
    synchronized (this) {
      countdown.takeTime();
    }
    reschedule();
  }

  private synchronized void reschedule() {
    if (timer != null) timer.cancel(false);
    timer = null;
    if (closed || expired) return;
    long delay = countdown.delayNanos(System.nanoTime());
    if (delay >= 0) timer = TIMERS.schedule(this::deadlineReached, delay, TimeUnit.NANOSECONDS);
  }

  private void deadlineReached() {
    if (!active()) return;
    synchronized (this) {
      if (closed || expired) return;
      long delay = countdown.delayNanos(System.nanoTime());
      if (delay != 0) {
        reschedule();
        return;
      }
      expired = true;
      timer = null;
    }
    // Keep the Host claim until the owner sends ASK_TIMED_OUT and closes it.
    // Closing first could let the Host deadline win before that result arrives.
    if (active()) timedOut.run();
  }

  private boolean active() {
    synchronized (this) {
      if (closed) return false;
    }
    if (current.getAsBoolean()) return true;
    close();
    return false;
  }

  private void finish(String code) {
    finish(code, false);
  }

  private void finish(String code, boolean onlyIfUnopened) {
    synchronized (this) {
      if (closed) return;
    }
    boolean notify = current.getAsBoolean();
    if (detach(onlyIfUnopened) && notify) ended.accept(code);
  }

  private boolean detach(boolean onlyIfUnopened) {
    synchronized (this) {
      if (closed || onlyIfUnopened && opened) return false;
      closed = true;
      if (timer != null) timer.cancel(false);
      timer = null;
      if (openingTimer != null) openingTimer.cancel(false);
      openingTimer = null;
    }
    JsonObject cancel = new JsonObject();
    cancel.addProperty("type", "cancel");
    cancel.addProperty("streamId", streamId);
    // A logical cancellation releases only this claim, never the tool/turn.
    WebSocket attached = socket;
    if (attached != null) {
      attached.send(cancel.toString());
      attached.close(1000, "question-wait-detached");
    }
    return true;
  }

  @Override
  public void close() {
    detach(false);
  }

  private static ScheduledThreadPoolExecutor timers() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "dsha-question-wait");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /** Countdown state is separate from its timer so focus events preserve unused time. */
  static final class Countdown {
    private boolean started;
    private boolean focused;
    private boolean held;
    private boolean engaged;
    private long remainingNanos;
    private long deadlineNanos;

    void start(long remainingMs, long now) {
      started = true;
      remainingNanos = TimeUnit.MILLISECONDS.toNanos(remainingMs);
      deadlineNanos = now + remainingNanos;
    }

    void holdFocus(long now) {
      if (focused || held || engaged) return;
      if (started) remainingNanos = Math.max(0, deadlineNanos - now);
      focused = true;
    }

    void releaseFocus(long now) {
      if (!focused) return;
      focused = false;
      if (started) deadlineNanos = now + remainingNanos;
    }

    void engage() {
      engaged = true;
      focused = false;
    }

    void takeTime() {
      held = true;
      focused = false;
    }

    long delayNanos(long now) {
      return !started || focused || held || engaged ? -1 : Math.max(0, deadlineNanos - now);
    }
  }
}
