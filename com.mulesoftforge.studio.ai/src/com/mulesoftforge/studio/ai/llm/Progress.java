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

  // What a streamed reply is doing now ("Thinking…", "Writing the answer…"); called when it changes.
  default void receiving(String what) {}

  boolean isCancelled();

  default void checkCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Cancelled.");
    }
  }
}
