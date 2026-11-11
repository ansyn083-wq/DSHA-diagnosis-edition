package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NativeChatRenderPlanTest {
  @Test
  public void appendingToLongHistoryKeepsEveryExistingRow() {
    var history = new ArrayList<NativeChatTranscript.Message>();
    var sequences = new ArrayList<Long>();
    for (int i = 0; i < 100; i++) {
      history.add(new NativeChatTranscript.Message(i % 2 == 1, "Message " + i));
      sequences.add((long) i);
    }
    var next = new ArrayList<>(history);
    var nextSequences = new ArrayList<>(sequences);
    next.add(new NativeChatTranscript.Message(true, "New reply"));
    nextSequences.add(100L);
    assertEquals(100, NativeChatRenderPlan.prefix(history, sequences, next, nextSequences));
  }

  @Test
  public void changedDurationRebuildsOnlyLastRowAndShiftedWindowCannotReuseWrongRows() {
    var first = new NativeChatTranscript.Message(false, "Question");
    var reply = new NativeChatTranscript.Message(true, "Reply", "Reasoning", -1);
    assertEquals(
        1,
        NativeChatRenderPlan.prefix(
            List.of(first, reply),
            List.of(2L, 3L),
            List.of(first, new NativeChatTranscript.Message(true, "Reply", "Reasoning", 1200)),
            List.of(2L, 3L)));
    assertEquals(
        0,
        NativeChatRenderPlan.prefix(
            List.of(first, reply), List.of(2L, 3L), List.of(reply), List.of(3L)));
    assertEquals(
        0, NativeChatRenderPlan.prefix(List.of(reply), List.of(3L), List.of(reply), List.of(9L)));
  }
}
