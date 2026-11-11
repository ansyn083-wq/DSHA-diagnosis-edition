package com.deepseekharness.app.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Reduces one session's gateway snapshots and live frames to plain-text chat. */
public final class NativeChatTranscript {
  public record Message(boolean assistant, String text, String reasoning, long turnMillis) {
    public Message(boolean assistant, String text) {
      this(assistant, text, "", -1);
    }
  }

  private static final int MAX_REASONING_CHARS = 256 * 1024;

  private final ArrayList<Message> messages = new ArrayList<>();
  private final ArrayList<Long> messageSequences = new ArrayList<>();
  private final StringBuilder partial = new StringBuilder();
  private final StringBuilder partialReasoning = new StringBuilder();
  private final StringBuilder turnReasoning = new StringBuilder();
  private int lastReply = -1;
  private boolean running;
  private boolean thinking;
  private long startedAt = -1;
  private long duration = -1;

  public boolean running() {
    return running;
  }

  public boolean thinking() {
    return thinking;
  }

  public long startedAt() {
    return startedAt;
  }

  public long duration() {
    return duration;
  }

  private long cursor = -1;
  private long revision;
  private String attempt;
  private boolean attemptEnded;
  private long attemptTurn;
  private long attemptStep;
  private int nextChunk;

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public List<Long> messageSequences() {
    return List.copyOf(messageSequences);
  }

  public String partial() {
    return partial.toString();
  }

  public String partialReasoning() {
    if (turnReasoning.length() == 0) return partialReasoning.toString();
    if (partialReasoning.length() == 0) return turnReasoning.toString();
    return turnReasoning + "\n\n" + partialReasoning;
  }

  public void apply(JsonObject item) {
    switch (item.get("type").getAsString()) {
      case "snapshot" -> restore(item);
      case "event" -> {
        JsonObject event = item.getAsJsonObject("event");
        long seq = event.get("seq").getAsLong();
        if (seq <= cursor) return;
        if (seq != cursor + 1) throw new IllegalArgumentException("DSH event gap");
        cursor = seq;
        appendMessage(event);
      }
      case "assistant-stream" -> applyAssistantFrame(item.getAsJsonObject("frame"));
      default -> {}
    }
  }

  private void restore(JsonObject item) {
    messages.clear();
    messageSequences.clear();
    running = thinking = false;
    startedAt = duration = -1;
    lastReply = -1;
    turnReasoning.setLength(0);
    clearAttempt();
    cursor = item.get("cursor").getAsLong();
    for (JsonElement record : item.getAsJsonArray("records")) {
      appendMessage(record.getAsJsonObject().getAsJsonObject("event"));
    }
    JsonObject baseline = item.getAsJsonObject("assistantStream");
    revision = baseline == null ? 0 : baseline.get("revision").getAsLong();
    if (baseline == null || !baseline.has("activeAttempt")) return;
    JsonObject active = baseline.getAsJsonObject("activeAttempt");
    startAttempt(active);
    nextChunk = active.get("nextIndex").getAsInt();
    for (JsonElement itemRecord : active.getAsJsonArray("stream")) {
      JsonObject record = itemRecord.getAsJsonObject();
      switch (record.get("type").getAsString()) {
        case "chunk" -> appendDelta(record.getAsJsonObject("chunk"));
        case "text-chunks", "reasoning-chunks" -> {
          boolean reasoning = "reasoning-chunks".equals(record.get("type").getAsString());
          thinking = reasoning;
          for (JsonElement text : record.getAsJsonArray("texts")) {
            if (reasoning) appendReasoning(partialReasoning, text.getAsString());
            else appendPartial(text.getAsString());
          }
        }
        default -> {}
      }
    }
  }

