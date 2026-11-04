package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.NativeChatScheduleRpc;
import com.deepseekharness.app.util.NativeChatScheduleRpc.Task;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Native retained-task list and compare-and-update editor. It never starts a Session. */
final class NativeChatScheduleDialog implements DefaultLifecycleObserver {
  private static final String[] KINDS = {"at", "every", "daily", "weekly", "cron"};
  private final ChatHomeActivity host;
  private final HarnessController harness;
  private final com.deepseekharness.app.util.PreviewPageSession.Identity boundIdentity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final NativeChatScheduleRpc.RequestGate gate = new NativeChatScheduleRpc.RequestGate();
  private final AlertDialog dialog;
  private final LinearLayout content;
  private final TextView notice;
  private final List<View> controls = new ArrayList<>();
  private AlertDialog confirmation;
  private List<Task> tasks = List.of();
  private int shown = 50;
  private boolean busy;
  private boolean editInvalid;
  private Task editing;
  private EditText title;
  private EditText prompt;
  private EditText value;
  private EditText timeZone;
  private EditText weekdays;
  private TextView valueLabel;
  private LinearLayout timingFields;
  private LinearLayout zoneFields;
  private LinearLayout weekdayFields;
  private Spinner kind;
  private CheckBox changeTiming;
  private Button save;
  private Button remove;

  static void show(ChatHomeActivity host, Runnable onDismiss) {
    new NativeChatScheduleDialog(host, onDismiss);
  }

