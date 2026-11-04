package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** 真实受控传输上的账号与自定义 API 合同回归；密钥均为虚构测试值。 */
public final class NativeChatAccountRpcTest {
  private MockWebServer server;
  private NativeChatAccountRpc account;
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final List<JsonObject> requests = new ArrayList<>();
  private JsonObject settings;
  private boolean rejectMutation;
  private boolean changeDuringDescribe;
  private boolean running;
  private String cookie;

  @Before
  public void setUp() throws Exception {
    settings =
        json(
            """
        {"writable":true,"hasDocument":true,"namespaces":[
          {"ns":"llm-pi-ai","autoGenerate":true,"schema":{},"applies":"live",
           "secrets":[],"revision":7,"base":{"providers":{}},
           "user":{"providers":{"dsha-custom-first":{"displayName":"Personal API",
             "api":"openai-completions","baseURL":"https://old.example/v1",
             "apiKeyEnv":"SHARED_KEY","models":[{"id":"old-model","name":"Keep name",
               "contextWindow":32000}],"headers":{"x-extra":"keep"}}}},
           "value":{"providers":{"dsha-custom-first":{"displayName":"Personal API",
             "api":"openai-completions","baseURL":"https://old.example/v1",
             "apiKeyEnv":"SHARED_KEY","models":[{"id":"old-model","name":"Keep name",
               "contextWindow":32000}],"headers":{"x-extra":"keep"}},
             "external-provider":{"api":"openai-completions","apiKeyEnv":"SHARED_KEY",
               "models":[{"id":"shared"}]}}}}]}
        """);
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            cookie = incoming.getHeader("Cookie");
            JsonObject request = json(incoming.getBody().readUtf8());
            requests.add(request);
            String method = request.get("method").getAsString();
            JsonElement value;
            switch (method) {
              case "settings/describe" -> {
                value = settings.deepCopy();
                if (changeDuringDescribe) current.set(false);
              }
              case "credentials/describe" ->
                  value =
                      json(
                          "{\"SHARED_KEY\":{\"configured\":true,\"writable\":true,\"source\":\"file\"}}");
              case "credentials/set" -> value = com.google.gson.JsonNull.INSTANCE;
              case "settings/mutate" -> value = namespace().deepCopy();
              case "account/hasRunningAccountTasks" ->
                  value = new com.google.gson.JsonPrimitive(running);
              case "account/signOut", "account/cancelSignIn" ->
                  value = json("{\"status\":\"signed-out\",\"attempt\":null}");
              default -> throw new AssertionError("Unexpected RPC: " + method);
            }
            JsonObject result = new JsonObject();
            boolean rejected = rejectMutation && "settings/mutate".equals(method);
            result.addProperty("ok", !rejected);
            if (rejected)
              result.add("error", json("{\"code\":\"settings/conflict\",\"message\":\"changed\"}"));
            else result.add("value", value);
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "server-response");
            reply.add("rpcId", request.get("rpcId"));
            reply.add("result", result);
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(reply.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    account =
        new NativeChatAccountRpc(
            new NativeChatRpc(
                "http://127.0.0.1:" + server.getPort(),
                "dsh-auth-fixture=fixture-cookie",
                current::get));
  }

  @After
  public void tearDown() throws IOException {
    if (server != null) server.close();
  }

  @Test
  public void onboardingRecognizesExistingExternalApiWithoutReadingSecretValues() throws Exception {
    namespace().getAsJsonObject("value").getAsJsonObject("providers").remove("dsha-custom-first");
    assertTrue(account.hasConfiguredApi());
    assertEquals(List.of("settings/describe", "credentials/describe"), methods());
    assertEquals("[\"SHARED_KEY\"]", args("credentials/describe").get("refs").toString());
  }

  @Test
  public void onboardingRecognizesNoConfiguredRoutesWithoutWritingAnything() throws Exception {
    namespace().getAsJsonObject("value").add("providers", new JsonObject());
    assertFalse(account.hasConfiguredApi());
    assertEquals(List.of("settings/describe"), methods());
  }

  @Test
  public void missingCredentialStatusIsUnknownRatherThanNewUser() throws Exception {
    namespace()
        .getAsJsonObject("value")
        .getAsJsonObject("providers")
        .getAsJsonObject("external-provider")
        .addProperty("apiKeyEnv", "UNKNOWN_KEY");
    assertThrows(IOException.class, account::hasConfiguredApi);
  }

