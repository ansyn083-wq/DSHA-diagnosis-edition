package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Unary requests to the authenticated DSH gateway, scoped to one running service. */
public final class NativeChatRpc {
  public static final String DEEPSEEK_ACCOUNT = "deepseek-account";
  public static final String DEEPSEEK_API = "deepseek-official";
  private static final String DEEPSEEK_KEY_REF = "DEEPSEEK_API_KEY";
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private final String origin;
  private final String cookie;
  private final BooleanSupplier current;

  public NativeChatRpc(String authUrl, String cookie, BooleanSupplier current) {
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (base == null || cookie == null || !cookie.matches("dsh-auth-[^=;\\s]+=[^;\\s]+")) {
      throw new IllegalArgumentException(
          "A local DSH origin and authentication cookie are required");
    }
    this.origin = base.substring(0, base.length() - 1);
    this.cookie = cookie;
    this.current = Objects.requireNonNull(current);
  }

  public JsonObject continuedQuestion(String sessionId) throws IOException {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", sessionId);
    JsonElement reply = call("session/projections", request);
    if (reply == null || !reply.isJsonObject()) return null;
    JsonObject questions = object(object(reply.getAsJsonObject(), "values"), "userQuestions");
    if (questions == null || !questions.has("active")) return null;
    for (JsonElement entry : questions.getAsJsonArray("active")) {
      JsonObject batch = entry.getAsJsonObject();
      if ("continued".equals(string(batch, "state"))) return batch;
    }
    return null;
  }

  public boolean answerQuestion(String sessionId, String callId, JsonArray answers)
      throws IOException {
    JsonObject args = new JsonObject();
    args.addProperty("agentId", sessionId);
    args.addProperty("callId", callId);
    JsonObject answer = new JsonObject();
    answer.add("answers", answers);
    args.add("answer", answer);
    return wire("userQuestions/answer", args).getAsBoolean();
  }

  /** Complete or delegate a forwarded owner event on the same authenticated gateway. */
  public void finishQuestionEvent(
      String clientId, String eventId, JsonArray answers, boolean cancel) throws IOException {
    JsonObject args = new JsonObject();
    args.addProperty("clientId", clientId);
    args.addProperty("eventId", eventId);
    JsonObject outcome = new JsonObject();
    if (answers != null) {
      outcome.addProperty("kind", "result");
      JsonObject value = new JsonObject();
      value.add("answers", answers);
      outcome.add("value", value);
    } else if (cancel) {
      outcome.addProperty("kind", "rejected");
      JsonObject error = new JsonObject();
      error.addProperty("name", "UserQuestionError");
      error.addProperty("code", "ASK_CANCELLED");
      error.addProperty("message", "The user closed the question");
      outcome.add("error", error);
    } else {
      outcome.addProperty("kind", "next");
    }
    args.add("outcome", outcome);
    wire("$events/result", args);
  }

  /** Expire a timed foreground wait without representing it as a user's cancellation. */
  public void timeoutQuestionEvent(String clientId, String eventId) throws IOException {
    JsonObject args = new JsonObject();
    args.addProperty("clientId", clientId);
    args.addProperty("eventId", eventId);
    JsonObject error = new JsonObject();
    error.addProperty("name", "UserQuestionError");
    error.addProperty("code", "ASK_TIMED_OUT");
    error.addProperty("message", "ask_user_question timed out before the user answered");
    JsonObject outcome = new JsonObject();
    outcome.addProperty("kind", "rejected");
    outcome.add("error", error);
    args.add("outcome", outcome);
    wire("$events/result", args);
  }

  public void cancel(String sessionId) throws IOException {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", sessionId);
    call("session/cancel", request);
  }

  public JsonObject prompt(String sessionId, String requestId, String text) throws IOException {
    return prompt(sessionId, requestId, text, new JsonArray());
  }

