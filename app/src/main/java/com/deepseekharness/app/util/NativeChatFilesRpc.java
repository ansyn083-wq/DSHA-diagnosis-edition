package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.MultipartReader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/** Read-only native previews using the locked workspaceFiles and Connection contracts. */
public final class NativeChatFilesRpc {
  public static final int PAGE_LINES = 200;
  public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 512 * 1024;
  private static final int MAX_METADATA_BYTES = 32 * 1024;
  private static final Pattern PART_NAME = Pattern.compile("(?:^|;)\\s*name=\"([^\"]+)\"(?=;|$)");
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .proxy(Proxy.NO_PROXY)
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(5, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .callTimeout(20, TimeUnit.SECONDS)
          .build();
  private final NativeChatRpc rpc;
  private final String origin;
  private final String cookie;
  private final String sessionId;
  private final BooleanSupplier current;

  public enum Kind {
    TEXT,
    IMAGE,
    PDF,
    UNSUPPORTED
  }

  /** Instances only come from a validated, session-scoped listing. */
  public static final class Entry {
    private final String sessionId, name, path, type;
    private final long bytes;

    private Entry(String sessionId, String name, String path, String type, long bytes) {
      this.sessionId = sessionId;
      this.name = name;
      this.path = path;
      this.type = type;
      this.bytes = bytes;
    }

    public String name() {
      return name;
    }

    public String path() {
      return path;
    }

    public String type() {
      return type;
    }

    public long bytes() {
      return bytes;
    }
  }

  public record Listing(String path, List<Entry> entries, boolean truncated) {}

  public record TextPage(String text, int offset, int lines, boolean eof, String version) {}

  private record FileIdentity(String absolutePath, String version, long bytes) {}

  public NativeChatFilesRpc(
      String authUrl, String cookie, String sessionId, BooleanSupplier current) {
    rpc = new NativeChatRpc(authUrl, cookie, current);
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > 512 || controls(sessionId))
      throw new IllegalArgumentException("A current session is required");
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    origin = base.substring(0, base.length() - 1);
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.current = Objects.requireNonNull(current);
  }

  public Listing list(String path) throws IOException {
    if (!safeRelative(path))
      throw new IllegalArgumentException("Only workspace-relative paths are supported");
    return parseListing(rpc.wire("workspaceFiles/list", args(path.isEmpty() ? "." : path)));
  }

