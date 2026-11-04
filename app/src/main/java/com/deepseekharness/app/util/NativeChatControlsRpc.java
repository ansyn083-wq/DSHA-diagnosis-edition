package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 当前会话的命令、目标、权限与消息反馈；只通过同代鉴权 RPC 传输。 */
public final class NativeChatControlsRpc {
  public static final String PERMISSION_COMMAND = "@deepseek-ai/dsh-permission-presets";
  private final NativeChatRpc rpc;
  private final String sessionId;

  public NativeChatControlsRpc(NativeChatRpc rpc, String sessionId) {
    this.rpc = Objects.requireNonNull(rpc);
    this.sessionId = nonBlank(sessionId);
  }

  public record Command(String sessionId, JsonObject record) {
    public Command {
      record = record.deepCopy();
    }

    @Override
    public JsonObject record() {
      return record.deepCopy();
    }

    public String name() {
      return text(record, "name");
    }

    public String description() {
      return text(record, "description");
    }

    public String definitionId() {
      return text(record, "definitionId");
    }

    public boolean hasInput() {
      return record.has("input");
    }

    public String hint() {
      return hasInput() ? text(record.getAsJsonObject("input"), "hint") : "";
    }
  }

  public record CommandResult(boolean success, String text) {}

  public record Goal(String sessionId, JsonObject record) {
    public Goal {
      record = record.deepCopy();
    }

    @Override
    public JsonObject record() {
      return record.deepCopy();
    }

    public String objective() {
      return text(record, "objective");
    }

    public String phase() {
      return text(record, "phase");
    }

    public String activation() {
      return text(record, "activation");
    }

    public long maxRounds() {
      return record.get("maxGoalRounds").getAsLong();
    }

    public long roundsStarted() {
      return record.get("roundsStarted").getAsLong();
    }

    public String blockedReason() {
      return record.has("blockedReason")
          ? text(record.getAsJsonObject("blockedReason"), "message")
          : "";
    }

    public JsonObject ref() {
      JsonObject ref = new JsonObject();
      ref.add("id", record.get("id"));
      ref.add("revision", record.get("revision"));
      return ref;
    }
  }

  public record Preset(String value, String name, String description) {}

  public record Permissions(
      String sessionId, String currentValue, List<Preset> options, Command command) {
    public Permissions {
      options = List.copyOf(options);
    }
  }

  public record Feedback(String sessionId, String messageId, JsonObject item) {
    public Feedback {
      item = item == null ? null : item.deepCopy();
    }

    @Override
    public JsonObject item() {
      return item == null ? null : item.deepCopy();
    }

    public boolean exists() {
      return item != null;
    }

    public String rating() {
      return exists() ? text(item, "rating") : "";
    }

    public String note() {
      return exists() ? text(item, "note") : "";
    }
  }

  public record FeedbackResult(boolean success, String code) {}

  /** 未确认的写请求禁止重发，必须先由用户刷新核对。关闭后旧回执永不复活。 */
  public static final class RequestGate {
    private boolean closed, busy, mutation, uncertain;
    private long generation;

    public synchronized long begin(boolean writing) {
      if (closed || busy || writing && uncertain) return 0;
      busy = true;
      mutation = writing;
      return ++generation;
    }

    public synchronized boolean current(long ticket) {
      return !closed && busy && ticket == generation;
    }

    public synchronized boolean finish(long ticket, boolean confirmed) {
      if (!current(ticket)) return false;
      busy = false;
      if (mutation && !confirmed) uncertain = true;
      else if (!mutation && confirmed) uncertain = false;
      return true;
    }

    public synchronized boolean uncertain() {
      return uncertain;
    }

    public synchronized void close() {
      closed = true;
      busy = false;
      generation++;
    }
  }

