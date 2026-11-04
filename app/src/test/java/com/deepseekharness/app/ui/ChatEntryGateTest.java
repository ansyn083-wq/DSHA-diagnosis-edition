package com.deepseekharness.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deepseekharness.app.util.ChatEntryPolicy;
import org.junit.Test;

public class ChatEntryGateTest {
  @Test
  public void authenticatedReadyEventIsConsumedOnlyOnce() {
    ChatSetupCoordinator.EntryGate gate = new ChatSetupCoordinator.EntryGate();
    assertFalse(gate.consume(ChatEntryPolicy.Action.READY, ChatEntryPolicy.Event.RENDER));
    assertTrue(
        gate.consume(ChatEntryPolicy.Action.READY, ChatEntryPolicy.Event.AUTHENTICATED_START));
    assertFalse(
        gate.consume(ChatEntryPolicy.Action.READY, ChatEntryPolicy.Event.AUTHENTICATED_START));
    assertFalse(gate.consume(ChatEntryPolicy.Action.CHECK, ChatEntryPolicy.Event.RESTORED));
  }
}
