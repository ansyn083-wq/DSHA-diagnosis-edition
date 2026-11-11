package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatMarkup;
import com.deepseekharness.app.util.UiText;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.ext.latex.JLatexMathBlock;
import io.noties.markwon.ext.latex.JLatexMathNode;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.tables.Table;
import io.noties.markwon.image.AsyncDrawableScheduler;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/** 完整回复的原生视图：普通文字可选择，表格横向滚动，公式只在本机绘制。 */
final class NativeChatRichMarkdown {
  // JLaTeXMath 共享字体/宏注册表；串行执行，队列有界且不阻塞主线程。
  private static final ThreadPoolExecutor MATH =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          task -> {
            Thread worker = new Thread(task, "dsha-native-math");
            worker.setDaemon(true);
            return worker;
          });

  private NativeChatRichMarkdown() {}

  static View createView(Context context, String source) {
    String text = source == null ? "" : source;
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = dp(context, 12);
    content.setPadding(padding, padding, padding, padding);
    try {
      if (text.length() > 100_000) throw new IllegalArgumentException("large markdown");
      Markwon markwon = renderer(context);
      Node parsed = markwon.parse(NativeChatMarkup.normalizeMath(text));
      sanitize(parsed, new int[2], 0);
      Document group = new Document();
      for (Node node = parsed.getFirstChild(); node != null; ) {
        Node next = node.getNext();
        if (node instanceof TableBlock) {
          appendText(content, markwon, group);
          group = new Document();
          content.addView(
              table(context, markwon, (TableBlock) node),
              new LinearLayout.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
          node.unlink();
          group.appendChild(node);
        }
        node = next;
      }
      appendText(content, markwon, group);
      if (content.getChildCount() == 0) content.addView(textView(context));
    } catch (RuntimeException | LinkageError failure) {
      // 无论解析还是依赖初始化失败，都保留完整原文，不丢失消息内容。
      release(content);
      content.removeAllViews();
      TextView fallback = textView(context);
      fallback.setText(text);
      content.addView(fallback);
    }
    return content;
  }

  private static Markwon renderer(Context context) {
    float size = 16 * context.getResources().getDisplayMetrics().scaledDensity;
    return Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(
            JLatexMathPlugin.create(
                size,
                builder -> {
                  builder.inlinesEnabled(true).executorService(MATH);
                  // 默认库会记录公式原文；这里保留可见文本占位，不向日志写会话内容。
                  builder.errorHandler((latex, error) -> null);
                  builder.theme().textColor(context.getColor(R.color.gpt_text));
                }))
        .usePlugin(
            new AbstractMarkwonPlugin() {
              @Override
              public void configureParser(Parser.Builder builder) {
                builder.extensions(Collections.singleton(TablesExtension.create()));
              }

              @Override
              public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                builder.linkResolver(
                    (view, link) -> {
                      if (!NativeChatMarkup.safeLink(link)) return;
                      try {
                        view.getContext()
                            .startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                    .addCategory(Intent.CATEGORY_BROWSABLE));
                      } catch (RuntimeException unavailable) {
                        Toast.makeText(
                                view.getContext(),
                                UiText.choose("无法打开链接", "Unable to open link"),
                                Toast.LENGTH_SHORT)
                            .show();
                      }
                    });
              }

              @Override
              public void configureVisitor(MarkwonVisitor.Builder builder) {
                builder.on(
                    HtmlInline.class,
                    (visitor, node) -> visitor.builder().append(node.getLiteral()));
                builder.on(
                    HtmlBlock.class,
                    (visitor, node) -> {
                      visitor.blockStart(node);
                      visitor.builder().append(node.getLiteral());
                      visitor.blockEnd(node);
                    });
                builder.on(
                    Link.class,
                    (visitor, link) -> {
                      int start = visitor.length();
                      visitor.visitChildren(link);
                      if (NativeChatMarkup.safeLink(link.getDestination())) {
                        io.noties.markwon.core.CoreProps.LINK_DESTINATION.set(
                            visitor.renderProps(), link.getDestination());
                        visitor.setSpansForNodeOptional(link, start);
                      }
                    });
                // Table.parse 需要直接渲染单元格；嵌套表格退化为可选择的文本，不隐藏内容。
                builder.on(
                    TableCell.class,
                    (visitor, cell) -> {
                      visitor.visitChildren(cell);
                    });
                builder.on(
                    TableBlock.class,
                    (visitor, node) -> {
                      visitor.blockStart(node);
                      visitor.visitChildren(node);
                      visitor.blockEnd(node);
                    });
                builder.on(TableHead.class, NativeChatRichMarkdown::visitTableRow);
                builder.on(TableBody.class, (visitor, node) -> visitor.visitChildren(node));
                builder.on(
                    org.commonmark.ext.gfm.tables.TableRow.class,
                    NativeChatRichMarkdown::visitTableRow);
              }
            })
        .build();
  }

  private static void visitTableRow(MarkwonVisitor visitor, Node row) {
    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
      cell.accept(visitor);
      if (cell.getNext() != null) visitor.builder().append("  |  ");
    }
    visitor.ensureNewLine();
  }

  private static void sanitize(Node parent, int[] count, int depth) {
    if (depth > 64) throw new IllegalArgumentException("deep markdown tree");
    for (Node node = parent.getFirstChild(); node != null; ) {
      if (++count[0] > 5000) throw new IllegalArgumentException("large markdown tree");
      Node next = node.getNext();
      String latex =
          node instanceof JLatexMathNode
              ? ((JLatexMathNode) node).latex()
              : node instanceof JLatexMathBlock ? ((JLatexMathBlock) node).latex() : null;
      if (latex != null && (++count[1] > 64 || !NativeChatMarkup.safeFormula(latex))) {
        Text literal = new Text(latex);
        if (node instanceof JLatexMathBlock) {
          Paragraph paragraph = new Paragraph();
          paragraph.appendChild(literal);
          node.insertBefore(paragraph);
        } else {
          node.insertBefore(literal);
        }
        node.unlink();
      } else {
        sanitize(node, count, depth + 1);
      }
      node = next;
    }
  }

  private static void appendText(LinearLayout parent, Markwon markwon, Node node) {
    if (node.getFirstChild() == null) return;
    TextView text = textView(parent.getContext());
    markwon.setParsedMarkdown(text, markwon.render(node));
    text.setTextIsSelectable(true);
    text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    if (parent.getChildCount() > 0) params.topMargin = dp(parent.getContext(), 8);
    parent.addView(text, params);
  }

  private static View table(Context context, Markwon markwon, TableBlock block) {
    Table parsed = Table.parse(markwon, block);
    if (parsed == null || parsed.rows().isEmpty())
      throw new IllegalArgumentException("empty table");
    int columns = parsed.rows().get(0).columns().size();
    if (columns > 32 || parsed.rows().size() * columns > 1024)
      throw new IllegalArgumentException("large table");
    HorizontalScrollView scroll = new HorizontalScrollView(context);
    scroll.setHorizontalScrollBarEnabled(true);
    scroll.setFillViewport(true);
    TableLayout table = new TableLayout(context);
    table.setStretchAllColumns(true);
    table.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setCollectionInfo(
                AccessibilityNodeInfo.CollectionInfo.obtain(parsed.rows().size(), columns, false));
          }
        });
    for (int r = 0; r < parsed.rows().size(); r++) {
      Table.Row source = parsed.rows().get(r);
      TableRow row = new TableRow(context);
      for (int c = 0; c < source.columns().size(); c++) {
        Table.Column value = source.columns().get(c);
        TextView cell = textView(context);
        cell.setMinWidth(dp(context, 112));
        cell.setMaxWidth(dp(context, 240));
        cell.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        cell.setGravity(
            Gravity.TOP
                | (value.alignment() == Table.Alignment.RIGHT
                    ? Gravity.RIGHT
                    : value.alignment() == Table.Alignment.CENTER
                        ? Gravity.CENTER_HORIZONTAL
                        : Gravity.LEFT));
        if (source.header()) {
          cell.setTypeface(cell.getTypeface(), Typeface.BOLD);
          ViewCompat.setAccessibilityHeading(cell, true);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(
            context.getColor(source.header() ? R.color.gpt_raised : R.color.gpt_bg));
        background.setStroke(dp(context, 1), context.getColor(R.color.gpt_line));
        cell.setBackground(background);
        final int rowIndex = r, columnIndex = c;
        cell.setAccessibilityDelegate(
            new View.AccessibilityDelegate() {
              @Override
              public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionItemInfo(
                    AccessibilityNodeInfo.CollectionItemInfo.obtain(
                        rowIndex, 1, columnIndex, 1, source.header()));
              }
            });
        markwon.setParsedMarkdown(cell, value.content());
        cell.setTextIsSelectable(true);
        cell.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        row.addView(
            cell,
            new TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      }
      table.addView(row);
    }
    scroll.addView(
        table,
        new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return scroll;
  }

  private static TextView textView(Context context) {
    TextView text = new TextView(context);
    text.setTextColor(context.getColor(R.color.gpt_text));
    text.setTextSize(16);
    text.setLineSpacing(dp(context, 3), 1f);
    text.setTextIsSelectable(true);
    return text;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static void release(View view) {
    if (view instanceof TextView) AsyncDrawableScheduler.unschedule((TextView) view);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) release(group.getChildAt(i));
    }
  }
}
