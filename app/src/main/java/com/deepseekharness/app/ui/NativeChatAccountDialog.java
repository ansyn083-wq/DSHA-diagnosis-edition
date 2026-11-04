package com.deepseekharness.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.NativeChatAccountRpc;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 本机账号与 API 配置窗口；写入只使用同代鉴权服务，不显示已有密钥。 */
final class NativeChatAccountDialog implements DefaultLifecycleObserver {
  private final ChatHomeActivity host;
  private final Consumer<String> useProvider;
  private final Consumer<String> removedProvider;
  private final HarnessController harness;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final NativeChatAccountRpc.RequestGate gate = new NativeChatAccountRpc.RequestGate();
  private final AlertDialog dialog;
  private final TextView accountStatus;
  private final TextView keyStatus;
  private final EditText apiKey;
  private final EditText customAddress;
  private final EditText customModel;
  private final EditText customKey;
  private final LinearLayout customFields;
  private final LinearLayout providerList;
  private final TextView customStatus;
  private final TextView editorTitle;
  private final Button expandCustom;
  private final Button refreshCustom;
  private final Button cancelEdit;
  private final Button saveCustom;
  private final Button login;
  private final Button logout;
  private final Button cancelLogin;
  private final TextView notice;
  private final Button saveKey;
  private final List<Button> providerButtons = new ArrayList<>();
  private NativeChatAccountRpc.Catalog catalog;
  private NativeChatAccountRpc.CustomApi editing;
  private AlertDialog confirmation;
  // 只在单一工作线程创建和使用；窗口存活期间不自动重绑新服务。
  private NativeChatRpc client;
  private volatile BooleanSupplier currentService = () -> false;
  private boolean signedIn;
  private boolean keyConfigured;
  private boolean openAuthorization;
  private boolean confirming;
  private String ownedAttempt = "";
  private long pollUntil;
  private final Runnable poll = this::refreshAccount;

  static AlertDialog show(
      ChatHomeActivity host,
      Consumer<String> useProvider,
      Consumer<String> removedProvider,
      Runnable onDismiss) {
    return new NativeChatAccountDialog(host, useProvider, removedProvider, onDismiss).dialog;
  }

  private NativeChatAccountDialog(
      ChatHomeActivity host,
      Consumer<String> useProvider,
      Consumer<String> removedProvider,
      Runnable onDismiss) {
    this.host = host;
    this.useProvider = useProvider;
    this.removedProvider = removedProvider;
    harness = HarnessController.get(host.getApplicationContext());
    LinearLayout body = new LinearLayout(host);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(8), dp(20), dp(12));
    addText(
        body,
        t(
            "模型请求使用 API。DeepSeek 登录用于账号资料。",
            "Model requests use your API. DeepSeek sign-in is for your account profile."),
        14,
        R.color.gpt_muted,
        false);

    LinearLayout api = section(body, "DeepSeek API");
    keyStatus =
        addText(api, t("正在读取 API 状态…", "Reading API status…"), 14, R.color.gpt_muted, false);
    apiKey =
        field(
            api,
            "API Key",
            t("输入 DeepSeek API Key", "Enter your DeepSeek API key"),
            InputType.TYPE_TEXT_VARIATION_PASSWORD);
    saveKey = addButton(api, t("保存并使用 API Key", "Save and use API key"), true, this::saveKey);

    LinearLayout accountSection = section(body, t("DeepSeek 账号", "DeepSeek account"));
    accountStatus =
        addText(
            accountSection,
            t("正在读取账号状态…", "Reading account status…"),
            14,
            R.color.gpt_muted,
            false);
    login = addButton(accountSection, t("登录 DeepSeek", "Sign in to DeepSeek"), false, this::signIn);
    cancelLogin =
        addButton(accountSection, t("取消本次登录", "Cancel sign-in"), false, this::cancelSignIn);
    logout =
        addButton(
            accountSection,
            t("退出 DeepSeek 登录", "Sign out of DeepSeek"),
            false,
            this::confirmSignOut);

