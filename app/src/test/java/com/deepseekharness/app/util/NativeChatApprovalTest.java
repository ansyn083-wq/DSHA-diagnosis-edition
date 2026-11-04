package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import org.junit.Test;

public final class NativeChatApprovalTest {
  private static final String FRAME =
      """
      {"type":"waterfall","event":"approval/request","eventId":"e1","agentId":"s1",
       "request":{"toolName":"bash","callId":"c1","reason":"Run the selected command"}}
      """;

  @Test
  public void onlyCurrentSessionApprovalCanBeClaimed() {
    var frame = JsonParser.parseString(FRAME).getAsJsonObject();
    assertNull(NativeChatApproval.from("client", "other", frame));
    assertNull(NativeChatApproval.from("", "s1", frame));
    var approval = NativeChatApproval.from("client", "s1", frame);
    assertEquals("bash", approval.toolName());
    assertEquals("c1", approval.callId());
    assertTrue(approval.claim());
    assertFalse(approval.claim());
  }

  @Test
  public void outcomeIsBoundToExactEventAndExplicitChoice() {
    var approval =
        NativeChatApproval.from("client", "s1", JsonParser.parseString(FRAME).getAsJsonObject());
    var args = approval.result(false);
    assertEquals("client", args.get("clientId").getAsString());
    assertEquals("e1", args.get("eventId").getAsString());
    assertEquals("rejected", args.getAsJsonObject("outcome").get("value").getAsString());
    assertEquals(
        "allowed-once",
        approval.result(true).getAsJsonObject("outcome").get("value").getAsString());
  }

  @Test
  public void malformedAndOtherEventsCannotBecomeApproval() {
    assertNull(
        NativeChatApproval.from("client", "s1", JsonParser.parseString("{}").getAsJsonObject()));
    var frame = JsonParser.parseString(FRAME).getAsJsonObject();
    frame.addProperty("event", "user-questions/request");
    assertNull(NativeChatApproval.from("client", "s1", frame));
    frame.addProperty("event", "approval/request");
    frame.getAsJsonObject("request").remove("toolName");
    assertNull(NativeChatApproval.from("client", "s1", frame));
  }
}
