package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GptResponsiveTest {
  @Test
  public void exactBreakpointsHaveOneMode() {
    assertEquals(GptResponsive.Mode.PHONE, GptResponsive.classify(760));
    assertEquals(GptResponsive.Mode.DRAWER, GptResponsive.classify(761));
    assertEquals(GptResponsive.Mode.DRAWER, GptResponsive.classify(1023));
    assertEquals(GptResponsive.Mode.SIDEBAR, GptResponsive.classify(1024));
  }
}
