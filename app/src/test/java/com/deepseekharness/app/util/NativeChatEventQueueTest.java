package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import static com.deepseekharness.app.util.NativeChatEventQueue.Admission.*;
import org.junit.Test;

public final class NativeChatEventQueueTest {
  private NativeChatEventQueue<String> queue(int pending, int seen) {
    var queue = new NativeChatEventQueue<String>(pending, seen);
    queue.reset("client", "session");
    return queue;
  }

  private NativeChatEventQueue.Admission add(NativeChatEventQueue<String> q, String id) {
    return q.offer("client", "session", id, id);
  }

  @Test
  public void preservesMixedInteractionArrivalOrder() {
    var q = queue(3, 6);
    assertEquals(QUEUED, q.offer("client", "session", "question", "question-value"));
    assertEquals(QUEUED, q.offer("client", "session", "approval", "approval-value"));
    assertEquals("question-value", q.peek().value());
    assertEquals("question", q.remove("question").eventId());
    assertEquals("approval", q.peek().eventId());
  }

  @Test
  public void duplicateActiveEventNeverReplacesOrAdds() {
    var q = queue(2, 4);
    add(q, "one");
    assertEquals(DUPLICATE, q.offer("client", "session", "one", "changed"));
    assertEquals("one", q.peek().value());
    assertEquals(1, q.size());
  }

  @Test
  public void cancellationCanRemoveWaitingOrActiveByExactId() {
    var q = queue(3, 6);
    add(q, "one");
    add(q, "two");
    add(q, "three");
    assertEquals("two", q.remove("two").value());
    assertEquals("one", q.peek().eventId());
    assertNull(q.remove("missing"));
    q.remove("one");
    assertEquals("three", q.peek().eventId());
  }

  @Test
  public void removedEventsKeepTombstonesAgainstLateDuplicate() {
    var q = queue(1, 4);
    add(q, "one");
    q.remove("one");
    assertEquals(DUPLICATE, add(q, "one"));
    assertNull(q.peek());
  }

  @Test
  public void boundedOverflowDelegatesOnlyOnceWithoutEviction() {
    var q = queue(1, 4);
    add(q, "one");
    assertEquals(DELEGATE, add(q, "two"));
    assertEquals(DUPLICATE, add(q, "two"));
    assertEquals("one", q.peek().eventId());
    assertEquals(1, q.size());
  }

  @Test
  public void exhaustedTombstonesRequireReconnectWithoutDroppingOrDeciding() {
    var q = queue(1, 2);
    add(q, "one");
    assertEquals(DELEGATE, add(q, "two"));
    assertEquals(RESET_REQUIRED, add(q, "three"));
    assertEquals(RESET_REQUIRED, add(q, "three"));
    assertEquals(DUPLICATE, add(q, "two"));
    assertEquals(1, q.size());
  }

  @Test
  public void staleClientAndOtherSessionCannotEnterQueue() {
    var q = queue(2, 4);
    assertEquals(SCOPE_MISMATCH, q.offer("old-client", "session", "one", "value"));
    assertEquals(SCOPE_MISMATCH, q.offer("client", "other-session", "one", "value"));
    assertEquals(0, q.size());
    assertEquals(QUEUED, add(q, "one"));
  }

  @Test
  public void malformedIdentityOrMissingValueCannotEnterQueue() {
    var q = queue(2, 4);
    assertEquals(INVALID, add(q, ""));
    assertEquals(INVALID, add(q, " "));
    assertEquals(INVALID, add(q, "x".repeat(4097)));
    assertEquals(INVALID, q.offer("client", "session", "one", null));
    assertEquals(INVALID, q.offer(null, "session", "one", "value"));
    assertEquals(0, q.size());
  }

  @Test
  public void resetSeparatesClientAndSessionGenerations() {
    var q = queue(2, 4);
    add(q, "one");
    q.reset("new-client", "new-session");
    assertNull(q.peek());
    assertEquals(SCOPE_MISMATCH, add(q, "two"));
    assertEquals(QUEUED, q.offer("new-client", "new-session", "one", "new"));
    assertEquals("new", q.peek().value());
  }

  @Test
  public void clearMakesQueueUnavailableUntilExplicitReset() {
    var q = queue(2, 4);
    add(q, "one");
    q.clear();
    assertEquals(0, q.size());
    assertNull(q.peek());
    assertEquals(SCOPE_MISMATCH, add(q, "one"));
    q.reset("client", "session");
    assertEquals(QUEUED, add(q, "one"));
  }

  @Test
  public void invalidResetCannotKeepPreviousAuthority() {
    var q = queue(2, 4);
    add(q, "one");
    assertThrows(IllegalArgumentException.class, () -> q.reset("", "session"));
    assertEquals(0, q.size());
    assertEquals(SCOPE_MISMATCH, add(q, "one"));
  }

  @Test
  public void unsupportedDelegationSharesDuplicateLedgerWithoutQueueingPayload() {
    var q = queue(1, 3);
    assertEquals(DELEGATE, q.delegate("client", "other-agent"));
    assertEquals(DUPLICATE, q.delegate("client", "other-agent"));
    assertEquals(DUPLICATE, add(q, "other-agent"));
    assertEquals(0, q.size());
    assertEquals(SCOPE_MISMATCH, q.delegate("old-client", "other"));
    assertEquals(INVALID, q.delegate("client", ""));
    assertEquals(QUEUED, add(q, "question"));
    assertEquals(DUPLICATE, q.delegate("client", "question"));
    assertEquals(DELEGATE, q.delegate("client", "unsupported"));
    assertEquals(RESET_REQUIRED, q.delegate("client", "full"));
  }

  @Test
  public void productionCapacityIsBounded() {
    var q = new NativeChatEventQueue<String>();
    q.reset("client", "session");
    for (int i = 0; i < 32; i++) assertEquals(QUEUED, add(q, "event-" + i));
    assertEquals(DELEGATE, add(q, "event-32"));
    for (int i = 33; i < 256; i++) assertEquals(DELEGATE, add(q, "event-" + i));
    assertEquals(RESET_REQUIRED, add(q, "event-256"));
  }
}
