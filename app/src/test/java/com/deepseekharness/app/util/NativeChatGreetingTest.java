package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.HashSet;
import org.junit.Test;

/** 选择由本地小时和显式随机值决定，不读取时钟、Android 或用户信息。 */
public final class NativeChatGreetingTest {
  @Test
  public void localHourBoundariesSelectOnlyMatchingTimeOfDay() {
    int[] hours = {0, 4, 5, 11, 12, 17, 18, 22, 23};
    String[] ids = {
      "night-0",
      "night-0",
      "morning-0",
      "morning-0",
      "afternoon-0",
      "afternoon-0",
      "evening-0",
      "evening-0",
      "night-0"
    };
    for (int i = 0; i < hours.length; i++) {
      assertEquals(ids[i], NativeChatGreeting.choose(hours[i], 16, "").id());
    }
  }

  @Test
  public void deterministicChoicesProvideFortyEightDistinctBilingualGreetings() {
    HashSet<String> ids = new HashSet<>(), chinese = new HashSet<>(), english = new HashSet<>();
    for (int hour : new int[] {3, 9, 14, 20}) {
      HashSet<String> atThisTime = new HashSet<>();
      for (int entropy = 0; entropy < 24; entropy++) {
        var choice = NativeChatGreeting.choose(hour, entropy, "");
        assertEquals(choice, NativeChatGreeting.choose(hour, entropy, ""));
        assertFalse(choice.chinese().isBlank());
        assertFalse(choice.english().isBlank());
        assertTrue(atThisTime.add(choice.id()));
        assertEquals(choice, NativeChatGreeting.restore(hour, choice.id()));
        ids.add(choice.id());
        chinese.add(choice.chinese());
        english.add(choice.english());
      }
    }
    assertEquals(48, ids.size());
    assertEquals(48, chinese.size());
    assertEquals(48, english.size());
  }

  @Test
  public void repeatedRandomValueNeverRepeatsPreviousGreeting() {
    for (int hour : new int[] {3, 9, 14, 20})
      for (long entropy : new long[] {0, 8, 15, 16, 23, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
        var first = NativeChatGreeting.choose(hour, entropy, "");
        var second = NativeChatGreeting.choose(hour, entropy, first.id());
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.chinese(), second.chinese());
      }
  }

  @Test
  public void rotationRestoresSameGreetingButChangedTimeRejectsOldSalutation() {
    var morning = NativeChatGreeting.restore(9, "morning-0");
    assertEquals("早上好，今天从哪里开始？", morning.chinese());
    assertEquals(morning, NativeChatGreeting.restore(10, morning.id()));
    assertNull(NativeChatGreeting.restore(15, morning.id()));
    var generic = NativeChatGreeting.choose(9, 0, "");
    assertEquals(generic, NativeChatGreeting.restore(23, generic.id()));
    assertNull(NativeChatGreeting.restore(9, "unknown"));
    assertNull(NativeChatGreeting.restore(9, null));
  }

  @Test
  public void invalidLocalHoursAreRejectedRatherThanChoosingWrongGreeting() {
    assertThrows(IllegalArgumentException.class, () -> NativeChatGreeting.choose(-1, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> NativeChatGreeting.choose(24, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> NativeChatGreeting.restore(99, "general-0"));
  }
}
