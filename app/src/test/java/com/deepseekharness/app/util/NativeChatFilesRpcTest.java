package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Contract and containment tests use the real loopback HTTP transport, including binary MIME. */
public final class NativeChatFilesRpcTest {
  private MockWebServer server;
  private NativeChatFilesRpc client;
  private final List<JsonObject> requests = new ArrayList<>();
  private final AtomicBoolean current = new AtomicBoolean(true);
  private String listing =
      "{\"path\":\"\",\"entries\":[{\"name\":\"report.md\",\"type\":\"file\",\"size\":3},{\"name\":\"images\",\"type\":\"directory\"}],\"truncated\":false}";
  private String stat =
      "{\"absolutePath\":\"/workspace/report.md\",\"version\":\"v1\",\"bytes\":3}";
  private String text =
      "{\"absolutePath\":\"/workspace/report.md\",\"version\":\"v1\",\"bytes\":3,\"offset\":1,\"lines\":1,\"text\":\"abc\",\"eof\":true}";
  private String binaryMeta =
      "{\"absolutePath\":\"/workspace/report.md\",\"version\":\"v1\",\"bytes\":3,\"offset\":0,\"data\":null,\"eof\":true}";
  private byte[] binary = new byte[] {0, 1, (byte) 255};
  private boolean refuseParent;
  private boolean changeDuringRead;
  private boolean wrongBinaryId;
  private boolean extraPart;
  private boolean redirectBinary;
  private String attachmentPath = "[\"data\"]";
  private String seenCookie;