  public TextPage readText(Entry entry, int offset) throws IOException {
    requireEntry(entry);
    if (offset < 1 || offset > Integer.MAX_VALUE - PAGE_LINES)
      throw new IllegalArgumentException("Invalid text page");
    FileIdentity identity = locate(entry, false);
    JsonObject args = args(identity.absolutePath());
    JsonObject range = new JsonObject();
    range.addProperty("offset", offset);
    range.addProperty("limit", PAGE_LINES);
    args.add("range", range);
    try {
      JsonObject result = object(rpc.wire("workspaceFiles/read", args));
      checkIdentity(result, identity);
      String text = string(result, "text");
      int lines = (int) integer(result, "lines", 0, PAGE_LINES);
      if (integer(result, "offset", 1, Integer.MAX_VALUE) != offset
          || text.indexOf('\0') >= 0
          || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
        throw new IOException("Invalid or oversized text preview");
      boolean eof = bool(result, "eof");
      if (lines == 0 && (!text.isEmpty() || !eof)
          || lines > 0 && text.split("\n", -1).length != lines)
        throw new IOException("Invalid text preview line count");
      requireCurrent();
      return new TextPage(text, offset, lines, eof, identity.version());
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid workspace text preview", invalid);
    }
  }

  public byte[] readBytes(Entry entry) throws IOException {
    requireEntry(entry);
    FileIdentity identity = locate(entry, true);
    JsonObject args = args(identity.absolutePath());
    args.add("options", new JsonObject());
    JsonObject payload = new JsonObject();
    payload.add("args", args);
    String id = UUID.randomUUID().toString();
    JsonObject request = new JsonObject();
    request.addProperty("type", "client-request");
    request.addProperty("rpcId", id);
    request.addProperty("method", "workspaceFiles/readBytes");
    request.add("payload", payload);
    requireCurrent();
    Request outgoing =
        new Request.Builder()
            .url(origin + "/api/workspaceFiles/readBytes")
            .header("Origin", origin)
            .header("Cookie", cookie)
            .post(RequestBody.create(request.toString(), MediaType.get("application/json")))
            .build();
    try (Response response = HTTP.newCall(outgoing).execute()) {
      requireCurrent();
      if (response.code() != 200 || response.body() == null)
        throw new IOException("Workspace binary preview HTTP " + response.code());
      MediaType type = response.body().contentType();
      if (type == null || !"multipart".equals(type.type()) || !"form-data".equals(type.subtype()))
        throw new IOException("Workspace binary preview unavailable");
      String boundary = type.parameter("boundary");
      if (boundary == null || boundary.isEmpty() || boundary.length() > 200 || controls(boundary))
        throw new IOException("Invalid binary preview boundary");
      byte[] body = bounded(response.body().byteStream(), MAX_FILE_BYTES + 65536);
      Map<String, byte[]> parts = new HashMap<>();
      try (MultipartReader reader = new MultipartReader(new Buffer().write(body), boundary)) {
        MultipartReader.Part part;
        while ((part = reader.nextPart()) != null) {
          try (MultipartReader.Part opened = part) {
            String disposition = opened.headers().get("Content-Disposition");
            var match = PART_NAME.matcher(disposition == null ? "" : disposition);
            if (parts.size() >= 2 || !match.find())
              throw new IOException("Invalid binary preview parts");
            String name = match.group(1);
            if (parts.containsKey(name) || !("metadata".equals(name) || "bytes-0".equals(name)))
              throw new IOException("Unexpected binary preview part");
            parts.put(
                name,
                bounded(
                    opened.body().inputStream(),
                    "metadata".equals(name) ? MAX_METADATA_BYTES : MAX_FILE_BYTES));
          }
        }
      }
      if (parts.size() != 2) throw new IOException("Incomplete binary preview");
      JsonObject envelope =
          object(JsonParser.parseString(new String(parts.get("metadata"), StandardCharsets.UTF_8)));
      if (!"server-response".equals(string(envelope, "type"))
          || !id.equals(string(envelope, "rpcId")))
        throw new IOException("Binary preview response mismatch");
      JsonArray attachments = envelope.getAsJsonArray("attachments");
      if (attachments == null || attachments.size() != 1)
        throw new IOException("Invalid binary preview attachments");
      JsonObject attachment = object(attachments.get(0));
      if (!"bytes".equals(string(attachment, "codec"))
          || !"bytes-0".equals(string(attachment, "part"))
          || !JsonParser.parseString("[\"data\"]").equals(attachment.get("path")))
        throw new IOException("Invalid binary preview attachment target");
      JsonObject result = object(envelope.get("result"));
      if (!bool(result, "ok")) throw new IOException("Workspace binary preview rejected");
      JsonObject value = object(result.get("value"));
      checkIdentity(value, identity);
      byte[] bytes = parts.get("bytes-0");
      if (!value.has("data")
          || !value.get("data").isJsonNull()
          || !bool(value, "eof")
          || integer(value, "offset", 0, Long.MAX_VALUE) != 0
          || identity.bytes() >= 0 && identity.bytes() != bytes.length)
        throw new IOException("Incomplete or changed binary preview");
      requireCurrent();
      return bytes;
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid workspace binary preview", invalid);
    }
  }

  /**
   * The backend permits general file reads, so a relative string alone is insufficient.
   * Resolve the regular file, then ask the confined directory API to authorize its canonical
   * parent before reading. Final symlinks fail stat; outside-workspace ancestor links fail list.
   * The backend is nontransactional: identity/version checks reject an observed replacement,
   * but do not claim to provide an atomic filesystem sandbox against concurrent mutation.
   */
  private FileIdentity locate(Entry entry, boolean binary) throws IOException {
    requireCurrent();
    try {
      JsonObject stat = object(rpc.wire("workspaceFiles/stat", args(entry.path())));
      String absolute = string(stat, "absolutePath");
      String version = string(stat, "version");
      if (!safeAbsolute(absolute) || version.isEmpty() || version.length() > 4096)
        throw new IOException("Invalid workspace file identity");
      long bytes = optionalSize(stat, "bytes");
      if (binary && bytes > MAX_FILE_BYTES) throw new IOException("Native preview exceeds 8 MiB");
      int slash = absolute.lastIndexOf('/');
      String parent = slash == 0 ? "/" : absolute.substring(0, slash);
      Listing authorized = parseListing(rpc.wire("workspaceFiles/list", args(parent)));
      String relativeParent = parentOf(entry.path());
      if (!authorized.path().equals(relativeParent)
          || !absolute.substring(slash + 1).equals(entry.name())
          || authorized.entries().stream()
              .noneMatch(child -> child.name().equals(entry.name()) && "file".equals(child.type())))
        throw new IOException(
            "File moved outside the selected workspace location; refresh the folder");
      requireCurrent();
      return new FileIdentity(absolute, version, bytes);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid workspace file identity", invalid);
    }
  }

  private Listing parseListing(JsonElement response) throws IOException {
    try {
      JsonObject value = object(response);
      String path = string(value, "path");
      if (!safeRelative(path)) throw new IOException("Invalid workspace directory path");
      JsonArray rows = value.getAsJsonArray("entries");
      if (rows == null || rows.size() > 2000)
        throw new IOException("Invalid workspace listing size");
      List<Entry> entries = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (JsonElement item : rows) {
        JsonObject row = object(item);
        String name = string(row, "name");
        String type = string(row, "type");
        if (name.isEmpty()
            || name.length() > 255
            || name.indexOf('/') >= 0
            || !safeRelative(name)
            || !names.add(name)
            || !("directory".equals(type) || "file".equals(type) || "other".equals(type)))
          throw new IOException("Invalid workspace directory entry");
        entries.add(
            new Entry(
                sessionId,
                name,
                path.isEmpty() ? name : path + "/" + name,
                type,
                optionalSize(row, "size")));
      }
      return new Listing(path, List.copyOf(entries), bool(value, "truncated"));
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid workspace listing", invalid);
    }
  }

  private void requireEntry(Entry entry) {
    if (entry == null || !sessionId.equals(entry.sessionId) || !"file".equals(entry.type()))
      throw new IllegalArgumentException("Select a file from this session's workspace");
  }

  private JsonObject args(String path) {
    JsonObject args = new JsonObject();
    args.addProperty("workspaceFileScopeId", sessionId);
    args.addProperty("path", path);
    return args;
  }

  private void requireCurrent() throws IOException {
    if (!current.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("Workspace preview closed or service changed");
  }

  private byte[] bounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      requireCurrent();
      if (output.size() + count > limit) throw new IOException("Native preview exceeds size limit");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static void checkIdentity(JsonObject value, FileIdentity identity) throws IOException {
    if (!identity.absolutePath().equals(string(value, "absolutePath"))
        || !identity.version().equals(string(value, "version"))
        || optionalSize(value, "bytes") != identity.bytes())
      throw new IOException("File changed while opening; refresh the preview");
  }

  public static String parentOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static boolean safeRelative(String path) {
    if (path == null
        || path.length() > 4096
        || path.startsWith("/")
        || path.indexOf('\\') >= 0
        || path.indexOf(':') >= 0
        || controls(path)) return false;
    if (path.isEmpty()) return true;
    for (String component : path.split("/", -1))
      if (component.isEmpty() || component.equals(".") || component.equals("..")) return false;
    return true;
  }

  private static boolean safeAbsolute(String path) {
    return path.startsWith("/") && path.length() > 1 && safeRelative(path.substring(1));
  }

  private static boolean controls(String value) {
    for (int i = 0; i < value.length(); i++)
      if (Character.isISOControl(value.charAt(i))) return true;
    return false;
  }

  private static JsonObject object(JsonElement value) throws IOException {
    if (value == null || !value.isJsonObject()) throw new IOException("Expected an object");
    return value.getAsJsonObject();
  }

  private static String string(JsonObject object, String key) throws IOException {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IOException("Expected a string");
    return value.getAsString();
  }

  private static boolean bool(JsonObject object, String key) throws IOException {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IOException("Expected a boolean");
    return value.getAsBoolean();
  }

  private static long integer(JsonObject object, String key, long min, long max)
      throws IOException {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IOException("Expected an integer");
    try {
      long number = value.getAsBigDecimal().longValueExact();
      if (number < min || number > max) throw new IOException("Integer outside preview bounds");
      return number;
    } catch (ArithmeticException invalid) {
      throw new IOException("Invalid integer", invalid);
    }
  }

  private static long optionalSize(JsonObject object, String key) throws IOException {
    return object.has(key) ? integer(object, key, 0, Long.MAX_VALUE) : -1;
  }

  /** Reject decompression-bomb dimensions before allocating a bitmap. */
  public static int imageSampleSize(int width, int height) {
    if (width < 1
        || height < 1
        || width > 20000
        || height > 20000
        || (long) width * height > 40000000L)
      throw new IllegalArgumentException("Image dimensions exceed native preview limits");
    int sample = 1;
    while ((width + sample - 1) / sample > 1600
        || (height + sample - 1) / sample > 1600
        || ((long) width + sample - 1) / sample * ((height + sample - 1) / sample) > 2000000L)
      sample *= 2;
    return sample;
  }

  public static Kind kind(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp").contains(ext)) return Kind.IMAGE;
    if (ext.equals("pdf")) return Kind.PDF;
    if (Set.of(
                "txt",
                "md",
                "markdown",
                "csv",
                "tsv",
                "json",
                "jsonl",
                "yaml",
                "yml",
                "toml",
                "xml",
                "html",
                "htm",
                "svg",
                "css",
                "js",
                "jsx",
                "ts",
                "tsx",
                "java",
                "kt",
                "kts",
                "py",
                "sh",
                "bash",
                "c",
                "h",
                "cpp",
                "hpp",
                "rs",
                "go",
                "sql",
                "log",
                "ini",
                "conf",
                "r",
                "tex",
                "diff",
                "patch")
            .contains(ext)
        || name.equalsIgnoreCase("README")
        || name.equalsIgnoreCase("LICENSE")
        || name.equals("Dockerfile")) return Kind.TEXT;
    return Kind.UNSUPPORTED;
  }
}
