package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Retained task management on the authenticated gateway; creation remains an agent tool. */
public final class NativeChatScheduleRpc {
  private final NativeChatRpc rpc;

  public NativeChatScheduleRpc(NativeChatRpc rpc) {
    this.rpc = Objects.requireNonNull(rpc);
  }

  /** The observed stored record is private so an edit cannot silently change its CAS baseline. */
  public record Task(String sessionId, String status, JsonObject record) {
    public Task {
      record = record.deepCopy();
    }

    @Override
    public JsonObject record() {
      return record.deepCopy();
    }

    public String id() {
      return text(record, "id");
    }

    public String title() {
      return text(record, "title");
    }

    public String prompt() {
      return text(record, "prompt");
    }

    public String kind() {
      return text(record, "kind");
    }

    public String scheduledAt() {
      return text(record, "scheduledAt");
    }

    public boolean active() {
      return "active".equals(status);
    }
  }

  /** A no-op with a returned record is successful, unlike a stale-record rejection. */
  public record UpdateResult(boolean success, boolean changed, String code) {}

  /** One in-flight operation per dialog, permanently invalidated when its owner is dismissed. */
  public static final class RequestGate {
    private boolean closed;
    private boolean busy;
    private long generation;

    public synchronized long begin() {
      if (closed || busy) return 0;
      busy = true;
      return ++generation;
    }

    public synchronized boolean current(long ticket) {
      return !closed && busy && ticket == generation;
    }

    public synchronized boolean finish(long ticket) {
      if (!current(ticket)) return false;
      busy = false;
      return true;
    }

    public synchronized void close() {
      closed = true;
      busy = false;
      generation++;
    }
  }

  public List<Task> catalog() throws IOException {
    JsonElement response = rpc.wire("schedule/catalog", new JsonObject());
    try {
      List<Task> tasks = new ArrayList<>();
      for (JsonElement row : response.getAsJsonArray()) {
        JsonObject value = row.getAsJsonObject().deepCopy();
        String session = required(value, "sessionId");
        String status = required(value, "status");
        if (!"active".equals(status) && !"inactive".equals(status)) {
          throw new IllegalArgumentException("Unknown schedule status");
        }
        value.remove("sessionId");
        value.remove("status");
        value.remove("lastDelivery");
        validateRecord(value);
        tasks.add(new Task(session, status, value));
      }
      return List.copyOf(tasks);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH scheduled-task catalog", invalid);
    }
  }

  public UpdateResult update(Task task, String title, String prompt, JsonObject change)
      throws IOException {
    if (!task.active()) throw new IllegalArgumentException("An ended task cannot be edited");
    String name = title.strip();
    String instruction = prompt.strip();
    if (name.isEmpty() || name.length() > 120 || instruction.isEmpty()) {
      throw new IllegalArgumentException("A task name and instruction are required");
    }
    JsonObject request = target(task);
    request.add("expected", task.record());
    request.addProperty("title", name);
    request.addProperty("prompt", instruction);
    if (change != null) request.add("change", change.deepCopy());
    JsonElement response = rpc.call("schedule/update", request);
    try {
      JsonObject value = response.getAsJsonObject();
      if (value.has("id") && !task.id().equals(required(value, "id"))) {
        throw new IllegalArgumentException("Wrong schedule identity");
      }
      String code = text(value, "code");
      if (!code.isEmpty()) return new UpdateResult(false, false, code);
      if (!task.id().equals(required(value, "id")))
        throw new IllegalArgumentException("Missing id");
      JsonObject record = value.getAsJsonObject("record");
      validateRecord(record);
      if (!task.id().equals(required(record, "id")))
        throw new IllegalArgumentException("Wrong record");
      return new UpdateResult(true, bool(value, "updated"), "");
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH scheduled-task update result", invalid);
    }
  }

  /** The backend does not support delete CAS. The caller must confirm this exact task first. */
  public boolean delete(Task task) throws IOException {
    JsonElement response = rpc.call("schedule/delete", target(task));
    try {
      JsonObject value = response.getAsJsonObject();
      if (!task.id().equals(required(value, "id")))
        throw new IllegalArgumentException("Wrong task");
      boolean deleted = bool(value, "deleted");
      if (!deleted && !"schedule_not_found".equals(text(value, "code"))) {
        throw new IllegalArgumentException("Unknown delete outcome");
      }
      return deleted;
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH scheduled-task deletion result", invalid);
    }
  }

