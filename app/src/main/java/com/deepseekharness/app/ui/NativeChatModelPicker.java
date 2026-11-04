package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.deepseekharness.app.util.NativeChatRpc.ModelCatalog;
import com.deepseekharness.app.util.NativeChatRpc.ModelOption;
import com.deepseekharness.app.util.NativeChatRpc.ModelSelection;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import java.util.function.Consumer;

final class NativeChatModelPicker {
  private NativeChatModelPicker() {}

  static void show(
      ChatHomeActivity host,
      ModelCatalog catalog,
      ModelSelection selected,
      Consumer<ModelOption> apply) {
    List<ModelOption> options = catalog.options();
    if (options.isEmpty()) {
      new MaterialAlertDialogBuilder(host)
          .setTitle(t("暂无可用模型", "No models available"))
          .setMessage(t("请先配置 API Key。", "Configure an API key first."))
          .setPositiveButton(
              t("账号与 API", "Account and API"), (dialog, which) -> host.showChatAccount())
          .setNegativeButton(t("关闭", "Close"), null)
          .show();
      return;
    }
    String[] labels = new String[options.size()];
    int checked = -1;
    for (int i = 0; i < options.size(); i++) {
      ModelOption option = options.get(i);
      labels[i] = option.name();
      if (option.selection().sameModel(selected)) checked = i;
    }
    new MaterialAlertDialogBuilder(host)
        .setTitle(t("选择模型", "Choose a model"))
        .setSingleChoiceItems(
            labels,
            checked,
            (dialog, which) -> {
              dialog.dismiss();
              apply.accept(options.get(which));
            })
        .setNegativeButton(t("取消", "Cancel"), null)
        .show();
  }

  static void bindEffort(
      View panel, ModelOption option, ModelSelection selection, Consumer<ModelSelection> apply) {
    var efforts = option.efforts();
    String[] names = new String[efforts.size() + 1];
    names[0] = t("默认", "Default");
    int selected = 0;
    for (int i = 0; i < efforts.size(); i++) {
      var effort = efforts.get(i);
      names[i + 1] = effort.name().isBlank() ? effort.id() : effort.name();
      if (effort.id().equals(selection.effort())) selected = i + 1;
    }
    configureEffortSlider(
        panel,
        names,
        selected,
        level -> {
          String effort = level == 0 ? "" : efforts.get(level - 1).id();
          apply.accept(new ModelSelection(selection.provider(), selection.model(), effort));
        });
  }

  static Slider configureEffortSlider(
      View panel, String[] names, int selected, Consumer<Integer> onChange) {
    Slider slider = panel.findViewById(R.id.native_chat_effort_slider);
    TextView value = panel.findViewById(R.id.native_chat_effort_value);
    float density = panel.getResources().getDisplayMetrics().density;
    slider.clearOnChangeListeners();
    slider.setValueFrom(0);
    slider.setValueTo(names.length - 1);
    slider.setStepSize(1);
    slider.setValue(selected);
    slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
    slider.setLabelFormatter(position -> names[Math.round(position)]);
    slider.setContentDescription(t("推理强度", "Reasoning effort"));
    slider.setTrackHeight(Math.round(8 * density));
    slider.setThumbRadius(Math.round(9 * density));
    slider.setThumbElevation(2 * density);
    slider.setThumbStrokeWidth(density);
    slider.setThumbStrokeColor(
        ColorStateList.valueOf(panel.getContext().getColor(R.color.gpt_line)));
    slider.setThumbTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
    slider.setTrackInactiveTintList(
        ColorStateList.valueOf(panel.getContext().getColor(R.color.gpt_line)));
    slider.setTickVisible(true);
    slider.setTickInactiveRadius(Math.max(1, Math.round(density)));
    slider.setTickActiveRadius(Math.max(1, Math.round(density)));
    slider.setTickInactiveTintList(
        ColorStateList.valueOf(panel.getContext().getColor(R.color.gpt_muted)));
    slider.setTickActiveTintList(ColorStateList.valueOf(0xCCFFFFFF));
    slider.setHaloRadius(Math.round(15 * density));
    showEffortValue(slider, value, names, selected);
    slider.addOnChangeListener(
        (view, position, fromUser) -> {
          int level = Math.round(position);
          showEffortValue(view, value, names, level);
          if (fromUser) onChange.accept(level);
        });
    return slider;
  }

  private static void showEffortValue(Slider slider, TextView value, String[] names, int level) {
    value.setText(names[level]);
    float intensity = (float) level / (names.length - 1);
    int accent =
        intensity <= 0.70f
            ? slider.getContext().getColor(R.color.gpt_link)
            : intensity < 1 ? 0xFF6653EA : 0xFF7950DF;
    slider.setTrackActiveTintList(ColorStateList.valueOf(accent));
    slider.setHaloTintList(ColorStateList.valueOf((accent & 0xFFFFFF) | 0x22000000));
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
