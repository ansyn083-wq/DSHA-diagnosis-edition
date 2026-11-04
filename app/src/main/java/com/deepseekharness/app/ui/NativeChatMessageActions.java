package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.TooltipCompat;
import com.deepseekharness.app.R;

/** 每条消息下的轻量图标操作；窄窗口保留完整触摸面积并允许横向滚动。 */
final class NativeChatMessageActions extends HorizontalScrollView {
  private final LinearLayout buttons;

  NativeChatMessageActions(Context context, boolean assistant) {
    super(context);
    setHorizontalScrollBarEnabled(false);
    setOverScrollMode(OVER_SCROLL_NEVER);
    LinearLayout.LayoutParams row =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    row.gravity = assistant ? Gravity.START : Gravity.END;
    row.topMargin = dp(2);
    row.bottomMargin = dp(8);
    setLayoutParams(row);
    buttons = new LinearLayout(context);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    addView(buttons, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
  }

  void addAction(int drawable, String label, Runnable click) {
    AppCompatImageButton button = new AppCompatImageButton(getContext());
    int target = getResources().getDimensionPixelSize(R.dimen.gpt_touch_min);
    button.setMinimumWidth(target);
    button.setMinimumHeight(target);
    button.setPadding(dp(13), dp(13), dp(13), dp(13));
    button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    button.setImageResource(drawable);
    button.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.gpt_muted)));
    TypedValue background = new TypedValue();
    if (getContext()
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)) {
      button.setBackgroundResource(background.resourceId);
    }
    button.setContentDescription(label);
    TooltipCompat.setTooltipText(button, label);
    button.setFocusable(true);
    button.setOnClickListener(view -> click.run());
    buttons.addView(button, new LinearLayout.LayoutParams(target, target));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