  public JsonObject prompt(String sessionId, String requestId, String text, JsonArray attachments)
      throws IOException {
    if (text.isBlank() && attachments.isEmpty())
      throw new IllegalArgumentException("A message is required");
    JsonArray content = new JsonArray();
    if (!text.isBlank()) {
      JsonObject part = new JsonObject();
      part.addProperty("type", "text");
      part.addProperty("text", text);
      content.add(part);
    }
    content.addAll(attachments);
    JsonObject args = new JsonObject();
    args.addProperty("sessionId", sessionId);
    args.addProperty("requestId", requestId);
    args.addProperty("mode", "queue");
    args.addProperty("clientTimeZone", TimeZone.getDefault().getID());
    args.add("content", content);
    return call("session/prompt", args).getAsJsonObject();
  }

  public String createSession() throws IOException {
    JsonElement result = call("session/create", new JsonObject());
    if (result == null || !result.isJsonObject()) throw new IOException("Invalid DSH session");
    JsonElement id = result.getAsJsonObject().get("sessionId");
    if (id == null
        || !id.isJsonPrimitive()
        || !id.getAsJsonPrimitive().isString()
        || id.getAsString().isBlank()) throw new IOException("Missing DSH session ID");
    return id.getAsString();
  }

  public record Session(String id, String title, long updatedAt) {}

  public List<Session> listSessions() throws IOException {
    try {
      JsonObject page = call("session/list", "_request", new JsonObject()).getAsJsonObject();
      List<Session> sessions = new ArrayList<>();
      for (JsonElement item : page.getAsJsonArray("items")) {
        JsonObject row = item.getAsJsonObject();
        if (row.get("blank").getAsBoolean() || "subagent".equals(string(row, "origin"))) continue;
        String id = string(row, "sessionId");
        if (id.isBlank()) continue;
        JsonObject projections = row.getAsJsonObject("projections");
        JsonObject values = projections == null ? null : projections.getAsJsonObject("values");
        sessions.add(new Session(id, string(values, "title"), row.get("updatedAt").getAsLong()));
      }
      return List.copyOf(sessions);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH conversation history", invalid);
    }
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object == null ? null : object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }

  public record AccountState(
      boolean signedIn, String attemptId, String phase, String authorizationUrl) {}

  public AccountState accountState() throws IOException {
    return accountView(wire("account/getState", new JsonObject()));
  }

  public AccountState startSignIn(String version, String locale) throws IOException {
    JsonObject args = new JsonObject();
    args.add("client", accountClient(version, locale));
    args.addProperty("callbackOrigin", origin);
    args.addProperty("loginSource", "web");
    return accountView(wire("account/startSignIn", args));
  }

  public String accountAvatarUrl(String version, String locale) throws IOException {
    JsonElement response = call("account/getProfile", "client", accountClient(version, locale));
    if (response == null || !response.isJsonObject()) return "";
    JsonObject profile = response.getAsJsonObject();
    if (!"ready".equals(string(profile, "status"))) return "";
    return string(object(profile, "value"), "avatarUrl");
  }

  private static JsonObject accountClient(String version, String locale) {
    JsonObject client = new JsonObject();
    client.addProperty("version", version);
    client.addProperty("locale", locale);
    client.addProperty(
        "timezoneOffsetSeconds",
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000);
    return client;
  }

  private AccountState accountView(JsonElement response) throws IOException {
    try {
      JsonObject account = response.getAsJsonObject();
      JsonObject attempt = object(account, "attempt");
      String url = string(attempt, "authorizeUrl");
      return new AccountState(
          "credential-stored".equals(string(account, "status")),
          string(attempt, "id"),
          string(attempt, "phase"),
          DeepSeekOAuthNavigation.allowed(origin + "/", origin, url) ? url : "");
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid DSH account status", malformed);
    }
  }

  public static final String CUSTOM_PROVIDER_PREFIX = "dsha-custom-";

