package com.deepseekharness.app.ui;

import android.widget.ScrollView;
import androidx.core.view.OneShotPreDrawListener;

/** 等本帧测量完成后跟随末条消息，不打断已经上滑阅读的用户。 */
final class NativeChatBottomFollow {
  private NativeChatBottomFollow() {}

  static void afterLayout(ScrollView scroll) {
    int requestedY = scroll.getScrollY();
    OneShotPreDrawListener.add(
        scroll,
        () -> {
          if (scroll.getChildCount() == 0) return;
          // 请求之后发生了滚动时保留阅读位置；内容缩短导致的末尾钳位仍可跟随。
          if (scroll.getScrollY() != requestedY && scroll.canScrollVertically(1)) return;
          scroll.scrollTo(0, scroll.getChildAt(0).getBottom());
        });
  }

  static void keepVisibleOnResize(ScrollView scroll) {
    scroll.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int oldHeight = oldBottom - oldTop;
          // 只处理同宽度的视口收缩，例如输入框长高或键盘出现。
          if (oldHeight <= 0
              || bottom - top >= oldHeight
              || right - left != oldRight - oldLeft
              || scroll.getChildCount() == 0) return;
          int oldVisibleHeight = oldHeight - scroll.getPaddingTop() - scroll.getPaddingBottom();
          if (scroll.getScrollY() + oldVisibleHeight >= scroll.getChildAt(0).getHeight()) {
            afterLayout(scroll);
          }
        });
  }
}
