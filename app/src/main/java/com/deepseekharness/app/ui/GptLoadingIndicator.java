package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import com.deepseekharness.app.R;

/** Quiet orbit that respects the system animation scale. */
public final class GptLoadingIndicator extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 360f);
  private float angle;

  public GptLoadingIndicator(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeWidth(dp(2));
    paint.setColor(context.getColor(R.color.gpt_action));
    animator.setDuration(1400);
    animator.setRepeatCount(ValueAnimator.INFINITE);
    animator.addUpdateListener(
        value -> {
          angle = (float) value.getAnimatedValue();
          invalidate();
        });
    setContentDescription(context.getString(R.string.native_chat_preparing_accessibility));
  }

  private float dp(float value) {
    return value * getResources().getDisplayMetrics().density;
  }

  private boolean animationsEnabled() {
    try {
      return Settings.Global.getFloat(
              getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
          > 0f;
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (animationsEnabled()) animator.start();
  }

  @Override
  protected void onDetachedFromWindow() {
    animator.cancel();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(5);
    paint.setAlpha(45);
    canvas.drawCircle(cx, cy, r, paint);
    paint.setAlpha(255);
    canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 92f, false, paint);
    paint.setStyle(Paint.Style.FILL);
    canvas.save();
    canvas.rotate(45f, cx, cy);
    canvas.drawRect(cx - dp(3), cy - dp(12), cx + dp(3), cy + dp(12), paint);
    canvas.drawRect(cx - dp(12), cy - dp(3), cx + dp(12), cy + dp(3), paint);
    canvas.restore();
    paint.setStyle(Paint.Style.STROKE);
  }
}