  private NativeChatScheduleDialog(ChatHomeActivity host, Runnable onDismiss) {
    this.host = host;
    harness = HarnessController.get(host.getApplicationContext());
    boundIdentity = harness.getReadyWebPageIdentity();
    var body = new LinearLayout(host);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(8), dp(20), dp(16));
    addText(
        body,
        t(
            "可编辑已有任务。新增定时任务请在聊天中告诉 AI。任务依赖 DSH 服务运行。",
            "Edit existing tasks here. Ask the AI in chat to create a task. Tasks require DSH to be running."),
        14,
        false);
    notice = addText(body, "", 14, false);
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    notice.setVisibility(View.GONE);
    content = new LinearLayout(host);
    content.setOrientation(LinearLayout.VERTICAL);
    body.addView(content, row());
    ScrollView scroll = new ScrollView(host);
    scroll.setFillViewport(true);
    scroll.addView(body);
    dialog =
        new MaterialAlertDialogBuilder(host)
            .setTitle(t("定时任务", "Scheduled tasks"))
            .setView(scroll)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    dialog.setOnDismissListener(
        ignored -> {
          gate.close();
          if (confirmation != null) confirmation.dismiss();
          host.getLifecycle().removeObserver(this);
          main.removeCallbacksAndMessages(null);
          worker.shutdownNow();
          onDismiss.run();
        });
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    host.getLifecycle().addObserver(this);
    showList();
    refresh();
  }

  private void refresh() {
    run(
        false,
        rpc -> {
          List<Task> loaded = rpc.catalog();
          return () -> {
            tasks = loaded;
            showList();
          };
        });
  }

  private void showList() {
    editing = null;
    editInvalid = false;
    content.removeAllViews();
    controls.clear();
    addButton(content, t("刷新", "Refresh"), false, this::refresh);
    if (tasks.isEmpty()) {
      addText(content, t("暂无定时任务。", "No scheduled tasks."), 15, false);
      return;
    }
    int count = Math.min(shown, tasks.size());
    for (int index = 0; index < count; index++) {
      Task task = tasks.get(index);
      LinearLayout card = section(content);
      addText(card, task.title(), 17, true);
      addText(
          card,
          (task.active() ? t("进行中", "Active") : t("已结束", "Ended")) + " · " + task.scheduledAt(),
          13,
          false);
      addText(card, ruleSummary(task), 14, false);
      addButton(
          card,
          task.active() ? t("查看与编辑", "View and edit") : t("查看", "View"),
          false,
          () -> showEditor(task));
    }
    if (count < tasks.size()) {
      addButton(
          content,
          t("显示更多", "Show more"),
          false,
          () -> {
            shown += 50;
            showList();
          });
    }
  }

  private void showEditor(Task task) {
    editing = task;
    editInvalid = false;
    notice.setVisibility(View.GONE);
    content.removeAllViews();
    controls.clear();
    addButton(
        content,
        t("返回任务列表", "Back to tasks"),
        false,
        () -> {
          if (editInvalid) tasks = List.of();
          showList();
          refresh();
        });
    addText(
        content,
        (task.active()
                ? t("下次执行（UTC）：", "Next run (UTC): ")
                : t("最终计划时间（UTC）：", "Final scheduled time (UTC): "))
            + task.scheduledAt(),
        14,
        false);
    title = field(content, t("任务名称", "Task name"), task.title(), 120, false);
    prompt =
        field(
            content,
            t("任务指令", "Instruction"),
            task.prompt(),
            Math.max(65_536, task.prompt().length()),
            true);
    if (task.active()) {
      changeTiming = new CheckBox(host);
      changeTiming.setText(t("修改执行时间", "Change timing"));
      changeTiming.setTextColor(host.getColor(R.color.gpt_text));
      content.addView(changeTiming, row());
      controls.add(changeTiming);
      timingFields = new LinearLayout(host);
      timingFields.setOrientation(LinearLayout.VERTICAL);
      content.addView(timingFields, row());
      addText(timingFields, t("重复方式", "Repeat"), 13, true);
      kind = new Spinner(host);
      kind.setMinimumHeight(dp(48));
      kind.setAdapter(
          new ArrayAdapter<>(
              host,
              android.R.layout.simple_spinner_dropdown_item,
              new String[] {
                t("单次（ISO 日期时间）", "Once (ISO date and time)"),
                t("固定间隔（秒）", "Interval (seconds)"),
                t("每天", "Daily"),
                t("每周", "Weekly"),
                t("Cron（五字段）", "Cron (five fields)")
              }));
      timingFields.addView(kind, row());
      controls.add(kind);
      valueLabel = addText(timingFields, "", 13, true);
      value = input(timingFields, "", 256, false);
      valueLabel.setLabelFor(value.getId());
      zoneFields = new LinearLayout(host);
      zoneFields.setOrientation(LinearLayout.VERTICAL);
      timingFields.addView(zoneFields, row());
      timeZone =
          field(
              zoneFields,
              t("IANA 时区（如 Asia/Shanghai）", "IANA time zone (e.g. Asia/Shanghai)"),
              text(task.record(), "timeZone", TimeZone.getDefault().getID()),
              128,
              false);
      weekdayFields = new LinearLayout(host);
      weekdayFields.setOrientation(LinearLayout.VERTICAL);
      timingFields.addView(weekdayFields, row());
      weekdays =
          field(
              weekdayFields,
              t("星期（1=周一，7=周日，逗号分隔）", "Weekdays (1=Monday, 7=Sunday, comma-separated)"),
              weekdayText(task),
              32,
              false);
      int initial =
          switch (task.kind()) {
            case "every" -> 1;
            case "daily" -> 2;
            case "weekly" -> 3;
            case "cron" -> 4;
            default -> 0;
          };
      kind.setSelection(initial);
      kind.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
              selectKind(task, KINDS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
          });
      selectKind(task, KINDS[initial]);
      timingFields.setVisibility(View.GONE);
      changeTiming.setOnCheckedChangeListener(
          (button, checked) -> timingFields.setVisibility(checked ? View.VISIBLE : View.GONE));
      addText(
          content,
          t(
              "不勾选“修改执行时间”会保留原计划。服务端会验证时区、规则与未来时间。",
              "Leave Change timing off to keep the current schedule. The server validates time zones, rules, and future dates."),
          13,
          false);
      save = addButton(content, t("保存修改", "Save changes"), true, () -> save(task));
    } else {
      title.setEnabled(false);
      prompt.setEnabled(false);
      addText(
          content,
          t(
              "已结束的任务不能重新启用或编辑。可在聊天中请求创建新任务。",
              "Ended tasks cannot be reactivated or edited. Ask in chat to create a new task."),
          14,
          false);
    }
    remove = addButton(content, t("删除任务", "Delete task"), false, () -> confirmDelete(task));
  }

  private void selectKind(Task task, String selected) {
    boolean local =
        "daily".equals(selected) || "weekly".equals(selected) || "cron".equals(selected);
    zoneFields.setVisibility(local ? View.VISIBLE : View.GONE);
    weekdayFields.setVisibility("weekly".equals(selected) ? View.VISIBLE : View.GONE);
    String label;
    String initial;
    switch (selected) {
      case "at" -> {
        label = t("ISO 日期时间（含 Z 或 UTC 偏移）", "ISO date and time (with Z or UTC offset)");
        initial = task.scheduledAt();
      }
      case "every" -> {
        label = t("间隔秒数（至少 60）", "Interval in seconds (at least 60)");
        initial = text(task.record(), "everySeconds", "3600");
      }
      case "cron" -> {
        label = t("Cron：分 时 日 月 星期", "Cron: minute hour day month weekday");
        initial = text(task.record(), "expression", "0 9 * * *");
      }
      default -> {
        label = t("当地时间（HH:mm 或 HH:mm:ss）", "Local time (HH:mm or HH:mm:ss)");
        initial = text(task.record(), "time", "09:00");
      }
    }
    valueLabel.setText(label);
    value.setText(initial);
  }

  private void save(Task task) {
    if (busy || editInvalid || editing != task) return;
    String name = title.getText().toString().strip();
    String instruction = prompt.getText().toString().strip();
    if (name.isEmpty()) {
      title.setError(t("请输入任务名称", "Enter a task name"));
      return;
    }
    if (instruction.isEmpty()) {
      prompt.setError(t("请输入任务指令", "Enter an instruction"));
      return;
    }
    JsonObject timing = null;
    if (changeTiming.isChecked()) {
      try {
        timing =
            NativeChatScheduleRpc.timing(
                KINDS[kind.getSelectedItemPosition()],
                value.getText().toString(),
                timeZone.getText().toString(),
                weekdays.getText().toString());
      } catch (IllegalArgumentException invalid) {
        showNotice(t("请检查时间、间隔、时区和星期的格式。", "Check the time, interval, time zone, and weekdays."));
        return;
      }
    }
    JsonObject change = timing;
    run(
        true,
        rpc -> {
          var result = rpc.update(task, name, instruction, change);
          return () -> {
            if (result.success()) {
              tasks = List.of();
              showList();
              refresh();
              showNotice(
                  result.changed()
                      ? t("修改已保存。", "Changes saved.")
                      : t("任务未变化。", "No changes needed."));
            } else {
              editInvalid =
                  "schedule_conflict".equals(result.code())
                      || "schedule_not_found".equals(result.code())
                      || "schedule_ended".equals(result.code());
              showNotice(failure(result.code()));
            }
          };
        });
  }

  private void confirmDelete(Task task) {
    if (busy || editInvalid || confirmation != null) return;
    confirmation =
        new MaterialAlertDialogBuilder(host)
            .setTitle(t("删除定时任务？", "Delete scheduled task?"))
            .setMessage(
                task.title()
                    + "\n\n"
                    + t(
                        "这会永久删除此任务及其已保存的执行记录，无法撤销。已发送到聊天的消息会保留，已开始的执行不会撤回。",
                        "This permanently deletes this task and its saved delivery history. It cannot be undone. Messages already sent to chat remain, and work already started is not recalled."))
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(
                t("删除", "Delete"),
                (ignored, which) ->
                    run(
                        true,
                        rpc -> {
                          boolean deleted = rpc.delete(task);
                          return () -> {
                            tasks = List.of();
                            showList();
                            refresh();
                            showNotice(
                                deleted
                                    ? t("任务已删除。", "Task deleted.")
                                    : t("任务已经不存在。", "Task no longer exists."));
                          };
                        }))
            .create();
    confirmation.setOnDismissListener(ignored -> confirmation = null);
    confirmation.show();
  }

  private interface Request {
    Runnable call(NativeChatScheduleRpc rpc) throws IOException;
  }

  private void run(boolean mutation, Request request) {
    if (!dialog.isShowing()) return;
    long ticket = gate.begin();
    if (ticket == 0) return;
    busy = true;
    notice.setVisibility(View.GONE);
    refreshControls();
    worker.execute(
        () -> {
          Runnable complete;
          BooleanSupplier sameService;
          try {
            var identity = boundIdentity;
            if (identity == null) throw new IOException("DSH not ready");
            sameService =
                () ->
                    identity.equals(harness.getReadyWebPageIdentity())
                        && !harness.isUserStopped()
                        && !harness.isStopping()
                        && !harness.hasUnresolvedWebSession();
            if (!gate.current(ticket) || !sameService.getAsBoolean())
              throw new IOException("DSH changed");
            String cookie = harness.exchangeDshAuthCookie();
            BooleanSupplier service = sameService;
            var rpc =
                new NativeChatRpc(
                    identity.authUrl(),
                    cookie,
                    () -> gate.current(ticket) && service.getAsBoolean());
            complete = request.call(new NativeChatScheduleRpc(rpc));
          } catch (IOException | RuntimeException unavailable) {
            sameService = () -> false;
            complete = () -> {};
          }
          Runnable result = complete;
          BooleanSupplier service = sameService;
          main.post(
              () -> {
                if (!gate.finish(ticket) || !dialog.isShowing()) return;
                busy = false;
                if (service.getAsBoolean()) result.run();
                else {
                  if (mutation) editInvalid = true;
                  showNotice(
                      mutation
                          ? t(
                              "未能确认操作结果，请返回列表刷新核对后再操作，不要重复提交。",
                              "Could not confirm the outcome. Return to the list and refresh before trying again.")
                          : t(
                              "暂时无法读取任务。请确认 DSH 已运行，然后刷新重试。",
                              "Could not read tasks. Check that DSH is running, then refresh."));
                }
                refreshControls();
              });
        });
  }

  private void refreshControls() {
    for (View control : controls) control.setEnabled(!busy);
    if (editing != null) {
      title.setEnabled(!busy && editing.active());
      prompt.setEnabled(!busy && editing.active());
      if (editing.active()) save.setEnabled(!busy && !editInvalid);
      remove.setEnabled(!busy && !editInvalid);
    }
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    dialog.dismiss();
  }

  private void showNotice(String message) {
    notice.setText(message);
    notice.setVisibility(View.VISIBLE);
  }

  private static String failure(String code) {
    return switch (code) {
      case "schedule_conflict" ->
          t(
              "任务在编辑期间已变化。请返回列表刷新后重新编辑。",
              "The task changed while you were editing. Return to the list and refresh before editing again.");
      case "schedule_not_found" ->
          t("任务已被删除。请返回列表刷新。", "The task was deleted. Return to the list and refresh.");
      case "schedule_ended" ->
          t("任务已结束，不能继续编辑。", "The task has ended and can no longer be edited.");
      case "subagent_session" ->
          t("此任务绑定子代理会话，不能修改。", "Tasks bound to a child-agent session cannot be edited.");
      case "not_future" -> t("请选择未来的执行时间。", "Choose a future time.");
      case "invalid_time_zone" ->
          t("时区无效，请使用 IANA 时区名称。", "Invalid time zone. Use an IANA time zone name.");
      case "frequency_too_high" -> t("执行间隔不能少于 60 秒。", "The interval must be at least 60 seconds.");
      default ->
          t(
              "服务端未接受修改，请检查名称、指令和时间规则。",
              "The server did not accept the change. Check the name, instruction, and timing rule.");
    };
  }

  private static String ruleSummary(Task task) {
    JsonObject record = task.record();
    return switch (task.kind()) {
      case "every" -> t("每 ", "Every ") + text(record, "everySeconds", "") + t(" 秒", " seconds");
      case "daily" ->
          t("每天 ", "Daily ") + text(record, "time", "") + " · " + text(record, "timeZone", "");
      case "weekly" ->
          t("每周 ", "Weekly ")
              + weekdayText(task)
              + " · "
              + text(record, "time", "")
              + " · "
              + text(record, "timeZone", "");
      case "cron" ->
          "Cron " + text(record, "expression", "") + " · " + text(record, "timeZone", "");
      default -> t("单次任务", "One-time task");
    };
  }

  private static String weekdayText(Task task) {
    JsonObject record = task.record();
    if (!record.has("weekdays")) return "1";
    List<String> values = new ArrayList<>();
    for (var day : record.getAsJsonArray("weekdays")) values.add(day.getAsString());
    return String.join(",", values);
  }

  private static String text(JsonObject value, String key, String fallback) {
    return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
  }

  private EditText field(
      LinearLayout parent, String label, String initial, int max, boolean multiline) {
    TextView caption = addText(parent, label, 13, true);
    EditText field = input(parent, initial, max, multiline);
    caption.setLabelFor(field.getId());
    return field;
  }

  private EditText input(LinearLayout parent, String initial, int max, boolean multiline) {
    EditText field = new EditText(host);
    field.setId(View.generateViewId());
    field.setTextSize(15);
    field.setTextColor(host.getColor(R.color.gpt_text));
    field.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (multiline
                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
    field.setSingleLine(!multiline);
    if (multiline) {
      field.setMinLines(3);
      field.setMaxLines(8);
    }
    if (max > 0) field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
    field.setText(initial);
    field.setSaveEnabled(false);
    if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    field.setPadding(dp(12), dp(10), dp(12), dp(10));
    field.setMinHeight(dp(48));
    field.setBackground(rounded(R.color.gpt_bg));
    parent.addView(field, row());
    controls.add(field);
    return field;
  }

  private LinearLayout section(LinearLayout parent) {
    var card = new LinearLayout(host);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(12), dp(10), dp(12), dp(12));
    card.setBackground(rounded(R.color.gpt_sidebar));
    var params = row();
    params.topMargin = dp(12);
    parent.addView(card, params);
    return card;
  }

  private TextView addText(LinearLayout parent, String value, int size, boolean bold) {
    TextView text = new TextView(host);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(host.getColor(bold ? R.color.gpt_text : R.color.gpt_muted));
    if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    var params = row();
    params.topMargin = dp(8);
    parent.addView(text, params);
    return text;
  }

  private Button addButton(LinearLayout parent, String label, boolean primary, Runnable click) {
    var button = new MaterialButton(host);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinHeight(dp(48));
    button.setCornerRadius(dp(12));
    button.setBackgroundTintList(
        ColorStateList.valueOf(host.getColor(primary ? R.color.gpt_action : R.color.gpt_raised)));
    button.setTextColor(host.getColor(primary ? R.color.gpt_action_on : R.color.gpt_text));
    button.setOnClickListener(
        view -> {
          if (!busy) click.run();
        });
    var params = row();
    params.topMargin = dp(8);
    parent.addView(button, params);
    controls.add(button);
    return button;
  }

  private GradientDrawable rounded(int color) {
    var drawable = new GradientDrawable();
    drawable.setColor(host.getColor(color));
    drawable.setCornerRadius(dp(12));
    return drawable;
  }

  private LinearLayout.LayoutParams row() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
