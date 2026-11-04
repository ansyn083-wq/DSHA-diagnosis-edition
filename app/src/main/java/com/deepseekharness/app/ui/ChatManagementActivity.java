package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** All original management surfaces remain reachable without an automatic install redirect. */
public final class ChatManagementActivity extends AppCompatActivity
    implements LocalNetworkPermissionHost {
  private static final int CONTENT = R.id.fragment_container;
  private static final String EXTRA_PAGE = "management_page";

  enum Page {
    SETTINGS("设置", "Settings"),
    PLUGINS("插件", "Plugins"),
    WORKSPACE("工作区", "Workspace");

    final String chinese;
    final String english;

    Page(String chinese, String english) {
      this.chinese = chinese;
      this.english = english;
    }
  }

  static Intent pageIntent(Context context, Page page) {
    return new Intent(context, ChatManagementActivity.class).putExtra(EXTRA_PAGE, page.name());
  }

  static Page initialPage(Intent intent) {
    String page = intent.getStringExtra(EXTRA_PAGE);
    if (page != null) {
      for (Page value : Page.values()) if (value.name().equals(page)) return value;
    }
    return Page.SETTINGS;
  }

  private Fragment fragmentFor(Page page) {
    return switch (page) {
      case SETTINGS -> new SettingsFragment();
      case WORKSPACE -> new WorkspaceFragment();
      case PLUGINS ->
          com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(
                  com.deepseekharness.app.core.HarnessController.get(this))
              ? new EnvironmentRecoveryFragment()
              : new PluginFragment();
    };
  }

  private boolean requestingLocalNetwork;
  private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
          granted -> {
            requestingLocalNetwork = false;
            if (granted)
              com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
            else
              Toast.makeText(
                      this,
                      UiText.choose(
                          "未允许局域网访问；本机对话仍可使用",
                          "Local network access denied; local chats still work"),
                      Toast.LENGTH_LONG)
                  .show();
          });

  @Override
  public void requestLocalNetwork() {
    if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this))
      com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
    else if (!requestingLocalNetwork) {
      requestingLocalNetwork = true;
      getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
          .edit()
          .putBoolean("local_network_permission_asked", true)
          .apply();
      localNetworkPermission.launch(com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION);
    }
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    Page page = initialPage(getIntent());
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(getColor(R.color.gpt_bg));
    root.setFitsSystemWindows(true);
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(8), 0, dp(8), 0);
    android.widget.ImageButton back = new android.widget.ImageButton(this);
    back.setImageResource(R.drawable.ic_ui_back);
    back.setBackgroundResource(R.drawable.bg_action_plain);
    back.setContentDescription(UiText.choose("返回", "Back"));
    back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView title = new TextView(this);
    title.setText(UiText.choose(page.chinese, page.english));
    title.setTextSize(20);
    title.setTextColor(getColor(R.color.gpt_text));
    title.setGravity(Gravity.CENTER);
    bar.addView(title, new LinearLayout.LayoutParams(0, dp(64), 1));
    android.widget.ImageButton more = new android.widget.ImageButton(this);
    more.setImageResource(R.drawable.ic_ui_more);
    more.setBackgroundResource(R.drawable.bg_action_plain);
    more.setContentDescription(UiText.choose("更多", "More"));
    bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
    if (page != Page.SETTINGS) more.setVisibility(android.view.View.INVISIBLE);
    more.setOnClickListener(
        v -> {
          PopupMenu menu = new PopupMenu(this, more);
          String[] names = {
            UiText.choose("清除首页草稿", "Clear home draft"), UiText.choose("外观", "Appearance")
          };
          for (int i = 0; i < names.length; i++) menu.getMenu().add(0, i, i, names[i]);
          menu.setOnMenuItemClickListener(
              item -> {
                switch (item.getItemId()) {
                  case 0:
                    new DshaDialogBuilder(this)
                        .setMessage(
                            UiText.choose(
                                "清除暂存在首页的草稿？", "Clear the draft saved on the home screen?"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            android.R.string.ok,
                            (d, w) ->
                                getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, MODE_PRIVATE)
                                    .edit()
                                    .remove("draft")
                                    .apply())
                        .show();
                    break;
                  case 1:
                    new DshaDialogBuilder(this)
                        .setTitle(UiText.choose("外观", "Appearance"))
                        .setItems(
                            new String[] {
                              UiText.choose("跟随系统", "System"),
                              UiText.choose("浅色", "Light"),
                              UiText.choose("深色", "Dark")
                            },
                            (d, w) ->
                                ThemeController.select(
                                    this, new String[] {"system", "light", "dark"}[w]))
                        .show();
                    break;
                }
                return true;
              });
          menu.show();
        });
    root.addView(bar);
    FrameLayout frame = new FrameLayout(this);
    frame.setId(CONTENT);
    root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    if (saved == null) {
      getSupportFragmentManager().beginTransaction().replace(CONTENT, fragmentFor(page)).commit();
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
