package com.deepseekharness.app.ui;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.ChatEntryPolicy;
import com.deepseekharness.app.util.EnvironmentIdentity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** No Activity reference, blocking disk reads or environment work on the UI thread. */
public final class ChatSetupCoordinator extends AndroidViewModel {
  public record State(
      ChatEntryPolicy.Action action, boolean preparing, boolean starting, boolean checking) {}

  public final MutableLiveData<State> state = new MutableLiveData<>();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final AtomicBoolean reading = new AtomicBoolean();
  private final AtomicBoolean preparing = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ChatEntryPolicy.Event> entryEvent =
      new AtomicReference<>(ChatEntryPolicy.Event.RENDER);
  private final EntryGate entryGate = new EntryGate();
  boolean startRequested;
  boolean autoStart = true;

  public ChatSetupCoordinator(@NonNull Application app) {
    super(app);
  }

  static final class EntryGate {
    private final AtomicBoolean consumed = new AtomicBoolean();

    boolean consume(ChatEntryPolicy.Action action, ChatEntryPolicy.Event event) {
      return ChatEntryPolicy.shouldEnter(action, event) && consumed.compareAndSet(false, true);
    }
  }

  void restoredAuthenticatedSession() {
    entryEvent.set(ChatEntryPolicy.Event.RESTORED);
  }

  void startedAuthenticatedSession() {
    entryEvent.set(ChatEntryPolicy.Event.AUTHENTICATED_START);
  }

  boolean consumeAutomaticEntry(State value) {
    return entryGate.consume(value.action(), entryEvent.getAndSet(ChatEntryPolicy.Event.RENDER));
  }

  void finishStartRequest() {
    // The admission callback can outlive the Activity that launched it.
    startRequested = false;
    refresh();
  }

  void refresh() {
    if (closed.get() || !reading.compareAndSet(false, true)) return;
    io.execute(
        () -> {
          try {
            Context app = getApplication();
            HarnessController c = HarnessController.get(app);
            BackupTask task = BackupTask.get(app);
            BackupTaskState.Snapshot s = task.snapshot();
            boolean busy = task.maintenanceBusy() || task.busy();
            boolean pending = task.pendingMaintenance();
            boolean unknown =
                c.needsWebSessionRestore()
                    || c.isRestoringWebSession()
                    || c.hasUnresolvedWebSession();
            boolean needs =
                !busy
                    && !unknown
                    && (!c.isEnvironmentReady()
                        || EnvironmentAccess.requiresMaintenanceBeforeStart(c));
            String key = candidate(c);
            boolean attempted =
                key.isEmpty()
                    || key.equals(
                        app.getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE)
                            .getString("attempted_identity", ""));
            var lastStop = c.lastStopResult();
            boolean stopUnconfirmed =
                c.isUserStopped()
                    && !c.isStopping()
                    && (lastStop != null && !lastStop.stopped() || !c.lastStopError().isEmpty());
            boolean failed =
                stopUnconfirmed
                    || c.isRestartBlocked()
                    || needs
                        && attempted
                        && (s.status == BackupTaskState.Status.FAILED
                            || s.status == BackupTaskState.Status.INTERRUPTED
                            || s.status == BackupTaskState.Status.CANCELLED);
            var action =
                ChatEntryPolicy.action(
                    busy,
                    pending,
                    unknown,
                    c.isUserStopped(),
                    failed,
                    c.getReadyWebPageIdentity() != null,
                    needs,
                    attempted);
            state.postValue(
                new State(
                    action,
                    preparing.get(),
                    c.isStarting() || c.isStopping(),
                    c.isRestoringWebSession()));
          } catch (RuntimeException e) {
            state.postValue(new State(ChatEntryPolicy.Action.ERROR, false, false, false));
          } finally {
            reading.set(false);
          }
        });
  }

  private String candidate(HarnessController c) {
    try {
      return "runtime:" + c.proot().expectedRuntimeDescriptor().id();
    } catch (java.io.IOException | RuntimeException e) {
      return "";
    }
  }

  /** Only called from the visible home after a PREPARE observation. */
  void prepare() {
    if (!preparing.compareAndSet(false, true)) return;
    io.execute(
        () -> {
          try {
            Context app = getApplication();
            HarnessController c = HarnessController.get(app);
            BackupTask task = BackupTask.get(app);
            if (c.isUserStopped()
                || c.needsWebSessionRestore()
                || c.hasUnresolvedWebSession()
                || c.isRestoringWebSession()) return;
            String key = candidate(c);
            var attempts =
                app.getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE);
            if (!EnvironmentIdentity.shouldAutoStart(
                c.isEnvironmentReady() && !EnvironmentAccess.requiresMaintenanceBeforeStart(c),
                task.busy(),
                task.pendingMaintenance(),
                key,
                attempts.getString("attempted_identity", ""))) return;
            // Same durable one-attempt contract as ExtractActivity; never clear it to retry.
            if (attempts.edit().putString("attempted_identity", key).commit())
              task.updateEnvironment();
          } catch (RuntimeException e) {
            // Never let a controller/pref failure escape the pool thread and kill the app.
            state.postValue(new State(ChatEntryPolicy.Action.ERROR, false, false, false));
          } finally {
            preparing.set(false);
            refresh();
          }
        });
  }

  @Override
  protected void onCleared() {
    closed.set(true);
    io.shutdown();
  }
}
