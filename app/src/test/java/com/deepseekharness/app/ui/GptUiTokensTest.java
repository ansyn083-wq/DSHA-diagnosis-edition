package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GptUiTokensTest {
  @Test
  public void widthsMatchConversationAndFormPolicy() {
    assertEquals(800, GptUiTokens.maxWidthDp(GptUiTokens.PageKind.CHAT));
    assertEquals(760, GptUiTokens.maxWidthDp(GptUiTokens.PageKind.FORM));
    assertEquals(-1, GptUiTokens.maxWidthDp(GptUiTokens.PageKind.TERMINAL));
  }
}
