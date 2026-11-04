package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

/** 复用同代鉴权传输的账号退出与原生自定义 API 管理，不读取密钥正文。 */
public final class NativeChatAccountRpc {
  private static final String NAMESPACE = "llm-pi-ai";
  private final NativeChatRpc rpc;

  public NativeChatAccountRpc(NativeChatRpc rpc) {
    this.rpc = Objects.requireNonNull(rpc);
  }

  public record CustomApi(
      String provider,
      String name,
      String address,
      String model,
      boolean keyConfigured,
      boolean editable,
      boolean removable,
      long revision) {}

  public record Catalog(boolean writable, long revision, List<CustomApi> providers) {}

  /** 窗口关闭后不可重启；工作线程与主线程共用同一准入状态。 */
  public static final class RequestGate {
    private boolean open = true;
    private boolean busy;
    private boolean uncertain;

    public synchronized boolean begin() {
      if (!open || busy) return false;
      busy = true;
      return true;
    }

    public synchronized void finish() {
      busy = false;
    }

    public synchronized void uncertainWrite() {
      uncertain = true;
    }

    public synchronized void reconciled() {
      uncertain = false;
    }

    public synchronized boolean canWrite() {
      return open && !uncertain;
    }

    public synchronized void close() {
      open = false;
    }

    public synchronized boolean isOpen() {
      return open;
    }

    public synchronized boolean isBusy() {
      return busy;
    }
  }

  private record Snapshot(
      boolean writable, long revision, JsonObject value, JsonObject user, JsonObject base) {}

