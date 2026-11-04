package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 同一事件连接的有界交互队列；移除请求后仍保留身份，避免迟到事件重复作答。 */
public final class NativeChatEventQueue<T> {
  public enum Admission {
    QUEUED,
    DUPLICATE,
    DELEGATE,
    INVALID,
    SCOPE_MISMATCH,
    RESET_REQUIRED
  }

  public record Entry<T>(String eventId, T value) {}

  private final int capacity, historyCapacity;
  private final Map<String, Entry<T>> pending = new LinkedHashMap<>();
  private final Set<String> seen = new LinkedHashSet<>();
  private String clientId = "", sessionId = "";

  public NativeChatEventQueue() {
    this(32, 256);
  }

  NativeChatEventQueue(int capacity, int historyCapacity) {
    if (capacity < 1 || historyCapacity < capacity)
      throw new IllegalArgumentException("Invalid queue limits");
    this.capacity = capacity;
    this.historyCapacity = historyCapacity;
  }

  /** 只能由新连接的 ready 身份调用；不把旧连接的请求或决定转移到新连接。 */
  public synchronized void reset(String clientId, String sessionId) {
    clear();
    if (!valid(clientId) || !valid(sessionId))
      throw new IllegalArgumentException("Missing event scope");
    this.clientId = clientId;
    this.sessionId = sessionId;
  }

  public synchronized void clear() {
    pending.clear();
    seen.clear();
    clientId = sessionId = "";
  }

  /** 调用方先校验事件类型及问题/审批内容；队列只管理身份、先后顺序和容量。 */
  public synchronized Admission offer(String clientId, String agentId, String eventId, T value) {
    if (!valid(agentId) || value == null) return Admission.INVALID;
    if (!sessionId.equals(agentId)) return Admission.SCOPE_MISMATCH;
    Admission registered = register(clientId, eventId);
    if (registered != Admission.DELEGATE) return registered;
    if (pending.size() >= capacity) return Admission.DELEGATE;
    pending.put(eventId, new Entry<>(eventId, value));
    return Admission.QUEUED;
  }

  /** 不支持/其他会话事件也登记一次 next；不保存其正文，不替旧连接发送结果。 */
  public synchronized Admission delegate(String clientId, String eventId) {
    return register(clientId, eventId);
  }

  private Admission register(String clientId, String eventId) {
    if (!valid(clientId) || !valid(eventId)) return Admission.INVALID;
    if (!this.clientId.equals(clientId)) return Admission.SCOPE_MISMATCH;
    if (seen.contains(eventId)) return Admission.DUPLICATE;
    // 不淘汰旧身份后继续发送决定；由调用方断开并重新连接，服务端保留未决事件。
    if (seen.size() >= historyCapacity) return Admission.RESET_REQUIRED;
    seen.add(eventId);
    return Admission.DELEGATE;
  }

  public synchronized Entry<T> peek() {
    return pending.isEmpty() ? null : pending.values().iterator().next();
  }

  /** 服务端 cancel 可移除队首或任意等待项，不影响其他请求。 */
  public synchronized Entry<T> remove(String eventId) {
    return pending.remove(eventId);
  }

  public synchronized int size() {
    return pending.size();
  }

  private static boolean valid(String id) {
    return id != null && !id.isBlank() && id.length() <= 4096;
  }
}