  private static JsonObject target(Task task) {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", task.sessionId());
    request.addProperty("id", task.id());
    return request;
  }

  /** Builds only supported selectors. The host remains authoritative for future dates and cron. */
  public static JsonObject timing(String kind, String value, String zone, String weekdays) {
    String input = value.strip();
    JsonObject change = new JsonObject();
    change.addProperty("kind", kind);
    try {
      switch (kind) {
        case "at" -> {
          if (!input.matches(
              "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})")) {
            throw new IllegalArgumentException("Use an ISO date and time with an explicit offset");
          }
          OffsetDateTime.parse(input);
          change.addProperty("at", input);
        }
        case "every" -> {
          long seconds = Long.parseLong(input);
          if (seconds < 60 || seconds > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Interval must be at least 60 seconds");
          }
          change.addProperty("every_seconds", seconds);
        }
        case "daily", "weekly", "cron" -> {
          String timeZone = zone.strip();
          if (!("UTC".equals(timeZone) || timeZone.contains("/"))) {
            throw new IllegalArgumentException("Use an explicit IANA time zone");
          }
          ZoneId.of(timeZone);
          JsonObject selector = new JsonObject();
          if ("cron".equals(kind)) {
            if (input.split("\\s+").length != 5) {
              throw new IllegalArgumentException("Use a five-field cron expression");
            }
            selector.addProperty("expression", input);
          } else {
            String clock = input.matches("\\d{2}:\\d{2}") ? input + ":00" : input;
            if (!clock.matches("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?")) {
              throw new IllegalArgumentException("Use HH:mm or HH:mm:ss");
            }
            LocalTime.parse(clock);
            selector.addProperty("time", clock);
          }
          selector.addProperty("time_zone", timeZone);
          if ("weekly".equals(kind)) {
            TreeSet<Integer> days = new TreeSet<>();
            for (String day : weekdays.split(",", -1)) {
              int number = Integer.parseInt(day.strip());
              if (number < 1 || number > 7 || !days.add(number)) {
                throw new IllegalArgumentException("Use distinct weekdays from 1 through 7");
              }
            }
            JsonArray values = new JsonArray();
            for (int day : days) values.add(day);
            selector.add("weekdays", values);
          }
          change.add(kind, selector);
        }
        default -> throw new IllegalArgumentException("Unsupported timing selector");
      }
      return change;
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid date, time, or time zone", invalid);
    }
  }

  private static void validateRecord(JsonObject value) {
    required(value, "id");
    String title = required(value, "title");
    if (title.length() > 120 || !title.equals(title.strip()))
      throw new IllegalArgumentException("Title");
    required(value, "prompt");
    OffsetDateTime.parse(required(value, "scheduledAt"));
    switch (required(value, "kind")) {
      case "after" -> positiveInteger(value, "afterSeconds", 1);
      case "every" -> positiveInteger(value, "everySeconds", 60);
      case "daily", "weekly" -> {
        LocalTime.parse(required(value, "time"));
        required(value, "timeZone");
        if ("weekly".equals(text(value, "kind"))) {
          JsonArray days = value.getAsJsonArray("weekdays");
          if (days.isEmpty()) throw new IllegalArgumentException("Missing weekdays");
          int previous = 0;
          for (JsonElement day : days) {
            int number = day.getAsBigDecimal().intValueExact();
            if (number <= previous || number > 7) throw new IllegalArgumentException("Weekdays");
            previous = number;
          }
        }
      }
      case "cron" -> {
        required(value, "expression");
        required(value, "timeZone");
      }
      case "at" -> {}
      default -> throw new IllegalArgumentException("Unknown schedule kind");
    }
  }

  private static void positiveInteger(JsonObject object, String key, long minimum) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("Missing schedule interval");
    }
    long number = value.getAsBigDecimal().longValueExact();
    if (number < minimum || number > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("Invalid schedule interval");
    }
  }

  private static String required(JsonObject object, String key) {
    String value = text(object, key);
    if (value.isBlank()) throw new IllegalArgumentException("Missing schedule " + key);
    return value;
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object == null ? null : object.get(key);
    if (value == null || value.isJsonNull()) return "";
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Invalid schedule " + key);
    }
    return value.getAsString();
  }

  private static boolean bool(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException("Missing boolean " + key);
    }
    return value.getAsBoolean();
  }
}
