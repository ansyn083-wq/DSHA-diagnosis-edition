package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.core.graphics.ColorUtils;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.NativeChatQuestionTrace.Reason;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Renders one gateway question batch without injecting its contents into the timeline. */
final class NativeChatQuestionDialog {
  private record Choice(CompoundButton button, String label) {}

  private record Question(String id, List<Choice> choices, EditText custom) {}

  record Observation(Reason reason, boolean showing, boolean attached, boolean focused) {}

  static AlertDialog show(
      Context context, JsonArray questions, Consumer<JsonArray> submit, Runnable cancel) {
    return show(context, questions, submit, cancel, ignored -> {});
  }

  static AlertDialog show(
      Context context,
      JsonArray questions,
      Consumer<JsonArray> submit,
      Runnable cancel,
      Consumer<Observation> observer) {
    var fields = new ArrayList<Question>();
    final AlertDialog[] owner = new AlertDialog[1];
    Consumer<Reason> inputSignal =
        reason -> {
          if (owner[0] != null) observe(observer, reason, owner[0]);
        };
    var scroll = new ScrollView(context);
    scroll.setVerticalScrollBarEnabled(false);
    var body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = dp(context, 20);
    body.setPaddingRelative(padding, dp(context, 6), padding, padding);
    body.setFocusableInTouchMode(true);
    scroll.addView(body);
    for (JsonElement element : questions) {
      JsonObject question = element.getAsJsonObject();
      if (questions.size() > 1) {
        String progress = (fields.size() + 1) + " / " + questions.size();
        addText(body, progress, 12, R.color.gpt_muted, fields.isEmpty() ? 0 : 18);
      }
      var heading = addText(body, question.get("question").getAsString(), 18, R.color.gpt_text, 4);
      heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      heading.setAccessibilityHeading(true);
      if (question.has("detail")) {
        addText(
            body,
            NativeChatMarkdown.render(question.get("detail").getAsString()),
            14,
            R.color.gpt_muted,
            6);
      }
      var choices = new ArrayList<Choice>();
      boolean multiple = question.has("multiSelect") && question.get("multiSelect").getAsBoolean();
      if (question.has("options") && !question.getAsJsonArray("options").isEmpty()) {
        addText(
            body,
            multiple ? t("可多选", "Choose one or more") : t("选择一项", "Choose one"),
            13,
            R.color.gpt_muted,
            10);
        for (JsonElement optionElement : question.getAsJsonArray("options")) {
          JsonObject option = optionElement.getAsJsonObject();
          String label = option.get("label").getAsString();
          var choice =
              optionView(
                  context,
                  label,
                  option.has("description") ? option.get("description").getAsString() : "",
                  multiple);
          choices.add(new Choice(choice, label));
          choice.setOnCheckedChangeListener(
              (button, checked) -> {
                updateChoiceBackground(button, checked);
                inputSignal.accept(Reason.EDIT);
                if (checked && !multiple)
                  for (Choice other : choices) {
                    if (other.button() != button) other.button().setChecked(false);
                  }
              });
          body.addView(choice, row(context, 6));
        }
      }
      var input =
          new TextInputLayout(
              context, null, com.google.android.material.R.attr.textInputOutlinedStyle);
      input.setHint(choices.isEmpty() ? t("输入答案", "Enter an answer") : t("自定义答案", "Custom answer"));
      var custom = new TextInputEditText(input.getContext());
      custom.setMaxLines(4);
      custom.setSaveEnabled(false);
      custom.setOnFocusChangeListener(
          (view, focused) -> inputSignal.accept(focused ? Reason.INPUT_FOCUS : Reason.INPUT_BLUR));
      custom.addTextChangedListener(
          new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
              if (before != 0 || count != 0) inputSignal.accept(Reason.EDIT);
            }

            @Override
            public void afterTextChanged(Editable value) {}
          });
      input.addView(custom, new LinearLayout.LayoutParams(-1, -2));
      body.addView(input, row(context, 12));
      fields.add(new Question(question.get("id").getAsString(), choices, custom));
    }
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(context)
            .setTitle(t("请选择或填写答案", "Choose or enter answers"))
            .setView(scroll)
            .setNegativeButton(
                android.R.string.cancel,
                (window, which) -> {
                  observe(observer, Reason.CANCEL_BUTTON, (AlertDialog) window);
                  cancel.run();
                })
            .setOnCancelListener(
                window -> {
                  // 返回键或系统取消只收起界面；仅明确点击取消按钮才拒绝提问。
                  observe(observer, Reason.DIALOG_CANCEL, (AlertDialog) window);
                })
            .setPositiveButton(t("提交", "Submit"), null)
            .create();
    owner[0] = dialog;
    dialog.setOnDismissListener(ignored -> observe(observer, Reason.DISMISSED, dialog));
    dialog.setOnShowListener(
        ignored -> {
          observe(observer, Reason.SHOW_CALLBACK, dialog);
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  view -> {
                    var answers = new JsonArray();
                    for (Question field : fields) {
                      var selected = new JsonArray();
                      for (Choice choice : field.choices()) {
                        if (choice.button().isChecked()) selected.add(choice.label());
                      }
                      String custom = field.custom().getText().toString().strip();
                      if (selected.isEmpty() && custom.isEmpty()) {
                        field.custom().requestFocus();
                        field.custom().setError(t("请选择或填写答案", "Choose or enter an answer"));
                        return;
                      }
                      var answer = new JsonObject();
                      answer.addProperty("id", field.id());
                      answer.add("selected", selected);
                      if (!custom.isEmpty()) answer.addProperty("custom", custom);
                      answers.add(answer);
                    }
                    observe(observer, Reason.SUBMIT_BUTTON, dialog);
                    submit.accept(answers);
                    dialog.dismiss();
                  });
        });
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    if (dialog.getWindow() != null)
      dialog
          .getWindow()
          .setSoftInputMode(
              WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                  | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    return dialog;
  }

  private static void observe(Consumer<Observation> observer, Reason reason, AlertDialog dialog) {
    try {
      View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
      observer.accept(
          new Observation(
              reason,
              dialog.isShowing(),
              decor != null && decor.isAttachedToWindow(),
              decor != null && decor.hasWindowFocus()));
    } catch (RuntimeException ignored) {
      /* 诊断不得影响提问回调。 */
    }
  }

  private static CompoundButton optionView(
      Context context, String label, String description, boolean multiple) {
    CompoundButton choice =
        multiple ? new AppCompatCheckBox(context) : new AppCompatRadioButton(context);
    var text = new SpannableStringBuilder(label);
    if (!label.isEmpty())
      text.setSpan(
          new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (!description.isBlank()) {
      int start = text.length() + 1;
      text.append('\n').append(description);
      text.setSpan(
          new RelativeSizeSpan(.87f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new ForegroundColorSpan(context.getColor(R.color.gpt_muted)),
          start,
          text.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    choice.setText(text);
    choice.setTextSize(15);
    choice.setTextColor(context.getColor(R.color.gpt_text));
    choice.setMinHeight(dp(context, 52));
    choice.setPaddingRelative(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
    choice.setCompoundDrawablePadding(dp(context, 8));
    choice.setButtonTintList(
        new ColorStateList(
            new int[][] {{android.R.attr.state_checked}, {}},
            new int[] {context.getColor(R.color.gpt_link), context.getColor(R.color.gpt_muted)}));
    choice.setSaveEnabled(false);
    updateChoiceBackground(choice, false);
    return choice;
  }

  private static void updateChoiceBackground(View option, boolean selected) {
    Context context = option.getContext();
    int accent = context.getColor(R.color.gpt_link);
    int surface = context.getColor(R.color.gpt_sidebar);
    var card = new GradientDrawable();
    card.setCornerRadius(dp(context, 14));
    card.setColor(
        selected
            ? ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 23), surface)
            : surface);
    card.setStroke(
        dp(context, 1), context.getColor(selected ? R.color.gpt_link : R.color.gpt_line));
    option.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 35)), card, null));
  }

  private static TextView addText(
      LinearLayout body, CharSequence text, int size, int color, int top) {
    Context context = body.getContext();
    var view = new TextView(context);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(context.getColor(color));
    body.addView(view, row(context, top));
    return view;
  }

  private static LinearLayout.LayoutParams row(Context context, int top) {
    var params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(context, top);
    return params;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
