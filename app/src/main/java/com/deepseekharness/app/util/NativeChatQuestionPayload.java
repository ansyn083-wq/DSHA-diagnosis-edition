package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 提问入队前只核验原生界面实际读取的字段类型，不限制有效选项数量。 */
public final class NativeChatQuestionPayload {
  private NativeChatQuestionPayload() {}

  public static boolean renderable(JsonArray questions) {
    if (questions == null || questions.isEmpty()) return false;
    for (JsonElement entry : questions) {
      if (!entry.isJsonObject()) return false;
      JsonObject question = entry.getAsJsonObject();
      if (!string(question, "id", false)
          || !string(question, "question", false)
          || !string(question, "detail", true)) return false;
      JsonElement multiple = question.get("multiSelect");
      if (multiple != null
          && (!multiple.isJsonPrimitive() || !multiple.getAsJsonPrimitive().isBoolean()))
        return false;
      JsonElement options = question.get("options");
      if (options == null) continue;
      if (!options.isJsonArray()) return false;
      for (JsonElement value : options.getAsJsonArray()) {
        if (!value.isJsonObject()) return false;
        JsonObject option = value.getAsJsonObject();
        if (!string(option, "label", false) || !string(option, "description", true)) return false;
      }
    }
    return true;
  }

  private static boolean string(JsonObject object, String key, boolean optional) {
    JsonElement value = object.get(key);
    return value == null
        ? optional
        : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
  }
}
