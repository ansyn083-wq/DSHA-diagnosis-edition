package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.NativeChatAccountRpc;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.PreviewPageSession.Identity;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 头像的唯一配置窗口；首次进入只在同代服务核验后提示，关闭即尊重用户选择。 */
final class NativeChatAccountEntry implements DefaultLifecycleObserver {
  private static final String OFFERED = "native_account_setup_offered";
  private final ChatHomeActivity host;
  private final HarnessController harness;
  private final SharedPreferences prefs;
  private final Consumer<String> useProvider;
  private final Consumer<String> removedProvider;
  private final Runnable onDismiss;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AlertDialog dialog;
  private long dialogGeneration;
  private Identity pending;
  private boolean checking;
  private boolean opening;
  private volatile boolean closed;
  private long retryAt;

  NativeChatAccountEntry(
      ChatHomeActivity host,
      Consumer<String> useProvider,
      Consumer<String> removedProvider,
      Runnable onDismiss) {
    this.host = host;
    this.useProvider = useProvider;
    this.removedProvider = removedProvider;
    this.onDismiss = onDismiss;
    harness = HarnessController.get(host.getApplicationContext());
    prefs = host.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Context.MODE_PRIVATE);
    host.getLifecycle().addObserver(this);
  }

  void show() {
    if (closed
        || opening
        || host.isFinishing()
        || host.isDestroyed()
        || dialog != null && dialog.isShowing()) return;
    opening = true;
    long generation = ++dialogGeneration;
    host.closeChatOverlay();
    try {
      dialog =
          NativeChatAccountDialog.show(
              host,
              useProvider,
              removedProvider,
              () -> {
                if (generation != dialogGeneration) return;
                dialog = null;
                if (!closed) onDismiss.run();
              });
      // 只记录“已展示”，不伪装账号已连接或 API 已配置，也不强制用户提交。
      Identity ready = harness.getReadyWebPageIdentity();
      if (dialog.isShowing() && current(ready)) markOffered();
    } finally {
      opening = false;
    }
  }

  void check(NativeChatRpc client, Identity identity) {
    if (closed || client == null || !current(identity) || prefs.getBoolean(OFFERED, false)) return;
    if (!prefs.getString("native_session", "").isBlank()
        || prefs.getAll().keySet().stream()
            .anyMatch(
                key ->
                    key.startsWith("native_provider:")
                        || key.startsWith("native_draft:")
                            && key.length() > "native_draft:".length())) {
      markOffered();
      return;
    }
    if (pending != null) {
      if (!pending.equals(identity)) pending = null;
      else {
        present();
        return;
      }
    }
    if (checking || SystemClock.elapsedRealtime() < retryAt) return;
    checking = true;
    worker.execute(
        () -> {
          Boolean alreadyConfigured;
          try {
            alreadyConfigured =
                client.accountState().signedIn()
                    || new NativeChatAccountRpc(client).hasConfiguredApi();
          } catch (IOException | RuntimeException unavailable) {
            alreadyConfigured = null;
          }
          Boolean result = alreadyConfigured;
          main.post(
              () -> {
                checking = false;
                if (closed || !current(identity) || prefs.getBoolean(OFFERED, false)) return;
                if (result == null) {
                  // 读取失败不是新用户；下一次就绪刷新可重试，不写已展示标记。
                  retryAt = SystemClock.elapsedRealtime() + 5_000;
                } else if (result) {
                  markOffered();
                } else {
                  pending = identity;
                  present();
                }
              });
        });
  }

  private boolean current(Identity identity) {
    return !closed
        && identity != null
        && identity.equals(harness.getReadyWebPageIdentity())
        && !harness.isUserStopped()
        && !harness.isStopping()
        && !harness.hasUnresolvedWebSession();
  }

  private void present() {
    if (pending == null || prefs.getBoolean(OFFERED, false)) return;
    if (!current(pending)) {
      pending = null;
      return;
    }
    if (!host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
    show();
    if (dialog != null && dialog.isShowing()) markOffered();
  }

  private void markOffered() {
    pending = null;
    prefs.edit().putBoolean(OFFERED, true).apply();
  }

  @Override
  public void onResume(LifecycleOwner owner) {
    present();
  }

  @Override
  public void onDestroy(LifecycleOwner owner) {
    close();
  }

  void close() {
    if (closed) return;
    closed = true;
    pending = null;
    host.getLifecycle().removeObserver(this);
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    AlertDialog active = dialog;
    dialog = null;
    dialogGeneration++;
    if (active != null) active.dismiss();
  }
}