    LinearLayout custom = section(body, t("自定义 API", "Custom API"));
    addText(
        custom,
        t(
            "管理在 DSHA 原生界面添加的 OpenAI 兼容 HTTPS 接口。",
            "Manage OpenAI-compatible HTTPS endpoints added in DSHA's native interface."),
        14,
        R.color.gpt_muted,
        false);
    customStatus =
        addText(custom, t("正在读取自定义 API…", "Reading custom APIs…"), 14, R.color.gpt_muted, false);
    providerList = new LinearLayout(host);
    providerList.setOrientation(LinearLayout.VERTICAL);
    custom.addView(providerList);
    refreshCustom = addButton(custom, t("刷新接口列表", "Refresh APIs"), false, this::reloadCustomApis);
    expandCustom =
        addButton(custom, t("添加自定义 API", "Add a custom API"), false, this::toggleCustomEditor);
    customFields = new LinearLayout(host);
    customFields.setOrientation(LinearLayout.VERTICAL);
    customFields.setVisibility(View.GONE);
    custom.addView(customFields);
    editorTitle =
        addText(customFields, t("添加自定义 API", "Add a custom API"), 15, R.color.gpt_text, true);
    customAddress =
        field(
            customFields,
            t("接口地址", "Base URL"),
            "https://example.com/v1",
            InputType.TYPE_TEXT_VARIATION_URI);
    customModel =
        field(
            customFields,
            t("模型 ID", "Model ID"),
            t("输入模型 ID", "Enter model ID"),
            InputType.TYPE_TEXT_VARIATION_NORMAL);
    customKey =
        field(
            customFields,
            "API Key",
            t("输入自定义 API Key", "Enter the custom API key"),
            InputType.TYPE_TEXT_VARIATION_PASSWORD);
    saveCustom =
        addButton(
            customFields, t("添加并使用自定义 API", "Add and use custom API"), true, this::saveCustomApi);
    cancelEdit =
        addButton(customFields, t("取消编辑", "Cancel editing"), false, () -> resetCustomEditor(false));
    notice = addText(body, "", 14, R.color.gpt_muted, false);
    notice.setVisibility(View.GONE);
    notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

