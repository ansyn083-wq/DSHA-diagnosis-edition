package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 模型已返回的思考内容；轻量箭头展开，不改变正文或消息归属。 */
final class NativeChatReasoningView extends LinearLayout {
  private final LinearLayout toggle;
  private final ImageView arrow;
  private final TextView body;
  private String reasoning = "";
  private boolean expanded;

  NativeChatReasoningView(Context context) {
    super(context);
    setOrientation(VERTICAL);
    setPadding(dp(12), 0, dp(12), 0);
    toggle = new LinearLayout(context);
    toggle.setOrientation(HORIZONTAL);
    toggle.setGravity(Gravity.CENTER_VERTICAL);
    toggle.setMinimumHeight(dp(48));
    toggle.setPaddingRelative(dp(4), 0, dp(12), 0);
    toggle.setFocusable(true);
    TypedValue background = new TypedValue();
    if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true))
      toggle.setBackgroundResource(background.resourceId);
    arrow = new androidx.appcompat.widget.AppCompatImageView(context);
    arrow.setImageResource(R.drawable.ic_chat_reasoning_chevron);
    arrow.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.gpt_muted)));
    arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    toggle.addView(arrow, new LayoutParams(dp(18), dp(18)));
    TextView label = new TextView(context);
    label.setText(UiText.choose("思考过程", "Reasoning"));
    label.setTextSize(13);
    label.setTextColor(context.getColor(R.color.gpt_muted));
    label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    var labelParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    labelParams.setMarginStart(dp(6));
    toggle.addView(label, labelParams);
    addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    toggle.setOnClickListener(view -> setExpanded(!expanded));
    body = new TextView(context);
    body.setBackgroundResource(R.drawable.gpt_surface_raised);
    body.setPadding(dp(14), dp(12), dp(14), dp(12));
    body.setTextSize(14);
    body.setTextColor(context.getColor(R.color.gpt_text));
    body.setTextIsSelectable(true);
    body.setLineSpacing(dp(3), 1f);
    var params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(2);
    addView(body, params);
    render();
  }

  void showReasoning(String text) {
    if (reasoning.equals(text)) return;
    reasoning = text;
    if (text.isBlank()) expanded = false;
    render();
  }

  boolean isExpanded() {
    return expanded;
  }

  void setExpanded(boolean value) {
    expanded = value && !reasoning.isBlank();
    render();
  }

  private void render() {
    boolean available = !reasoning.isBlank();
    setVisibility(available ? VISIBLE : GONE);
    arrow.setRotation(expanded ? 90 : 0);
    toggle.setContentDescription(expanded
        ? UiText.choose("收起思考过程", "Hide reasoning")
        : UiText.choose("展开思考过程", "Show reasoning"));
    ViewCompat.setStateDescription(toggle,
        expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
    body.setVisibility(available && expanded ? VISIBLE : GONE);
    String visible = available && expanded ? reasoning : "";
    if (!body.getText().toString().equals(visible)) body.setText(visible);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
