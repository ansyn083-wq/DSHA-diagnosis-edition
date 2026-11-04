package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;

public final class NativeChatRetryTest {
  @Test
  public void rehydratesImagesIntoPromptWireShape() throws Exception {
    JsonArray content =
        JsonParser.parseString(
                """
        [{"type":"text","text":"look"},{"type":"image","attachment":{"attachmentId":"img","name":"x.png"}}]
        """)
            .getAsJsonArray();
    var submitted =
        NativeChatRetry.attachments(
            content,
            id -> {
              assertEquals("img", id);
              return JsonParser.parseString(
                      """
          {"attachment":{"attachmentId":"img","mediaType":"image/png","name":"x.png"},"data":"AA=="}
          """)
                  .getAsJsonObject();
            });
    assertEquals(1, submitted.size());
    var image = submitted.get(0).getAsJsonObject();
    assertEquals("AA==", image.get("data").getAsString());
    assertEquals("image/png", image.get("mediaType").getAsString());
    assertFalse(image.has("attachment"));
  }

  @Test
  public void fileReferencesRequireNewUploadReceiptWithoutLosingAttachment() {
    var content =
        JsonParser.parseString(
                """
        [{"type":"file","attachment":{"attachmentId":"f","name":"x.txt"}}]
        """)
            .getAsJsonArray();
    assertTrue(NativeChatRetry.requiresFileUpload(content));
    assertThrows(
        IOException.class,
        () ->
            NativeChatRetry.attachments(
                content,
                id -> {
                  fail("File reference must never be interpreted as image or receipt");
                  return null;
                }));
  }

  @Test
  public void mismatchedOrUnsupportedImageCannotBeResent() {
    var content =
        JsonParser.parseString(
                """
        [{"type":"image","attachment":{"attachmentId":"img"}}]
        """)
            .getAsJsonArray();
    assertThrows(
        IOException.class,
        () ->
            NativeChatRetry.attachments(
                content,
                id ->
                    JsonParser.parseString(
                            """
        {"attachment":{"attachmentId":"different","mediaType":"image/png"},"data":"AA=="}
        """)
                        .getAsJsonObject()));
  }
}