  private void appendMessage(JsonObject event) {
    String type = event.get("type").getAsString();
    if ("turn/start".equals(type)) {
      running = true;
      thinking = false;
      startedAt = event.has("time") ? event.get("time").getAsLong() : -1;
      duration = -1;
      lastReply = -1;
      turnReasoning.setLength(0);
      return;
    }
    if ("turn/end".equals(type)) {
      // 结束帧可能先于持久消息；没有正文时仍保留模型实际返回的思考。
      String remaining = partialReasoning();
      if (!remaining.isBlank()) {
        messages.add(new Message(true, partial.toString(), remaining, -1));
        messageSequences.add(event.has("seq") ? -event.get("seq").getAsLong() - 2 : -1L);
        trimMessages();
        lastReply = messages.size() - 1;
      }
      partial.setLength(0);
      partialReasoning.setLength(0);
      if (running && startedAt >= 0 && event.has("time")) {
        duration = Math.max(0, event.get("time").getAsLong() - startedAt);
        if (lastReply >= 0) {
          Message reply = messages.get(lastReply);
          messages.set(lastReply, new Message(true, reply.text(), reply.reasoning(), duration));
        }
      }
      lastReply = -1;
      turnReasoning.setLength(0);
      running = thinking = false;
      return;
    }
    boolean assistant = "assistant/message".equals(type);
    if (!assistant && !"user/message".equals(type)) return;
    JsonObject data = event.getAsJsonObject("data");
    if (!assistant && !isHumanMessage(data)) return;
    JsonObject message = assistant ? data.getAsJsonObject("message") : data;
    if (assistant && !hasSource(message, "model")) return;
    boolean matchesAttempt =
        assistant
            && attempt != null
            && data.has("turn")
            && data.has("step")
            && data.get("turn").getAsLong() == attemptTurn
            && data.get("step").getAsLong() == attemptStep;
    StringBuilder text = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    for (JsonElement part : message.getAsJsonArray("content")) {
      JsonObject block = part.getAsJsonObject();
      String kind = block.get("type").getAsString();
      if ("text".equals(kind)) {
        text.append(block.get("text").getAsString());
      } else if (assistant && "reasoning".equals(kind)) {
        JsonElement value = block.get("text");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
          if (reasoning.length() > 0) appendReasoning(reasoning, "\n\n");
          appendReasoning(reasoning, value.getAsString());
        }
      } else if ("image".equals(kind) || "file".equals(kind)) {
        if (text.length() > 0) text.append('\n');
        JsonObject attachment = block.getAsJsonObject("attachment");
        JsonElement name = attachment == null ? null : attachment.get("name");
        String label =
            name == null || name.isJsonNull() || name.getAsString().isBlank()
                ? ("image".equals(kind)
                    ? UiText.choose("图片", "Image")
                    : UiText.choose("文件", "File"))
                : name.getAsString();
        text.append('[').append(label).append(']');
      }
    }
    if (assistant) {
      if (reasoning.length() == 0 && matchesAttempt) appendReasoning(reasoning, partialReasoning);
      if (reasoning.length() > 0) {
        if (turnReasoning.length() > 0) appendReasoning(turnReasoning, "\n\n");
        appendReasoning(turnReasoning, reasoning);
      }
    }
    if (!text.toString().isBlank()) {
      messages.add(
          assistant
              ? new Message(true, text.toString(), turnReasoning.toString(), -1)
              : new Message(false, text.toString()));
      messageSequences.add(event.has("seq") ? event.get("seq").getAsLong() : -1L);
      trimMessages();
      if (assistant) {
        if (running) lastReply = messages.size() - 1;
        turnReasoning.setLength(0);
      }
    } else if (assistant && !running && turnReasoning.length() > 0) {
      messages.add(new Message(true, "", turnReasoning.toString(), -1));
      messageSequences.add(event.has("seq") ? event.get("seq").getAsLong() : -1L);
      trimMessages();
      turnReasoning.setLength(0);
    }
    if (matchesAttempt) {
      partial.setLength(0);
      partialReasoning.setLength(0);
      thinking = false;
    }
  }

  private void trimMessages() {
    if (messages.size() > 150) {
      messages.remove(0);
      messageSequences.remove(0);
      if (lastReply >= 0) lastReply--;
    }
  }

  private static boolean isHumanMessage(JsonObject message) {
    // DSH also emits model context as user/message. Use provenance, never the text.
    return hasSource(message, "user");
  }

  private static boolean hasSource(JsonObject message, String expected) {
    JsonElement source = message.get("source");
    if (source == null || source.isJsonNull()) return true;
    if (!source.isJsonObject()) return false;
    JsonElement kind = source.getAsJsonObject().get("kind");
    return kind != null && kind.isJsonPrimitive() && expected.equals(kind.getAsString());
  }

  private void applyAssistantFrame(JsonObject frame) {
    long received = frame.get("revision").getAsLong();
    if (received != revision + 1) throw new IllegalArgumentException("DSH assistant stream gap");
    revision = received;
    if ("start".equals(frame.get("type").getAsString())) {
      startAttempt(frame);
      return;
    }
    if (attemptEnded
        || !frame.get("attemptId").getAsString().equals(attempt)
        || frame.get("index").getAsInt() != nextChunk) {
      throw new IllegalArgumentException("DSH assistant attempt mismatch");
    }
    // end 只结束生成状态；对应的 assistant/message 接管正文后再清空。
    // 新 attempt、快照和 turn/end 都有独立清理边界，不跨重试串用。
    if ("end".equals(frame.get("type").getAsString())) {
      attemptEnded = true;
      thinking = false;
    } else if ("chunk".equals(frame.get("type").getAsString())) {
      appendDelta(frame.getAsJsonObject("chunk"));
      nextChunk++;
    }
  }

  private void startAttempt(JsonObject frame) {
    clearAttempt();
    attempt = frame.get("attemptId").getAsString();
    attemptTurn = frame.get("turn").getAsLong();
    attemptStep = frame.get("step").getAsLong();
  }

  private void clearAttempt() {
    thinking = false;
    attempt = null;
    attemptEnded = false;
    nextChunk = 0;
    partial.setLength(0);
    partialReasoning.setLength(0);
  }

  private void appendDelta(JsonObject chunk) {
    String kind = chunk.get("type").getAsString();
    if ("reasoning-delta".equals(kind)) {
      thinking = true;
      JsonElement value = chunk.get("text");
      if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
        appendReasoning(partialReasoning, value.getAsString());
      }
    } else if ("text-delta".equals(kind)) {
      thinking = false;
      appendPartial(chunk.get("text").getAsString());
    }
  }

  private static void appendReasoning(StringBuilder target, CharSequence text) {
    int count = Math.min(text.length(), MAX_REASONING_CHARS - target.length());
    if (count > 0 && count < text.length() && Character.isHighSurrogate(text.charAt(count - 1)))
      count--;
    target.append(text, 0, count);
  }

  private void appendPartial(String text) {
    if (partial.length() + text.length() > 1024 * 1024) {
      throw new IllegalArgumentException("DSH live message is too large");
    }
    partial.append(text);
  }
}
