package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class NativeChatRpcTest {
  private MockWebServer server;
  private String origin;
  private final AtomicReference<String> profile =
      new AtomicReference<>(
          """
      {"status":"ready","value":{"id":"own-account","name":"Example","contact":null,
          "avatarUrl":"https://avatars.example.com/own-account.png"}}
      """);
  private final AtomicReference<String> sessionProvider =
      new AtomicReference<>("deepseek-official");
  private final AtomicBoolean current = new AtomicBoolean(true);
  private final AtomicBoolean wrongId = new AtomicBoolean(false);
  private final AtomicBoolean unsafeAuthorization = new AtomicBoolean(false);
  private final AtomicBoolean replaceDuringCall = new AtomicBoolean(false);
  private final AtomicReference<JsonObject> request = new AtomicReference<>();
  private final AtomicReference<RecordedRequest> upload = new AtomicReference<>();
  private final AtomicReference<String> cookie = new AtomicReference<>();

  @Before
  public void startHost() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            cookie.set(incoming.getHeader("Cookie"));
            if (incoming.getPath().startsWith("/api/session/uploadFileBinary?")) {
              upload.set(incoming);
              return new MockResponse()
                  .setBody(
                      """
              {"ok":true,"value":{"receiptId":"owned-upload-receipt"}}
              """);
            }
            JsonObject envelope =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            request.set(envelope);
            String value;
            if ("/api/$events/result".equals(incoming.getPath())) {
              value = "null";
            } else if ("/api/session/create".equals(incoming.getPath())) {
              value = "{\"sessionId\":\"host-session\"}";
            } else if ("/api/session/prompt".equals(incoming.getPath())) {
              value = "{\"accepted\":true}";
            } else if ("/api/session/list".equals(incoming.getPath())) {
              value =
                  """
              {"items":[
                {"sessionId":"first","updatedAt":2000,"blank":false,
                  "projections":{"values":{"title":"Existing conversation"}}},
                {"sessionId":"blank","updatedAt":3000,"blank":true},
                {"sessionId":"child","updatedAt":1500,"origin":"subagent","blank":false},
                {"sessionId":"second","updatedAt":1000,"blank":false}
              ]}
              """;
            } else if ("/api/account/startSignIn".equals(incoming.getPath())) {
              String address =
                  unsafeAuthorization.get()
                      ? "https://evil.example/dsh/authorize"
                      : "https://platform.deepseek.com/dsh/authorize?fixture=1";
              value =
                  """
              {"status":"signed-out","attempt":{"id":"test-login","phase":"waiting-browser",
                "authorizeUrl":"%s"}}
              """
                      .formatted(address);
            } else if ("/api/account/getState".equals(incoming.getPath())) {
              value =
                  """
              {"status":"credential-stored","attempt":null}
              """;
            } else if ("/api/account/getProfile".equals(incoming.getPath())) {
              value = profile.get();
            } else if ("/api/settings/describe".equals(incoming.getPath())) {
              value =
                  """
              {"namespaces":[{"ns":"llm-deepseek-api-key","value":{"apiKeyEnv":"OWNED_DEEPSEEK"}}]}
              """;
            } else if ("/api/credentials/set".equals(incoming.getPath())) {
              value = "null";
            } else if ("/api/credentials/describe".equals(incoming.getPath())) {
              value =
                  """
              {"OWNED_DEEPSEEK":{"configured":true}}
              """;
            } else if ("/api/session/modelCatalog".equals(incoming.getPath())) {
              value =
                  """
              {"default":{"provider":"deepseek-account","model":"preferred","reasoningEffort":"high"},
               "groups":[{"id":"deepseek-account","models":[{"id":"preferred"},{"id":"account-model"}]},
                         {"id":"deepseek-official","name":"DeepSeek","models":[{"id":"first"},
                           {"id":"preferred","name":"Fixture model","reasoning":{"defaultEffort":"high",
                             "efforts":[{"id":"low","name":"Low"},{"id":"high","name":"High"}]}}]}]}
              """;
            } else if ("/api/session/projections".equals(incoming.getPath())) {
              value =
                  """
              {"asOfSeq":2,"values":{"modelSelection":{"next":{"provider":"%s",
                 "model":"preferred","reasoningEffort":"high"}}}}
              """
                      .formatted(sessionProvider.get());
            } else if ("/api/session/selectModel".equals(incoming.getPath())) {
              value =
                  """
              {"selected":{"provider":"deepseek-official","model":"preferred"}}
              """;
            } else {
              return new MockResponse().setResponseCode(404);
            }
            JsonObject response =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            response.addProperty(
                "rpcId", wrongId.get() ? "other" : envelope.get("rpcId").getAsString());
            if (replaceDuringCall.get()) current.set(false);
            return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(response.toString());
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    origin = "http://127.0.0.1:" + server.getPort();
  }

  @After
  public void stopHost() throws IOException {
    server.close();
  }

  @Test
  public void readsOnlyTheCurrentPlatformProfileAvatar() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    assertEquals(
        "https://avatars.example.com/own-account.png", client.accountAvatarUrl("test", "zh"));
    JsonObject args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals("zh", args.getAsJsonObject("client").get("locale").getAsString());
    profile.set(
        """
        {"status":"ready","value":{"id":"other-account","name":null,"contact":null,"avatarUrl":null}}
        """);
    assertEquals("", client.accountAvatarUrl("test", "zh"));
    profile.set("null");
    assertEquals("", client.accountAvatarUrl("test", "zh"));
  }

  @Test
  public void sendsAuthenticatedPromptInGatewayEnvelope() throws Exception {
    JsonObject accepted = call();
    assertTrue(accepted.get("accepted").getAsBoolean());
    assertEquals("dsh-auth-test=secret", cookie.get());
    assertEquals("client-request", request.get().get("type").getAsString());
    assertEquals("session/prompt", request.get().get("method").getAsString());
    JsonObject body =
        request.get().getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request");
    assertNotNull(body);
    assertEquals("queue", body.get("mode").getAsString());
    assertEquals(
        "hello", body.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
  }

  @Test
  public void rejectsResponseForDifferentRequest() {
    wrongId.set(true);
    assertThrows(IOException.class, this::call);
  }

  @Test
  public void dropsResponseAfterServiceGenerationChanges() {
    replaceDuringCall.set(true);
    assertThrows(IOException.class, this::call);
  }

  @Test
  public void staleServiceIsNotContacted() {
    current.set(false);
    assertThrows(IOException.class, this::call);
    assertNull(request.get());
  }

  @Test
  public void rejectsNonLoopbackAddress() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NativeChatRpc("https://example.org/", "dsh-auth-test=secret", current::get));
  }

  @Test
  public void usesSessionIdReturnedByHost() throws Exception {
    assertEquals(
        "host-session",
        new NativeChatRpc(origin, "dsh-auth-test=secret", current::get).createSession());
  }

  @Test
  public void readsConversationTitlesWithoutResumingAgents() throws Exception {
    var sessions = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get).listSessions();
    assertEquals(2, sessions.size());
    assertEquals("first", sessions.get(0).id());
    assertEquals("Existing conversation", sessions.get(0).title());
    assertEquals("second", sessions.get(1).id());
    assertEquals("", sessions.get(1).title());
    var args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals(0, args.getAsJsonObject("_request").size());
  }

  @Test
  public void startsOfficialAuthorizationUsingThisHostForCallback() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    var state = client.startSignIn("diag-test", "zh-CN");
    assertEquals("waiting-browser", state.phase());
    assertTrue(state.authorizationUrl().startsWith("https://platform.deepseek.com/dsh/authorize?"));
    JsonObject args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals(origin, args.get("callbackOrigin").getAsString());
    assertEquals("web", args.get("loginSource").getAsString());
    assertEquals("zh-CN", args.getAsJsonObject("client").get("locale").getAsString());
    assertTrue(client.accountState().signedIn());
  }

  @Test
  public void discardsAuthorizationUrlFromUntrustedOrigin() throws Exception {
    unsafeAuthorization.set(true);
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    assertEquals("", client.startSignIn("diag-test", "en").authorizationUrl());
  }

  @Test
  public void savesApiKeyThroughHostCredentialReference() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    client.saveDeepSeekApiKey("owned-fixture-key");
    JsonObject args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals("OWNED_DEEPSEEK", args.get("ref").getAsString());
    assertEquals("owned-fixture-key", args.get("value").getAsString());
    assertTrue(client.hasDeepSeekApiKey());
  }

  @Test
  public void usesSelectedProviderWithoutReplacingItsExistingDefaultModel() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    client.selectDeepSeekModel("chat", NativeChatRpc.DEEPSEEK_API);
    JsonObject args =
        request.get().getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request");
    assertEquals("chat", args.get("sessionId").getAsString());
    assertEquals("deepseek-official", args.get("provider").getAsString());
    assertEquals("preferred", args.get("model").getAsString());
    assertEquals("high", args.get("reasoningEffort").getAsString());
  }

  @Test
  public void usesModelCatalogEffortIdsAndSessionSelection() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    var option =
        client.modelCatalog().options().stream()
            .filter(model -> model.name().equals("Fixture model"))
            .findFirst()
            .orElseThrow();
    assertEquals("DeepSeek", option.providerName());
    assertEquals("low", option.efforts().get(0).id());
    assertEquals("high", client.sessionModel("chat").effort());
    client.selectModel(
        "chat",
        new NativeChatRpc.ModelSelection(
            option.selection().provider(),
            option.selection().model(),
            option.efforts().get(0).id()));
    JsonObject body =
        request.get().getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request");
    assertEquals("chat", body.get("sessionId").getAsString());
    assertEquals("low", body.get("reasoningEffort").getAsString());
  }

  @Test
  public void hidesAccountModelsAndMapsLegacySelectionToApi() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    var catalog = client.modelCatalog();
    assertEquals(2, catalog.options().size());
    assertEquals("deepseek-official", catalog.defaultModel().provider());
    sessionProvider.set("deepseek-account");
    client.ensureApiModel("chat", new NativeChatRpc.ModelSelection("", "", ""));
    var selection =
        request.get().getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request");
    assertEquals("session/selectModel", request.get().get("method").getAsString());
    assertEquals("deepseek-official", selection.get("provider").getAsString());
    assertEquals("preferred", selection.get("model").getAsString());
    assertEquals("high", selection.get("reasoningEffort").getAsString());
  }

  @Test
  public void keepsExistingApiModelWithoutSelectingItAgain() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    client.ensureApiModel("chat", new NativeChatRpc.ModelSelection("", "", ""));
    assertEquals("session/projections", request.get().get("method").getAsString());
    var account = new NativeChatRpc.ModelSelection("deepseek-account", "preferred", "");
    assertThrows(IOException.class, () -> client.selectModel("chat", account));
  }

  @Test
  public void correlatesLiveQuestionAnswersWithTheirForwardedEvent() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    var answers =
        JsonParser.parseString(
                """
        [{"id":"proxy","selected":["Nginx"]}]
        """)
            .getAsJsonArray();
    client.finishQuestionEvent("client-1", "event-2", answers, false);
    assertEquals("$events/result", request.get().get("method").getAsString());
    var args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals("client-1", args.get("clientId").getAsString());
    assertEquals("event-2", args.get("eventId").getAsString());
    var outcome = args.getAsJsonObject("outcome");
    assertEquals("result", outcome.get("kind").getAsString());
    assertEquals(answers, outcome.getAsJsonObject("value").getAsJsonArray("answers"));

    client.finishQuestionEvent("client-1", "event-2", null, true);
    outcome =
        request.get().getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("outcome");
    assertEquals("rejected", outcome.get("kind").getAsString());
    assertEquals("ASK_CANCELLED", outcome.getAsJsonObject("error").get("code").getAsString());
  }

  @Test
  public void sendsTypedTimeoutWithoutClaimingUserCancelled() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    client.timeoutQuestionEvent("client-1", "event-2");
    assertEquals("$events/result", request.get().get("method").getAsString());
    var args = request.get().getAsJsonObject("payload").getAsJsonObject("args");
    assertEquals("client-1", args.get("clientId").getAsString());
    assertEquals("event-2", args.get("eventId").getAsString());
    var outcome = args.getAsJsonObject("outcome");
    assertEquals("rejected", outcome.get("kind").getAsString());
    assertEquals(2, outcome.size());
    var error = outcome.getAsJsonObject("error");
    assertEquals(3, error.size());
    assertEquals("UserQuestionError", error.get("name").getAsString());
    assertEquals("ASK_TIMED_OUT", error.get("code").getAsString());
    assertEquals(
        "ask_user_question timed out before the user answered", error.get("message").getAsString());
  }

  @Test
  public void failedTimeoutResponseIsNotRetriedAutomatically() throws Exception {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            return new MockResponse().setResponseCode(503);
          }
        });
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    assertThrows(IOException.class, () -> client.timeoutQuestionEvent("client-1", "event-2"));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  public void staleGenerationCannotSendTimeout() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    current.set(false);
    assertThrows(IOException.class, () -> client.timeoutQuestionEvent("client-1", "event-2"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  public void lostTimeoutResponseDoesNotReplayTheEventResult() throws Exception {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            return new MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST);
          }
        });
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    assertThrows(IOException.class, () -> client.timeoutQuestionEvent("client-1", "event-2"));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  public void stagesAttachmentAndSubmitsReceiptWithoutText() throws Exception {
    var client = new NativeChatRpc(origin, "dsh-auth-test=secret", current::get);
    var parts = new com.google.gson.JsonArray();
    parts.add(
        client.stageFile("chat+1", "a & b.txt", new ByteArrayInputStream(new byte[] {1, 2, 3})));
    assertEquals("dsh-auth-test=secret", upload.get().getHeader("Cookie"));
    assertEquals("application/octet-stream", upload.get().getHeader("Content-Type"));
    assertEquals("chat+1", upload.get().getRequestUrl().queryParameter("sessionId"));
    assertEquals("a & b.txt", upload.get().getRequestUrl().queryParameter("name"));
    assertArrayEquals(new byte[] {1, 2, 3}, upload.get().getBody().readByteArray());
    assertTrue(client.prompt("chat+1", "r1", "", parts).get("accepted").getAsBoolean());
    var content =
        request
            .get()
            .getAsJsonObject("payload")
            .getAsJsonObject("args")
            .getAsJsonObject("request")
            .getAsJsonArray("content");
    assertEquals(1, content.size());
    assertEquals("file", content.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals(
        "owned-upload-receipt", content.get(0).getAsJsonObject().get("receiptId").getAsString());
  }

  private JsonObject call() throws Exception {
    return new NativeChatRpc(origin, "dsh-auth-test=secret", current::get)
        .prompt("session-1", "request-1", "hello");
  }
}
