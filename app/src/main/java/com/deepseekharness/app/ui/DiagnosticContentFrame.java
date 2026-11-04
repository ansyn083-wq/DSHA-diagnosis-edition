package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 当前窗口限宽并保持滚动；不依赖屏幕型号。 */
public final class DiagnosticContentFrame extends FrameLayout {
  public DiagnosticContentFrame(Context c, AttributeSet a) {
    super(c, a);
  }

  @Override
  protected void onMeasure(int w, int h) {
    int width = Math.max(0, MeasureSpec.getSize(w) - getPaddingLeft() - getPaddingRight());
    int cap =
        Math.round(
            GptUiTokens.maxWidthDp(GptUiTokens.PageKind.CHAT)
                * getResources().getDisplayMetrics().density);
    for (int i = 0; i < getChildCount(); i++)
      ((LayoutParams) getChildAt(i).getLayoutParams()).width = Math.min(width, cap);
    super.onMeasure(w, h);
  }
}