  public boolean hasRunningAccountTasks() throws IOException {
    JsonElement result = rpc.wire("account/hasRunningAccountTasks", new JsonObject());
    if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isBoolean()) {
      throw new IOException("Invalid DSH running account task status");
    }
    return result.getAsBoolean();
  }

  public void signOut(String version, String locale) throws IOException {
    JsonObject client = new JsonObject();
    client.addProperty("version", version);
    client.addProperty("locale", locale);
    client.addProperty(
        "timezoneOffsetSeconds",
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000);
    JsonObject args = new JsonObject();
    args.add("client", client);
    signedOut(rpc.wire("account/signOut", args));
  }

  public void cancelSignIn(String attemptId) throws IOException {
    if (attemptId == null || attemptId.isBlank()) {
      throw new IllegalArgumentException("A sign-in attempt is required");
    }
    JsonObject args = new JsonObject();
    args.addProperty("attemptId", attemptId);
    // 取消只绑定本窗口发起的 attempt；服务器可能已经完成该次授权。
    JsonElement result = rpc.wire("account/cancelSignIn", args);
    if (result == null || !result.isJsonObject())
      throw new IOException("Invalid DSH account status");
  }

  private static void signedOut(JsonElement result) throws IOException {
    if (result == null
        || !result.isJsonObject()
        || !"signed-out".equals(string(result.getAsJsonObject(), "status"))) {
      throw new IOException("DSH did not confirm account sign-out");
    }
  }

  /** 首次进入只核对已配置状态；覆盖第三方 API 引用，不读取任何密钥正文。 */
  public boolean hasConfiguredApi() throws IOException {
    try {
      JsonObject description = rpc.wire("settings/describe", new JsonObject()).getAsJsonObject();
      JsonArray namespaces = description.getAsJsonArray("namespaces");
      if (namespaces == null) throw new IOException("Missing DSH settings description");
      java.util.Set<String> references = new java.util.LinkedHashSet<>();
      for (JsonElement item : namespaces) {
        JsonObject namespace = item.getAsJsonObject();
        if (!string(namespace, "ns").startsWith("llm-")) continue;
        JsonObject value = object(namespace, "value");
        String direct = string(value, "apiKeyEnv");
        if (!direct.isBlank()) references.add(direct);
        JsonObject providers = object(value, "providers");
        if (providers == null) continue;
        for (var provider : providers.entrySet()) {
          if (!provider.getValue().isJsonObject()) continue;
          String ref = string(provider.getValue().getAsJsonObject(), "apiKeyEnv");
          if (!ref.isBlank()) references.add(ref);
        }
      }
      if (references.isEmpty()) return false;
      JsonArray refs = new JsonArray();
      references.forEach(refs::add);
      JsonObject args = new JsonObject();
      args.add("refs", refs);
      JsonObject described = rpc.wire("credentials/describe", args).getAsJsonObject();
      boolean configured = false;
      for (String ref : references) {
        JsonObject info = object(described, ref);
        JsonElement state = info == null ? null : info.get("configured");
        if (state == null || !state.isJsonPrimitive() || !state.getAsJsonPrimitive().isBoolean())
          throw new IOException("Missing DSH credential status");
        configured |= state.getAsBoolean();
      }
      return configured;
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid DSH API configuration status", malformed);
    }
  }

  public Catalog customApis() throws IOException {
    Snapshot snapshot = snapshot();
    JsonObject providers = object(snapshot.value(), "providers");
    JsonArray refs = new JsonArray();
    List<String> ids = new ArrayList<>();
    if (providers != null) {
      for (var entry : providers.entrySet()) {
        if (!nativeProvider(entry.getKey()) || !entry.getValue().isJsonObject()) continue;
        ids.add(entry.getKey());
        String ref = string(entry.getValue().getAsJsonObject(), "apiKeyEnv");
        if (!ref.isBlank() && !refs.contains(new com.google.gson.JsonPrimitive(ref))) refs.add(ref);
      }
    }
    JsonObject credentials = new JsonObject();
    if (!refs.isEmpty()) {
      JsonObject args = new JsonObject();
      args.add("refs", refs);
      JsonElement result = rpc.wire("credentials/describe", args);
      if (result == null || !result.isJsonObject())
        throw new IOException("Invalid DSH credential status");
      credentials = result.getAsJsonObject();
    }
    List<CustomApi> entries = new ArrayList<>();
    for (String id : ids) {
      JsonObject route = providers.getAsJsonObject(id);
      JsonObject credential = object(credentials, string(route, "apiKeyEnv"));
      String name = string(route, "displayName");
      entries.add(
          new CustomApi(
              id,
              name.isBlank() ? id : name,
              string(route, "baseURL"),
              firstModel(route),
              bool(credential, "configured"),
              editable(snapshot, id, route),
              removable(snapshot, id),
              snapshot.revision()));
    }
    return new Catalog(snapshot.writable(), snapshot.revision(), List.copyOf(entries));
  }

  public String addCustomApi(String address, String model, String key) throws IOException {
    validate(address, model, key, true);
    Snapshot snapshot = snapshot();
    requireWritable(snapshot);
    String id = UUID.randomUUID().toString().replace("-", "");
    String provider = NativeChatRpc.CUSTOM_PROVIDER_PREFIX + id;
    String ref = storeNewCredential(key);
    JsonObject route = new JsonObject();
    route.addProperty("displayName", model);
    route.addProperty("api", "openai-completions");
    route.addProperty("baseURL", address);
    route.addProperty("apiKeyEnv", ref);
    JsonObject modelEntry = new JsonObject();
    modelEntry.addProperty("id", model);
    JsonArray models = new JsonArray();
    models.add(modelEntry);
    route.add("models", models);
    JsonArray ops = new JsonArray();
    ops.add(set(provider, null, route));
    mutate(snapshot.revision(), ops);
    return provider;
  }

  public void updateCustomApi(CustomApi expected, String address, String model, String key)
      throws IOException {
    validate(address, model, key, false);
    Snapshot snapshot = currentSnapshot(expected);
    JsonObject route = object(object(snapshot.value(), "providers"), expected.provider());
    if (!editable(snapshot, expected.provider(), route)) {
      throw new IOException("This provider needs the advanced settings editor");
    }
    JsonArray ops = new JsonArray();
    if (!address.equals(string(route, "baseURL"))) {
      ops.add(set(expected.provider(), "baseURL", new com.google.gson.JsonPrimitive(address)));
    }
    if (!model.equals(firstModel(route))) {
      JsonObject op = set(expected.provider(), "models", new com.google.gson.JsonPrimitive(model));
      op.getAsJsonArray("path").add("0");
      op.getAsJsonArray("path").add("id");
      ops.add(op);
    }
    if (!key.isBlank()) {
      // 新引用避免覆盖其他路由共享的密钥；冲突/失败时也不删除任何既有凭据。
      ops.add(
          set(
              expected.provider(),
              "apiKeyEnv",
              new com.google.gson.JsonPrimitive(storeNewCredential(key))));
    }
    if (!ops.isEmpty()) mutate(expected.revision(), ops);
  }

  public void removeCustomApi(CustomApi expected) throws IOException {
    Snapshot snapshot = currentSnapshot(expected);
    if (!removable(snapshot, expected.provider())) {
      throw new IOException("This provider is not a removable user profile");
    }
    JsonObject op = new JsonObject();
    op.addProperty("op", "unset");
    op.add("path", path(expected.provider(), null));
    JsonArray ops = new JsonArray();
    ops.add(op);
    // 引用可能被其他插件/配置使用。移除路由不等于有权删除底层共享密钥。
    mutate(expected.revision(), ops);
  }

  private Snapshot currentSnapshot(CustomApi expected) throws IOException {
    if (expected == null || !nativeProvider(expected.provider())) {
      throw new IllegalArgumentException("A native custom provider is required");
    }
    Snapshot snapshot = snapshot();
    requireWritable(snapshot);
    if (snapshot.revision() != expected.revision()) {
      throw new IOException("DSH settings changed; reload before editing");
    }
    if (object(object(snapshot.value(), "providers"), expected.provider()) == null) {
      throw new IOException("DSH provider no longer exists");
    }
    return snapshot;
  }

  private Snapshot snapshot() throws IOException {
    try {
      JsonObject description = rpc.wire("settings/describe", new JsonObject()).getAsJsonObject();
      JsonArray namespaces = description.getAsJsonArray("namespaces");
      if (namespaces == null) throw new IOException("Invalid DSH settings description");
      for (JsonElement item : namespaces) {
        JsonObject ns = item.getAsJsonObject();
        if (!NAMESPACE.equals(string(ns, "ns"))) continue;
        JsonElement revision = ns.get("revision");
        if (revision == null
            || !revision.isJsonPrimitive()
            || !revision.getAsJsonPrimitive().isNumber())
          throw new IOException("Missing settings revision");
        long number = revision.getAsBigDecimal().longValueExact();
        if (number < 0 || number > 9007199254740991L)
          throw new IOException("Invalid settings revision");
        JsonObject value = object(ns, "value");
        if (value == null) throw new IOException("Invalid DSH provider settings");
        return new Snapshot(
            bool(description, "writable"), number, value, object(ns, "user"), object(ns, "base"));
      }
      return new Snapshot(false, -1, new JsonObject(), null, null);
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid DSH provider settings", malformed);
    }
  }

  private static boolean editable(Snapshot snapshot, String provider, JsonObject route) {
    if (!snapshot.writable() || route == null || !"openai-completions".equals(string(route, "api")))
      return false;
    JsonElement models = route.get("models");
    JsonObject stored = object(object(snapshot.user(), "providers"), provider);
    JsonElement storedModels = stored == null ? null : stored.get("models");
    return models != null
        && models.isJsonArray()
        && models.getAsJsonArray().size() == 1
        && models.getAsJsonArray().get(0).isJsonObject()
        && !firstModel(route).isBlank()
        && storedModels != null
        && storedModels.isJsonArray()
        && storedModels.getAsJsonArray().size() == 1
        && storedModels.getAsJsonArray().get(0).isJsonObject();
  }

  private static boolean removable(Snapshot snapshot, String provider) {
    return snapshot.writable()
        && object(object(snapshot.user(), "providers"), provider) != null
        && object(object(snapshot.base(), "providers"), provider) == null;
  }

  private static String firstModel(JsonObject route) {
    JsonElement models = route == null ? null : route.get("models");
    if (models == null
        || !models.isJsonArray()
        || models.getAsJsonArray().isEmpty()
        || !models.getAsJsonArray().get(0).isJsonObject()) return "";
    return string(models.getAsJsonArray().get(0).getAsJsonObject(), "id");
  }

  private String storeNewCredential(String key) throws IOException {
    String ref =
        "DSHA_CUSTOM_"
            + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)
            + "_API_KEY";
    JsonObject args = new JsonObject();
    args.addProperty("ref", ref);
    args.addProperty("value", key);
    rpc.wire("credentials/set", args);
    return ref;
  }

  private void mutate(long revision, JsonArray ops) throws IOException {
    JsonObject args = new JsonObject();
    args.addProperty("ns", NAMESPACE);
    args.addProperty("expectedRevision", revision);
    args.add("ops", ops);
    JsonElement result = rpc.wire("settings/mutate", args);
    if (result == null
        || !result.isJsonObject()
        || !NAMESPACE.equals(string(result.getAsJsonObject(), "ns"))) {
      throw new IOException("Invalid DSH settings write response");
    }
  }

  private static JsonObject set(String provider, String field, JsonElement value) {
    JsonObject op = new JsonObject();
    op.addProperty("op", "set");
    op.add("path", path(provider, field));
    op.add("value", value);
    return op;
  }

  private static JsonArray path(String provider, String field) {
    JsonArray path = new JsonArray();
    path.add("providers");
    path.add(provider);
    if (field != null) path.add(field);
    return path;
  }

  private static void requireWritable(Snapshot snapshot) throws IOException {
    if (!snapshot.writable()) throw new IOException("DSH provider settings are read-only");
  }

  private static void validate(String address, String model, String key, boolean requireKey) {
    if (address == null
        || !NativeChatRpc.validCustomApiAddress(address)
        || model == null
        || model.isBlank()
        || key == null
        || requireKey && key.isBlank()) {
      throw new IllegalArgumentException("Invalid custom API configuration");
    }
  }

  private static boolean nativeProvider(String value) {
    return value != null
        && value.startsWith(NativeChatRpc.CUSTOM_PROVIDER_PREFIX)
        && value.length() > NativeChatRpc.CUSTOM_PROVIDER_PREFIX.length();
  }

  private static JsonObject object(JsonObject owner, String key) {
    JsonElement value = owner == null ? null : owner.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String string(JsonObject owner, String key) {
    JsonElement value = owner == null ? null : owner.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }

  private static boolean bool(JsonObject owner, String key) {
    JsonElement value = owner == null ? null : owner.get(key);
    return value != null
        && value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isBoolean()
        && value.getAsBoolean();
  }
}
