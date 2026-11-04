package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeChatFilesDialogTest {
  private final Context context =
      new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);

  private View preview(String name, String text) throws Exception {
    try {
      return (View)
          Class.forName("com.deepseekharness.app.ui.NativeChatFilesDialog")
              .getDeclaredMethod("createTextPreview", Context.class, String.class, String.class)
              .invoke(null, context, name, text);
    } catch (ClassNotFoundException | NoSuchMethodException absent) {
      throw new AssertionError("Workspace file preview needs a native text renderer", absent);
    }
  }

  @Test
  public void activeHtmlAndSvgStaySelectableLiteralText() throws Exception {
    for (String name : new String[] {"index.html", "drawing.svg", "script.js"}) {
      String input = "<script>steal()</script>\n<img src=\"https://example.invalid/private\">";
      View view = preview(name, input);
      assertTrue(view instanceof TextView);
      assertFalse(view instanceof android.webkit.WebView);
      assertEquals(input, ((TextView) view).getText().toString());
      assertTrue(((TextView) view).isTextSelectable());
    }
  }

  @Test
  public void markdownUsesLocalTextFormattingAndDoesNotLoadRemoteImages() throws Exception {
    TextView view =
        (TextView)
            preview("report.md", "# Report\n\n**bold**\n![image](https://example.invalid/private)");
    assertTrue(view.isTextSelectable());
    assertTrue(view.getText().toString().contains("Report"));
    assertTrue(view.getText().toString().contains("bold"));
    assertTrue(view.getText().toString().contains("example.invalid"));
  }

  @Test
  public void emptyDocumentStillCreatesSelectableContent() throws Exception {
    TextView view = (TextView) preview("empty.txt", "");
    assertEquals("", view.getText().toString());
    assertTrue(view.isTextSelectable());
  }

  @Test
  public void realPngPreviewUsesBoundedNativeDecoding() throws Exception {
    Bitmap source = Bitmap.createBitmap(3200, 200, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, encoded));
    source.recycle();
    Bitmap result =
        (Bitmap)
            Class.forName("com.deepseekharness.app.ui.NativeChatFilesDialog")
                .getDeclaredMethod("decodeImage", byte[].class)
                .invoke(null, (Object) encoded.toByteArray());
    assertEquals(1600, result.getWidth());
    assertEquals(100, result.getHeight());
    result.recycle();
  }

  @Test
  public void cancelledPdfDoesNotCreateOrRenderAnyTemporaryFile() throws Exception {
    java.util.Set<String> before = previewFiles();
    var render =
        Class.forName("com.deepseekharness.app.ui.NativeChatFilesDialog")
            .getDeclaredMethod(
                "renderPdf",
                Context.class,
                byte[].class,
                int.class,
                java.util.function.BooleanSupplier.class);
    render.setAccessible(true);
    try {
      render.invoke(
          null, context, new byte[] {1, 2, 3}, 0, (java.util.function.BooleanSupplier) () -> false);
      fail("A closed preview must not create or render a PDF");
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getCause() instanceof IOException);
    }
    assertEquals(before, previewFiles());
  }

  private java.util.Set<String> previewFiles() {
    String[] names =
        context.getCacheDir().list((directory, name) -> name.startsWith("dsha-native-preview-"));
    return names == null
        ? java.util.Set.of()
        : new java.util.HashSet<>(java.util.Arrays.asList(names));
  }

  @Test
  public void invalidBinaryNeverBecomesAnImage() throws Exception {
    try {
      Class.forName("com.deepseekharness.app.ui.NativeChatFilesDialog")
          .getDeclaredMethod("decodeImage", byte[].class)
          .invoke(null, (Object) new byte[] {1, 2, 3});
      fail("Unknown content must not become a preview");
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getCause() instanceof IOException);
    } catch (ClassNotFoundException | NoSuchMethodException absent) {
      throw new AssertionError("Missing bounded image decoder", absent);
    }
  }
}
