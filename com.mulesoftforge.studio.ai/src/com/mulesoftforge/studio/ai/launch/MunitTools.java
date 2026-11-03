package com.mulesoftforge.studio.ai.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

// The model's tool for running the project's MUnit suites in Studio, as Run / Debug MUnit suite do. Offered in the
// modes that may start the app (Agent and Diagnose). It runs the suites as saved on disk and, in run mode, waits for
// MUnit's results; in debug mode the tests stop at the user's breakpoints, so it returns once the run has started.
public final class MunitTools {
  public static final String RUN = "run_munit";
  public static final String FOLDER = "src/test/munit/";
  public static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
  // MUnit first packages the app with Maven; without a launch or any result by then, Studio did not start the run.
  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
  // Results can come a moment after the launch ends.
  private static final long RESULTS_CATCH_UP_MS = 3_000;
  private static final long POLL_MS = 500;
  private static final int MAX_CAUSE = 1_500;
  private static final int MAX_CAUSES = 8_000;
  private static final int MAX_CONSOLE = 4_000;
  private static final int MAX_SUITES = 200;
  private static final List<String> MODES = List.of("run", "debug");
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final List<ToolSpecification> SPECIFICATIONS = List.of(
      ToolSpecification.builder()
          .name(RUN)
          .description("Run this project's MUnit tests in Studio, as Run MUnit suite does, and return each test's"
              + " result with the failure messages. It runs the suites as saved on disk: changes you proposed are"
              + " only there once the user accepted them. Mode run waits for the results (packaging the app first"
              + " can take a few minutes); mode debug starts the run so the tests stop at Studio's breakpoints and"
              + " returns at once. Use it when the user asks to run or rerun tests, or to check suites they have"
              + " accepted.")
          .parameters(JsonObjectSchema.builder()
              .addProperty("suites", JsonArraySchema.builder()
                  .description("Optional. Suite files to run, e.g. src/test/munit/employees-api-test-suite.xml;"
                      + " every suite under src/test/munit when empty")
                  .items(new JsonStringSchema())
                  .build())
              .addProperty("tests", JsonArraySchema.builder()
                  .description("Optional. Test names to run in those suites, e.g. get-employee-not-found-test;"
                      + " every test when empty")
                  .items(new JsonStringSchema())
                  .build())
              .addEnumProperty("mode", MODES)
              .build())
          .build());

  private final ProjectFiles files;
  private final MunitRunner runner;
  private final ChangeSet proposed;
  private final SecretMask secrets;
  private final BooleanSupplier cancelled;
  private final Duration runTimeout;

  // proposed holds this turn's proposals, which are not on disk yet. cancelled is checked while waiting, so
  // cancelling the turn does not wait for the tests.
  public MunitTools(ProjectFiles files, MunitRunner runner, ChangeSet proposed, SecretMask secrets,
      BooleanSupplier cancelled, Duration runTimeout) {
    this.files = files;
    this.runner = runner;
    this.proposed = proposed;
    this.secrets = secrets;
    this.cancelled = cancelled == null ? () -> false : cancelled;
    this.runTimeout = runTimeout;
  }

  public static boolean handles(String tool) {
    return RUN.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    JsonNode args = arguments(request);
    int suites = args.path("suites").size();
    String which = suites == 0 ? "all MUnit suites" : suites == 1 ? args.path("suites").get(0).asText()
        : suites + " MUnit suites";
    return (args.path("mode").asText("run").equals("debug") ? "Debugging " : "Running ") + which;
  }

