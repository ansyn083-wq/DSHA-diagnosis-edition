package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** One sidebar, docked in a wide window and layered over chat in a narrow window. */
public final class ChatHomeLayout extends FrameLayout {
  private static final int DOCK_WIDTH_DP = 720;
  private View content;
  private View sidebar;
  private View scrim;
  private View toggle;
  private boolean measured;
  private boolean wide;
  private boolean open;
  private Runnable sidebarClosed = () -> {};

  public ChatHomeLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    content = findViewById(R.id.native_chat_content);
    sidebar = findViewById(R.id.native_chat_sidebar);
    scrim = findViewById(R.id.native_chat_scrim);
    toggle = findViewById(R.id.native_chat_features);
    scrim.setOnClickListener(v -> closeOverlay());
    toggle.setOnClickListener(v -> toggleSidebar());
    findViewById(R.id.native_chat_sidebar_close).setOnClickListener(v -> toggleSidebar());
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
    float density = getResources().getDisplayMetrics().density;
    boolean dock = width >= Math.round(DOCK_WIDTH_DP * density);
    if (!measured || dock != wide) {
      measured = true;
      wide = dock;
      open = wide;
    }
    int preferred = getResources().getDimensionPixelSize(R.dimen.gpt_sidebar_width);
    int sidebarWidth = Math.min(preferred, Math.max(0, width - Math.round(48 * density)));
    ((LayoutParams) sidebar.getLayoutParams()).width = sidebarWidth;
    LayoutParams chatBounds = (LayoutParams) content.getLayoutParams();
    int dockInset = wide && open ? sidebarWidth : 0;
    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    // FrameLayout reads physical margins before it measures the chat child.
    chatBounds.leftMargin = rtl ? 0 : dockInset;
    chatBounds.rightMargin = rtl ? dockInset : 0;
    render();
    super.onMeasure(widthSpec, heightSpec);
  }

  void setOnSidebarClosed(Runnable action) {
    sidebarClosed = action;
  }

  void toggleSidebar() {
    open = !open;
    if (!open) sidebarClosed.run();
    render();
    requestLayout();
  }

  boolean closeOverlay() {
    if (wide || !open) return false;
    toggleSidebar();
    return true;
  }

  private void render() {
    boolean overlay = open && !wide;
    sidebar.setVisibility(open ? VISIBLE : GONE);
    toggle.setVisibility(open ? INVISIBLE : VISIBLE);
    scrim.setVisibility(overlay ? VISIBLE : GONE);
    content.setImportantForAccessibility(
        overlay
            ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    toggle.setContentDescription(UiText.choose("DSHA，打开侧栏", "DSHA, open sidebar"));
  }
}
