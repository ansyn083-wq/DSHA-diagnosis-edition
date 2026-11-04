package com.deepseekharness.app.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One live owner-event approval. History is never executable approval state. */
public final class NativeChatApproval {
  private final String clientId, eventId, toolName, callId, reason;
  private boolean claimed;

  private NativeChatApproval(
      String clientId, String eventId, String toolName, String callId, String reason) {
    this.clientId = clientId;
    this.eventId = eventId;
    this.toolName = toolName;
    this.callId = callId;
    this.reason = reason;
  }

  public static NativeChatApproval from(String clientId, String sessionId, JsonObject frame) {
    if (clientId == null
        || clientId.isBlank()
        || sessionId == null
        || sessionId.isBlank()
        || frame == null
        || !"waterfall".equals(text(frame, "type"))
        || !"approval/request".equals(text(frame, "event"))
        || !sessionId.equals(text(frame, "agentId"))) return null;
    JsonElement value = frame.get("request");
    if (value == null || !value.isJsonObject()) return null;
    JsonObject request = value.getAsJsonObject();
    String id = text(frame, "eventId"), tool = text(request, "toolName");
    if (id.isBlank() || tool.isBlank()) return null;
    return new NativeChatApproval(
        clientId, id, tool, text(request, "callId"), text(request, "reason"));
  }

  public String eventId() {
    return eventId;
  }

  public String toolName() {
    return toolName;
  }

  public String callId() {
    return callId;
  }

  public String reason() {
    return reason;
  }

  public boolean claim() {
    if (claimed) return false;
    claimed = true;
    return true;
  }

  public JsonObject result(boolean allow) {
    JsonObject outcome = new JsonObject();
    outcome.addProperty("kind", "result");
    outcome.addProperty("value", allow ? "allowed-once" : "rejected");
    JsonObject args = new JsonObject();
    args.addProperty("clientId", clientId);
    args.addProperty("eventId", eventId);
    args.add("outcome", outcome);
    return args;
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }
}