  @Test
  public void listsOnlyNativeCustomRoutesWithoutExposingKeys() throws Exception {
    var catalog = account.customApis();
    assertTrue(catalog.writable());
    assertEquals(7, catalog.revision());
    assertEquals(1, catalog.providers().size());
    var provider = catalog.providers().get(0);
    assertEquals("dsha-custom-first", provider.provider());
    assertEquals("Personal API", provider.name());
    assertEquals("https://old.example/v1", provider.address());
    assertEquals("old-model", provider.model());
    assertTrue(provider.keyConfigured());
    assertTrue(provider.editable());
    assertTrue(provider.removable());
    assertEquals("dsh-auth-fixture=fixture-cookie", cookie);
    assertEquals(List.of("settings/describe", "credentials/describe"), methods());
  }

  @Test
  public void updatesVisibleFieldsWithoutReplacingUnknownSettingsOrExistingSecret()
      throws Exception {
    var provider = account.customApis().providers().get(0);
    requests.clear();
    account.updateCustomApi(provider, "https://new.example/v1", "new-model", "");
    assertEquals(List.of("settings/describe", "settings/mutate"), methods());
    var args = args("settings/mutate");
    assertEquals("llm-pi-ai", args.get("ns").getAsString());
    assertEquals(7, args.get("expectedRevision").getAsLong());
    JsonArray ops = args.getAsJsonArray("ops");
    assertEquals(2, ops.size());
    assertEquals(
        "baseURL", ops.get(0).getAsJsonObject().getAsJsonArray("path").get(2).getAsString());
    var model = ops.get(1).getAsJsonObject();
    assertEquals(
        "[\"providers\",\"dsha-custom-first\",\"models\",\"0\",\"id\"]",
        model.get("path").toString());
    assertEquals("new-model", model.get("value").getAsString());
  }

  @Test
  public void replacementKeyGetsIsolatedReferenceWithoutOverwritingSharedCredential()
      throws Exception {
    var provider = account.customApis().providers().get(0);
    requests.clear();
    account.updateCustomApi(provider, provider.address(), provider.model(), "fixture-new-key");
    assertEquals(List.of("settings/describe", "credentials/set", "settings/mutate"), methods());
    var credential = args("credentials/set");
    assertNotEquals("SHARED_KEY", credential.get("ref").getAsString());
    assertTrue(credential.get("ref").getAsString().startsWith("DSHA_CUSTOM_"));
    var ops = args("settings/mutate").getAsJsonArray("ops");
    assertEquals(1, ops.size());
    assertEquals(
        "apiKeyEnv", ops.get(0).getAsJsonObject().getAsJsonArray("path").get(2).getAsString());
    assertEquals(credential.get("ref"), ops.get(0).getAsJsonObject().get("value"));
  }

  @Test
  public void staleEditorCannotWriteSettingsOrCredentials() throws Exception {
    var provider = account.customApis().providers().get(0);
    namespace().addProperty("revision", 8);
    requests.clear();
    assertThrows(
        IOException.class,
        () ->
            account.updateCustomApi(
                provider, "https://new.example/v1", "new-model", "fixture-new-key"));
    assertEquals(List.of("settings/describe"), methods());
  }

  @Test
  public void removalUsesRevisionAndLeavesSharedCredentialUntouched() throws Exception {
    var provider = account.customApis().providers().get(0);
    requests.clear();
    account.removeCustomApi(provider);
    assertEquals(List.of("settings/describe", "settings/mutate"), methods());
    var args = args("settings/mutate");
    assertEquals(7, args.get("expectedRevision").getAsInt());
    assertEquals(
        "[{\"op\":\"unset\",\"path\":[\"providers\",\"dsha-custom-first\"]}]",
        args.get("ops").toString());
  }

  @Test
  public void conflictIsNotRetriedOrReportedAsSuccess() throws Exception {
    var provider = account.customApis().providers().get(0);
    requests.clear();
    rejectMutation = true;
    assertThrows(IOException.class, () -> account.removeCustomApi(provider));
    assertEquals(List.of("settings/describe", "settings/mutate"), methods());
  }

  @Test
  public void addCreatesCredentialAndRevisionGuardedProfile() throws Exception {
    String provider = account.addCustomApi("https://new.example/v1", "new-model", "fixture-key");
    assertTrue(provider.startsWith(NativeChatRpc.CUSTOM_PROVIDER_PREFIX));
    assertEquals(List.of("settings/describe", "credentials/set", "settings/mutate"), methods());
    var args = args("settings/mutate");
    assertEquals(7, args.get("expectedRevision").getAsInt());
    var op = args.getAsJsonArray("ops").get(0).getAsJsonObject();
    assertEquals(provider, op.getAsJsonArray("path").get(1).getAsString());
    assertEquals(args("credentials/set").get("ref"), op.getAsJsonObject("value").get("apiKeyEnv"));
  }

