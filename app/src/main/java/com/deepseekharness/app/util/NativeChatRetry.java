package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

/** Converts durable image references back into the gateway's prompt upload shape. */
public final class NativeChatRetry {
  private NativeChatRetry() {}

  public interface ImageLoader {
    JsonObject load(String id) throws IOException;
  }

  private static final Set<String> IMAGE_TYPES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

  public static boolean requiresFileUpload(JsonArray content) {
    for (JsonElement part : content) {
      if (part.isJsonObject() && "file".equals(text(part.getAsJsonObject(), "type"))) return true;
    }
    return false;
  }

  public static JsonArray attachments(JsonArray content, ImageLoader loader) throws IOException {
    if (requiresFileUpload(content))
      throw new IOException("File attachments require a new upload receipt");
    JsonArray submitted = new JsonArray();
    for (JsonElement part : content) {
      if (!part.isJsonObject()) throw new IOException("Invalid retry content");
      JsonObject block = part.getAsJsonObject();
      String type = text(block, "type");
      if ("text".equals(type)) continue;
      if (!"image".equals(type)
          || !block.has("attachment")
          || !block.get("attachment").isJsonObject()) {
        throw new IOException("Unsupported retry content");
      }
      String id = text(block.getAsJsonObject("attachment"), "attachmentId");
      if (id.isBlank()) throw new IOException("Missing image identity");
      JsonObject stored = loader.load(id);
      JsonObject ref =
          stored != null && stored.has("attachment") && stored.get("attachment").isJsonObject()
              ? stored.getAsJsonObject("attachment")
              : null;
      if (ref == null
          || !id.equals(text(ref, "attachmentId"))
          || !IMAGE_TYPES.contains(text(ref, "mediaType"))
          || text(stored, "data").isBlank())
        throw new IOException("Invalid durable image response");
      JsonObject image = new JsonObject();
      image.addProperty("type", "image");
      image.addProperty("mediaType", text(ref, "mediaType"));
      image.addProperty("data", text(stored, "data"));
      if (!text(ref, "name").isBlank()) image.addProperty("name", text(ref, "name"));
      submitted.add(image);
    }
    return submitted;
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }
}
