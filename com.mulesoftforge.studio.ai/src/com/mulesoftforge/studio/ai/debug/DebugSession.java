package com.mulesoftforge.studio.ai.debug;

import java.time.Duration;

// The debugger as the model's tools see it. Studio backs it with its Mule debugger; the harness with a fake.
public interface DebugSession {
  // ok with the result as text and its media type, or not ok with the DataWeave error.
  record Evaluation(boolean ok, String value, String mimeType, String error, long millis) {}

  // Where the debugger is stopped now, or null when it is not stopped at a processor.
  DebugStop current();

  // Runs a DataWeave script against the stopped event, in the app's own runtime.
  Evaluation evaluate(String script, Duration timeout) throws Exception;

  // The field at accessor with the children Studio had not sent yet, or null when no such field was shown.
  DebugField expand(String accessor, Duration timeout) throws Exception;
}
