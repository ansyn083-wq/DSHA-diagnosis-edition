package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import org.junit.Test;

public final class NativeChatTranscriptTest {
  @Test
  public void restoresMessagesAndReplacesPartialTextWithCommittedReply() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(
        chat,
        """
        {"type":"snapshot","cursor":3,"records":[
          {"type":"event","event":{"type":"user/message","seq":2,"data":{"content":[{"type":"text","text":"你好"}]}}},
          {"type":"event","event":{"type":"turn/start","seq":3,"data":{"turn":1}}}
        ],"assistantStream":{"revision":2,"activeAttempt":{"attemptId":"a","turn":1,"step":0,
          "nextIndex":1,"stream":[{"type":"text-chunks","texts":["你"]}]}}}
        """);
    assertEquals("你好", chat.messages().get(0).text());
    assertEquals("你", chat.partial());
    apply(
        chat,
        """
        {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":3,
          "index":1,"chunk":{"type":"text-delta","index":0,"text":"好"}}}
        """);
    assertEquals("你好", chat.partial());
    apply(
        chat,
        """
        {"type":"event","event":{"type":"assistant/message","seq":4,"data":{"turn":1,"step":0,
          "message":{"content":[{"type":"text","text":"你好！"}]}}}}
        """);
    assertEquals("", chat.partial());
    assertEquals(2, chat.messages().size());
    assertTrue(chat.messages().get(1).assistant());
    assertEquals("你好！", chat.messages().get(1).text());
  }

  @Test
  public void hidesContextFromHistoryAndLiveChatWithoutSkippingEventSequence() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(
        chat,
        """
        {"type":"snapshot","cursor":3,"records":[
          {"event":{"type":"user/message","seq":1,"data":{"source":{"kind":"user"},
            "content":[{"type":"text","text":"Current runtime context."}]}}},
          {"event":{"type":"user/message","seq":2,"data":{"source":{"kind":"runtime-context","form":"snapshot"},
            "content":[{"type":"text","text":"Current runtime context."}]}}},
          {"event":{"type":"user/message","seq":3,"data":{"source":{"kind":"time-context","form":"snapshot"},
            "content":[{"type":"text","text":"Time sampled while preparing turn 1"}]}}}
        ]}
        """);
    assertEquals(
        java.util.List.of(new NativeChatTranscript.Message(false, "Current runtime context.")),
        chat.messages());
    apply(
        chat,
        """
        {"type":"event","event":{"type":"user/message","seq":4,"data":{"source":{"kind":"time-context"},
          "content":[{"type":"text","text":"Time sampled while preparing turn 2"}]}}}
        """);
    apply(
        chat,
        """
        {"type":"event","event":{"type":"assistant/message","seq":5,"data":{
          "message":{"content":[{"type":"text","text":"在的。"}]}}}}
        """);
    assertEquals(
        java.util.List.of(
            new NativeChatTranscript.Message(false, "Current runtime context."),
            new NativeChatTranscript.Message(true, "在的。")),
        chat.messages());
  }

  @Test
  public void gapIsRejectedInsteadOfSilentlyLosingEvents() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(chat, "{\"type\":\"snapshot\",\"cursor\":3,\"records\":[]}");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                chat,
                "{\"type\":\"event\",\"event\":{\"type\":\"turn/end\",\"seq\":5,\"data\":{}}}"));
  }

  @Test
  public void tracksTurnDurationWithoutMixingReasoningOrInternalAssistantTextIntoReply() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(
        chat,
        """
        {"type":"snapshot","cursor":0,"records":[
          {"event":{"type":"turn/start","seq":0,"time":1000,"data":{"turn":1}}}
        ],"assistantStream":{"revision":1,"activeAttempt":{"attemptId":"a","turn":1,"step":0,
          "nextIndex":0,"stream":[]}}}
        """);
    assertTrue(chat.running());
    assertEquals(1000, chat.startedAt());
    apply(
        chat,
        """
        {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":2,
          "index":0,"chunk":{"type":"reasoning-delta","text":"private scratch text"}}}
        """);
    assertTrue(chat.thinking());
    assertEquals("", chat.partial());
    apply(
        chat,
        """
        {"type":"event","event":{"type":"assistant/message","seq":1,"data":{"message":{
          "source":{"kind":"plugin"},"content":[{"type":"text","text":"internal trace"}]}}}}
        """);
    assertTrue(chat.messages().isEmpty());
    apply(
        chat,
        """
        {"type":"event","event":{"type":"turn/end","seq":2,"time":3500,"data":{"turn":1}}}
        """);
    assertFalse(chat.running());
    assertFalse(chat.thinking());
    assertEquals(2500, chat.duration());
  }

  @Test
  public void associatesReasoningAndDurationWithTheCorrectReplyAfterReconnect() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(
        chat,
        """
        {"type":"snapshot","cursor":6,"records":[
          {"event":{"type":"turn/start","seq":1,"time":1000,"data":{"turn":1}}},
          {"event":{"type":"assistant/message","seq":2,"data":{"turn":1,"step":0,"message":{
            "source":{"kind":"model"},"content":[
              {"type":"reasoning","text":"Compare the two approaches."},
              {"type":"text","text":"Use the first approach."}]}}}},
          {"event":{"type":"turn/end","seq":3,"time":3200,"data":{"turn":1}}},
          {"event":{"type":"turn/start","seq":4,"time":4000,"data":{"turn":2}}},
          {"event":{"type":"assistant/message","seq":5,"data":{"turn":2,"step":0,"message":{
            "source":{"kind":"model"},"content":[{"type":"text","text":"A direct reply."}]}}}},
          {"event":{"type":"turn/end","seq":6,"time":4600,"data":{"turn":2}}}
        ]}
        """);
    assertEquals("Use the first approach.", chat.messages().get(0).text());
    assertEquals("Compare the two approaches.", chat.messages().get(0).reasoning());
    assertEquals(2200, chat.messages().get(0).turnMillis());
    assertEquals("", chat.messages().get(1).reasoning());
    assertEquals(600, chat.messages().get(1).turnMillis());
  }

  @Test
  public void restoresAndUpdatesVisibleReasoningSeparatelyFromLiveReply() {
    NativeChatTranscript chat = new NativeChatTranscript();
    apply(
        chat,
        """
        {"type":"snapshot","cursor":3,"records":[],
          "assistantStream":{"revision":1,"activeAttempt":{"attemptId":"a","turn":1,"step":0,
          "nextIndex":1,"stream":[{"type":"reasoning-chunks","texts":["Consider "]}]}}}
        """);
    assertEquals("Consider ", chat.partialReasoning());
    assertTrue(chat.thinking());
    apply(
        chat,
        """
        {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":2,"index":1,
          "chunk":{"type":"reasoning-delta","text":"the options."}}}
        """);
    apply(
        chat,
        """
        {"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","revision":3,"index":2,
          "chunk":{"type":"text-delta","text":"First."}}}
        """);
    assertEquals("Consider the options.", chat.partialReasoning());
    assertEquals("First.", chat.partial());
    assertFalse(chat.thinking());
    apply(
        chat,
        """
        {"type":"event","event":{"type":"assistant/message","seq":4,"data":{"turn":1,"step":0,
          "message":{"source":{"kind":"model"},"content":[{"type":"text","text":"First."}]}}}}
        """);
    assertEquals("Consider the options.", chat.messages().get(0).reasoning());
    assertEquals("", chat.partialReasoning());
  }

  private static void apply(NativeChatTranscript chat, String frame) {
    chat.apply(JsonParser.parseString(frame).getAsJsonObject());
  }
}
