package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Read-only bounded presentation data. It never executes a tool or approves an audit event. */
public final class NativeChatDetails {
  public record Tool(
      String id, String name, String arguments, String result, String state, long seq) {}

  public record Attachment(String id, String name, boolean image, long bytes, long seq) {}

  private final LinkedHashMap<String, Tool> tools = new LinkedHashMap<>();
  private final LinkedHashMap<String, Attachment> attachments = new LinkedHashMap<>();
  private final ArrayList<Long> messageSequences = new ArrayList<>();
  private long cursor = -1, inputTokens, outputTokens;
  private JsonArray lastUserContent = new JsonArray();
  private boolean failedTurn, turnOpen;
  private int pendingHumans, turnHumans;
  private final LinkedHashMap<Long, String> messageIds = new LinkedHashMap<>();
  private static final int LIMIT = 128 * 1024;

  public List<Tool> tools() {
    return List.copyOf(tools.values());
  }

  public List<Attachment> attachments() {
    return List.copyOf(attachments.values());
  }

  public List<Long> messageSequences() {
    return List.copyOf(messageSequences);
  }

  public long inputTokens() {
    return inputTokens;
  }

  public long outputTokens() {
    return outputTokens;
  }

  public boolean canRetry() {
    return failedTurn && turnHumans == 1 && !lastUserContent.isEmpty();
  }

  public JsonArray retryContent() {
    return lastUserContent.deepCopy();
  }

  public String messageId(long seq) {
    return messageIds.getOrDefault(seq, "");
  }

  public void apply(JsonObject item) {
    if ("snapshot".equals(text(item, "type"))) {
      tools.clear();
      attachments.clear();
      messageSequences.clear();
      inputTokens = outputTokens = 0;
      lastUserContent = new JsonArray();
      failedTurn = turnOpen = false;
      pendingHumans = turnHumans = 0;
      messageIds.clear();
      cursor = number(item, "cursor", -1);
      for (JsonElement record : array(item, "records")) {
        JsonObject event = object(record.getAsJsonObject(), "event");
        if (event != null) event(event);
      }
    } else if ("event".equals(text(item, "type"))) {
      JsonObject event = object(item, "event");
      if (event == null) return;
      long seq = number(event, "seq", -1);
      if (seq <= cursor) return;
      if (seq != cursor + 1) throw new IllegalArgumentException("DSH detail event gap");
      cursor = seq;
      event(event);
    }
  }

  private void event(JsonObject event) {
    String type = text(event, "type");
    JsonObject data = object(event, "data");
    if (data == null) return;
    long seq = number(event, "seq", -1);
    if ("turn/start".equals(type)) {
      failedTurn = false;
      turnOpen = true;
      turnHumans = pendingHumans;
      pendingHumans = 0;
      if (turnHumans != 1) lastUserContent = new JsonArray();
    } else if ("turn/end".equals(type)) {
      JsonObject reason = object(data, "reason");
      failedTurn = reason != null && "error".equals(text(reason, "kind"));
      turnOpen = false;
      if (!failedTurn) lastUserContent = new JsonArray();
    } else if ("tool/call".equals(type)) {
      String id = text(data, "callId");
      if (id.isBlank()) return;
      tools.put(
          id, new Tool(id, text(data, "name"), bound(text(data, "arguments")), "", "running", seq));
      trim(tools);
    } else if ("tool/result".equals(type)) {
      JsonObject message = object(data, "message");
      if (message == null) return;
      String id = text(message, "toolCallId");
      addAttachments(message, seq);
      Tool old = tools.get(id);
      if (old == null) return;
      boolean error =
          data.has("error") || (message.has("isError") && message.get("isError").getAsBoolean());
      tools.put(
          id,
          new Tool(
              id,
              old.name(),
              old.arguments(),
              contentText(message),
              error ? "error" : "completed",
              old.seq()));
    } else if ("user/message".equals(type) || "assistant/message".equals(type)) {
      boolean assistant = "assistant/message".equals(type);
      JsonObject message = assistant ? object(data, "message") : data;
      if (message == null) return;
      if (!visible(message, assistant ? "model" : "user")) {
        if (!assistant) {
          JsonObject source = object(message, "source");
          String kind = source == null ? "" : text(source, "kind");
          if (!"runtime-context".equals(kind) && !"time-context".equals(kind)) {
            lastUserContent = new JsonArray();
            pendingHumans = turnHumans = 0;
          }
        }
        return;
      }
      if (!assistant) {
        if (turnOpen) turnHumans++;
        else pendingHumans++;
        lastUserContent = array(message, "content").deepCopy();
        failedTurn = false;
      }
      if (hasText(message) || hasAttachments(message)) {
        String messageId = text(message, "id");
        if (assistant && !messageId.isBlank()) messageIds.put(seq, messageId);
        while (messageIds.size() > 150) messageIds.remove(messageIds.keySet().iterator().next());
        messageSequences.add(seq);
        if (messageSequences.size() > 150) messageSequences.remove(0);
      }
      addAttachments(message, seq);
      JsonObject usage = object(data, "usage");
      if (assistant && usage != null) {
        inputTokens = addCount(inputTokens, number(usage, "inputTokens", 0));
        outputTokens = addCount(outputTokens, number(usage, "outputTokens", 0));
      }
    }
  }

