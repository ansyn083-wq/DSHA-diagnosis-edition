package com.deepseekharness.app.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 会话管理复用当前服务身份的 RPC，不猜测归档状态，不重放写请求。 */
public final class NativeChatSessionsRpc {
  private final NativeChatRpc rpc;

  public NativeChatSessionsRpc(NativeChatRpc rpc) {
    this.rpc = Objects.requireNonNull(rpc);
  }

  public record SearchItem(String sessionId, String snippet) {}

  public record SearchResult(List<SearchItem> items, boolean hasMore) {
    public SearchResult {
      items = List.copyOf(items);
    }
  }

  /** 新搜索取代旧读取；写操作期间拒绝重复提交；关闭后永远不接受旧回调。 */
  public static final class RequestGate {
    private boolean closed;
    private boolean mutation;
    private boolean pending;
    private long generation;

    public synchronized long beginRead() {
      if (closed || mutation) return 0;
      pending = true;
      return ++generation;
    }

    public synchronized long beginMutation() {
      if (closed || mutation) return 0;
      mutation = true;
      pending = true;
      return ++generation;
    }

    public synchronized boolean current(long ticket) {
      return !closed && pending && ticket != 0 && ticket == generation;
    }

    public synchronized boolean finish(long ticket) {
      if (!current(ticket)) return false;
      mutation = false;
      pending = false;
      return true;
    }

    public synchronized void close() {
      closed = true;
      mutation = false;
      pending = false;
      generation++;
    }
  }

  public SearchResult search(String query) throws IOException {
    String normalized = nonempty(query);
    if (normalized.length() > 500 || normalized.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Search requires at most 500 UTF-16 units without NUL");
    }
    JsonObject request = new JsonObject();
    request.addProperty("query", normalized);
    return searchResult(rpc.call("session/search", request));
  }

  public String rename(String sessionId, String title) throws IOException {
    JsonObject request = session(sessionId);
    request.addProperty("title", nonempty(title));
    return renamedTitle(rpc.call("session/rename", request));
  }

  /** 不传 stopActivity：运行中的任务必须由服务端拒绝，不隐式取消工作。 */
  public Set<String> archive(String sessionId) throws IOException {
    Set<String> result = archivedIds(rpc.call("workspace/archiveSession", session(sessionId)));
    if (!result.contains(sessionId)) throw new IOException("DSH archive was not confirmed");
    return result;
  }

  public Set<String> restore(String sessionId) throws IOException {
    Set<String> result = archivedIds(rpc.call("workspace/unarchiveSession", session(sessionId)));
    if (result.contains(sessionId)) throw new IOException("DSH restore was not confirmed");
    return result;
  }

  /** atSeq 是用户选中消息对应的真实事件序号，不能使用列表位置代替。 */
  public String fork(String sessionId, long atSeq) throws IOException {
    if (atSeq < 0 || atSeq > 9007199254740991L) {
      throw new IllegalArgumentException("A non-negative safe event sequence is required");
    }
    JsonObject request = session(sessionId);
    request.addProperty("atSeq", atSeq);
    return fork(request, sessionId);
  }

  public String forkLatestCompleted(String sessionId) throws IOException {
    return fork(session(sessionId), sessionId);
  }

  private String fork(JsonObject request, String source) throws IOException {
    // 需要迁移的旧会话应先明确打开，管理列表不替用户启动迁移。
    request.addProperty("allowMigration", false);
    return forkedSession(rpc.call("session/fork", request), source);
  }

  static SearchResult searchResult(JsonElement value) throws IOException {
    try {
      JsonObject object = value.getAsJsonObject();
      JsonElement more = object.get("hasMore");
      if (more == null || !more.isJsonPrimitive() || !more.getAsJsonPrimitive().isBoolean()) {
        throw new IllegalArgumentException("Missing search boundary");
      }
      List<SearchItem> result = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      for (JsonElement item : object.getAsJsonArray("items")) {
        JsonObject row = item.getAsJsonObject();
        String id = requiredString(row, "sessionId", false);
        String snippet = requiredString(row, "snippet", true);
        if (seen.add(id)) result.add(new SearchItem(id, snippet));
      }
      return new SearchResult(result, more.getAsBoolean());
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH conversation search", invalid);
    }
  }

  static String renamedTitle(JsonElement value) throws IOException {
    try {
      JsonObject object = value.getAsJsonObject();
      String title = requiredString(object, "title", false);
      JsonElement seq = object.get("seq");
      if (seq == null || !seq.isJsonPrimitive() || !seq.getAsJsonPrimitive().isNumber()) {
        throw new IllegalArgumentException("Missing rename sequence");
      }
      long exact = seq.getAsBigDecimal().longValueExact();
      if (exact < 0 || exact > 9007199254740991L)
        throw new IllegalArgumentException("Invalid sequence");
      return title;
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH conversation rename", invalid);
    }
  }

  static String forkedSession(JsonElement value, String source) throws IOException {
    try {
      String id = requiredString(value.getAsJsonObject(), "sessionId", false);
      if (id.equals(source)) throw new IllegalArgumentException("Fork reused source identity");
      return id;
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH conversation fork", invalid);
    }
  }

  public static Set<String> archivedIds(JsonElement value) throws IOException {
    try {
      Set<String> result = new LinkedHashSet<>();
      for (JsonElement id : value.getAsJsonObject().getAsJsonArray("archivedSessionIds")) {
        if (!id.isJsonPrimitive()
            || !id.getAsJsonPrimitive().isString()
            || id.getAsString().isBlank()) {
          throw new IllegalArgumentException("Invalid archived identity");
        }
        result.add(id.getAsString());
      }
      return Set.copyOf(result);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH archive state", invalid);
    }
  }

  private static JsonObject session(String id) {
    nonempty(id);
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", id);
    return request;
  }

  private static String nonempty(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("A value is required");
    return value.strip();
  }

  private static String requiredString(JsonObject value, String key, boolean allowEmpty) {
    JsonElement field = value.get(key);
    if (field == null
        || !field.isJsonPrimitive()
        || !field.getAsJsonPrimitive().isString()
        || !allowEmpty && field.getAsString().isBlank()) {
      throw new IllegalArgumentException("Invalid " + key);
    }
    return field.getAsString();
  }
}
