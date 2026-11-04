package com.deepseekharness.app.util;

/** Presentation policy only. The existing controller remains the execution authority. */
public final class ChatEntryPolicy {
  public enum Action {
    WAIT,
    RECOVER,
    CHECK,
    STOPPED,
    READY,
    PREPARE,
    ERROR,
    START
  }

  public enum Event {
    RENDER,
    RECREATED,
    AUTHENTICATED_START,
    RESTORED
  }

  private ChatEntryPolicy() {}

  public static Action action(
      boolean busy,
      boolean pending,
      boolean unknown,
      boolean stopped,
      boolean failed,
      boolean webReady,
      boolean maintenance,
      boolean attempted) {
    if (busy) return Action.WAIT;
    if (pending) return Action.RECOVER;
    if (unknown) return Action.CHECK;
    if (stopped) return failed || maintenance ? Action.ERROR : Action.STOPPED;
    if (webReady) return Action.READY;
    if (failed) return Action.ERROR;
    if (maintenance) return attempted ? Action.ERROR : Action.PREPARE;
    return Action.START;
  }

  public static boolean shouldEnter(Action action, Event event) {
    return action == Action.READY
        && (event == Event.AUTHENTICATED_START || event == Event.RESTORED);
  }
}
