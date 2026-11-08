package com.mulesoftforge.studio.ai.llm;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import com.mulesoftforge.studio.ai.edit.TodoItem;

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

  // Reasoning text as it streams in, for models that send it. A preview, like partial.
  default void thinking(String text) {}

  // Once per model call that reasoned, when its reasoning ends: the reasoning text (possibly empty, when the
  // provider only reports that it is thinking) and how long it took.
  default void thought(String text, long millis) {}

  // "Thought for 2.3 s", "Thought for 14 s", "Thought for 1 min 5 s".
  static String thoughtFor(long millis) {
    long seconds = millis / 1000;
    String took = millis < 10_000 ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
        : seconds < 60 ? seconds + " s"
        : seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
    return "Thought for " + took;
  }

  // Latest task list from update_todo_list; empty clears it. Called on the worker thread.
  default void todos(List<TodoItem> items) {}

  // Asks the user to allow one call that changes something outside the project, and waits for the answer. Without
  // anyone to ask, the answer is no.
  default boolean approve(String question, String detail) {
    return false;
  }

  boolean isCancelled();

  default void checkCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Cancelled.");
    }
  }
}