  public List<Command> commands() throws IOException {
    JsonElement response = rpc.wire("commands/list", agent());
    try {
      List<Command> values = new ArrayList<>();
      HashSet<String> names = new HashSet<>();
      for (JsonElement row : response.getAsJsonArray()) {
        JsonObject record = row.getAsJsonObject();
        String name = required(record, "name");
        if (!name.matches("[a-z0-9][a-z0-9_-]*") || !names.add(name))
          throw new IllegalArgumentException();
        required(record, "description");
        if (record.has("input")) required(record.getAsJsonObject("input"), "hint");
        values.add(new Command(sessionId, record));
      }
      return List.copyOf(values);
    } catch (RuntimeException invalid) {
      throw malformed("commands", invalid);
    }
  }

  public CommandResult execute(Command observed, String input) throws IOException {
    sameSession(observed.sessionId());
    nonNullInput(input);
    if (!observed.hasInput() && !input.isEmpty())
      throw new IllegalArgumentException("Command takes no input");
    if (!commands().contains(observed))
      throw new IOException("Command changed; refresh before running");
    return executeLine("/" + observed.name() + (input.isEmpty() ? "" : " " + input));
  }

  private CommandResult executeLine(String line) throws IOException {
    JsonObject args = agent();
    args.addProperty("line", line);
    args.add("submittedAttachments", new JsonArray());
    JsonElement response = rpc.wire("commands/execute", args);
    try {
      JsonObject result = response.getAsJsonObject();
      required(result, "commandId");
      JsonObject outcome = result.getAsJsonObject("result");
      String kind = required(outcome, "kind");
      if (!kind.equals("success") && !kind.equals("error")) throw new IllegalArgumentException();
      if (kind.equals("error")) required(outcome, "text");
      return new CommandResult(kind.equals("success"), text(outcome, "text"));
    } catch (RuntimeException invalid) {
      throw malformed("command result", invalid);
    }
  }

  public Goal goal() throws IOException {
    JsonElement response = rpc.wire("goals/get", agent());
    if (response == null || response.isJsonNull()) return null;
    return readGoal(response);
  }

  public void createGoal(String objective, long maxRounds) throws IOException {
    JsonObject args = agent();
    args.add("request", goalRequest(objective, maxRounds));
    JsonElement response = rpc.wire("goals/create", args);
    try {
      validateRef(response.getAsJsonObject().getAsJsonObject("ref"));
    } catch (RuntimeException invalid) {
      throw malformed("created goal", invalid);
    }
  }

  public void editGoal(Goal observed, String objective, long maxRounds) throws IOException {
    JsonObject args = goalTarget(observed);
    args.add("request", goalRequest(objective, maxRounds));
    Goal result = readGoal(rpc.wire("goals/edit", args));
    verifyNextRef(observed, result.ref());
  }

  public void changeGoal(Goal observed, String operation) throws IOException {
    if (!List.of("pause", "resume", "complete", "clear").contains(operation)) {
      throw new IllegalArgumentException("Unknown goal operation");
    }
    JsonElement response = rpc.wire("goals/" + operation, goalTarget(observed));
    if (!operation.equals("clear")) {
      verifyNextRef(observed, readGoal(response).ref());
      return;
    }
    try {
      validateRef(response.getAsJsonObject());
      verifyNextRef(observed, response.getAsJsonObject());
    } catch (RuntimeException invalid) {
      throw malformed("cleared goal", invalid);
    }
  }

  private static void verifyNextRef(Goal observed, JsonObject result) throws IOException {
    JsonObject ref = observed.ref();
    if (!required(ref, "id").equals(required(result, "id"))
        || result.get("revision").getAsLong() != ref.get("revision").getAsLong() + 1) {
      throw new IOException("Goal acknowledgement does not match the observed revision");
    }
  }

  private JsonObject goalTarget(Goal observed) {
    sameSession(observed.sessionId());
    JsonObject args = agent();
    JsonObject ref = observed.ref();
    validateRef(ref);
    args.add("ref", ref);
    return args;
  }

  private static JsonObject goalRequest(String objective, long maxRounds) {
    nonBlank(objective);
    if (maxRounds < 1 || maxRounds > 9007199254740991L)
      throw new IllegalArgumentException("Invalid round budget");
    JsonObject request = new JsonObject();
    request.addProperty("objective", objective.strip());
    request.addProperty("maxGoalRounds", maxRounds);
    return request;
  }

