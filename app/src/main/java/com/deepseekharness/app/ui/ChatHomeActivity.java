package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.ChatEntryPolicy;
import com.deepseekharness.app.util.UiText;

/** Native chat launcher using the authenticated local DSH service. */
public final class ChatHomeActivity extends AppCompatActivity {
  private NativeChatPanel chat;
  private NativeChatAccountEntry accountEntry;
  private ChatHomeLayout homeLayout;
  private static final java.util.concurrent.Executor START_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  static final String DRAFT_PREFS = "dsha-chat-home";

  /** Web-side import rejects longer drafts; keep the two sides in step. */
  static final int MAX_DRAFT_CHARS = 65536;

  private static final long POLL_ACTIVE_MS = 1000;
  private static final long POLL_IDLE_MS = 5000;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable persistDraft = this::saveDraft;
  private ChatSetupCoordinator model;
  private TextView status;
  private View setupLinks;
  private Button action;
  private View setupCard;
  private Button restartService;
  private TextView recoveryHelp;
  private EditText draft;
  private ChatSetupCoordinator.State latest;
  private static final long START_DETAILS_DELAY_MS = 12_000;
  private long waitingSinceMs = -1;
  private com.deepseekharness.app.core.UpdateEngine startupUpdates;
  private com.google.android.material.snackbar.Snackbar updateNotice;
  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          model.refresh();
          main.postDelayed(this, pollDelayMs());
        }
      };

  private long pollDelayMs() {
    ChatSetupCoordinator.State s = latest;
    if (s == null) return POLL_ACTIVE_MS;
    ChatEntryPolicy.Action a = s.action();
    return a == ChatEntryPolicy.Action.READY || a == ChatEntryPolicy.Action.STOPPED
        ? POLL_IDLE_MS
        : POLL_ACTIVE_MS;
  }

  private void saveDraft() {
    if (draft == null) return;
    String value = draft.getText().toString();
    if (value.length() > MAX_DRAFT_CHARS) value = value.substring(0, MAX_DRAFT_CHARS);
    getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit().putString("draft", value).apply();
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (BrowserTasks.duplicateLauncher(this, saved)) {
      finish();
      return;
    }
    model = new ViewModelProvider(this).get(ChatSetupCoordinator.class);
    // The old tutorial is replaced by the explicit first-home runtime notice below.
    new com.deepseekharness.app.core.ConfigStore(this).setWelcomed(true);
    buildUi(saved);
    model.state.observe(this, this::render);
    com.deepseekharness.app.core.ConfigStore config =
        new com.deepseekharness.app.core.ConfigStore(this);
    startupUpdates = com.deepseekharness.app.core.UpdateEngine.get(this);
    startupUpdates.state().observe(this, ignored -> showStartupUpdate());
    startupUpdates.checkOnStartup(config.isCheckUpdate());
    HarnessController.get(this)
        .restoreRunningWebSession(
            () -> {
              HarnessController controller = HarnessController.get(getApplicationContext());
              if (controller.getReadyWebPageIdentity() != null
                  && !controller.isUserStopped()
                  && !controller.hasUnresolvedWebSession()) model.restoredAuthenticatedSession();
              model.refresh();
            });
  }

  private void buildUi(Bundle savedState) {
    setContentView(R.layout.activity_diagnostic_home);
    homeLayout = findViewById(R.id.native_chat_home);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (homeLayout.closeOverlay()) return;
                setEnabled(false);
                try {
                  getOnBackPressedDispatcher().onBackPressed();
                } finally {
                  setEnabled(true);
                }
              }
            });
    status = findViewById(R.id.diagnostic_status);
    setupLinks = findViewById(R.id.native_chat_setup_links);
    action = findViewById(R.id.diagnostic_primary);
    action.setOnClickListener(v -> primary());
    setupCard = findViewById(R.id.native_chat_setup_card);
    restartService = findViewById(R.id.diagnostic_recover_service);
    restartService.setText(UiText.choose("停止并重启服务", "Stop and restart service"));
    restartService.setOnClickListener(v -> confirmServiceRestart());
    recoveryHelp = findViewById(R.id.diagnostic_recovery_help);
    recoveryHelp.setText(
        UiText.choose(
            "可重新核验，或停止旧服务后重新启动。聊天记录和文件会保留。",
            "Check again, or stop and restart the service. Chats and files are kept."));
    View settingsButton = findViewById(R.id.diagnostic_settings);
    settingsButton.setContentDescription(UiText.choose("设置", "Settings"));
    settingsButton.setOnClickListener(v -> settings());
    findViewById(R.id.diagnostic_details).setOnClickListener(v -> details());
    findViewById(R.id.diagnostic_export)
        .setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    draft = findViewById(R.id.diagnostic_draft);
    draft.setFilters(
        new android.text.InputFilter[] {
          new android.text.InputFilter.LengthFilter(MAX_DRAFT_CHARS)
        });
    String saved = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getString("draft", "");
    draft.setText(saved.length() > MAX_DRAFT_CHARS ? saved.substring(0, MAX_DRAFT_CHARS) : saved);
    draft.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            main.removeCallbacks(persistDraft);
            main.postDelayed(persistDraft, 400);
          }

          @Override
          public void afterTextChanged(Editable value) {}
        });
    chat = new NativeChatPanel(this, draft, savedState);
    homeLayout.setOnSidebarClosed(chat::dismissSearch);
  }

  private NativeChatAccountEntry accountEntry() {
    if (accountEntry == null)
      accountEntry =
          new NativeChatAccountEntry(
              this, chat::chooseProvider, chat::forgetProvider, chat::refreshAccountControls);
    return accountEntry;
  }

  void showChatAccount() {
    accountEntry().show();
  }

  void checkChatAccountSetup(
      com.deepseekharness.app.util.NativeChatRpc rpc,
      com.deepseekharness.app.util.PreviewPageSession.Identity identity) {
    accountEntry().check(rpc, identity);
  }

  void closeChatOverlay() {
    homeLayout.closeOverlay();
  }

  private void render(ChatSetupCoordinator.State s) {
    latest = s;
    boolean resumed = getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    boolean ready = s.action() == ChatEntryPolicy.Action.READY;
    setupCard.setVisibility(ready ? View.GONE : View.VISIBLE);
    if (!ready) chat.suspend();
    boolean waiting =
        !ready
            && (s.starting()
                || model.startRequested
                || switch (s.action()) {
                  case PREPARE -> model.autoStart || s.preparing();
                  case START -> model.autoStart;
                  case WAIT -> true;
                  case CHECK -> s.checking();
                  default -> false;
                });
    long now = android.os.SystemClock.elapsedRealtime();
    if (!waiting) waitingSinceMs = -1;
    else if (waitingSinceMs < 0) waitingSinceMs = now;
    boolean waitingLong = waiting && now - waitingSinceMs >= START_DETAILS_DELAY_MS;
    setupCard.setBackgroundResource(waiting ? 0 : R.drawable.gpt_surface_raised);
    status.setTextColor(getColor(waiting ? R.color.gpt_muted : R.color.gpt_text));
    status.setTextSize(waiting ? 13 : 16);
    action.setVisibility(waiting ? View.GONE : View.VISIBLE);
    setupLinks.setVisibility(!waiting || waitingLong ? View.VISIBLE : View.GONE);
    action.setEnabled(!waiting);
    boolean needsRecovery = s.action() == ChatEntryPolicy.Action.CHECK && !waiting;
    restartService.setVisibility(needsRecovery ? View.VISIBLE : View.GONE);
    recoveryHelp.setVisibility(needsRecovery ? View.VISIBLE : View.GONE);
    String label;
    switch (s.action()) {
      case READY:
        label = GptStatusPanel.title(s.action(), s.checking());
        if (resumed) chat.ready(HarnessController.get(this).getReadyWebPageIdentity());
        break;
      case PREPARE:
        label = GptStatusPanel.title(s.action(), s.checking());
        if (resumed && model.autoStart) model.prepare();
        if (!model.autoStart) {
          action.setEnabled(true);
          action.setText(UiText.choose("准备环境", "Prepare runtime"));
        }
        break;
      case START:
        label = GptStatusPanel.title(s.action(), s.checking());
        if (resumed && model.autoStart && !s.starting() && !model.startRequested) startWeb();
        if (!model.autoStart && !s.starting() && !model.startRequested) {
          label = UiText.choose("可以启动聊天服务", "Ready to start the chat service");
          action.setEnabled(true);
          action.setText(UiText.choose("启动对话", "Start chat"));
        }
        break;
      case WAIT:
        label = GptStatusPanel.title(s.action(), s.checking());
        break;
      case CHECK:
        label = GptStatusPanel.title(s.action(), s.checking());
        action.setText(
            waiting ? UiText.choose("核验中…", "Checking…") : UiText.choose("重新核验", "Check again"));
        break;
      case STOPPED:
        label =
            waiting
                ? UiText.choose("正在等待服务切换", "Waiting for the service to switch")
                : GptStatusPanel.title(s.action(), s.checking());
        action.setText(UiText.choose("启动对话", "Start chat"));
        break;
      case RECOVER:
        label = GptStatusPanel.title(s.action(), s.checking());
        action.setText(UiText.choose("查看详情", "Details"));
        break;
      default:
        label = GptStatusPanel.title(s.action(), s.checking());
        action.setText(UiText.choose("查看详情", "Details"));
    }
    status.setText(label);
  }

  private void primary() {
    if (latest == null) return;
    switch (latest.action()) {
      case READY:
        chat.ready(HarnessController.get(this).getReadyWebPageIdentity());
        break;
      case STOPPED:
        startWeb();
        break;
      case START:
        startWeb();
        break;
      case PREPARE:
        model.autoStart = true;
        model.prepare();
        break;
      case CHECK:
        HarnessController.get(this).requestWebSessionRecheck(() -> model.refresh());
        break;
      default:
        details();
    }
  }

  private void confirmServiceRestart() {
    if (model.startRequested
        || latest == null
        || latest.action() != ChatEntryPolicy.Action.CHECK
        || latest.checking()
        || latest.starting()) return;
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(UiText.choose("停止并重启服务", "Stop and restart service"))
        .setMessage(
            UiText.choose(
                "旧服务中正在执行的任务可能会中断。确认停止后才会重新启动。",
                "Running tasks in the old service may be interrupted. Restart waits for confirmed shutdown."))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(UiText.choose("重启", "Restart"), (dialog, which) -> restartOldService())
        .show();
  }

  private void restartOldService() {
    if (model.startRequested
        || latest == null
        || latest.action() != ChatEntryPolicy.Action.CHECK
        || latest.checking()
        || latest.starting()
        || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
    model.autoStart = false;
    model.startRequested = true;
    render(latest);
    boolean queued =
        FirstRunWebStart.enqueueVisible(
            this,
            START_IO,
            false,
            true,
            message ->
                main.post(
                    () -> {
                      if (!isDestroyed())
                        status.setText(com.deepseekharness.app.util.UiStateText.render(message));
                    }),
            model::finishStartRequest);
    if (!queued) {
      model.finishStartRequest();
      render(latest);
    }
  }

  private void startWeb() {
    if (model.startRequested
        || latest != null && latest.starting()
        || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
    model.startRequested = true;
    model.autoStart = false;
    boolean queued =
        FirstRunWebStart.enqueueVisible(
            this,
            START_IO,
            false,
            false,
            message ->
                main.post(
                    () -> {
                      if (!isDestroyed())
                        status.setText(com.deepseekharness.app.util.UiStateText.render(message));
                    }),
            model::finishStartRequest);
    if (!queued) model.finishStartRequest();
    else model.refresh();
  }

  void settings() {
    closeChatOverlay();
    model.autoStart = false;
    startActivity(new Intent(this, ChatManagementActivity.class));
  }

  private void details() {
    model.autoStart = false;
    startActivity(
        new Intent(this, MainActivity.class)
            .putExtra("limited_entry", true)
            .putExtra("open_launch", true));
  }

  private void showStartupUpdate() {
    if (startupUpdates == null
        || updateNotice != null
        || isFinishing()
        || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
        || !new com.deepseekharness.app.core.ConfigStore(this).isCheckUpdate()) return;
    com.deepseekharness.app.util.UpdatePolicy.Release release = startupUpdates.startupNotice();
    if (release == null) return;
    updateNotice =
        com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                UiText.format("发现新版本 %s", release.version),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(
                UiText.text("查看更新"), view -> startActivity(new Intent(this, UpdateActivity.class)));
    updateNotice.addCallback(
        new com.google.android.material.snackbar.Snackbar.Callback() {
          @Override
          public void onShown(com.google.android.material.snackbar.Snackbar bar) {
            startupUpdates.markStartupNoticeShown(release);
          }

          @Override
          public void onDismissed(com.google.android.material.snackbar.Snackbar bar, int event) {
            if (updateNotice == bar) updateNotice = null;
          }
        });
    updateNotice.show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (model != null) {
      BrowserTasks.rememberMain(this);
      String saved = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getString("draft", "");
      if (!saved.contentEquals(draft.getText())) draft.setText(saved);
      main.removeCallbacks(poll);
      if (latest != null) render(latest);
      main.post(poll);
      model.refresh();
      showStartupUpdate();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && chat != null) chat.maybeOpenQuestion();
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(poll);
    main.removeCallbacks(persistDraft);
    saveDraft();
    if (chat != null) chat.suspend();
    if (updateNotice != null) {
      updateNotice.dismiss();
      updateNotice = null;
    }
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (chat != null) chat.saveComposerState(state);
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacksAndMessages(null);
    if (accountEntry != null) accountEntry.close();
    if (chat != null) chat.destroy();
    super.onDestroy();
  }
}