    ScrollView scroll = new ScrollView(host);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    body.setFocusableInTouchMode(true);
    scroll.addView(body);
    dialog =
        new DshaDialogBuilder(host)
            .setTitle(t("账号与 API", "Account and API"))
            .setView(scroll)
            .setNegativeButton(t("关闭", "Close"), null)
            .create();
    dialog.setOnDismissListener(
        d -> {
          gate.close();
          host.getLifecycle().removeObserver(this);
          main.removeCallbacksAndMessages(null);
          if (confirmation != null) confirmation.dismiss();
          worker.shutdownNow();
          apiKey.setText("");
          customKey.setText("");
          onDismiss.run();
        });
    dialog.show();
    secure(dialog);
    host.getLifecycle().addObserver(this);
    refreshControls();
    run(
        rpc -> {
          var account = rpc.accountState();
          boolean configured = rpc.hasDeepSeekApiKey();
          var customApis = new NativeChatAccountRpc(rpc).customApis();
          return () -> {
            keyConfigured = configured;
            showKeyState();
            showAccount(account);
            showCustomApis(customApis);
          };
        });
  }

  private void signIn() {
    if (blocked()) return;
    accountStatus.setText(t("正在准备 DeepSeek 授权…", "Preparing DeepSeek authorization…"));
    openAuthorization = true;
    pollUntil = SystemClock.elapsedRealtime() + 180_000;
    mutate(
        rpc -> {
          var state = rpc.startSignIn(Constants.DSH_VERSION, new ConfigStore(host).getUiLanguage());
          return () -> {
            ownedAttempt = state.attemptId();
            showAccount(state);
          };
        });
  }

  private void cancelSignIn() {
    if (blocked() || ownedAttempt.isEmpty()) return;
    String attempt = ownedAttempt;
    openAuthorization = false;
    pollUntil = 0;
    main.removeCallbacks(poll);
    mutate(
        rpc -> {
          new NativeChatAccountRpc(rpc).cancelSignIn(attempt);
          var state = rpc.accountState();
          return () -> {
            ownedAttempt = "";
            showAccount(state);
          };
        });
  }

  private void confirmSignOut() {
    if (blocked() || !signedIn) return;
    run(
        rpc -> {
          Boolean running;
          try {
            running = new NativeChatAccountRpc(rpc).hasRunningAccountTasks();
          } catch (IOException unavailable) {
            running = null;
          }
          Boolean impact = running;
          return () -> {
            String message =
                impact == null
                    ? t(
                        "暂时无法确认任务状态。退出登录可能中断使用此账号的任务。API Key 和聊天记录会保留，是否继续？",
                        "Running tasks could not be checked. Signing out may interrupt tasks using this account. API keys and chats are kept. Continue?")
                    : impact
                        ? t(
                            "仍有任务在使用此账号，退出登录可能中断这些任务。API Key 和聊天记录会保留，是否继续？",
                            "Tasks are using this account and may be interrupted. API keys and chats are kept. Sign out?")
                        : t(
                            "退出此 DeepSeek 账号？API Key 和聊天记录会保留。",
                            "Sign out of this DeepSeek account? API keys and chats are kept.");
            confirm(
                t("退出登录", "Sign out"),
                message,
                t("退出登录", "Sign out"),
                () ->
                    mutate(
                        active -> {
                          new NativeChatAccountRpc(active)
                              .signOut(
                                  Constants.DSH_VERSION, new ConfigStore(host).getUiLanguage());
                          return () -> {
                            ownedAttempt = "";
                            pollUntil = 0;
                            openAuthorization = false;
                            showAccount(new NativeChatRpc.AccountState(false, "", "", ""));
                            showNotice(
                                t(
                                    "已退出 DeepSeek 登录，API 配置已保留。",
                                    "Signed out of DeepSeek. API settings are kept."));
                          };
                        }));
          };
        });
  }

  private void refreshAccount() {
    if (!host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
    run(
        rpc -> {
          var state = rpc.accountState();
          return () -> showAccount(state);
        });
  }

  private void showAccount(NativeChatRpc.AccountState state) {
    signedIn = state.signedIn();
    String phase = state.phase();
    boolean pending =
        switch (phase) {
          case "initializing", "waiting-browser", "exchanging", "committing" -> true;
          default -> false;
        };
    boolean polling = pollUntil > SystemClock.elapsedRealtime();
    if (!state.attemptId().equals(ownedAttempt) || !pending) ownedAttempt = "";
    if ("waiting-browser".equals(phase) && openAuthorization) {
      openAuthorization = false;
      if (state.authorizationUrl().isEmpty()) {
        pollUntil = 0;
        accountStatus.setText(t("授权地址校验失败", "Authorization address could not be verified"));
        return;
      }
      try {
        host.startActivity(
            new Intent(Intent.ACTION_VIEW, Uri.parse(state.authorizationUrl()))
                .addCategory(Intent.CATEGORY_BROWSABLE));
      } catch (android.content.ActivityNotFoundException unavailable) {
        pollUntil = 0;
        accountStatus.setText(t("需要浏览器打开授权页", "A browser is needed for authorization"));
        return;
      }
    }
    accountStatus.setText(
        signedIn
            ? t("DeepSeek 账号已登录", "DeepSeek account signed in")
            : pending && polling
                ? t("请在授权页完成登录，再返回 DSHA", "Finish authorization, then return to DSHA")
                : "failed".equals(phase) || "expired".equals(phase)
                    ? t("授权未完成，可重新登录", "Authorization did not complete. You can retry.")
                    : t("DeepSeek 账号未登录", "DeepSeek account signed out"));
    if (!pending || !polling) {
      openAuthorization = false;
      pollUntil = 0;
    }
  }

  private void showCustomApis(NativeChatAccountRpc.Catalog next) {
    catalog = next;
    providerList.removeAllViews();
    providerButtons.clear();
    customStatus.setText(
        !next.writable()
            ? t("当前配置为只读。", "These settings are read-only.")
            : next.providers().isEmpty()
                ? t("还没有原生自定义 API。", "No native custom APIs yet.")
                : t("选择已有接口，或编辑、删除配置。", "Use an existing API, or edit or delete its settings."));
    for (var provider : next.providers()) {
      LinearLayout row = section(providerList, SensitiveData.redact(provider.name()));
      addText(row, SensitiveData.redact(provider.address()), 13, R.color.gpt_muted, false);
      addText(
          row,
          provider.keyConfigured()
              ? t("API Key 已配置", "API key configured")
              : t("API Key 未配置", "API key not configured"),
          13,
          R.color.gpt_muted,
          false);
      providerButtons.add(
          addButton(
              row,
              t("使用此 API", "Use this API"),
              true,
              () -> {
                if (!blocked()) chooseProvider(provider.provider());
              }));
      if (provider.editable()) {
        providerButtons.add(
            addButton(row, t("编辑配置", "Edit API"), false, () -> editCustomApi(provider)));
      } else {
        addText(
            row,
            t(
                "复杂或继承的配置请在高级设置中编辑。",
                "Edit complex or inherited configurations in advanced settings."),
            13,
            R.color.gpt_muted,
            false);
      }
      if (provider.removable()) {
        providerButtons.add(
            addButton(row, t("删除此 API", "Delete this API"), false, () -> confirmRemove(provider)));
      }
    }
  }

  private void reloadCustomApis() {
    run(
        rpc -> {
          var next = new NativeChatAccountRpc(rpc).customApis();
          var account = rpc.accountState();
          boolean configured = rpc.hasDeepSeekApiKey();
          return () -> {
            boolean uncertain = !gate.canWrite();
            gate.reconciled();
            showCustomApis(next);
            showAccount(account);
            keyConfigured = configured;
            showKeyState();
            if (uncertain) {
              resetCustomEditor(false);
              apiKey.setText("");
              showNotice(
                  t(
                      "已重新读取状态，请核对现有配置后再操作。未确认的输入已清空，避免重复添加。",
                      "State refreshed. Check existing settings before another change. Unconfirmed inputs were cleared to prevent duplicate additions."));
            }
          };
        });
  }

  private void toggleCustomEditor() {
    if (blocked()) return;
    resetCustomEditor(customFields.getVisibility() != View.VISIBLE || editing != null);
  }

  private void resetCustomEditor(boolean show) {
    if (blocked()) return;
    editing = null;
    customAddress.setText("");
    customModel.setText("");
    customKey.setText("");
    customAddress.setError(null);
    customModel.setError(null);
    customKey.setError(null);
    customKey.setHint(t("输入自定义 API Key", "Enter the custom API key"));
    editorTitle.setText(t("添加自定义 API", "Add a custom API"));
    saveCustom.setText(t("添加并使用自定义 API", "Add and use custom API"));
    customFields.setVisibility(show ? View.VISIBLE : View.GONE);
    expandCustom.setText(
        show ? t("收起自定义配置", "Hide custom API settings") : t("添加自定义 API", "Add a custom API"));
  }

  private void editCustomApi(NativeChatAccountRpc.CustomApi provider) {
    if (blocked()) return;
    resetCustomEditor(true);
    editing = provider;
    customAddress.setText(provider.address());
    customModel.setText(provider.model());
    customKey.setHint(t("留空保留现有 API Key", "Leave blank to keep the current API key"));
    editorTitle.setText(t("编辑：", "Edit: ") + SensitiveData.redact(provider.name()));
    saveCustom.setText(t("保存修改", "Save changes"));
  }

  private void confirmRemove(NativeChatAccountRpc.CustomApi provider) {
    if (blocked()) return;
    confirm(
        t("删除自定义 API", "Delete custom API"),
        t("删除“", "Delete “")
            + SensitiveData.redact(provider.name())
            + t(
                "”？使用它的会话需要重新选择模型。已保存的密钥会保留，以免影响其他配置。",
                "”? Conversations using it will need another model. Stored keys are kept to protect other configurations."),
        t("删除", "Delete"),
        () ->
            mutate(
                rpc -> {
                  var accounts = new NativeChatAccountRpc(rpc);
                  accounts.removeCustomApi(provider);
                  return () -> {
                    removedProvider.accept(provider.provider());
                    if (editing != null && editing.provider().equals(provider.provider()))
                      resetCustomEditor(false);
                    reloadCustomApis();
                    showNotice(t("接口已删除，已保存的密钥未删除。", "API removed. Stored keys were kept."));
                  };
                }));
  }

  private void confirm(String title, String message, String accept, Runnable action) {
    if (blocked()) return;
    confirming = true;
    refreshControls();
    confirmation =
        new DshaDialogBuilder(host)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(
                accept,
                (d, which) -> {
                  confirming = false;
                  if (gate.isOpen()) action.run();
                })
            .create();
    confirmation.setOnDismissListener(
        d -> {
          confirming = false;
          confirmation = null;
          if (gate.isOpen()) refreshControls();
        });
    confirmation.show();
    secure(confirmation);
  }

  private void saveCustomApi() {
    if (blocked()) return;
    String address = customAddress.getText().toString().strip();
    String model = customModel.getText().toString().strip();
    String key = customKey.getText().toString().strip();
    if (!NativeChatRpc.validCustomApiAddress(address)) {
      customAddress.setError(t("请输入 HTTPS API 地址", "Enter an HTTPS API address"));
      return;
    }
    if (model.isEmpty()) {
      customModel.setError(t("请输入模型 ID", "Enter a model ID"));
      return;
    }
    if (key.isEmpty() && editing == null) {
      customKey.setError(t("请输入自定义 API Key", "Enter the custom API key"));
      return;
    }
    if (!key.isEmpty()) SensitiveData.registerKnownSecret(key);
    var selected = editing;
    mutate(
        rpc -> {
          var accounts = new NativeChatAccountRpc(rpc);
          if (selected == null) {
            String provider = accounts.addCustomApi(address, model, key);
            return () -> chooseProvider(provider);
          }
          accounts.updateCustomApi(selected, address, model, key);
          return () -> {
            resetCustomEditor(false);
            reloadCustomApis();
            showNotice(t("自定义 API 已更新。", "Custom API updated."));
          };
        });
  }

  private void saveKey() {
    if (blocked()) return;
    String value = apiKey.getText().toString().strip();
    if (value.isEmpty()) {
      if (keyConfigured) chooseProvider(NativeChatRpc.DEEPSEEK_API);
      else apiKey.setError(t("请输入 API Key", "Enter an API key"));
      return;
    }
    SensitiveData.registerKnownSecret(value);
    mutate(
        rpc -> {
          rpc.saveDeepSeekApiKey(value);
          return () -> {
            apiKey.setText("");
            keyConfigured = true;
            showKeyState();
            chooseProvider(NativeChatRpc.DEEPSEEK_API);
          };
        });
  }

  private void showKeyState() {
    keyStatus.setText(
        keyConfigured
            ? t("DeepSeek API Key 已配置", "DeepSeek API key configured")
            : t("DeepSeek API Key 未配置", "DeepSeek API key not configured"));
    saveKey.setText(
        keyConfigured
            ? t("使用或更新 API Key", "Use or update API key")
            : t("保存并使用 API Key", "Save and use API key"));
  }

  private void chooseProvider(String provider) {
    if (!gate.isOpen()) return;
    if (!currentService.getAsBoolean()) {
      showNotice(t("服务已变更，请关闭并重新打开本窗口。", "The service changed. Close and reopen this window."));
      return;
    }
    useProvider.accept(provider);
    dialog.dismiss();
  }

  private interface Request {
    Runnable call(NativeChatRpc rpc) throws IOException;
  }

  private boolean blocked() {
    return !gate.isOpen() || gate.isBusy() || confirming;
  }

  private void mutate(Request request) {
    if (!gate.canWrite()) return;
    run(request, true);
  }

  private void run(Request request) {
    run(request, false);
  }

  private void run(Request request, boolean mutation) {
    if (mutation && !gate.canWrite()) return;
    if (confirming || !dialog.isShowing() || !gate.begin()) return;
    notice.setVisibility(View.GONE);
    main.removeCallbacks(poll);
    refreshControls();
    worker.execute(
        () -> {
          Runnable complete;
          try {
            if (client == null) {
              var identity = harness.getReadyWebPageIdentity();
              if (identity == null
                  || harness.isUserStopped()
                  || harness.isStopping()
                  || harness.hasUnresolvedWebSession()) throw new IOException("DSH not ready");
              String cookie = harness.exchangeDshAuthCookie();
              currentService =
                  () ->
                      gate.isOpen()
                          && identity.equals(harness.getReadyWebPageIdentity())
                          && !harness.isUserStopped()
                          && !harness.isStopping()
                          && !harness.hasUnresolvedWebSession();
              client = new NativeChatRpc(identity.authUrl(), cookie, currentService);
            }
            complete = request.call(client);
          } catch (IOException | RuntimeException unavailable) {
            complete =
                () -> {
                  if (mutation) gate.uncertainWrite();
                  openAuthorization = false;
                  pollUntil = 0;
                  showNotice(
                      t(
                          "操作未能确认完成。请刷新状态后重试；若配置已变更，请取消编辑后重新选择接口。服务重启后请关闭并重新打开本窗口。",
                          "The operation could not be confirmed. Refresh before retrying; if settings changed, cancel editing and reopen the API. After a service restart, close and reopen this window."));
                };
          }
          Runnable result = complete;
          main.post(
              () -> {
                if (!gate.isOpen() || !dialog.isShowing()) return;
                gate.finish();
                if (client != null && !currentService.getAsBoolean()) {
                  openAuthorization = false;
                  pollUntil = 0;
                  showNotice(
                      t(
                          "服务已变更，请关闭并重新打开本窗口。",
                          "The service changed. Close and reopen this window."));
                  refreshControls();
                  return;
                }
                result.run();
                if (!gate.isOpen()) return;
                refreshControls();
                if (!gate.isBusy()
                    && pollUntil > SystemClock.elapsedRealtime()
                    && host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                  main.postDelayed(poll, 900);
                }
              });
        });
  }

  private void showNotice(String text) {
    notice.setText(text);
    notice.setVisibility(View.VISIBLE);
  }

  @Override
  public void onResume(LifecycleOwner owner) {
    if (pollUntil > 0) refreshAccount();
  }

  @Override
  public void onPause(LifecycleOwner owner) {
    main.removeCallbacks(poll);
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    dialog.dismiss();
  }

  private void refreshControls() {
    boolean enabled = !blocked();
    login.setEnabled(enabled && gate.canWrite() && ownedAttempt.isEmpty());
    login.setVisibility(signedIn ? View.GONE : View.VISIBLE);
    logout.setEnabled(enabled && gate.canWrite());
    logout.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    cancelLogin.setEnabled(enabled && gate.canWrite());
    cancelLogin.setVisibility(!signedIn && !ownedAttempt.isEmpty() ? View.VISIBLE : View.GONE);
    boolean writable = gate.canWrite() && catalog != null && catalog.writable();
    saveCustom.setEnabled(enabled && writable);
    expandCustom.setEnabled(enabled && writable);
    refreshCustom.setEnabled(enabled);
    cancelEdit.setEnabled(enabled);
    saveKey.setEnabled(enabled && gate.canWrite());
    apiKey.setEnabled(enabled);
    customAddress.setEnabled(enabled);
    customModel.setEnabled(enabled);
    customKey.setEnabled(enabled);
    for (Button button : providerButtons) button.setEnabled(enabled && gate.canWrite());
  }

  private void secure(AlertDialog target) {
    if (target.getWindow() == null) return;
    target.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    target.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }

  private EditText field(LinearLayout body, String label, String hint, int variation) {
    TextView caption = addText(body, label, 13, R.color.gpt_text, true);
    EditText field = new EditText(host);
    field.setId(View.generateViewId());
    caption.setLabelFor(field.getId());
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT | variation);
    field.setTextColor(host.getColor(R.color.gpt_text));
    field.setHintTextColor(host.getColor(R.color.gpt_muted));
    field.setTextSize(15);
    field.setHint(hint);
    field.setBackground(rounded(R.color.gpt_bg, 12));
    field.setPaddingRelative(dp(12), dp(10), dp(12), dp(10));
    field.setMinHeight(dp(48));
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2048)});
    field.setSaveEnabled(false);
    field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    LinearLayout.LayoutParams params = row();
    params.topMargin = dp(5);
    body.addView(field, params);
    return field;
  }

  private LinearLayout section(LinearLayout parent, String title) {
    var card = new LinearLayout(host);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(14), dp(10), dp(14), dp(12));
    card.setBackground(rounded(R.color.gpt_sidebar, 18));
    var params = row();
    params.topMargin = dp(12);
    parent.addView(card, params);
    addText(card, title, 17, R.color.gpt_text, true).setAccessibilityHeading(true);
    return card;
  }

  private TextView addText(LinearLayout parent, String value, int size, int color, boolean bold) {
    var text = new TextView(host);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(host.getColor(color));
    if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    var params = row();
    params.topMargin = dp(5);
    parent.addView(text, params);
    return text;
  }

  private Button addButton(LinearLayout body, String label, boolean primary, Runnable click) {
    var button = new MaterialButton(host);
    button.setAllCaps(false);
    button.setText(label);
    button.setMinHeight(dp(48));
    button.setCornerRadius(dp(12));
    button.setBackgroundTintList(
        ColorStateList.valueOf(host.getColor(primary ? R.color.gpt_action : R.color.gpt_raised)));
    button.setTextColor(host.getColor(primary ? R.color.gpt_action_on : R.color.gpt_text));
    if (click != null) button.setOnClickListener(v -> click.run());
    var params = row();
    params.topMargin = dp(7);
    body.addView(button, params);
    return button;
  }

  private GradientDrawable rounded(int color, int radius) {
    var background = new GradientDrawable();
    background.setColor(host.getColor(color));
    background.setCornerRadius(dp(radius));
    return background;
  }

  private LinearLayout.LayoutParams row() {
    return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
