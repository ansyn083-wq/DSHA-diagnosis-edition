package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.text.Spanned;
import android.text.style.MetricAffectingSpan;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import com.deepseekharness.app.R;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 原生富文本的视图合同：窄屏表格仍可读，文本可选择，HTML 不执行。 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public class NativeChatMarkdownTest {
  private final Context context =
      new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);

  @org.junit.Before
  public void initializeFormulaAssets() {
    // 此测试禁用应用清单，显式执行生产 ContentProvider 的初始化入口。
    ru.noties.jlatexmath.JLatexMathAndroid.init(context);
  }

  private View render(String markdown) {
    return NativeChatMarkdown.createView(context, markdown);
  }

  @Test
  public void wideTableKeepsSelectableCellsAndScrollsAt360dp() throws Exception {
    View view =
        render(
            "| One | Two | Three | Four |\n| --- | :---: | ---: | --- |\n"
                + "| alpha | beta | gamma | **delta** |\n\nAfter table");
    List<HorizontalScrollView> scrolls = descendants(view, HorizontalScrollView.class);
    assertEquals(1, scrolls.size());
    HorizontalScrollView scroll = scrolls.get(0);
    assertTrue(scroll.isHorizontalScrollBarEnabled());
    int width = Math.round(360 * context.getResources().getDisplayMetrics().density);
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    view.layout(0, 0, width, view.getMeasuredHeight());
    TableLayout table = descendants(view, TableLayout.class).get(0);
    assertEquals(2, table.getChildCount());
    assertTrue(
        "Table must scroll instead of squeezing its columns",
        table.getMeasuredWidth() > scroll.getMeasuredWidth());
    TableRow row = (TableRow) table.getChildAt(1);
    TextView cell = (TextView) row.getChildAt(3);
    assertEquals("delta", cell.getText().toString());
    assertTrue(cell.isTextSelectable());
    android.text.TextPaint paint = new android.text.TextPaint(cell.getPaint());
    for (MetricAffectingSpan span :
        ((Spanned) cell.getText()).getSpans(0, 5, MetricAffectingSpan.class)) {
      span.updateMeasureState(paint);
    }
    assertTrue(
        "Bold cell markup must affect paint: "
            + java.util.Arrays.toString(((Spanned) cell.getText()).getSpans(0, 5, Object.class)),
        paint.isFakeBoldText() || (paint.getTypeface() != null && paint.getTypeface().isBold()));
    assertTrue(text(view).endsWith("After table\n"));
  }

  @Test
  public void ordinaryMarkdownStillUsesSelectableAndroidText() throws Exception {
    View view = render("# Heading\n\n**bold** and `code`\n\n- first\n- second");
    assertEquals(0, descendants(view, android.webkit.WebView.class).size());
    assertTrue(text(view), text(view).contains("bold and "));
    assertTrue(text(view), text(view).contains("code"));
    assertFalse(text(view).contains("**"));
    for (TextView text : descendants(view, TextView.class)) assertTrue(text.isTextSelectable());
  }

  @Test
  public void htmlAndRemoteImagesAreLiteralAndNeverWebViews() throws Exception {
    View view =
        render(
            "<script>doEvil()</script>\n\n![private image](https://example.invalid/track)\n\nend");
    assertTrue(text(view).contains("<script>doEvil()</script>"));
    assertTrue(text(view).contains("private image"));
    assertEquals(0, descendants(view, android.widget.ImageView.class).size());
    assertEquals(0, descendants(view, android.webkit.WebView.class).size());
  }

  @Test
  public void validInlineAndDisplayMathBecomeLocalFormulaDrawables() throws Exception {
    View view =
        render(
            "Value $x^2$ then \\(\\frac{a}{b}\\).\n\n$$\n\\sum_{i=1}^n i\n$$\n\n\\[\\begin{bmatrix}1&2\\\\3&4\\end{bmatrix}\\]");
    List<io.noties.markwon.image.AsyncDrawableSpan> formulas = new ArrayList<>();
    for (TextView text : descendants(view, TextView.class)) {
      if (text.getText() instanceof Spanned) {
        Spanned spanned = (Spanned) text.getText();
        java.util.Collections.addAll(
            formulas,
            spanned.getSpans(0, spanned.length(), io.noties.markwon.image.AsyncDrawableSpan.class));
      }
    }
    assertEquals(4, formulas.size());
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(
              android.os.Looper.getMainLooper())
          .idle();
      if (formulas.stream().allMatch(span -> span.getDrawable().hasResult())) break;
      Thread.sleep(20);
    }
    for (io.noties.markwon.image.AsyncDrawableSpan span : formulas) {
      assertTrue(
          "Formula must have rendered, not only show its raw placeholder",
          span.getDrawable().hasResult());
      assertTrue(span.getDrawable().getResult() instanceof ru.noties.jlatexmath.JLatexMathDrawable);
      android.graphics.drawable.Drawable drawable = span.getDrawable().getResult();
      int width = drawable.getBounds().width(), height = drawable.getBounds().height();
      assertTrue(width > 0 && height > 0);
      android.graphics.Bitmap bitmap =
          android.graphics.Bitmap.createBitmap(
              width, height, android.graphics.Bitmap.Config.ARGB_8888);
      drawable.draw(new android.graphics.Canvas(bitmap));
      int[] pixels = new int[width * height];
      bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
      boolean hasInk = false;
      for (int pixel : pixels)
        if (android.graphics.Color.alpha(pixel) != 0) {
          hasInk = true;
          break;
        }
      assertTrue("Native formula drawable must paint actual pixels", hasInk);
      bitmap.recycle();
    }
  }

  @Test
  public void unsafeMathAndBadFormulasKeepTheirText() throws Exception {
    String source = "$$\\newcommand{\\x}{oops}$$ and $$\\notARealCommand{abc}$$";
    View view = render(source);
    assertTrue(text(view).contains("newcommand"));
    assertTrue(text(view).contains("notARealCommand"));
  }

  @Test
  public void unsafeLinksAreNotClickable() throws Exception {
    View view =
        render(
            "[bad](javascript:evil) [private](https://user:secret@example.com/) [safe](https://example.com/)");
    List<android.text.style.ClickableSpan> links = new ArrayList<>();
    for (TextView text : descendants(view, TextView.class)) {
      Spanned spanned = (Spanned) text.getText();
      java.util.Collections.addAll(
          links, spanned.getSpans(0, spanned.length(), android.text.style.ClickableSpan.class));
    }
    assertEquals(1, links.size());
  }

  @Test
  public void deeplyNestedMarkdownFallsBackWithoutDroppingText() {
    String source = "> ".repeat(80) + "Nested";
    assertEquals(source + "\n", text(render(source)));
  }

  @Test
  public void nestedTablesRetainReadableCellBoundaries() {
    View view = render("> | One | Two |\n> | --- | --- |\n> | alpha | beta |");
    assertTrue(text(view), text(view).contains("alpha  |  beta"));
  }

  private static String text(View view) {
    StringBuilder out = new StringBuilder();
    for (TextView text : descendants(view, TextView.class)) out.append(text.getText()).append('\n');
    return out.toString();
  }

  private static <T extends View> List<T> descendants(View view, Class<T> type) {
    List<T> result = new ArrayList<>();
    if (type.isInstance(view)) result.add(type.cast(view));
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        result.addAll(descendants(group.getChildAt(i), type));
    }
    return result;
  }
}
