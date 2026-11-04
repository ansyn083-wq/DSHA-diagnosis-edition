package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import org.junit.Test;

public final class NativeChatDetailsTest {
  private static void apply(NativeChatDetails details, String json) {
    details.apply(JsonParser.parseString(json).getAsJsonObject());
  }

  @Test
  public void bindsResultToToolCallAndIgnoresReplayedSequence() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":2,"records":[
          {"event":{"seq":1,"type":"tool/call","data":{"callId":"c","name":"bash","arguments":"pwd"}}},
          {"event":{"seq":2,"type":"tool/result","data":{"message":{"toolCallId":"c","content":[{"type":"text","text":"/workspace"}]}}}}
        ]}
        """);
    assertEquals(1, details.tools().size());
    assertEquals("/workspace", details.tools().get(0).result());
    assertEquals("completed", details.tools().get(0).state());
    apply(
        details,
        """
        {"type":"event","event":{"seq":2,"type":"tool/call","data":{"callId":"other","name":"bash","arguments":"bad"}}}
        """);
    assertEquals(1, details.tools().size());
  }

  @Test
  public void keepsAttachmentsAsMetadataAndOnlyCountsModelUsage() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":4,"records":[
         {"event":{"seq":1,"type":"user/message","data":{"content":[{"type":"file","attachment":{"attachmentId":"f","name":"notes.txt","bytes":20}}]}}},
         {"event":{"seq":2,"type":"assistant/message","data":{"message":{"source":{"kind":"model"},"content":[{"type":"text","text":"ok"}]},"usage":{"inputTokens":12,"outputTokens":3}}}},
         {"event":{"seq":3,"type":"assistant/message","data":{"message":{"source":{"kind":"plugin"},"content":[{"type":"text","text":"internal"}]},"usage":{"inputTokens":100,"outputTokens":50}}}},
         {"event":{"seq":4,"type":"user/message","data":{"source":{"kind":"runtime-context"},"content":[{"type":"file","attachment":{"attachmentId":"private","name":"hidden","bytes":1}}]}}}
        ]}
        """);
    assertEquals(1, details.attachments().size());
    assertEquals("f", details.attachments().get(0).id());
    assertEquals(12, details.inputTokens());
    assertEquals(3, details.outputTokens());
    assertEquals(java.util.List.of(1L, 2L), details.messageSequences());
  }

  @Test
  public void snapshotsReplaceRatherThanDuplicateAndAuditDoesNotGrant() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":1,"records":[{"event":{"seq":1,"type":"approval/asked","data":{"id":"a","toolName":"bash"}}}]}
        """);
    assertTrue(details.tools().isEmpty());
    apply(details, "{\"type\":\"snapshot\",\"cursor\":0,\"records\":[]}");
    assertTrue(details.messageSequences().isEmpty());
    assertEquals(0, details.inputTokens());
  }

  @Test
  public void keepsResultAttachmentWhenCallIsOutsideLoadedHistory() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":5,"records":[{"event":{"seq":5,"type":"tool/result","data":{
          "message":{"toolCallId":"older","content":[{"type":"image","attachment":{"attachmentId":"img","bytes":24}}]}}}}]}
        """);
    assertEquals(1, details.attachments().size());
  }

  @Test
  public void retryRequiresKnownFailedTurnAndKeepsOriginalAttachmentReferences() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":3,"records":[
          {"event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"inspect"},{"type":"file","attachment":{"attachmentId":"f","name":"x.txt"}}]}}},
          {"event":{"seq":2,"type":"turn/start","data":{"turn":1}}},
          {"event":{"seq":3,"type":"turn/end","data":{"reason":{"kind":"error"}}}}
        ]}
        """);
    assertTrue(details.canRetry());
    assertEquals(2, details.retryContent().size());
    details.retryContent().remove(0);
    assertEquals(2, details.retryContent().size());
    apply(
        details,
        """
        {"type":"event","event":{"seq":4,"type":"turn/start","data":{"turn":2}}}
        """);
    assertFalse(details.canRetry());
  }

  @Test
  public void feedbackUsesDurableMessageIdNotEventSequence() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":27,"records":[
          {"event":{"seq":27,"type":"assistant/message","data":{"message":{"id":"message-stable-id","content":[{"type":"text","text":"answer"}]}}}}
        ]}
        """);
    assertEquals("message-stable-id", details.messageId(27));
    assertEquals("", details.messageId(26));
  }

  @Test
  public void failedScheduledTurnNeverRetriesEarlierSuccessfulHumanWork() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":6,"records":[
          {"event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"old successful command"}]}}},
          {"event":{"seq":2,"type":"turn/start","data":{}}},
          {"event":{"seq":3,"type":"turn/end","data":{"reason":{"kind":"complete"}}}},
          {"event":{"seq":4,"type":"user/message","data":{"source":{"kind":"schedule"},"content":[{"type":"text","text":"scheduled action"}]}}},
          {"event":{"seq":5,"type":"turn/start","data":{}}},
          {"event":{"seq":6,"type":"turn/end","data":{"reason":{"kind":"error"}}}}
        ]}
        """);
    assertFalse(details.canRetry());
  }

  @Test
  public void failedAutonomousContinuationNeverReusesFailedHumanCandidate() {
    NativeChatDetails details = new NativeChatDetails();
    apply(
        details,
        """
        {"type":"snapshot","cursor":5,"records":[
          {"event":{"seq":1,"type":"user/message","data":{"content":[{"type":"text","text":"old failed command"}]}}},
          {"event":{"seq":2,"type":"turn/start","data":{}}},
          {"event":{"seq":3,"type":"turn/end","data":{"reason":{"kind":"error"}}}},
          {"event":{"seq":4,"type":"turn/start","data":{}}},
          {"event":{"seq":5,"type":"turn/end","data":{"reason":{"kind":"error"}}}}
        ]}
        """);
    assertFalse(details.canRetry());
  }
}
