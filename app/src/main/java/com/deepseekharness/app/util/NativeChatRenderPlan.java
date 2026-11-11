package com.deepseekharness.app.util;

import java.util.List;

/** 相同持久消息保留已有视图；窗口裁剪、正文或来源序号改变时只重建受影响后缀。 */
public final class NativeChatRenderPlan {
  private NativeChatRenderPlan() {}

  public static int prefix(
      List<NativeChatTranscript.Message> previous, List<Long> previousSequences,
      List<NativeChatTranscript.Message> next, List<Long> nextSequences) {
    int count = Math.min(previous.size(), next.size());
    int index = 0;
    while (index < count
        && previous.get(index).equals(next.get(index))
        && sequence(previousSequences, index) == sequence(nextSequences, index)) index++;
    return index;
  }

  public static long sequence(List<Long> sequences, int index) {
    return index < sequences.size() ? sequences.get(index) : -1;
  }
}
