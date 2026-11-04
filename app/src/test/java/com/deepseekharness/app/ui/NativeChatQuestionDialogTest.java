package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import static com.deepseekharness.app.util.NativeChatQuestionTrace.Reason.*;
import android.app.Application;
import android.os.Looper;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** 实际原生弹窗的回调区分；不启动后端或使用真实对话。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {30, 34},
    application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeChatQuestionDialogTest {
  private ChatHomeActivity host;
  private AlertDialog dialog;
  private final List<NativeChatQuestionDialog.Observation> observed = new ArrayList<>();
  private final AtomicInteger cancelled = new AtomicInteger();
  private final AtomicInteger submitted = new AtomicInteger();

  @Before
  public void prepare() {
    host = Robolectric.buildActivity(ChatHomeActivity.class).get();
    host.setTheme(R.style.Theme_DeepseekHarness);
  }

  @After
  public void cleanup() {
    if (dialog != null) dialog.dismiss();
    ShadowLooper.shadowMainLooper().idle();
  }

  private void open() {
    open(true);
  }

  private void open(boolean idle) {
    JsonArray questions =
        JsonParser.parseString("[{\"id\":\"q\",\"question\":\"Synthetic question\"}]")
            .getAsJsonArray();
    dialog =
        NativeChatQuestionDialog.show(
            host,
            questions,
            answers -> submitted.incrementAndGet(),
            cancelled::incrementAndGet,
            observed::add);
    if (idle) ShadowLooper.shadowMainLooper().idle();
  }

  private long signals(com.deepseekharness.app.util.NativeChatQuestionTrace.Reason reason) {
    return observed.stream().filter(item -> item.reason() == reason).count();
  }

  @Test
  public void showCallbackRecordsActualWindowStateWithoutUserVisibilityClaim() {
    open();
    assertEquals(1, signals(SHOW_CALLBACK));
    assertTrue(observed.get(0).showing());
  }

  @Test
  public void negativeButtonAndGenericCancelAreDistinctSignals() {
    open();
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(1, cancelled.get());
    assertEquals(1, signals(CANCEL_BUTTON));
    assertEquals(0, signals(DIALOG_CANCEL));
    open();
    dialog.cancel();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(1, cancelled.get());
    assertEquals(1, signals(DIALOG_CANCEL));
  }

  @Test
  public void programmaticDismissNeverInvokesCancel() {
    open();
    dialog.dismiss();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(0, cancelled.get());
    assertEquals(1, signals(DISMISSED));
    assertEquals(0, signals(DIALOG_CANCEL));
  }

  @Test
  public void successfulSubmitIsDistinctFromDismissAndContainsNoAnswer() {
    open();
    findInput(dialog.getWindow().getDecorView()).setText("private synthetic answer");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(1, submitted.get());
    assertEquals(0, cancelled.get());
    assertEquals(1, signals(SUBMIT_BUTTON));
    assertEquals(1, signals(DISMISSED));
    assertFalse(observed.toString().contains("private"));
  }

  @Test
  public void backKeyDismissesWithoutRejectingAndSameQuestionCanBeAnsweredAfterReopen() {
    open();
    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(dialog.isShowing());
    assertEquals(0, cancelled.get());
    assertEquals(0, submitted.get());
    assertEquals(1, signals(DIALOG_CANCEL));
    open();
    findInput(dialog.getWindow().getDecorView()).setText("answer after reopening");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(1, submitted.get());
    assertEquals(0, cancelled.get());
  }

  @Test
  public void immediateBackBeforeFirstLayoutNeverRejectsQuestion() {
    open(false);
    assertFalse(dialog.getWindow().getDecorView().isLaidOut());
    dialog.onBackPressed();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(0, cancelled.get());
    assertFalse(dialog.isShowing());
  }

  @Test
  public void outsideTouchBeforeFirstLayoutKeepsQuestionOpenAndNeverRejects() {
    open(false);
    assertFalse(dialog.getWindow().getDecorView().isLaidOut());
    MotionEvent touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -100, -100, 0);
    try {
      dialog.dispatchTouchEvent(touch);
    } finally {
      touch.recycle();
    }
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(0, cancelled.get());
    assertEquals(0, submitted.get());
    assertTrue(dialog.isShowing());
    assertEquals(0, signals(DIALOG_CANCEL));
  }

  @Test
  public void onlyActualTextChangesEngageQuestionWithoutLoggingText() {
    open();
    assertEquals(0, signals(EDIT));
    EditText input = findInput(dialog.getWindow().getDecorView());
    input.setText("private edit");
    assertEquals(1, signals(EDIT));
    assertFalse(observed.toString().contains("private edit"));
  }

  @Test
  public void selectingAnOptionEngagesQuestionWithoutLoggingItsLabel() {
    JsonArray questions =
        JsonParser.parseString(
                "[{\"id\":\"q\",\"question\":\"Choice\",\"options\":[{\"label\":\"private option\"}]}]")
            .getAsJsonArray();
    dialog =
        NativeChatQuestionDialog.show(
            host,
            questions,
            answers -> submitted.incrementAndGet(),
            cancelled::incrementAndGet,
            observed::add);
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(0, signals(EDIT));
    findChoice(dialog.getWindow().getDecorView()).performClick();
    assertEquals(1, signals(EDIT));
    assertFalse(observed.toString().contains("private option"));
  }

  @Test
  public void pristineInputFocusAndBlurAreDistinctFromAnswerEditing() {
    open();
    EditText input = findInput(dialog.getWindow().getDecorView());
    input.clearFocus();
    observed.clear();
    input.requestFocus();
    assertEquals(1, signals(INPUT_FOCUS));
    input.clearFocus();
    assertEquals(1, signals(INPUT_BLUR));
    assertEquals(0, signals(EDIT));
  }

  private static CompoundButton findChoice(View view) {
    if (view instanceof CompoundButton choice) return choice;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        CompoundButton found = findChoice(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private static EditText findInput(View view) {
    if (view instanceof EditText input) return input;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText found = findInput(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }
}
