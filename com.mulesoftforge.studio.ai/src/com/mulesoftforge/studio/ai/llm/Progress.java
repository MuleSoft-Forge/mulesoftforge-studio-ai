package com.mulesoftforge.studio.ai.llm;

import java.util.concurrent.CancellationException;

// Called from the background thread while a turn runs.
public interface Progress {
  Progress NONE = new Progress() {
    @Override
    public void step(String message) {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  };

  void step(String message);

  default void answered(CallUsage usage, long seconds) {
    step("Model answered in " + seconds + " s");
  }

  // What the model call is doing now ("Waiting for the first data…", "Thinking…", "Writing the answer…"); called
  // when it changes, from the start of each call until it answered.
  default void receiving(String what) {}

  // Answer text as it streams in, piece by piece. A preview only: the finished answer replaces it.
  default void partial(String text) {}

  // Latest task list from update_todo_list; empty clears it. Called on the worker thread.
  default void todos(java.util.List<com.mulesoftforge.studio.ai.edit.TodoItem> items) {}

  boolean isCancelled();

  default void checkCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Cancelled.");
    }
  }
}
