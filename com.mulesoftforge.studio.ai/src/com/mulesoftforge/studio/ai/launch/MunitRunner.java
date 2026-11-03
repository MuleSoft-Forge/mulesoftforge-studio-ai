package com.mulesoftforge.studio.ai.launch;

import java.util.List;

// Running the project's MUnit suites the way Studio's Run / Debug MUnit suite do. Studio backs it with its MUnit
// runner; the harness with a fake. mode is "run" or "debug".
public interface MunitRunner {
  enum Outcome { PASSED, FAILED, ERROR, IGNORED }

  // suite is the path under src/test/munit; cause is MUnit's message and stack trace for a failure or an error.
  record Test(String suite, String name, Outcome outcome, String cause) {}

  // error is why the run, or a suite before its tests, could not run (the app did not start, a suite did not
  // parse); null otherwise.
  record Run(boolean finished, int tests, List<Test> results, String error) {}

  // suites are paths under src/test/munit; tests are test names in them, or empty for every test. Returns once
  // the launch is under way.
  void start(String project, List<String> suites, List<String> tests, String mode) throws Exception;

  // What MUnit reported for the run started last here, or null while it has reported nothing.
  Run run();

  // Whether an MUnit launch is still running in Studio.
  boolean running();

  // The text of the last MUnit launch's console, or null when there is none.
  String console();
}