  private Goal readGoal(JsonElement response) throws IOException {
    try {
      JsonObject value = response.getAsJsonObject();
      validateRef(value);
      required(value, "objective");
      String phase = required(value, "phase");
      if (!List.of("active", "paused", "blocked", "complete").contains(phase))
        throw new IllegalArgumentException();
      if (!List.of("armed", "disarmed").contains(required(value, "activation")))
        throw new IllegalArgumentException();
      integer(value, "maxGoalRounds", 1);
      integer(value, "roundsStarted", 0);
      integer(value, "createdAt", 0);
      integer(value, "updatedAt", 0);
      if (phase.equals("blocked")) {
        JsonObject reason = value.getAsJsonObject("blockedReason");
        required(reason, "code");
        required(reason, "message");
      }
      return new Goal(sessionId, value);
    } catch (RuntimeException invalid) {
      throw malformed("goal", invalid);
    }
  }

  public Permissions permissions() throws IOException {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", sessionId);
    JsonElement projection = rpc.call("session/projections", request);
    try {
      JsonObject values = projection.getAsJsonObject().getAsJsonObject("values");
      if (!values.has("permissions")) return new Permissions(sessionId, "", List.of(), null);
      String selected = required(values.getAsJsonObject("permissions"), "currentValue");
      JsonObject catalog =
          rpc.wire("permissionPresets/catalog", new JsonObject()).getAsJsonObject();
      List<Preset> options = new ArrayList<>();
      HashSet<String> names = new HashSet<>();
      for (JsonElement row : catalog.getAsJsonArray("options")) {
        JsonObject option = row.getAsJsonObject();
        String value = required(option, "value");
        if (!names.add(value) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
          throw new IllegalArgumentException();
        options.add(new Preset(value, required(option, "name"), text(option, "description")));
      }
      Command command =
          commands().stream()
              .filter(c -> PERMISSION_COMMAND.equals(c.definitionId()))
              .findFirst()
              .orElse(null);
      return new Permissions(sessionId, selected, options, command);
    } catch (RuntimeException invalid) {
      throw malformed("permissions", invalid);
    }
  }

  /** 官方权限设置只提供 /permission 命令，不伪造不存在的 setter 或修改全局默认值。 */
  public CommandResult selectPermission(Permissions observed, Preset choice) throws IOException {
    sameSession(observed.sessionId());
    if (observed.command() == null
        || !observed.options().contains(choice)
        || choice.description().isBlank()) {
      throw new IllegalArgumentException("A described, selectable permission preset is required");
    }
    Permissions latest = permissions();
    if (!latest.equals(observed))
      throw new IOException("Permissions changed; review the refreshed options");
    return executeLine("/" + observed.command().name() + " " + choice.value());
  }

  public Feedback feedback(String messageId) throws IOException {
    nonBlank(messageId);
    JsonObject request = feedbackTarget(messageId);
    request.remove("messageId");
    JsonObject response = feedbackValue(rpc.call("messageFeedback/list", request));
    try {
      JsonObject found = null;
      HashSet<String> ids = new HashSet<>();
      for (JsonElement row : response.getAsJsonArray("items")) {
        JsonObject value = row.getAsJsonObject();
        validateFeedback(value);
        String id = required(value, "messageId");
        if (!ids.add(id)) throw new IllegalArgumentException();
        if (id.equals(messageId)) found = value;
      }
      return new Feedback(sessionId, messageId, found);
    } catch (RuntimeException invalid) {
      throw malformed("feedback", invalid);
    }
  }

  public FeedbackResult putFeedback(Feedback observed, String rating, String note)
      throws IOException {
    sameSession(observed.sessionId());
    if (!List.of("positive", "negative").contains(rating))
      throw new IllegalArgumentException("Invalid rating");
    JsonObject request = feedbackTarget(observed.messageId());
    request.addProperty("rating", rating);
    if (note != null && !note.isBlank()) request.addProperty("note", note);
    JsonObject item = observed.item();
    if (item != null) {
      validateFeedback(item);
      if (item.has("category")) request.add("category", item.get("category"));
      request.add("ifVersion", item.get("version"));
    } else request.add("ifVersion", JsonNull.INSTANCE);
    return feedbackResult(rpc.call("messageFeedback/put", request), false, observed.messageId());
  }

  public FeedbackResult deleteFeedback(Feedback observed) throws IOException {
    sameSession(observed.sessionId());
    if (!observed.exists()) throw new IllegalArgumentException("Feedback is already absent");
    JsonObject item = observed.item();
    validateFeedback(item);
    JsonObject request = feedbackTarget(observed.messageId());
    request.add("ifVersion", item.get("version"));
    return feedbackResult(rpc.call("messageFeedback/delete", request), true, observed.messageId());
  }

  private static FeedbackResult feedbackResult(
      JsonElement response, boolean deletion, String messageId) throws IOException {
    try {
      JsonObject result = response.getAsJsonObject();
      if (!booleanValue(result, "ok"))
        return new FeedbackResult(false, required(result.getAsJsonObject("error"), "code"));
      JsonObject value = result.getAsJsonObject("value");
      if (deletion) {
        if (!booleanValue(value, "absent")) throw new IllegalArgumentException();
      } else {
        validateFeedback(value);
        if (!messageId.equals(required(value, "messageId"))) throw new IllegalArgumentException();
      }
      return new FeedbackResult(true, "");
    } catch (RuntimeException invalid) {
      throw malformed("feedback result", invalid);
    }
  }

  private static JsonObject feedbackValue(JsonElement response) throws IOException {
    try {
      JsonObject result = response.getAsJsonObject();
      if (!booleanValue(result, "ok"))
        throw new IOException(
            "Feedback rejected: " + required(result.getAsJsonObject("error"), "code"));
      return result.getAsJsonObject("value");
    } catch (RuntimeException invalid) {
      throw malformed("feedback list", invalid);
    }
  }

  private JsonObject feedbackTarget(String messageId) {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", sessionId);
    request.addProperty("messageId", nonBlank(messageId));
    return request;
  }

  private static void validateFeedback(JsonObject value) {
    required(value, "messageId");
    required(value, "version");
    if (!List.of("positive", "negative").contains(required(value, "rating")))
      throw new IllegalArgumentException();
    integer(value, "createdAt", 0);
    integer(value, "updatedAt", 0);
    if (value.has("note")) required(value, "note");
    if (value.has("category")) required(value, "category");
  }

  private JsonObject agent() {
    JsonObject args = new JsonObject();
    args.addProperty("agentId", sessionId);
    return args;
  }

  private void sameSession(String owner) {
    if (!sessionId.equals(owner))
      throw new IllegalArgumentException("Snapshot belongs to another session");
  }

  private static void validateRef(JsonObject value) {
    required(value, "id");
    integer(value, "revision", 1);
  }

  private static void integer(JsonObject value, String key, long minimum) {
    JsonElement number = value.get(key);
    if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    java.math.BigDecimal decimal = number.getAsBigDecimal();
    long exact = decimal.longValueExact();
    if (exact < minimum || exact > 9007199254740991L) throw new IllegalArgumentException();
  }

  private static boolean booleanValue(JsonObject value, String key) {
    JsonElement entry = value.get(key);
    if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException();
    return entry.getAsBoolean();
  }

  private static String text(JsonObject value, String key) {
    JsonElement entry = value.get(key);
    if (entry == null || entry.isJsonNull()) return "";
    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException();
    return entry.getAsString();
  }

  private static String required(JsonObject value, String key) {
    return nonBlank(text(value, key));
  }

  private static String nonBlank(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Required text is missing");
    return value;
  }

  private static void nonNullInput(String input) {
    if (input == null || input.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid command input");
  }

  private static IOException malformed(String what, RuntimeException error) {
    return new IOException("Invalid DSH " + what, error);
  }
}
