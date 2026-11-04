package com.deepseekharness.app.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import java.util.regex.Pattern;

/** Formats common reply markup in a selectable TextView. */
final class NativeChatMarkdown {
  private static final Pattern INLINE =
      Pattern.compile("`([^`]+)`|\\*\\*(\\S(?:.*?\\S)?)\\*\\*|__([\\p{L}\\p{N}].*?)__");
  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");
  private static final Pattern BULLET = Pattern.compile("^(\\h*)[-+*]\\h+");
  private static final int SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

  private NativeChatMarkdown() {}

  /** 完整消息使用原生表格与公式；流式部分保持下面的轻量格式化。 */
  static android.view.View createView(android.content.Context context, String text) {
    return NativeChatRichMarkdown.createView(context, text);
  }

  static CharSequence render(String text) {
    var out = new SpannableStringBuilder();
    boolean code = false;
    boolean first = true;
    for (String line : text.split("\n", -1)) {
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("```") && (!code || trimmed.stripTrailing().equals("```"))) {
        code = !code;
        continue;
      }
      if (!first) out.append('\n');
      first = false;
      int start = out.length();
      if (code) {
        out.append(line);
        if (out.length() > start)
          out.setSpan(new TypefaceSpan("monospace"), start, out.length(), SPAN);
        continue;
      }
      var heading = HEADING.matcher(line);
      var bullet = BULLET.matcher(line);
      boolean title = heading.find();
      if (title) line = line.substring(heading.end());
      else if (bullet.find()) line = bullet.group(1) + "• " + line.substring(bullet.end());
      appendInline(out, line);
      if (title && out.length() > start) {
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), SPAN);
        out.setSpan(new RelativeSizeSpan(1.15f), start, out.length(), SPAN);
      }
    }
    return out;
  }

  private static void appendInline(SpannableStringBuilder out, String line) {
    var tokens = INLINE.matcher(line);
    int offset = 0;
    while (tokens.find()) {
      out.append(line, offset, tokens.start());
      int start = out.length();
      String code = tokens.group(1);
      String bold = tokens.group(2) == null ? tokens.group(3) : tokens.group(2);
      out.append(code == null ? bold : code);
      if (out.length() > start) {
        out.setSpan(
            code == null ? new StyleSpan(Typeface.BOLD) : new TypefaceSpan("monospace"),
            start,
            out.length(),
            SPAN);
      }
      offset = tokens.end();
    }
    out.append(line, offset, line.length());
  }
}
