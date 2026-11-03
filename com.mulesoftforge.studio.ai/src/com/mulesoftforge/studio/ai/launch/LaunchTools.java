package com.mulesoftforge.studio.ai.launch;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// The model's tools for starting and stopping the project's app in Studio, as Run As and Debug As > Mule
// Application do. Offered only in modes that may change the running app (Agent and Diagnose). run_app waits until
// the app has deployed or failed; starting it is the whole request, so the model is told not to call a flow next.
public final class LaunchTools {
  public static final String RUN = "run_app";
  public static final String STOP = "stop_app";
  public static final Duration DEPLOY_TIMEOUT = Duration.ofMinutes(3);
  public static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
  // Without any sign of a launch by then, Studio did not start one (a dialog may be asking the user something).
  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
  private static final long POLL_MS = 250;
  private static final long CONSOLE_CATCH_UP_MS = 1_500;
  private static final int MAX_CONSOLE = 4_000;
  private static final List<String> MODES = List.of("run", "debug");
  // Rows of Mule's status tables: "* app * DEPLOYED *", or "* app * domain * DEPLOYED *" in older runtimes. The
  // domain table looks the same, so rows count only under a header naming APPLICATION.
  private static final Pattern STATUS_ROW = Pattern.compile(
      "^\\*\\s+(\\S+)\\s+\\*(?:\\s+\\S+\\s+\\*)?\\s+(DEPLOYED|FAILED)\\s+\\*\\s*$");
  private static final Pattern DEPLOY_FAILED = Pattern.compile("Failed to deploy artifact");
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final List<ToolSpecification> SPECIFICATIONS = List.of(
      ToolSpecification.builder()
          .name(RUN)
          .description("Start this project's Mule app in Studio, as Run As > Mule Application (mode run) or Debug"
              + " As > Mule Application (mode debug) do, and wait until it has deployed or failed. Returns what"
              + " the console printed. Use it only when the user asks to run, debug or restart the app. It only"
              + " starts the app: do not call an endpoint afterwards unless the user also asked for that call. An app"
              + " already running in the other mode is stopped first; one running in the same mode is left alone"
              + " unless restart is true.")
          .parameters(JsonObjectSchema.builder()
              .addEnumProperty("mode", MODES)
              .addBooleanProperty("restart", "Optional, default false. true stops and starts the app even when"
                  + " it already runs in this mode; only when the user asked to restart or redeploy it")
              .required("mode")
              .build())
          .build(),
      ToolSpecification.builder()
          .name(STOP)
          .description("Stop the Mule app running in Studio, like the Terminate button in the Console view. Use it"
              + " only when the user asks to stop the app.")
          .parameters(JsonObjectSchema.builder().build())
          .build());

  private enum Deploy { DEPLOYED, FAILED }

  private final ProjectFiles files;
  private final AppLauncher launcher;
  private final AppConsole console;
  private final SecretMask secrets;
  private final BooleanSupplier cancelled;
  private final Duration deployTimeout;

  // console is null when it cannot be read: run_app then reports the launch started, without the deployment.
  // cancelled is checked while waiting, so cancelling the turn does not wait for the app.
  public LaunchTools(ProjectFiles files, AppLauncher launcher, AppConsole console, SecretMask secrets,
      BooleanSupplier cancelled, Duration deployTimeout) {
    this.files = files;
    this.launcher = launcher;
    this.console = console;
    this.secrets = secrets;
    this.cancelled = cancelled == null ? () -> false : cancelled;
    this.deployTimeout = deployTimeout;
  }

  public static boolean handles(String tool) {
    return RUN.equals(tool) || STOP.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    if (STOP.equals(request.name())) {
      return "Stopping the app";
    }
    String mode = arguments(request).path("mode").asText("run");
    return mode.equals("debug") ? "Starting the app in debug mode (Debug As > Mule Application)"
        : "Starting the app (Run As > Mule Application)";
  }