  // Never throws: failures go back to the model as text.
  public String execute(ToolExecutionRequest request) {
    try {
      return run(arguments(request));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "ERROR: interrupted.";
    } catch (Exception e) {
      return "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private String run(JsonNode args) throws Exception {
    String mode = args.path("mode").asText("run").strip().toLowerCase(Locale.ROOT);
    if (!MODES.contains(mode)) {
      return "ERROR: mode must be run or debug.";
    }
    List<String> available = suites();
    if (available.isEmpty()) {
      return "ERROR: this project has no MUnit suites under " + FOLDER + ". Create one first.";
    }
    List<String> suites = new ArrayList<>();
    for (JsonNode suite : args.path("suites")) {
      String path = underFolder(suite.asText());
      if (!available.contains(path)) {
        return "ERROR: no suite " + FOLDER + path + ". Suites: " + String.join(", ", available) + ".";
      }
      suites.add(path);
    }
    if (suites.isEmpty()) {
      suites = available;
    }
    List<String> tests = new ArrayList<>();
    for (JsonNode test : args.path("tests")) {
      if (!test.asText().isBlank()) {
        tests.add(test.asText().strip());
      }
    }
    List<String> unsaved = unsaved();
    if (!unsaved.isEmpty()) {
      return "NOT SAVED: you proposed changes to " + String.join(", ", unsaved) + " in this turn, and MUnit runs"
          + " the files on disk. Tell the user to accept the proposals and then ask you to run the tests; do not run"
          + " them now.";
    }
    if (runner.running()) {
      return "ERROR: an MUnit run is already going on in Studio. Wait for it to end, or ask the user to stop it.";
    }
    long started = System.nanoTime();
    runner.start(files.name(), suites, tests, mode);
    return mode.equals("debug") ? debugging(suites, started) : results(started);
  }

  private String debugging(List<String> suites, long started) throws InterruptedException {
    while (seconds(started) < START_TIMEOUT.toSeconds()) {
      if (runner.running() || runner.run() != null) {
        return "STARTED: MUnit is running " + String.join(", ", suites) + " in debug mode. The tests stop at the"
            + " breakpoints set in Studio and their results appear in the MUnit view. End your turn; the user can"
            + " ask you to look at the event when it stops.";
      }
      Thread.sleep(POLL_MS);
    }
    return notStarted();
  }

  private String results(long started) throws InterruptedException {
    boolean seen = false;
    long endedAt = 0;
    while (true) {
      if (cancelled.getAsBoolean()) {
        return "ERROR: the user cancelled while the tests ran. The run goes on in Studio's MUnit view.";
      }
      MunitRunner.Run run = runner.run();
      if (run != null && run.finished()) {
        return masked(report(run, seconds(started)));
      }
      boolean running = runner.running();
      if (running || run != null) {
        seen = true;
        endedAt = 0;
      }
      if (!running && seen) {
        endedAt = endedAt == 0 ? System.nanoTime() : endedAt;
        if ((System.nanoTime() - endedAt) / 1_000_000 >= RESULTS_CATCH_UP_MS) {
          run = runner.run();
          return run != null && run.finished() ? masked(report(run, seconds(started)))
              : masked("ENDED: the MUnit run stopped before it reported results (" + seconds(started) + " s)."
                  + (run != null && run.error() != null ? " MUnit said: " + run.error() : "")
                  + " Explain why from the console:\n" + console());
        }
      } else if (!seen && seconds(started) >= START_TIMEOUT.toSeconds()) {
        return notStarted();
      }
      if (seconds(started) >= runTimeout.toSeconds()) {
        return masked("STILL RUNNING after " + seconds(started) + " s; the results will appear in Studio's MUnit"
            + " view. " + (run == null ? "" : progress(run)) + "Console so far:\n" + console());
      }
      Thread.sleep(POLL_MS);
    }
  }

  private String report(MunitRunner.Run run, long seconds) {
    int passed = count(run, MunitRunner.Outcome.PASSED);
    int failed = count(run, MunitRunner.Outcome.FAILED);
    int errors = count(run, MunitRunner.Outcome.ERROR);
    int ignored = count(run, MunitRunner.Outcome.IGNORED);
    StringBuilder out = new StringBuilder();
    if (run.error() != null && run.results().isEmpty()) {
      return "ERROR: MUnit could not run the tests (" + seconds + " s): " + cut(run.error(), MAX_CAUSE)
          + "\n\nCONSOLE, end of the run:\n" + console();
    }
    out.append(failed + errors == 0 && run.error() == null ? "PASSED" : "FAILED").append(": ")
        .append(passed).append(" passed, ").append(failed).append(" failed, ").append(errors).append(" errors")
        .append(ignored > 0 ? ", " + ignored + " ignored" : "").append(" (").append(seconds).append(" s).\n");
    if (run.error() != null) {
      out.append("\nA suite could not run: ").append(cut(run.error(), MAX_CAUSE)).append('\n');
    }
    for (MunitRunner.Test test : run.results()) {
      out.append("\n").append(test.outcome()).append(' ').append(test.suite()).append(" / ").append(test.name());
      if ((test.outcome() == MunitRunner.Outcome.FAILED || test.outcome() == MunitRunner.Outcome.ERROR)
          && test.cause() != null && !test.cause().isBlank() && out.length() < MAX_CAUSES) {
        out.append(":\n").append(cut(test.cause().strip(), MAX_CAUSE));
      }
    }
    if (failed + errors > 0) {
      out.append("\n\nExplain each failure: a wrong expectation in the test, a wrong mock, or a bug in the flow.");
    }
    return out.toString();
  }

  private static String progress(MunitRunner.Run run) {
    return run.results().size() + " of " + run.tests() + " tests have finished. ";
  }

  private static int count(MunitRunner.Run run, MunitRunner.Outcome outcome) {
    return (int) run.results().stream().filter(test -> test.outcome() == outcome).count();
  }

  private String notStarted() {
    return "ERROR: Studio did not start the MUnit run within " + START_TIMEOUT.toSeconds() + " s. A dialog in"
        + " Studio may be waiting for the user, or MUnit is not set up in pom.xml.";
  }

  private List<String> suites() throws Exception {
    List<String> suites = new ArrayList<>();
    for (String path : files.list(FOLDER.substring(0, FOLDER.length() - 1), MAX_SUITES)) {
      String normal = path.replace('\\', '/');
      if (normal.startsWith(FOLDER) && normal.endsWith(".xml")) {
        suites.add(normal.substring(FOLDER.length()));
      }
    }
    return suites;
  }

  private List<String> unsaved() {
    Set<String> paths = new LinkedHashSet<>();
    if (proposed != null) {
      for (FileChange change : proposed.all()) {
        paths.add(change.path());
      }
    }
    return List.copyOf(paths);
  }

  private static String underFolder(String path) {
    String normal = path.strip().replace('\\', '/');
    while (normal.startsWith("/")) {
      normal = normal.substring(1);
    }
    return normal.startsWith(FOLDER) ? normal.substring(FOLDER.length()) : normal;
  }

  private String console() {
    String text;
    try {
      text = runner.console();
    } catch (RuntimeException e) {
      text = null;
    }
    return text == null || text.isBlank() ? "(no MUnit console)" : AppConsole.tail(AppConsole.clean(text), MAX_CONSOLE);
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }

  private static long seconds(long started) {
    return (System.nanoTime() - started) / 1_000_000_000;
  }

  private String masked(String text) {
    return secrets.mask(null, text).text();
  }

  private static JsonNode arguments(ToolExecutionRequest request) {
    try {
      return JSON.readTree(request.arguments() == null || request.arguments().isBlank() ? "{}" : request.arguments());
    } catch (Exception e) {
      return JSON.createObjectNode();
    }
  }
}
