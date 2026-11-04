package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** A collapsed preview for reasoning text explicitly returned by the model. */
final class NativeChatReasoningView extends LinearLayout {
  private final TextView toggle;
  private final TextView body;
  private String reasoning = "";
  private boolean expanded;

  NativeChatReasoningView(Context context) {
    super(context);
    setOrientation(VERTICAL);
    setPadding(dp(12), 0, dp(12), 0);

    toggle = new androidx.appcompat.widget.AppCompatButton(context);
    toggle.setBackgroundResource(R.drawable.gpt_button_secondary);
    toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    toggle.setMinHeight(dp(48));
    toggle.setMinimumHeight(dp(48));
    toggle.setAllCaps(false);
    toggle.setTextSize(13);
    toggle.setTextColor(context.getColor(R.color.gpt_text));
    toggle.setPaddingRelative(dp(14), 0, dp(14), 0);
    addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    toggle.setOnClickListener(
        view -> {
          expanded = !expanded;
          render();
        });

    body = new TextView(context);
    body.setBackgroundResource(R.drawable.gpt_surface_raised);
    body.setPadding(dp(14), dp(12), dp(14), dp(12));
    body.setTextSize(14);
    body.setTextColor(context.getColor(R.color.gpt_text));
    body.setTextIsSelectable(true);
    body.setLineSpacing(dp(3), 1f);
    var params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(6);
    addView(body, params);
    render();
  }

  void showReasoning(String text) {
    if (reasoning.equals(text)) return;
    reasoning = text;
    if (text.isEmpty()) expanded = false;
    render();
  }

  private void render() {
    boolean available = !reasoning.isBlank();
    setVisibility(available ? VISIBLE : GONE);
    toggle.setText(
        expanded
            ? UiText.choose("收起思考过程", "Hide reasoning")
            : UiText.choose("展开思考过程", "Show reasoning"));
    ViewCompat.setStateDescription(
        toggle, expanded ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
    body.setVisibility(available && expanded ? VISIBLE : GONE);
    body.setText(available && expanded ? reasoning : "");
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
