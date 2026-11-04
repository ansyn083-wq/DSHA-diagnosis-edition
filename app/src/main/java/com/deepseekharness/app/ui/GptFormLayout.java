package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Centers form content and prevents tablet layouts from stretching rows edge to edge. */
public final class GptFormLayout extends LinearLayout {
  public GptFormLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int cap =
        Math.round(
            GptUiTokens.maxWidthDp(GptUiTokens.PageKind.FORM)
                * getResources().getDisplayMetrics().density);
    int width = Math.min(MeasureSpec.getSize(widthSpec), cap);
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
  }
}