  public static boolean validCustomApiAddress(String address) {
    try {
      java.net.URI uri = new java.net.URI(address);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (java.net.URISyntaxException invalid) {
      return false;
    }
  }

  public String addCustomApi(String address, String model, String key) throws IOException {
    return new NativeChatAccountRpc(this).addCustomApi(address, model, key);
  }

  public void finishApproval(NativeChatApproval approval, boolean allow) throws IOException {
    wire("$events/result", approval.result(allow));
  }

  public JsonObject imageAttachment(String sessionId, String attachmentId) throws IOException {
    JsonObject request = new JsonObject();
    request.addProperty("sessionId", sessionId);
    request.addProperty("attachmentId", attachmentId);
    return call("session/attachment", request).getAsJsonObject();
  }

  public boolean hasDeepSeekApiKey() throws IOException {
    String ref = deepSeekApiKeyRef();
    JsonObject args = new JsonObject();
    JsonArray refs = new JsonArray();
    refs.add(ref);
    args.add("refs", refs);
    JsonObject entry = object(wire("credentials/describe", args).getAsJsonObject(), ref);
    return entry != null && entry.has("configured") && entry.get("configured").getAsBoolean();
  }

  public void saveDeepSeekApiKey(String value) throws IOException {
    if (value.isBlank()) throw new IllegalArgumentException("A DeepSeek API key is required");
    JsonObject args = new JsonObject();
    args.addProperty("ref", deepSeekApiKeyRef());
    args.addProperty("value", value);
    wire("credentials/set", args);
  }

  private String deepSeekApiKeyRef() throws IOException {
    JsonElement response = wire("settings/describe", new JsonObject());
    for (JsonElement entry : response.getAsJsonObject().getAsJsonArray("namespaces")) {
      JsonObject ns = entry.getAsJsonObject();
      if (!"llm-deepseek-api-key".equals(string(ns, "ns"))) continue;
      String configured = string(object(ns, "value"), "apiKeyEnv");
      if (!configured.isBlank()) return configured;
    }
    return DEEPSEEK_KEY_REF;
  }

  public record ModelSelection(String provider, String model, String effort) {
    public boolean sameModel(ModelSelection other) {
      return other != null && provider.equals(other.provider) && model.equals(other.model);
    }
  }

  public record Effort(String id, String name) {}

  public record ModelOption(
      String providerName, String name, ModelSelection selection, List<Effort> efforts) {}

  public record ModelCatalog(ModelSelection defaultModel, List<ModelOption> options) {
    public ModelSelection resolve(ModelSelection pending, ModelSelection session)
        throws IOException {
      if (isApiProvider(pending.provider()) && pending.model().isEmpty()) {
        return forProvider(pending.provider());
      }
      ModelSelection preferred = pending.model().isEmpty() ? session : pending;
      ModelSelection match = resolveApiSelection(preferred, options);
      if (match != null) return match;
      if (isApiProvider(pending.provider()))
        throw new IOException("Selected API model unavailable");
      if (defaultModel != null) return defaultModel;
      throw new IOException("No API model available");
    }

    public ModelSelection forProvider(String provider) throws IOException {
      ModelSelection first = null;
      for (ModelOption option : options) {
        ModelSelection model = option.selection();
        if (!provider.equals(model.provider())) continue;
        if (model.sameModel(defaultModel)) return defaultModel;
        if (first == null) first = model;
      }
      if (first != null) return first;
      throw new IOException("DeepSeek model unavailable");
    }
  }

  public ModelCatalog modelCatalog() throws IOException {
    JsonObject catalog = wire("session/modelCatalog", new JsonObject()).getAsJsonObject();
    List<ModelOption> options = new ArrayList<>();
    for (JsonElement entry : catalog.getAsJsonArray("groups")) {
      JsonObject group = entry.getAsJsonObject();
      String provider = string(group, "id");
      if (!isApiProvider(provider)) continue;
      String providerName = string(group, "name");
      for (JsonElement item : group.getAsJsonArray("models")) {
        JsonObject model = item.getAsJsonObject();
        String id = string(model, "id");
        JsonObject reasoning = object(model, "reasoning");
        List<Effort> efforts = new ArrayList<>();
        if (reasoning != null) {
          for (JsonElement e : reasoning.getAsJsonArray("efforts")) {
            JsonObject effort = e.getAsJsonObject();
            efforts.add(new Effort(string(effort, "id"), string(effort, "name")));
          }
        }
        options.add(
            new ModelOption(
                providerName.isEmpty() ? provider : providerName,
                string(model, "name").isEmpty() ? id : string(model, "name"),
                new ModelSelection(provider, id, string(reasoning, "defaultEffort")),
                List.copyOf(efforts)));
      }
    }
    ModelSelection preferred =
        resolveApiSelection(readSelection(object(catalog, "default")), options);
    if (preferred == null && !options.isEmpty()) preferred = options.get(0).selection();
    return new ModelCatalog(preferred, List.copyOf(options));
  }

  public static boolean isApiProvider(String provider) {
    return DEEPSEEK_API.equals(provider) || provider.startsWith(CUSTOM_PROVIDER_PREFIX);
  }

  private static ModelSelection resolveApiSelection(
      ModelSelection preferred, List<ModelOption> options) {
    if (preferred == null) return null;
    String provider =
        DEEPSEEK_ACCOUNT.equals(preferred.provider()) ? DEEPSEEK_API : preferred.provider();
    for (ModelOption option : options) {
      ModelSelection candidate = option.selection();
      if (!provider.equals(candidate.provider()) || !preferred.model().equals(candidate.model()))
        continue;
      String effort = preferred.effort();
      if (effort.isEmpty()
          || option.efforts().stream().anyMatch(value -> value.id().equals(effort))) {
        return new ModelSelection(provider, candidate.model(), effort);
      }
      return candidate;
    }
    return null;
  }

  /** Choose an API route before prompting, including for chats saved with an older account route. */
  public void ensureApiModel(String sessionId, ModelSelection pending) throws IOException {
    ModelSelection session = sessionModel(sessionId);
    if (session != null && isApiProvider(session.provider()) && pending.provider().isEmpty())
      return;
    ModelSelection selected = modelCatalog().resolve(pending, session);
    if (!selected.equals(session)) selectModel(sessionId, selected);
  }

  public ModelSelection sessionModel(String sessionId) throws IOException {
    JsonObject args = new JsonObject();
    args.addProperty("sessionId", sessionId);
    JsonElement reply = call("session/projections", args);
    if (reply == null || !reply.isJsonObject()) return null;
    JsonObject model = object(object(reply.getAsJsonObject(), "values"), "modelSelection");
    return readSelection(object(model, "next"));
  }

  private static ModelSelection readSelection(JsonObject value) {
    String model = string(value, "model");
    String provider = string(value, "provider");
    return model.isBlank() || provider.isBlank()
        ? null
        : new ModelSelection(provider, model, string(value, "reasoningEffort"));
  }

  public void selectDeepSeekModel(String sessionId, String provider) throws IOException {
    if (!isApiProvider(provider)) {
      throw new IllegalArgumentException("Unknown DeepSeek provider");
    }
    selectModel(sessionId, modelCatalog().forProvider(provider));
  }

  public void selectModel(String sessionId, ModelSelection choice) throws IOException {
    if (!isApiProvider(choice.provider())) throw new IOException("An API model is required");
    JsonObject args = new JsonObject();
    args.addProperty("sessionId", sessionId);
    args.addProperty("provider", choice.provider());
    args.addProperty("model", choice.model());
    if (!choice.effort().isBlank()) args.addProperty("reasoningEffort", choice.effort());
    call("session/selectModel", args);
  }

  private static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

  /** Stage a generic file on the same local Session that will receive the prompt. */
  public JsonObject stageFile(String sessionId, String name, InputStream source)
      throws IOException {
    String query =
        "?sessionId="
            + URLEncoder.encode(sessionId, "UTF-8")
            + "&name="
            + URLEncoder.encode(name, "UTF-8");
    HttpURLConnection connection =
        connect("/api/session/uploadFileBinary" + query, "application/octet-stream");
    try {
      connection.setChunkedStreamingMode(16384);
      try (var output = connection.getOutputStream()) {
        byte[] buffer = new byte[16384];
        int total = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
          total += count;
          if (total > MAX_UPLOAD_BYTES) throw new IOException("Attachment exceeds 20 MiB");
          output.write(buffer, 0, count);
        }
        if (total == 0) throw new IOException("Attachment is empty");
      }
      if (connection.getResponseCode() != 200) throw new IOException("DSH file upload failed");
      try (InputStream input = connection.getInputStream()) {
        JsonObject reply = JsonParser.parseString(readResponse(input)).getAsJsonObject();
        requireCurrent();
        if (!reply.get("ok").getAsBoolean()) throw new IOException("DSH rejected the attachment");
        String receipt = string(object(reply, "value"), "receiptId");
        if (receipt.isBlank()) throw new IOException("DSH upload receipt missing");
        JsonObject part = new JsonObject();
        part.addProperty("type", "file");
        part.addProperty("receiptId", receipt);
        return part;
      }
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid DSH upload response", malformed);
    } finally {
      connection.disconnect();
    }
  }