  @Before
  public void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest incoming) {
            JsonObject req =
                JsonParser.parseString(incoming.getBody().readUtf8()).getAsJsonObject();
            requests.add(req);
            seenCookie = incoming.getHeader("Cookie");
            assertEquals("/api/" + req.get("method").getAsString(), incoming.getPath());
            MockResponse outgoing =
                new MockResponse().setHeader("Content-Type", "application/json");
            String method = req.get("method").getAsString();
            JsonObject args = req.getAsJsonObject("payload").getAsJsonObject("args");
            boolean parent =
                method.equals("workspaceFiles/list")
                    && args.get("path").getAsString().startsWith("/");
            String value =
                method.endsWith("/list") ? listing : method.endsWith("/stat") ? stat : text;
            JsonObject envelope =
                JsonParser.parseString(
                        "{\"type\":\"server-response\",\"result\":{\"ok\":true,\"value\":"
                            + value
                            + "}}")
                    .getAsJsonObject();
            envelope.add("rpcId", req.get("rpcId"));
            if (parent && refuseParent)
              envelope.add(
                  "result",
                  JsonParser.parseString(
                      "{\"ok\":false,\"error\":{\"code\":\"workspace-file/outside-workspace\"}}"));
            byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);
            if (method.endsWith("/readBytes")) {
              if (redirectBinary) {
                return outgoing.setResponseCode(302).setHeader("Location", "/unexpected");
              }
              envelope.getAsJsonObject("result").add("value", JsonParser.parseString(binaryMeta));
              if (wrongBinaryId) envelope.addProperty("rpcId", "other-request");
              envelope.add(
                  "attachments",
                  JsonParser.parseString(
                      "[{\"codec\":\"bytes\",\"part\":\"bytes-0\",\"path\":"
                          + attachmentPath
                          + "}]"));
              var output = new Buffer();
              output.write(
                  "--test-boundary\r\nContent-Disposition: form-data; name=\"bytes-0\"; filename=\"blob\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                      .getBytes(StandardCharsets.UTF_8));
              output.write(binary);
              output.write(
                  ("\r\n--test-boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n"
                          + envelope
                          + "\r\n")
                      .getBytes(StandardCharsets.UTF_8));
              if (extraPart)
                output.write(
                    "--test-boundary\r\nContent-Disposition: form-data; name=\"surprise\"\r\n\r\nx\r\n"
                        .getBytes(StandardCharsets.UTF_8));
              output.write("--test-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
              body = output.readByteArray();
              outgoing.setHeader("Content-Type", "multipart/form-data; boundary=test-boundary");
            }
            if (changeDuringRead && (method.endsWith("/read") || method.endsWith("/readBytes")))
              current.set(false);
            return outgoing.setBody(new Buffer().write(body));
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    client = new NativeChatFilesRpc(url(), "dsh-auth-test=secret", "session-1", current::get);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getPort();
  }

  @After
  public void stop() throws IOException {
    if (server != null) server.shutdown();
  }

  @Test
  public void rootListingUsesActualSessionLookupContract() throws Exception {
    var found = client.list("");
    assertEquals("", found.path());
    assertEquals(2, found.entries().size());
    assertEquals("report.md", found.entries().get(0).path());
    assertEquals(3, found.entries().get(0).bytes());
    assertEquals("directory", found.entries().get(1).type());
    assertFalse(found.truncated());
    assertEquals(
        JsonParser.parseString("{\"workspaceFileScopeId\":\"session-1\",\"path\":\".\"}"), args(0));
    assertEquals("dsh-auth-test=secret", seenCookie);
  }

  @Test
  public void unsafeInputPathsNeverReachBackend() {
    for (String path :
        new String[] {
          "../secret", "/etc", "a/../b", "https://site", "a\\b", "a//b", "a/./b", "a\u0000b"
        }) assertThrows(IllegalArgumentException.class, () -> client.list(path));
    assertTrue(requests.isEmpty());
  }

  @Test
  public void malformedNamesAndUnknownKindsAreRejected() {
    for (String entry :
        new String[] {
          "{\"name\":\"../bad\",\"type\":\"file\"}",
          "{\"name\":\".\",\"type\":\"file\"}",
          "{\"name\":\"a/b\",\"type\":\"file\"}",
          "{\"name\":\"a\",\"type\":\"symlink\"}",
          "{\"name\":\"a\",\"type\":\"file\",\"size\":-1}"
        }) {
      listing = "{\"path\":\"\",\"entries\":[" + entry + "],\"truncated\":false}";
      assertThrows(IOException.class, () -> client.list(""));
    }
  }

  @Test
  public void textReadFirstChecksCanonicalParentThenReadsExactFile() throws Exception {
    var entry = client.list("").entries().get(0);
    var page = client.readText(entry, 1);
    assertEquals("abc", page.text());
    assertEquals(1, page.lines());
    assertTrue(page.eof());
    assertEquals("workspaceFiles/stat", method(1));
    assertEquals("/workspace", args(2).get("path").getAsString());
    assertEquals("workspaceFiles/list", method(2));
    assertEquals("/workspace/report.md", args(3).get("path").getAsString());
    assertEquals(JsonParser.parseString("{\"offset\":1,\"limit\":200}"), args(3).get("range"));
  }

  @Test
  public void ancestorSymlinkOutsideWorkspaceIsRefusedBeforeContentRead() throws Exception {
    var entry = client.list("").entries().get(0);
    stat = stat.replace("/workspace/report.md", "/private/report.md");
    refuseParent = true;
    assertThrows(IOException.class, () -> client.readText(entry, 1));
    assertEquals(3, requests.size());
    assertEquals("workspaceFiles/list", method(2));
  }

  @Test
  public void changedCanonicalParentAndChangedVersionsAreRejected() throws Exception {
    var entry = client.list("").entries().get(0);
    listing = listing.replace("\"path\":\"\"", "\"path\":\"different\"");
    assertThrows(IOException.class, () -> client.readText(entry, 1));
    assertEquals(3, requests.size());
    listing = listing.replace("\"path\":\"different\"", "\"path\":\"\"");
    text = text.replace("v1", "v2");
    assertThrows(IOException.class, () -> client.readText(entry, 1));
  }

  @Test
  public void contentFromAnotherResolvedPathIsNeverShown() throws Exception {
    var entry = client.list("").entries().get(0);
    text = text.replace("/workspace/report.md", "/secret/report.md");
    assertThrows(IOException.class, () -> client.readText(entry, 1));
  }

  @Test
  public void crossSessionEntryAndDirectoryCannotBeRead() throws Exception {
    var entries = client.list("").entries();
    var other = new NativeChatFilesRpc(url(), "dsh-auth-test=secret", "session-2", current::get);
    assertThrows(IllegalArgumentException.class, () -> other.readText(entries.get(0), 1));
    assertThrows(IllegalArgumentException.class, () -> client.readText(entries.get(1), 1));
    assertThrows(IllegalArgumentException.class, () -> client.readText(entries.get(0), 0));
    assertEquals(1, requests.size());
  }

  @Test
  public void binaryReadUsesMultipartBytesAndValidatedMetadata() throws Exception {
    var entry = client.list("").entries().get(0);
    assertArrayEquals(binary, client.readBytes(entry));
    assertEquals("workspaceFiles/readBytes", method(3));
    assertEquals(new JsonObject(), args(3).get("options"));
    assertEquals("dsh-auth-test=secret", seenCookie);
  }

  @Test
  public void binaryRpcCorrelationAttachmentPathAndUnknownPartsAreValidated() throws Exception {
    var entry = client.list("").entries().get(0);
    wrongBinaryId = true;
    assertThrows(IOException.class, () -> client.readBytes(entry));
    wrongBinaryId = false;
    attachmentPath = "[\"other\"]";
    assertThrows(IOException.class, () -> client.readBytes(entry));
    attachmentPath = "[\"data\"]";
    extraPart = true;
    assertThrows(IOException.class, () -> client.readBytes(entry));
  }

  @Test
  public void oversizedBinaryRefusedBeforeTransferAndRedirectIsNeverFollowed() throws Exception {
    var entry = client.list("").entries().get(0);
    stat = stat.replace("\"bytes\":3", "\"bytes\":9000000");
    assertThrows(IOException.class, () -> client.readBytes(entry));
    assertEquals(2, requests.size());
    stat = stat.replace("9000000", "3");
    redirectBinary = true;
    assertThrows(IOException.class, () -> client.readBytes(entry));
    assertEquals(5, requests.size());
  }

  @Test
  public void malformedTextMetadataAndIncompleteBinaryAreRejected() throws Exception {
    var entry = client.list("").entries().get(0);
    text = text.replace("\"offset\":1", "\"offset\":2");
    assertThrows(IOException.class, () -> client.readText(entry, 1));
    binaryMeta = binaryMeta.replace("\"eof\":true", "\"eof\":false");
    assertThrows(IOException.class, () -> client.readBytes(entry));
  }

  @Test
  public void closedDialogOrServiceReplacementPreventsAndDiscardsWork() throws Exception {
    var entry = client.list("").entries().get(0);
    current.set(false);
    assertThrows(IOException.class, () -> client.readText(entry, 1));
    assertEquals(1, requests.size());
    current.set(true);
    changeDuringRead = true;
    assertThrows(IOException.class, () -> client.readText(entry, 1));
    current.set(true);
    assertThrows(IOException.class, () -> client.readBytes(entry));
  }

  @Test
  public void remoteOriginsAndMalformedCookiesAreNotAccepted() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NativeChatFilesRpc("https://evil.test", "dsh-auth-x=secret", "s", () -> true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NativeChatFilesRpc(url(), "bad=x; dsh-auth-x=secret", "s", () -> true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NativeChatFilesRpc(url(), "dsh-auth-x=secret", "", () -> true));
  }

  @Test
  public void safePreviewKindsNeverExecuteActiveDocuments() {
    assertEquals(NativeChatFilesRpc.Kind.TEXT, NativeChatFilesRpc.kind("demo.HTML"));
    assertEquals(NativeChatFilesRpc.Kind.TEXT, NativeChatFilesRpc.kind("drawing.svg"));
    assertEquals(NativeChatFilesRpc.Kind.IMAGE, NativeChatFilesRpc.kind("chart.png"));
    assertEquals(NativeChatFilesRpc.Kind.PDF, NativeChatFilesRpc.kind("report.pdf"));
    assertEquals(NativeChatFilesRpc.Kind.UNSUPPORTED, NativeChatFilesRpc.kind("deck.pptx"));
    assertEquals(NativeChatFilesRpc.Kind.UNSUPPORTED, NativeChatFilesRpc.kind("program.exe"));
  }

  @Test
  public void imageSamplingBoundsDecodedPixelsAndRefusesBombDimensions() {
    assertEquals(1, NativeChatFilesRpc.imageSampleSize(800, 600));
    assertEquals(2, NativeChatFilesRpc.imageSampleSize(3200, 200));
    assertEquals(2, NativeChatFilesRpc.imageSampleSize(1600, 1600));
    for (int[] size : new int[][] {{0, 1}, {-1, 2}, {Integer.MAX_VALUE, 2}, {10000, 10000}})
      assertThrows(
          IllegalArgumentException.class,
          () -> NativeChatFilesRpc.imageSampleSize(size[0], size[1]));
  }

  private JsonObject args(int index) {
    return requests.get(index).getAsJsonObject("payload").getAsJsonObject("args");
  }

  private String method(int index) {
    return requests.get(index).get("method").getAsString();
  }
}