  // Never throws: failures go back to the model as text.
  public String execute(ToolExecutionRequest request) {
    try {
      return switch (request.name()) {
        case RUN -> run(arguments(request));
        case STOP -> stop();
        default -> "ERROR: unknown tool " + request.name();
      };
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
    boolean restart = args.path("restart").asBoolean(false);
    String project = files.name();
    AppLauncher.Running now = launcher.running(project);
    if (now != null && now.mode().equals(mode) && !restart) {
      return "ALREADY RUNNING: " + now.name() + " runs in " + mode + " mode, so nothing was restarted. Pass"
          + " restart true only if the user asked to restart or redeploy it.";
    }
    String stopped = "";
    if (now != null) {
      launcher.stop();
      if (!stopped()) {
        return "ERROR: " + now.name() + " did not stop within " + STOP_TIMEOUT.toSeconds() + " s, so it was not"
            + " started again. Ask the user to stop it in Studio (Terminate in the Console view).";
      }
      stopped = "Stopped " + now.name() + " (" + now.mode() + " mode) first.\n";
    }
    AppConsole.Text before = read(project);
    long started = System.nanoTime();
    launcher.start(project, mode);
    return stopped + deployed(project, mode, before, started);
  }

  private String deployed(String project, String mode, AppConsole.Text before, long started) throws Exception {
    boolean seen = false;
    long reportedAt = 0;
    while (true) {
      if (cancelled.getAsBoolean()) {
        return "ERROR: the user cancelled while the app was starting. The launch goes on in Studio.";
      }
      long seconds = (System.nanoTime() - started) / 1_000_000_000;
      AppConsole.Text now = read(project);
      // A relaunch gets a new console under the same title, whose start-up can end exactly like the last one's.
      if (before != null && now != null && now.name().equals(before.name())
          && now.text().length() < before.text().length()) {
        before = null;
      }
      String added = now == null ? "" : AppConsole.clean(AppConsole.added(before, now));
      Deploy logged = deploy(added, project);
      AppLauncher.Deployment reported = launcher.deployment(project);
      Deploy deploy = reported == AppLauncher.Deployment.STARTED ? Deploy.DEPLOYED
          : reported == AppLauncher.Deployment.FAILED ? Deploy.FAILED : logged;
      // The runtime's report can come before Mule's log of it; give the console a moment so the reason is shown.
      if (reported != null && logged == null && console != null) {
        reportedAt = reportedAt == 0 ? System.nanoTime() : reportedAt;
        if ((System.nanoTime() - reportedAt) / 1_000_000 < CONSOLE_CATCH_UP_MS) {
          Thread.sleep(POLL_MS);
          continue;
        }
      }
      if (deploy == Deploy.DEPLOYED) {
        return masked("DEPLOYED: " + project + " is running in " + mode + " mode (" + seconds + " s)."
            + (mode.equals("debug") ? " Requests the user sends now stop at the breakpoints set in Studio." : "")
            + " The app was started as asked: do not call an endpoint unless the user asked for that too; tell the"
            + " user it is running and end your turn."
            + "\n\nCONSOLE, end of the start-up:\n" + AppConsole.tail(added, MAX_CONSOLE));
      }
      if (deploy == Deploy.FAILED) {
        return masked("FAILED: " + project + " did not deploy (" + seconds + " s). Explain why from the console"
            + " below:\n" + AppConsole.tail(added, MAX_CONSOLE));
      }
      boolean running = launcher.running(project) != null;
      if (running && console == null) {
        return "STARTED: " + project + " is starting in " + mode + " mode. The console cannot be read, so ask"
            + " the user to watch for DEPLOYED in it.";
      }
      if (running) {
        seen = true;
      } else if (seen) {
        return masked("STOPPED: the launch ended before the app deployed (" + seconds + " s). Console:\n"
            + AppConsole.tail(added, MAX_CONSOLE));
      } else if (added.isEmpty() && seconds >= START_TIMEOUT.toSeconds()) {
        return "ERROR: Studio did not start a launch within " + START_TIMEOUT.toSeconds() + " s. A dialog in"
            + " Studio may be waiting for the user, or the project cannot run as a Mule application.";
      }
      if (seconds >= deployTimeout.toSeconds()) {
        return masked("STILL STARTING after " + seconds + " s. Use read_console later to see whether it deployed."
            + " Console so far:\n" + AppConsole.tail(added, MAX_CONSOLE));
      }
      Thread.sleep(POLL_MS);
    }
  }

  // DEPLOYED when the project's application row (or the only one) says so, or Mule logged "Started app 'project'";
  // FAILED when an application row or Mule says it failed.
  private static Deploy deploy(String text, String project) {
    if (DEPLOY_FAILED.matcher(text).find()) {
      return Deploy.FAILED;
    }
    Deploy found = text.contains("Started app '" + project + "'") ? Deploy.DEPLOYED : null;
    boolean applications = false;
    for (String line : text.split("\\R")) {
      String row = line.strip();
      if (row.contains("+ APPLICATION +")) {
        applications = true;
        continue;
      }
      if (row.contains("+ DOMAIN +")) {
        applications = false;
        continue;
      }
      Matcher status = STATUS_ROW.matcher(row);
      if (!applications || !status.matches()) {
        continue;
      }
      Deploy deploy = Deploy.valueOf(status.group(2));
      if (deploy == Deploy.FAILED) {
        return Deploy.FAILED;
      }
      if (status.group(1).equals(project) || found == null) {
        found = deploy;
      }
    }
    return found;
  }

  private String stop() throws Exception {
    AppLauncher.Running now = launcher.running(files.name());
    if (now == null) {
      return "NOT RUNNING: no Mule app is running in Studio, so nothing was stopped.";
    }
    launcher.stop();
    return stopped() ? "STOPPED: " + now.name() + " (" + now.mode() + " mode)."
        : "ERROR: " + now.name() + " did not stop within " + STOP_TIMEOUT.toSeconds() + " s. Ask the user to stop"
            + " it in Studio (Terminate in the Console view).";
  }

  private boolean stopped() throws InterruptedException {
    long end = System.nanoTime() + STOP_TIMEOUT.toNanos();
    while (System.nanoTime() < end) {
      if (launcher.running(files.name()) == null) {
        return true;
      }
      Thread.sleep(POLL_MS);
    }
    return launcher.running(files.name()) == null;
  }

  private AppConsole.Text read(String project) {
    if (console == null) {
      return null;
    }
    try {
      return console.read(project);
    } catch (RuntimeException e) {
      return null;
    }
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