  @Test
  public void readOnlyProfileCannotWriteCredentialsOrSettings() throws Exception {
    settings.addProperty("writable", false);
    assertFalse(account.customApis().writable());
    requests.clear();
    assertThrows(
        IOException.class, () -> account.addCustomApi("https://new.example/v1", "model", "key"));
    assertEquals(List.of("settings/describe"), methods());
  }

  @Test
  public void missingOrFractionalRevisionCannotBeUsedForMutation() throws Exception {
    namespace().remove("revision");
    assertThrows(IOException.class, account::customApis);
    namespace().addProperty("revision", 1.5);
    assertThrows(IOException.class, account::customApis);
  }

  @Test
  public void serviceReplacementStopsMultiStepMutationBeforeCredentialWrite() throws Exception {
    changeDuringDescribe = true;
    assertThrows(
        IOException.class, () -> account.addCustomApi("https://new.example/v1", "model", "key"));
    assertEquals(List.of("settings/describe"), methods());
  }

  @Test
  public void multipleModelsAreListedButCannotBeClobberedBySingleModelEditor() throws Exception {
    namespace()
        .getAsJsonObject("value")
        .getAsJsonObject("providers")
        .getAsJsonObject("dsha-custom-first")
        .getAsJsonArray("models")
        .add(json("{\"id\":\"second\"}"));
    var provider = account.customApis().providers().get(0);
    assertFalse(provider.editable());
    requests.clear();
    assertThrows(
        IOException.class,
        () -> account.updateCustomApi(provider, "https://new.example/v1", "new-model", ""));
    assertFalse(methods().contains("settings/mutate"));
  }

  @Test
  public void inheritedProfileIsNotPresentedAsRemovable() throws Exception {
    var route =
        namespace().getAsJsonObject("value").getAsJsonObject("providers").get("dsha-custom-first");
    namespace()
        .getAsJsonObject("base")
        .getAsJsonObject("providers")
        .add("dsha-custom-first", route.deepCopy());
    var provider = account.customApis().providers().get(0);
    assertFalse(provider.removable());
    assertThrows(IOException.class, () -> account.removeCustomApi(provider));
  }

  @Test
  public void logoutUsesClientMetadataAndNeverChangesApiKeys() throws Exception {
    running = true;
    assertTrue(account.hasRunningAccountTasks());
    account.signOut("fixture-version", "zh-CN");
    assertEquals(List.of("account/hasRunningAccountTasks", "account/signOut"), methods());
    JsonObject args = args("account/signOut");
    assertEquals(1, args.size());
    assertEquals("fixture-version", args.getAsJsonObject("client").get("version").getAsString());
    assertEquals("zh-CN", args.getAsJsonObject("client").get("locale").getAsString());
    assertTrue(args.getAsJsonObject("client").has("timezoneOffsetSeconds"));
  }

  @Test
  public void cancellationNamesOnlyTheOwnedLoginAttempt() throws Exception {
    account.cancelSignIn("attempt-1");
    assertEquals("{\"attemptId\":\"attempt-1\"}", args("account/cancelSignIn").toString());
    requests.clear();
    assertThrows(IllegalArgumentException.class, () -> account.cancelSignIn(""));
    assertTrue(requests.isEmpty());
  }

  @Test
  public void invalidAddressCannotSendAKey() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> account.addCustomApi("http://unsafe.example", "model", "key"));
    assertTrue(requests.isEmpty());
  }

  @Test
  public void repeatedClicksAndLateCompletionCannotReopenDismissedWindow() {
    var gate = new NativeChatAccountRpc.RequestGate();
    assertTrue(gate.begin());
    assertFalse(gate.begin());
    gate.finish();
    assertTrue(gate.begin());
    gate.close();
    gate.finish();
    assertFalse(gate.isOpen());
    assertFalse(gate.begin());
  }

  private JsonObject namespace() {
    return settings.getAsJsonArray("namespaces").get(0).getAsJsonObject();
  }

  private static JsonObject json(String value) {
    return JsonParser.parseString(value).getAsJsonObject();
  }

  private List<String> methods() {
    return requests.stream().map(r -> r.get("method").getAsString()).toList();
  }

  private JsonObject args(String method) {
    return requests.stream()
        .filter(r -> method.equals(r.get("method").getAsString()))
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("payload")
        .getAsJsonObject("args");
  }

  @Test
  public void uncertainWriteRequiresReconciliationBeforeAnotherMutation() {
    var gate = new NativeChatAccountRpc.RequestGate();
    assertTrue(gate.canWrite());
    gate.uncertainWrite();
    assertFalse(gate.canWrite());
    assertTrue(gate.begin());
    gate.finish();
    assertFalse(gate.canWrite());
    gate.reconciled();
    assertTrue(gate.canWrite());
    gate.close();
    assertFalse(gate.canWrite());
  }
}
