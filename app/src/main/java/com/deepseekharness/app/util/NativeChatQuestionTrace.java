package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 仅记录提问流程的固定状态，不接收答案或输出正文。 */
public final class NativeChatQuestionTrace {
  public enum Phase {
    STREAM_OPEN,
    STREAM_DETACH,
    EVENT_RECEIVED,
    EVENT_CANCEL,
    PROJECTION,
    SUPPRESSED,
    DIALOG,
    SUBMIT,
    CANCEL,
    DELEGATE,
    RPC_CONFIRMED,
    RPC_FAILED,
    TOOL_CALL,
    TOOL_RESULT,
    UNMATCHED_TOOL_RESULT,
    TURN_START,
    TURN_END,
    TURN_CANCEL,
    QUEUED,
    CLAIM_OPEN,
    CLAIM_READY,
    CLAIM_ENDED,
    TIMEOUT
  }

  public enum Reason {
    NONE,
    LIVE,
    SNAPSHOT,
    HOST_NOT_RESUMED,
    HOST_NO_FOCUS,
    HOST_FINISHING,
    ALREADY_SHOWN,
    DIALOG_OPEN,
    ANSWER_PENDING,
    NO_RPC,
    OTHER_SESSION,
    NO_QUESTIONS,
    QUESTION_BUSY,
    CLIENT_NOT_READY,
    STALE_CALLBACK,
    RENDER_FAILURE,
    REFOLLOW,
    SUSPEND,
    DESTROY,
    SESSION_SWITCH,
    DISCONNECTED,
    SNAPSHOT_DEADLINE,
    STREAM_ENDED,
    STREAM_CLOSED,
    STREAM_CONNECTION,
    STREAM_FRAME,
    STREAM_SIZE,
    STREAM_OPEN_FAILED,
    REPLACED,
    REMOTE_CANCEL,
    SUBMIT_BUTTON,
    CANCEL_BUTTON,
    DIALOG_CANCEL,
    EXPLICIT_STOP,
    SHOW_CALLBACK,
    DISMISSED,
    EDIT,
    INPUT_FOCUS,
    INPUT_BLUR,
    ANSWER,
    REJECT,
    NEXT,
    IO_FAILURE,
    RUNTIME_FAILURE,
    NOT_ACCEPTED,
    ASK_ABORTED,
    ASK_CANCELLED,
    ASK_TIMED_OUT,
    OTHER,
    SUCCESS,
    COMPLETE,
    CANCELLED,
    ABORTED,
    ERROR,
    DUPLICATE_EVENT,
    QUEUE_FULL,
    QUEUE_LIMIT,
    INVALID_EVENT,
    CLAIM_NOT_READY,
    CLAIM_OPENING_TIMEOUT,
    SETTLED
  }

  // 盐只在本进程中保留；关联标记不能跨应用重启追踪，也不写入导出。
  private static final byte[] SALT = new byte[32];

  static {
    new SecureRandom().nextBytes(SALT);
  }

  private static final int LIMIT = 256;
  private static final java.util.concurrent.ThreadPoolExecutor WRITER =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0,
          java.util.concurrent.TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(256),
          task -> {
            Thread thread = new Thread(task, "dsha-question-diagnostics");
            thread.setDaemon(true);
            return thread;
          },
          new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
  private final Consumer<String> sink;
  private final Set<String> calls = new LinkedHashSet<>();
  private final Set<String> suppressed = new LinkedHashSet<>();

  public static final class Ref {
    private final String session, call, event;

    private Ref(String session, String call, String event) {
      this.session = session;
      this.call = call;
      this.event = event;
    }

    private String fields() {
      return "s=" + session + " c=" + call + " e=" + event;
    }
  }

  public NativeChatQuestionTrace(Consumer<String> sink) {
    this.sink = sink;
  }

  /** 只把格式化后的固定字段交给有界后台写入；满载时丢弃诊断，不阻塞提问。 */
  public static NativeChatQuestionTrace asynchronous(Consumer<String> sink) {
    return new NativeChatQuestionTrace(
        line ->
            WRITER.execute(
                () -> {
                  try {
                    sink.accept(line);
                  } catch (RuntimeException ignored) {
                  }
                }));
  }

  public Ref ref(String session, String call, String event) {
    return new Ref(hash("session", session), hash("call", call), hash("event", event));
  }

  public Ref eventRef(String session, JsonObject frame) {
    return ref(
        session, text(object(object(frame, "request"), "wait"), "callId"), text(frame, "eventId"));
  }

  public synchronized void mark(Phase phase, Ref ref, Reason reason) {
    emit(phase, ref, reason, Reason.LIVE, "");
  }

  public synchronized void once(Phase phase, Ref ref, Reason reason) {
    String key = phase + " " + ref.fields() + " " + reason;
    if (suppressed.contains(key)) return;
    boundedAdd(suppressed, key);
    mark(phase, ref, reason);
  }

  public synchronized void dialog(
      Ref ref, Reason signal, boolean showing, boolean attached, boolean focused) {
    emit(
        Phase.DIALOG,
        ref,
        signal,
        Reason.LIVE,
        " showing="
            + (showing ? 1 : 0)
            + " attached="
            + (attached ? 1 : 0)
            + " focused="
            + (focused ? 1 : 0));
  }

