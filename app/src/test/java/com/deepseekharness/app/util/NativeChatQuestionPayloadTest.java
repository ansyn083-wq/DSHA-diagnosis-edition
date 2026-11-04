package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.Test;

public final class NativeChatQuestionPayloadTest {
  @Test
  public void rejectsEveryMalformedFieldBeforeRenderingOrTimedClaim() {
    for (String question :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"id\":\"q\"}",
            "{\"id\":2,\"question\":\"Q\"}",
            "{\"id\":\"q\",\"question\":null}",
            "{\"id\":\"q\",\"question\":\"Q\",\"detail\":null}",
            "{\"id\":\"q\",\"question\":\"Q\",\"multiSelect\":\"true\"}",
            "{\"id\":\"q\",\"question\":\"Q\",\"options\":{}}",
            "{\"id\":\"q\",\"question\":\"Q\",\"options\":[null]}",
            "{\"id\":\"q\",\"question\":\"Q\",\"options\":[{}]}",
            "{\"id\":\"q\",\"question\":\"Q\",\"options\":[{\"label\":\"A\",\"description\":null}]}")) {
      assertFalse(
          question,
          NativeChatQuestionPayload.renderable(
              JsonParser.parseString("[" + question + "]").getAsJsonArray()));
    }
  }

  @Test
  public void retainsBackendValidEmptyStringsAndAnyOptionCount() {
    JsonArray questions =
        JsonParser.parseString(
                "[{\"id\":\"\",\"question\":\"\",\"detail\":\"\",\"multiSelect\":false,\"options\":[]}]")
            .getAsJsonArray();
    JsonArray options = questions.get(0).getAsJsonObject().getAsJsonArray("options");
    for (int i = 0; i < 20; i++) {
      JsonObject option = new JsonObject();
      option.addProperty("label", "");
      option.addProperty("description", "");
      options.add(option);
    }
    assertTrue(NativeChatQuestionPayload.renderable(questions));
  }

  @Test
  public void rejectsMissingOrEmptyBatch() {
    assertFalse(NativeChatQuestionPayload.renderable(null));
    assertFalse(NativeChatQuestionPayload.renderable(new JsonArray()));
  }
}
