package com.deepseekharness.app.util;

import java.util.function.Consumer;

/** 主线程内合并显示请求；协议事件仍逐个处理，持久提交立即刷新。 */
public final class NativeChatRenderQueue {
  private final Consumer<Runnable> schedule, remove;
  private final Runnable render, flush;
  private boolean pending;

  public NativeChatRenderQueue(
      Consumer<Runnable> schedule, Consumer<Runnable> remove, Runnable render) {
    this.schedule = schedule;
    this.remove = remove;
    this.render = render;
    flush = () -> {
      pending = false;
      render.run();
    };
  }

  public void request(boolean immediate) {
    if (immediate) {
      cancel();
      render.run();
    } else if (!pending) {
      pending = true;
      schedule.accept(flush);
    }
  }

  public void cancel() {
    if (pending) remove.accept(flush);
    pending = false;
  }
}