  /** 历史快照单独标记；从不读取参数、消息正文、答案或异常文本。 */
  public synchronized void observe(String session, JsonObject item) {
    if ("snapshot".equals(text(item, "type"))) {
      JsonElement records = item.get("records");
      if (records == null || !records.isJsonArray()) return;
      var rows = records.getAsJsonArray();
      // 网关本身有大小限制；此处还限制一次快照的诊断工作量。
      for (int i = Math.max(0, rows.size() - LIMIT); i < rows.size(); i++) {
        JsonElement row = rows.get(i);
        if (row.isJsonObject())
          observeEvent(session, object(row.getAsJsonObject(), "event"), Reason.SNAPSHOT);
      }
    } else if ("event".equals(text(item, "type"))) {
      observeEvent(session, object(item, "event"), Reason.LIVE);
    }
  }

  private void observeEvent(String session, JsonObject event, Reason source) {
    JsonObject data = object(event, "data");
    if (data == null) return;
    String type = text(event, "type");
    if ("tool/call".equals(type) && "ask_user_question".equals(text(data, "name"))) {
      Ref ref = ref(session, text(data, "callId"), "");
      if (valid(ref.call)) boundedAdd(calls, ref.session + ":" + ref.call);
      emit(Phase.TOOL_CALL, ref, Reason.NONE, source, "");
    } else if ("tool/result".equals(type)) {
      Ref ref = ref(session, text(object(data, "message"), "toolCallId"), "");
      Reason reason = errorCode(text(object(data, "error"), "code"));
      boolean known = valid(ref.call) && calls.contains(ref.session + ":" + ref.call);
      if (!known && reason == Reason.OTHER) return;
      if (known && object(data, "error") == null) {
        JsonElement isError =
            object(data, "message") == null ? null : object(data, "message").get("isError");
        reason =
            isError != null
                    && isError.isJsonPrimitive()
                    && isError.getAsJsonPrimitive().isBoolean()
                    && isError.getAsBoolean()
                ? Reason.OTHER
                : Reason.SUCCESS;
      }
      emit(known ? Phase.TOOL_RESULT : Phase.UNMATCHED_TOOL_RESULT, ref, reason, source, "");
    } else if ("turn/start".equals(type)) {
      emit(Phase.TURN_START, ref(session, "", ""), Reason.NONE, source, "");
    } else if ("turn/end".equals(type)) {
      Reason reason =
          switch (text(object(data, "reason"), "kind")) {
            case "complete", "completed" -> Reason.COMPLETE;
            case "aborted" -> Reason.ABORTED;
            case "cancelled", "canceled" -> Reason.CANCELLED;
            case "error" -> Reason.ERROR;
            default -> Reason.OTHER;
          };
      String cause =
          switch (text(object(object(data, "reason"), "reason"), "kind")) {
            case "user" -> "USER";
            case "parent" -> "PARENT";
            case "hook" -> "HOOK";
            case "disposed" -> "DISPOSED";
            case "legacy" -> "LEGACY";
            default -> "OTHER";
          };
      emit(
          Phase.TURN_END,
          ref(session, "", ""),
          reason,
          source,
          reason == Reason.ABORTED ? " cause=" + cause : "");
    }
  }

  public static Reason streamFailure(String code) {
    return switch (code == null ? "" : code) {
      case "stream-ended" -> Reason.STREAM_ENDED;
      case "stream-closed" -> Reason.STREAM_CLOSED;
      case "stream-connection" -> Reason.STREAM_CONNECTION;
      case "stream-frame" -> Reason.STREAM_FRAME;
      case "stream-size" -> Reason.STREAM_SIZE;
      case "stream-open" -> Reason.STREAM_OPEN_FAILED;
      default -> Reason.OTHER;
    };
  }

  private static Reason errorCode(String code) {
    return switch (code) {
      case "ASK_ABORTED" -> Reason.ASK_ABORTED;
      case "ASK_CANCELLED" -> Reason.ASK_CANCELLED;
      case "ASK_TIMED_OUT" -> Reason.ASK_TIMED_OUT;
      default -> Reason.OTHER;
    };
  }

  private void emit(Phase phase, Ref ref, Reason reason, Reason source, String state) {
    try {
      sink.accept(
          "phase="
              + phase
              + " "
              + ref.fields()
              + " reason="
              + reason
              + " source="
              + source
              + state);
    } catch (RuntimeException ignored) {
      // 诊断失败不能改变提问、回答或关闭流程。
    }
  }

  private static boolean valid(String id) {
    return !"-".equals(id) && !"invalid".equals(id);
  }

  private static void boundedAdd(Set<String> values, String value) {
    values.add(value);
    while (values.size() > LIMIT) values.remove(values.iterator().next());
  }

  private static String hash(String domain, String value) {
    if (value == null || value.isEmpty()) return "-";
    if (value.length() > 4096) return "invalid";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(SALT);
      digest.update(domain.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(32);
      final String hex = "0123456789abcdef";
      for (int i = 0; i < 16; i++)
        result.append(hex.charAt((bytes[i] & 255) >>> 4)).append(hex.charAt(bytes[i] & 15));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      return "invalid";
    }
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object == null ? null : object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }

  private static JsonObject object(JsonObject object, String key) {
    JsonElement value = object == null ? null : object.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }
}