  private static long addCount(long total, long value) {
    return value < 0 ? total : total > Long.MAX_VALUE - value ? Long.MAX_VALUE : total + value;
  }

  private static boolean visible(JsonObject message, String kind) {
    JsonElement raw = message.get("source");
    return raw == null
        || raw.isJsonNull()
        || raw.isJsonObject() && kind.equals(text(raw.getAsJsonObject(), "kind"));
  }

  private static boolean hasText(JsonObject message) {
    for (JsonElement entry : array(message, "content")) {
      if (entry.isJsonObject()) {
        JsonObject part = entry.getAsJsonObject();
        if ("text".equals(text(part, "type")) && !text(part, "text").isBlank()) return true;
      }
    }
    return false;
  }

  private boolean hasAttachments(JsonObject message) {
    for (JsonElement entry : array(message, "content")) {
      if (!entry.isJsonObject()) continue;
      String kind = text(entry.getAsJsonObject(), "type");
      if ("image".equals(kind) || "file".equals(kind)) return true;
    }
    return false;
  }

  private void addAttachments(JsonObject message, long seq) {
    for (JsonElement entry : array(message, "content")) {
      if (!entry.isJsonObject()) continue;
      JsonObject part = entry.getAsJsonObject();
      String kind = text(part, "type");
      if (!"image".equals(kind) && !"file".equals(kind)) continue;
      JsonObject ref = object(part, "attachment");
      if (ref == null || text(ref, "attachmentId").isBlank()) continue;
      String id = text(ref, "attachmentId"), name = text(ref, "name");
      attachments.put(
          id,
          new Attachment(
              id,
              name.isBlank() ? kind : name,
              "image".equals(kind),
              number(ref, "bytes", -1),
              seq));
      trim(attachments);
    }
  }

  private static String contentText(JsonObject message) {
    StringBuilder text = new StringBuilder();
    for (JsonElement entry : array(message, "content")) {
      if (!entry.isJsonObject()) continue;
      JsonObject part = entry.getAsJsonObject();
      if (!"text".equals(text(part, "type"))) continue;
      if (text.length() > 0) text.append('\n');
      String value = text(part, "text");
      text.append(value, 0, Math.min(value.length(), Math.max(0, LIMIT - text.length())));
      if (text.length() >= LIMIT) break;
    }
    return text.toString();
  }

  private static <T> void trim(LinkedHashMap<String, T> map) {
    while (map.size() > 100) map.remove(map.keySet().iterator().next());
  }

  private static String bound(String value) {
    return value.length() <= LIMIT ? value : value.substring(0, LIMIT) + "\n…";
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }

  private static long number(JsonObject object, String key, long fallback) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
        ? value.getAsLong()
        : fallback;
  }

  private static JsonObject object(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static JsonArray array(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
  }
}