  private static JsonObject object(JsonObject owner, String key) {
    JsonElement value = owner == null ? null : owner.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  JsonElement call(String method, JsonObject args) throws IOException {
    return call(method, "request", args);
  }

  JsonElement call(String method, String parameter, JsonObject args) throws IOException {
    JsonObject wireArgs = new JsonObject();
    wireArgs.add(parameter, args);
    return wire(method, wireArgs);
  }

  JsonElement wire(String method, JsonObject wireArgs) throws IOException {
    requireCurrent();
    String rpcId = UUID.randomUUID().toString();
    JsonObject payload = new JsonObject();
    payload.add("args", wireArgs);
    JsonObject request = new JsonObject();
    request.addProperty("type", "client-request");
    request.addProperty("rpcId", rpcId);
    request.addProperty("method", method);
    request.add("payload", payload);
    byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
    HttpURLConnection connection = connect("/api/" + method, "application/json");
    try {
      connection.setFixedLengthStreamingMode(body.length);
      requireCurrent();
      try (var output = connection.getOutputStream()) {
        output.write(body);
      }
      int status = connection.getResponseCode();
      if (status != 200) throw new IOException("DSH gateway HTTP " + status);
      try (InputStream input = connection.getInputStream()) {
        JsonObject response = JsonParser.parseString(readResponse(input)).getAsJsonObject();
        requireCurrent();
        if (!"server-response".equals(response.get("type").getAsString())
            || !rpcId.equals(response.get("rpcId").getAsString())) {
          throw new IOException("DSH gateway response does not match the request");
        }
        JsonObject result = response.getAsJsonObject("result");
        if (!result.get("ok").getAsBoolean()) {
          String code = result.getAsJsonObject("error").get("code").getAsString();
          throw new IOException("DSH gateway rejected request: " + code);
        }
        return result.get("value");
      }
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid DSH gateway response", invalid);
    } finally {
      connection.disconnect();
    }
  }

  private HttpURLConnection connect(String path, String contentType) throws IOException {
    requireCurrent();
    HttpURLConnection connection =
        (HttpURLConnection) new URL(origin + path).openConnection(Proxy.NO_PROXY);
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(15000);
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Origin", origin);
    connection.setRequestProperty("Cookie", cookie);
    connection.setRequestProperty("Content-Type", contentType);
    connection.setDoOutput(true);
    return connection;
  }

  private void requireCurrent() throws IOException {
    if (!current.getAsBoolean()) throw new IOException("DSH service changed");
  }

  private static String readResponse(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (bytes.size() + count > MAX_RESPONSE_BYTES) {
        throw new IOException("DSH gateway response is too large");
      }
      bytes.write(buffer, 0, count);
    }
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }
}
