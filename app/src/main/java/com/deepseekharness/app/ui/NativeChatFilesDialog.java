package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatFilesRpc;
import com.deepseekharness.app.util.NativeChatFilesRpc.Entry;
import com.deepseekharness.app.util.NativeChatFilesRpc.Kind;
import com.deepseekharness.app.util.NativeChatFilesRpc.Listing;
import com.deepseekharness.app.util.NativeChatFilesRpc.TextPage;
import com.deepseekharness.app.util.NativeChatSessionsRpc.RequestGate;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Session-bound file tree and bounded native previews. No WebView or external file URLs. */
final class NativeChatFilesDialog implements DefaultLifecycleObserver {
  private final ChatHomeActivity host;
  private final String authUrl, cookie, sessionId;
  private final BooleanSupplier current;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final RequestGate gate = new RequestGate();
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AlertDialog dialog;
  private final TextView location, notice;
  private final LinearLayout rows;
  private final Button up, refresh;
  private Listing listing;
  private String path = "";
  private int shown = 40;
  private Preview preview;

  static void show(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      BooleanSupplier current) {
    new NativeChatFilesDialog(host, authUrl, cookie, sessionId, current);
  }

  private NativeChatFilesDialog(
      ChatHomeActivity host,
      String authUrl,
      String cookie,
      String sessionId,
      BooleanSupplier current) {
    this.host = host;
    this.authUrl = authUrl;
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.current = current;
    LinearLayout body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(16));
    label(
        body,
        t(
            "浏览当前会话工作区中的文件和生成作品。只读预览，不执行文件内容。",
            "Files and generated work in this chat's workspace. Read-only previews never execute file content."));
    location = label(body, "");
    location.setTextIsSelectable(true);
    LinearLayout navigation = new LinearLayout(host);
    body.addView(navigation, row());
    up = button(navigation, t("上一级", "Up"), () -> load(NativeChatFilesRpc.parentOf(path)));
    refresh = button(navigation, t("刷新", "Refresh"), () -> load(path));
    notice = label(body, "");
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    rows = column();
    body.addView(rows, row());
    ScrollView scroll = new ScrollView(host);
    scroll.setFillViewport(true);
    scroll.addView(body);
    dialog =
        new DshaDialogBuilder(host)
            .setTitle(t("工作区文件", "Workspace files"))
            .setView(scroll)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    dialog.setOnDismissListener(
        ignored -> {
          open.set(false);
          gate.close();
          if (preview != null) preview.dialog.dismiss();
          worker.shutdownNow();
          host.getLifecycle().removeObserver(this);
        });
    dialog.show();
    host.getLifecycle().addObserver(this);
    load("");
  }

  private boolean active() {
    return open.get() && current.getAsBoolean();
  }

  private boolean valid(long ticket) {
    return active() && gate.current(ticket);
  }

  private NativeChatFilesRpc client(BooleanSupplier valid) {
    return new NativeChatFilesRpc(authUrl, cookie, sessionId, valid);
  }

  private void load(String requested) {
    if (!active()) {
      unavailable();
      return;
    }
    long ticket = gate.beginRead();
    if (ticket == 0) return;
    rows.removeAllViews();
    refresh.setEnabled(false);
    up.setEnabled(false);
    notice.setText(t("正在读取文件夹…", "Reading folder…"));
    worker.execute(
        () -> {
          try {
            Listing result = client(() -> valid(ticket)).list(requested);
            main.post(
                () -> {
                  if (!valid(ticket) || !gate.finish(ticket)) return;
                  listing = result;
                  path = result.path();
                  shown = 40;
                  render();
                });
          } catch (IOException | RuntimeException failure) {
            main.post(
                () -> {
                  if (!open.get() || !gate.finish(ticket)) return;
                  refresh.setEnabled(active());
                  up.setEnabled(active() && !path.isEmpty());
                  notice.setText(
                      active()
                          ? t(
                              "无法读取此文件夹。请检查文件是否仍位于当前工作区，再刷新重试。",
                              "Could not read this folder. Check that it is still in this workspace, then refresh.")
                          : t(
                              "会话或服务已变化，请关闭后重新打开。",
                              "The chat or service changed. Close and reopen files."));
                });
          }
        });
  }

  private void render() {
    rows.removeAllViews();
    location.setText(path.isEmpty() ? t("工作区 /", "Workspace /") : "./" + path + "/");
    refresh.setEnabled(active());
    up.setEnabled(active() && !path.isEmpty());
    List<Entry> entries = new ArrayList<>(listing.entries());
    entries.sort(
        Comparator.comparing((Entry entry) -> !"directory".equals(entry.type()))
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
    for (int i = 0; i < Math.min(entries.size(), shown); i++) {
      Entry entry = entries.get(i);
      String type =
          "directory".equals(entry.type())
              ? t("文件夹", "Folder")
              : "other".equals(entry.type())
                  ? t("不支持的文件类型", "Unsupported file type")
                  : typeLabel(entry.name());
      String caption =
          entry.name() + "\n" + type + (entry.bytes() < 0 ? "" : " · " + size(entry.bytes()));
      Button item =
          button(
              rows,
              caption,
              () -> {
                if (!active()) {
                  unavailable();
                  return;
                }
                if ("directory".equals(entry.type())) load(entry.path());
                else if ("file".equals(entry.type())) openPreview(entry);
              });
      item.setAllCaps(false);
      item.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
      item.setEnabled(active() && !"other".equals(entry.type()));
    }
    if (shown < entries.size())
      button(
          rows,
          t("显示更多", "Show more"),
          () -> {
            shown += 40;
            render();
          });
    notice.setText(
        listing.truncated()
            ? t(
                "目录较大，后端仅返回部分文件。请打开子文件夹继续浏览。",
                "This large folder is only partially listed. Browse its subfolders for more files.")
            : entries.isEmpty() ? t("这个文件夹中还没有文件。", "This folder has no files yet.") : "");
  }

  private void unavailable() {
    notice.setText(t("会话或服务已变化，请关闭后重新打开。", "The chat or service changed. Close and reopen files."));
    up.setEnabled(false);
    refresh.setEnabled(false);
  }

  private void openPreview(Entry entry) {
    if (preview != null) preview.dialog.dismiss();
    preview = new Preview(entry);
  }

  private final class Preview {
    private final Entry entry;
    private final AlertDialog dialog;
    private final AtomicBoolean visible = new AtomicBoolean(true);
    private final RequestGate requests = new RequestGate();
    private final LinearLayout content;
    private final TextView status;
    private final Button previous, next, reload;
    private Bitmap bitmap;
    private ImageView image;
    private byte[] pdfBytes;
    private int page = 0;
    private int offset = 1;
    private int lines;
    private boolean eof;
    private int pageCount;

    Preview(Entry entry) {
      this.entry = entry;
      LinearLayout body = column();
      body.setPadding(dp(16), dp(8), dp(16), dp(16));
      label(body, "./" + entry.path() + (entry.bytes() < 0 ? "" : " · " + size(entry.bytes())));
      status = label(body, "");
      status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      LinearLayout controls = new LinearLayout(host);
      body.addView(controls, row());
      previous =
          button(
              controls,
              t("上一页", "Previous"),
              () -> {
                if (NativeChatFilesRpc.kind(entry.name()) == Kind.PDF) loadPdf(page - 1);
                else loadText(Math.max(1, offset - NativeChatFilesRpc.PAGE_LINES));
              });
      next =
          button(
              controls,
              t("下一页", "Next"),
              () -> {
                if (NativeChatFilesRpc.kind(entry.name()) == Kind.PDF) loadPdf(page + 1);
                else loadText(offset + lines);
              });
      reload = button(body, t("重新读取文件", "Reload file"), this::load);
      content = column();
      body.addView(content, row());
      ScrollView scroll = new ScrollView(host);
      scroll.setFillViewport(true);
      scroll.addView(body);
      dialog =
          new DshaDialogBuilder(host)
              .setTitle(entry.name())
              .setView(scroll)
              .setNegativeButton(t("关闭预览", "Close preview"), null)
              .create();
      dialog.setOnDismissListener(
          ignored -> {
            visible.set(false);
            requests.close();
            pdfBytes = null;
            clearBitmap();
            if (preview == this) preview = null;
          });
      dialog.show();
      load();
    }

    private boolean activePreview() {
      return visible.get() && active();
    }

    private boolean validPreview(long ticket) {
      return activePreview() && requests.current(ticket);
    }

    private void busy() {
      previous.setEnabled(false);
      next.setEnabled(false);
      reload.setEnabled(false);
      status.setText(t("正在读取预览…", "Reading preview…"));
    }

    private void load() {
      if (!activePreview()) return;
      switch (NativeChatFilesRpc.kind(entry.name())) {
        case TEXT -> loadText(1);
        case PDF -> {
          pdfBytes = null;
          loadPdf(0);
        }
        case IMAGE -> loadImage();
        default -> {
          previous.setVisibility(View.GONE);
          next.setVisibility(View.GONE);
          reload.setVisibility(View.GONE);
          status.setText(
              t(
                  "此格式尚不支持原生预览。文件仍保留在当前工作区；Office 文档可转为 PDF 后预览。",
                  "This format has no native preview yet. The file remains in this workspace. Convert Office documents to PDF to preview them."));
        }
      }
    }

    private void loadText(int wanted) {
      if (!activePreview()) return;
      long ticket = requests.beginRead();
      busy();
      worker.execute(
          () -> {
            try {
              TextPage result = client(() -> validPreview(ticket)).readText(entry, wanted);
              main.post(
                  () -> {
                    if (!validPreview(ticket) || !requests.finish(ticket)) return;
                    clearBitmap();
                    content.removeAllViews();
                    content.addView(createTextPreview(host, entry.name(), result.text()), row());
                    offset = result.offset();
                    lines = result.lines();
                    eof = result.eof();
                    status.setText(
                        lines == 0
                            ? t("空文件或已到文件末尾。", "Empty file or end of file.")
                            : t("行 ", "Lines ")
                                + offset
                                + "–"
                                + (offset + lines - 1)
                                + (eof ? t(" · 文件末尾", " · End of file") : ""));
                    previous.setEnabled(offset > 1);
                    next.setEnabled(!eof && lines > 0);
                    reload.setEnabled(true);
                  });
            } catch (IOException | RuntimeException failure) {
              failed(ticket);
            }
          });
    }

    private void loadImage() {
      if (!activePreview()) return;
      long ticket = requests.beginRead();
      busy();
      previous.setVisibility(View.GONE);
      next.setVisibility(View.GONE);
      worker.execute(
          () -> {
            try {
              byte[] bytes = client(() -> validPreview(ticket)).readBytes(entry);
              Bitmap result = decodeImage(bytes);
              main.post(
                  () -> {
                    if (!validPreview(ticket) || !requests.finish(ticket)) {
                      result.recycle();
                      return;
                    }
                    showBitmap(result);
                    status.setText(
                        t(
                            "图片预览（最大 8 MiB；大图已缩放）",
                            "Image preview (8 MiB limit; large images are scaled)"));
                    reload.setEnabled(true);
                  });
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
              failed(ticket);
            }
          });
    }

    private void loadPdf(int wanted) {
      if (!activePreview() || wanted < 0) return;
      long ticket = requests.beginRead();
      busy();
      byte[] cached = pdfBytes;
      worker.execute(
          () -> {
            try {
              byte[] bytes =
                  cached == null ? client(() -> validPreview(ticket)).readBytes(entry) : cached;
              PdfPage result = renderPdf(host, bytes, wanted, () -> validPreview(ticket));
              main.post(
                  () -> {
                    if (!validPreview(ticket) || !requests.finish(ticket)) {
                      result.bitmap().recycle();
                      return;
                    }
                    pdfBytes = bytes;
                    page = wanted;
                    pageCount = result.count();
                    showBitmap(result.bitmap());
                    status.setText(t("PDF 第 ", "PDF page ") + (page + 1) + " / " + pageCount);
                    previous.setEnabled(page > 0);
                    next.setEnabled(page + 1 < pageCount);
                    reload.setEnabled(true);
                  });
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
              failed(ticket);
            }
          });
    }

    private void showBitmap(Bitmap result) {
      clearBitmap();
      content.removeAllViews();
      bitmap = result;
      image = new ImageView(host);
      image.setAdjustViewBounds(true);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      image.setContentDescription(entry.name());
      image.setImageBitmap(result);
      content.addView(image, row());
    }

    private void clearBitmap() {
      if (image != null) {
        image.setImageDrawable(null);
        image = null;
      }
      if (bitmap != null) {
        bitmap.recycle();
        bitmap = null;
      }
    }

    private void failed(long ticket) {
      main.post(
          () -> {
            if (!visible.get() || !requests.finish(ticket)) return;
            status.setText(
                active()
                    ? t(
                        "无法预览。文件可能已变化、超出预览大小限制，或不支持此格式。请刷新后重试。",
                        "Preview unavailable. The file may have changed, exceeded the preview size limits, or use an unsupported format. Refresh and try again.")
                    : t(
                        "会话或服务已变化，请关闭后重新打开。",
                        "The chat or service changed. Close and reopen files."));
            reload.setEnabled(active());
          });
    }
  }

  static View createTextPreview(Context context, String name, String text) {
    TextView result = new TextView(context);
    String lower = name.toLowerCase(Locale.ROOT);
    boolean markdown = lower.endsWith(".md") || lower.endsWith(".markdown");
    result.setText(markdown ? NativeChatMarkdown.render(text) : text);
    result.setTextColor(context.getColor(R.color.gpt_text));
    result.setTextSize(15);
    result.setTextIsSelectable(true);
    if (!markdown) result.setTypeface(Typeface.MONOSPACE);
    // Deliberately no HTML parser, WebView, link movement, image loader or remote resources.
    return result;
  }

  static Bitmap decodeImage(byte[] data) throws IOException {
    if (data == null || data.length == 0 || data.length > NativeChatFilesRpc.MAX_FILE_BYTES)
      throw new IOException("Image preview exceeds size limit");
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
    BitmapFactory.Options options = new BitmapFactory.Options();
    try {
      options.inSampleSize = NativeChatFilesRpc.imageSampleSize(bounds.outWidth, bounds.outHeight);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Unsupported image dimensions", invalid);
    }
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (bitmap == null) throw new IOException("Unsupported image format");
    if (bitmap.getWidth() > 1600
        || bitmap.getHeight() > 1600
        || (long) bitmap.getWidth() * bitmap.getHeight() > 2000000L) {
      bitmap.recycle();
      throw new IOException("Decoded image exceeds native preview bounds");
    }
    return bitmap;
  }

  private record PdfPage(Bitmap bitmap, int count) {}

  private static PdfPage renderPdf(
      Context context, byte[] bytes, int pageIndex, BooleanSupplier current) throws IOException {
    if (!current.getAsBoolean()
        || bytes.length == 0
        || bytes.length > NativeChatFilesRpc.MAX_FILE_BYTES)
      throw new IOException("PDF preview unavailable");
    File temporary = File.createTempFile("dsha-native-preview-", ".pdf", context.getCacheDir());
    try {
      try (FileOutputStream output = new FileOutputStream(temporary)) {
        output.write(bytes);
      }
      try (ParcelFileDescriptor descriptor =
              ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer pdf = new PdfRenderer(descriptor)) {
        int count = pdf.getPageCount();
        if (!current.getAsBoolean() || pageIndex < 0 || pageIndex >= count)
          throw new IOException("PDF page unavailable");
        try (PdfRenderer.Page page = pdf.openPage(pageIndex)) {
          int width = page.getWidth(), height = page.getHeight();
          if (width < 1 || height < 1) throw new IOException("Invalid PDF page size");
          double scale =
              Math.min(
                  1600.0 / Math.max(width, height),
                  Math.sqrt(2000000.0 / ((double) width * height)));
          int renderedWidth = Math.max(1, (int) (width * scale));
          int renderedHeight = Math.max(1, (int) (height * scale));
          Bitmap bitmap =
              Bitmap.createBitmap(renderedWidth, renderedHeight, Bitmap.Config.ARGB_8888);
          try {
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            if (!current.getAsBoolean()) throw new IOException("PDF preview closed");
            return new PdfPage(bitmap, count);
          } catch (IOException | RuntimeException | Error failure) {
            bitmap.recycle();
            throw failure;
          }
        }
      }
    } finally {
      temporary.delete();
    }
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    dialog.dismiss();
  }

  private LinearLayout column() {
    LinearLayout result = new LinearLayout(host);
    result.setOrientation(LinearLayout.VERTICAL);
    return result;
  }

  private LinearLayout.LayoutParams row() {
    return new LinearLayout.LayoutParams(-1, -2);
  }

  private TextView label(LinearLayout parent, String value) {
    TextView result = new TextView(host);
    result.setText(value);
    result.setTextColor(host.getColor(R.color.gpt_muted));
    result.setTextSize(14);
    result.setPadding(0, dp(6), 0, dp(6));
    parent.addView(result, row());
    return result;
  }

  private Button button(LinearLayout parent, String title, Runnable action) {
    MaterialButton result = new MaterialButton(host);
    result.setText(title);
    result.setAllCaps(false);
    result.setMinHeight(dp(48));
    result.setTextColor(host.getColor(R.color.gpt_text));
    result.setBackgroundTintList(ColorStateList.valueOf(host.getColor(R.color.gpt_raised)));
    result.setCornerRadius(dp(12));
    result.setOnClickListener(ignored -> action.run());
    LinearLayout.LayoutParams params =
        parent.getOrientation() == LinearLayout.VERTICAL
            ? row()
            : new LinearLayout.LayoutParams(0, -2, 1f);
    params.topMargin = dp(6);
    parent.addView(result, params);
    return result;
  }

  private static String typeLabel(String name) {
    return switch (NativeChatFilesRpc.kind(name)) {
      case TEXT -> t("文本预览", "Text preview");
      case IMAGE -> t("图片", "Image");
      case PDF -> "PDF";
      case UNSUPPORTED -> t("文件 · 暂不支持预览", "File · No native preview");
    };
  }

  private static String size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
