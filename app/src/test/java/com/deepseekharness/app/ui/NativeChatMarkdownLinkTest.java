package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public class NativeChatMarkdownLinkTest {
  @Test
  public void selectableBodyLinkOpensOnlyAfterUserTap() {
    checkTap("[safe](https://example.com/docs)");
  }

  @Test
  public void selectableTableLinkOpensOnlyAfterUserTap() {
    checkTap("| Link |\n| --- |\n| [safe](https://example.com/docs) |");
  }

  private void checkTap(String markdown) {
    try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
      Activity activity = controller.get();
      var context = new ContextThemeWrapper(activity, R.style.Theme_DeepseekHarness);
      View root = NativeChatMarkdown.createView(context, markdown);
      activity.setContentView(root);
      root.measure(
          View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 360, 200);
      TextView text = findText(root);
      assertNotNull(text);
      assertTrue(text.isTextSelectable());
      ShadowActivity shadow = Shadow.extract(activity);
      assertNull("No launch before user tap", shadow.getNextStartedActivity());
      float x = text.getTotalPaddingLeft() + text.getLayout().getPrimaryHorizontal(1);
      float y =
          text.getTotalPaddingTop()
              + (text.getLayout().getLineTop(0) + text.getLayout().getLineBottom(0)) / 2f;
      MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, x, y, 0);
      MotionEvent up = MotionEvent.obtain(100, 150, MotionEvent.ACTION_UP, x, y, 0);
      text.dispatchTouchEvent(down);
      text.dispatchTouchEvent(up);
      down.recycle();
      up.recycle();
      Intent started = shadow.getNextStartedActivity();
      assertNotNull(
          "User tap must open a safe link; movement=" + text.getMovementMethod().getClass(),
          started);
      assertEquals("https://example.com/docs", started.getDataString());
      assertTrue("Opening a link must not disable selection", text.isTextSelectable());
    }
  }

  private TextView findText(View view) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals("safe"))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView found = findText(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }
}
