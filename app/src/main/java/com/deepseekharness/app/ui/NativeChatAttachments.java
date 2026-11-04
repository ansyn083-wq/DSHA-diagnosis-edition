package com.deepseekharness.app.ui;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.UiText;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class NativeChatAttachments {
  private static final int MAX_ATTACHMENTS = 4;
  private static final int MAX_IMAGE_EDGE = 2048;
  private static final String CAMERA_DIR = "native-chat-camera";
  private static final String STATE_KEY = "native-chat-attachments";

  record Draft(String id, Uri uri, boolean image, boolean camera) {}

  private final ChatHomeActivity host;
  private final ContentResolver resolver;
  private final Supplier<String> selectedSession;
  private final Consumer<String> error;
  private final View strip;
  private final LinearLayout chips;
  private final View plus;
  private final ActivityResultLauncher<String[]> photos;
  private final ActivityResultLauncher<String[]> documents;
  private final ActivityResultLauncher<Uri> camera;
  private final HashMap<String, List<Draft>> drafts = new HashMap<>();
  private String pickerSession;
  private Uri cameraOutput;
  private List<Draft> rendered = List.of();
  private boolean renderedEditable = true;

  NativeChatAttachments(
      ChatHomeActivity host,
      Bundle saved,
      Supplier<String> selectedSession,
      Consumer<String> error) {
    this.host = host;
    this.selectedSession = selectedSession;
    this.error = error;
    resolver = host.getContentResolver();
    strip = host.findViewById(R.id.native_chat_attachment_strip);
    chips = host.findViewById(R.id.native_chat_attachments);
    plus = host.findViewById(R.id.native_chat_add);
    plus.setContentDescription(t("添加附件", "Add attachment"));
    plus.setOnClickListener(this::showMenu);
    restore(saved);
    photos =
        host.registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> onPick(uri, true, false));
    documents =
        host.registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> onPick(uri, false, false));
    camera =
        host.registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            accepted -> {
              Uri output = cameraOutput;
              cameraOutput = null;
              if (!accepted && output != null) deleteCamera(output);
              onPick(accepted ? output : null, true, true);
            });
  }

  private void showMenu(View anchor) {
    LinearLayout menu = new LinearLayout(host);
    menu.setOrientation(LinearLayout.VERTICAL);
    menu.setPadding(dp(10), dp(8), dp(10), dp(8));
    ScrollView viewport = new ScrollView(host);
    viewport.setVerticalScrollBarEnabled(false);
    viewport.addView(menu);
    Rect visible = new Rect();
    anchor.getWindowVisibleDisplayFrame(visible);
    int[] anchorPosition = new int[2];
    anchor.getLocationOnScreen(anchorPosition);
    int above = anchorPosition[1] - visible.top - dp(8);
    int below = visible.bottom - anchorPosition[1] - anchor.getHeight() - dp(8);
    boolean openAbove = above >= dp(244) || above >= below;
    int popupHeight = Math.min(dp(248), Math.max(dp(60), openAbove ? above : below));
    PopupWindow popup = new PopupWindow(viewport, dp(228), popupHeight, true);
    popup.setBackgroundDrawable(host.getDrawable(R.drawable.gpt_attachment_popup));
    popup.setElevation(dp(10));
    popup.setOutsideTouchable(true);
    popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    addAction(
        menu,
        popup,
        R.drawable.ic_native_chat_camera,
        t("拍照", "Camera"),
        () -> choose(this::capture));
    addAction(
        menu,
        popup,
        R.drawable.ic_native_chat_photo,
        t("照片", "Photos"),
        () -> choose(() -> photos.launch(new String[] {"image/*"})));
    addAction(
        menu,
        popup,
        R.drawable.ic_native_chat_file,
        t("文件", "Files"),
        () -> choose(() -> documents.launch(new String[] {"*/*"})));
    addAction(
        menu,
        popup,
        R.drawable.ic_plugins,
        t("插件", "Plugins"),
        () -> host.startActivity(HomeFeatureMenu.Feature.PLUGINS.intent(host)));
    int verticalOffset = openAbove ? -anchor.getHeight() - popupHeight - dp(6) : dp(6);
    popup.showAsDropDown(anchor, 0, verticalOffset, Gravity.START);
  }

  private void addAction(
      LinearLayout menu, PopupWindow popup, int iconRes, String name, Runnable action) {
    LinearLayout row = new LinearLayout(host);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(6), 0, dp(10), 0);
    android.util.TypedValue background = new android.util.TypedValue();
    host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
    row.setBackgroundResource(background.resourceId);
    row.setFocusable(true);
    row.setOnClickListener(
        view -> {
          popup.dismiss();
          action.run();
        });
    row.setContentDescription(name);
    ImageView icon = new ImageView(host);
    icon.setImageResource(iconRes);
    icon.setScaleType(ImageView.ScaleType.CENTER);
    icon.setColorFilter(host.getColor(R.color.gpt_text));
    icon.setBackgroundResource(R.drawable.gpt_avatar_circle);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
    TextView label = new TextView(host);
    label.setText(name);
    label.setTextColor(host.getColor(R.color.gpt_text));
    label.setTextSize(16);
    label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
    labelParams.setMarginStart(dp(12));
    row.addView(label, labelParams);
    menu.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private void choose(Runnable picker) {
    String target = selectedSession.get();
    if (draftsFor(target).size() >= MAX_ATTACHMENTS) {
      error.accept(t("一条消息最多添加 4 个附件", "You can add up to 4 attachments"));
      return;
    }
    pickerSession = target;
    try {
      picker.run();
    } catch (RuntimeException unavailable) {
      pickerSession = null;
      if (cameraOutput != null) {
        deleteCamera(cameraOutput);
        cameraOutput = null;
      }
      error.accept(t("设备无法打开这个选择器", "Could not open this picker"));
    }
  }

  private void capture() {
    File directory = new File(host.getCacheDir(), CAMERA_DIR);
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IllegalStateException("Camera cache unavailable");
    }
    File[] old = directory.listFiles();
    if (old != null) {
      long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
      for (File file : old) if (file.lastModified() < cutoff) file.delete();
    }
    File output = new File(directory, UUID.randomUUID() + ".jpg");
    cameraOutput = FileProvider.getUriForFile(host, host.getPackageName() + ".updates", output);
    camera.launch(cameraOutput);
  }

  private void onPick(Uri uri, boolean image, boolean fromCamera) {
    String target = pickerSession;
    pickerSession = null;
    if (uri == null || !"content".equals(uri.getScheme())) return;
    if (target == null || draftsFor(target).size() >= MAX_ATTACHMENTS) {
      if (fromCamera) deleteCamera(uri);
      return;
    }
    List<Draft> attachments = new ArrayList<>(draftsFor(target));
    attachments.add(new Draft(UUID.randomUUID().toString(), uri, image, fromCamera));
    drafts.put(target, attachments);
    render(true);
  }

  List<Draft> draftsFor(String target) {
    return List.copyOf(drafts.getOrDefault(target, List.of()));
  }

  void moveNewChatDraft(String session) {
    List<Draft> unsent = drafts.remove("");
    if (unsent != null) drafts.put(session, unsent);
  }

  void clearSent(String target) {
    List<Draft> sent = drafts.remove(target);
    if (sent == null) return;
    for (Draft draft : sent) if (draft.camera()) deleteCamera(draft.uri());
  }

  void render(boolean editable) {
    List<Draft> shown = draftsFor(selectedSession.get());
    if (shown.equals(rendered) && editable == renderedEditable) return;
    rendered = shown;
    renderedEditable = editable;
    chips.removeAllViews();
    for (int i = 0; i < shown.size(); i++) {
      Draft draft = shown.get(i);
      Button chip = new Button(host);
      chip.setAllCaps(false);
      chip.setText((draft.image() ? t("图片", "Image") : t("文件", "File")) + " " + (i + 1) + "  ×");
      chip.setEnabled(editable);
      chip.setOnClickListener(
          view -> {
            List<Draft> active = drafts.get(selectedSession.get());
            if (active == null || !active.remove(draft)) return;
            if (draft.camera()) deleteCamera(draft.uri());
            if (active.isEmpty()) drafts.remove(selectedSession.get());
            render(true);
          });
      chips.addView(chip);
    }
    strip.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
  }

  JsonArray encode(NativeChatRpc client, String target, List<Draft> attachments)
      throws IOException {
    JsonArray encoded = new JsonArray();
    for (Draft draft : attachments) {
      String name = displayName(draft.uri());
      if (draft.image()) {
        encoded.add(encodeImage(draft.uri(), name));
      } else {
        try (InputStream input = open(draft.uri())) {
          encoded.add(client.stageFile(target, name, input));
        }
      }
    }
    return encoded;
  }

  private InputStream open(Uri uri) throws IOException {
    InputStream input = resolver.openInputStream(uri);
    if (input == null) throw new IOException("Attachment unavailable");
    return input;
  }

  private JsonObject encodeImage(Uri uri, String name) throws IOException {
    Bitmap photo =
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(resolver, uri),
            (decoder, info, source) -> {
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              int width = info.getSize().getWidth();
              int height = info.getSize().getHeight();
              int edge = Math.max(width, height);
              if (edge > MAX_IMAGE_EDGE) {
                decoder.setTargetSize(
                    Math.max(1, Math.round(width * (MAX_IMAGE_EDGE / (float) edge))),
                    Math.max(1, Math.round(height * (MAX_IMAGE_EDGE / (float) edge))));
              }
            });
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      if (!photo.compress(Bitmap.CompressFormat.JPEG, 88, bytes)) {
        throw new IOException("Could not encode image");
      }
    } finally {
      photo.recycle();
    }
    JsonObject part = new JsonObject();
    part.addProperty("type", "image");
    part.addProperty("mediaType", "image/jpeg");
    int extension = name.lastIndexOf('.');
    part.addProperty("name", (extension > 0 ? name.substring(0, extension) : name) + ".jpg");
    part.addProperty("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
    return part;
  }

  private String displayName(Uri uri) {
    try (var cursor =
        resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
        String name = cursor.getString(0).replaceAll("[\\\\/\\p{Cntrl}]", "_").strip();
        if (!name.isEmpty()) return name.length() <= 200 ? name : name.substring(0, 200);
      }
    } catch (RuntimeException unavailable) {
      // The user grant may expose the bytes without exposing metadata.
    }
    return "attachment";
  }

  private void deleteCamera(Uri uri) {
    try {
      resolver.delete(uri, null, null);
    } catch (RuntimeException unavailable) {
      // The narrow camera cache is pruned on the next capture.
    }
  }

  void save(Bundle state) {
    Bundle saved = new Bundle();
    saved.putString("picker", pickerSession);
    if (cameraOutput != null) saved.putString("camera", cameraOutput.toString());
    ArrayList<Bundle> rows = new ArrayList<>();
    drafts.forEach(
        (session, list) -> {
          for (Draft draft : list) {
            Bundle row = new Bundle();
            row.putString("session", session);
            row.putString("id", draft.id());
            row.putString("uri", draft.uri().toString());
            row.putBoolean("image", draft.image());
            row.putBoolean("camera", draft.camera());
            rows.add(row);
          }
        });
    saved.putParcelableArrayList("rows", rows);
    state.putBundle(STATE_KEY, saved);
  }

  private void restore(Bundle state) {
    Bundle saved = state == null ? null : state.getBundle(STATE_KEY);
    if (saved == null) return;
    pickerSession = saved.getString("picker");
    String cameraUri = saved.getString("camera");
    if (cameraUri != null) cameraOutput = Uri.parse(cameraUri);
    ArrayList<Bundle> rows = saved.getParcelableArrayList("rows");
    if (rows == null) return;
    for (Bundle row : rows) {
      String uri = row.getString("uri");
      String id = row.getString("id");
      String session = row.getString("session");
      if (uri == null || id == null || session == null) continue;
      drafts
          .computeIfAbsent(session, key -> new ArrayList<>())
          .add(new Draft(id, Uri.parse(uri), row.getBoolean("image"), row.getBoolean("camera")));
    }
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
