package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;

public final class NativeChatRenderQueueTest {
  @Test public void burstRendersLatestStateOnceAndCommitFlushesImmediately() {
    var queued = new ArrayList<Runnable>();
    var displayed = new ArrayList<Integer>();
    int[] current = {0};
    var updates = new NativeChatRenderQueue(queued::add, queued::remove, () -> displayed.add(current[0]));
    for (int i = 1; i <= 100; i++) { current[0] = i; updates.request(false); }
    assertEquals(1, queued.size());
    queued.remove(0).run();
    assertEquals(java.util.List.of(100), displayed);
    current[0] = 101;
    updates.request(false);
    updates.request(true);
    assertTrue(queued.isEmpty());
    assertEquals(java.util.List.of(100, 101), displayed);
  }
  @Test public void cancelDropsPendingWorkBeforeSessionSwitchOrDestroy() {
    var queued = new ArrayList<Runnable>();
    int[] renders = {0};
    var updates = new NativeChatRenderQueue(queued::add, queued::remove, () -> renders[0]++);
    updates.request(false);
    updates.cancel();
    assertTrue(queued.isEmpty());
    assertEquals(0, renders[0]);
    updates.request(false);
    queued.remove(0).run();
    assertEquals(1, renders[0]);
  }
}
