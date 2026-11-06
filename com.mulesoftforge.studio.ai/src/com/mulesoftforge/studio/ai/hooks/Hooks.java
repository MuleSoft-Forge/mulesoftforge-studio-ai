package com.mulesoftforge.studio.ai.hooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

// The user's own commands, run on chat events. The file has the shape Codex's hooks.json and Copilot's hook
// settings share (Claude Code's): {"hooks": {"PreToolUse": [{"matcher": "edit_file|write_file", "hooks":
// [{"type": "command", "command": "...", "timeout": 30}]}]}}. Each command gets the event as JSON on stdin.
// Exit 0 with JSON on stdout can block (PreToolUse "permissionDecision": "deny", or "decision": "block") and add
// "additionalContext"; exit 2 blocks with stderr as the reason; any other exit is reported and ignored.
// Cline's {"cancel": true, "errorMessage", "contextModification"} is read too.
public final class Hooks {
  public enum Event {
    PRE_TOOL_USE("PreToolUse", true),
    POST_TOOL_USE("PostToolUse", true),
    USER_PROMPT_SUBMIT("UserPromptSubmit", true),
    STOP("Stop", false),
    NOTIFICATION("Notification", false);

    public final String wire;
    // Whether the hook's answer changes what happens; the others only observe.
    final boolean controls;

    Event(String wire, boolean controls) {
      this.wire = wire;
      this.controls = controls;
    }
  }

  // What the hooks for one event decided. reason is why it was blocked; context is text for the model; problems
  // are hooks that failed, timed out or answered something unreadable, which did not block anything.
  public record Outcome(boolean blocked, String reason, List<String> context, List<String> problems) {
    static final Outcome NONE = new Outcome(false, null, List.of(), List.of());

    public String contextText() {
      return String.join("\n\n", context);
    }
  }

  // A UserPromptSubmit hook stopped the message before anything was sent.
  public static final class Blocked extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Blocked(String message) {
      super(message);
    }
  }

  record Command(Pattern matcher, String matcherText, String command, int timeoutSeconds) {}

  public static final Hooks NONE = new Hooks(new EnumMap<>(Event.class), null);
  static final int DEFAULT_TIMEOUT_SECONDS = 30;
  static final int MAX_TIMEOUT_SECONDS = 600;
  private static final int MAX_OUTPUT = 64 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  // Codex's rule: empty or * matches every tool, letters, digits, _ and | are exact names, anything else a regex.
  private static final Pattern EXACT_NAMES = Pattern.compile("[A-Za-z0-9_|]+");

  private final Map<Event, List<Command>> commands;
  private final String problem;

  private Hooks(Map<Event, List<Command>> commands, String problem) {
    this.commands = commands;
    this.problem = problem;
  }

  // ~/.mulesoftforge/studio-ai/hooks.json: per user, not per project, so opening a project never runs its commands.
  public static Path userFile() {
    return Path.of(System.getProperty("user.home"), ".mulesoftforge", "studio-ai", "hooks.json");
  }

  // A file that does not exist means no hooks. One that cannot be read gives no hooks and a problem().
  public static Hooks load(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return NONE;
    }
    try {
      return parse(Files.readString(file));
    } catch (Exception e) {
      return new Hooks(new EnumMap<>(Event.class), "Could not read " + file + ": " + e.getMessage());
    }
  }

  public static Hooks parse(String text) {
    Map<Event, List<Command>> found = new EnumMap<>(Event.class);
    JsonNode root;
    try {
      root = JSON.readTree(text);
    } catch (IOException e) {
      return new Hooks(found, "hooks.json is not valid JSON: " + firstLine(e.getMessage()));
    }
    JsonNode events = root == null ? null : root.path("hooks");
    if (events == null || !events.isObject()) {
      return new Hooks(found, "hooks.json has no \"hooks\" object.");
    }
    List<String> problems = new ArrayList<>();
    for (Event event : Event.values()) {
      for (JsonNode group : events.path(event.wire)) {
        String matcherText = group.path("matcher").asText("").trim();
        Pattern matcher;
        try {
          matcher = matcher(matcherText);
        } catch (PatternSyntaxException e) {
          problems.add(event.wire + " matcher " + matcherText + " is not a valid pattern");
          continue;
        }
        for (JsonNode hook : group.path("hooks")) {
          String type = hook.path("type").asText("command");
          String command = hook.path("command").asText("").trim();
          if (!"command".equals(type) || command.isEmpty()) {
            continue;
          }
          int timeout = hook.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
          timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeout));
          found.computeIfAbsent(event, k -> new ArrayList<>()).add(new Command(matcher, matcherText, command, timeout));
        }
      }
    }
    return new Hooks(found, problems.isEmpty() ? null : String.join("; ", problems));
  }

  private static Pattern matcher(String text) {
    if (text.isEmpty() || text.equals("*")) {
      return null;
    }
    if (EXACT_NAMES.matcher(text).matches()) {
      return Pattern.compile("(?:" + text + ")");
    }
    return Pattern.compile(text);
  }

  // Why the file gave fewer hooks than it seems to define; null when it was read as written.
  public String problem() {
    return problem;
  }

  public boolean isEmpty() {
    return commands.isEmpty();
  }

  public boolean has(Event event) {
    return commands.containsKey(event);
  }

  public int count() {
    return commands.values().stream().mapToInt(List::size).sum();
  }

  // The base of every event's input; the caller adds the event's own fields.
  public static ObjectNode input(Event event, String sessionId, Path cwd) {
    ObjectNode input = JSON.createObjectNode();
    input.put("hook_event_name", event.wire);
    input.put("timestamp", Instant.now().toString());
    if (sessionId != null) {
      input.put("session_id", sessionId);
    }
    if (cwd != null) {
      input.put("cwd", cwd.toString());
    }
    return input;
  }

  // Tool arguments as an object when they are JSON, else as the string they are.
  public static JsonNode toolInput(String arguments) {
    try {
      JsonNode parsed = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      return parsed == null ? JSON.createObjectNode() : parsed;
    } catch (IOException e) {
      return JSON.getNodeFactory().textNode(arguments);
    }
  }

  // Runs the event's commands one after another, those whose matcher fits target (the tool name; null for events
  // without one). The first block stops the rest. Blocking only counts for events that can block.
  public Outcome run(Event event, String target, ObjectNode input, Path cwd, BooleanSupplier cancelled) {
    List<Command> list = commands.get(event);
    if (list == null) {
      return Outcome.NONE;
    }
    List<String> context = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    String stdin;
    try {
      stdin = JSON.writeValueAsString(input);
    } catch (IOException e) {
      return new Outcome(false, null, List.of(), List.of("could not write the hook input: " + e.getMessage()));
    }
    for (Command command : list) {
      if (command.matcher() != null && (target == null || !command.matcher().matcher(target).matches())) {
        continue;
      }
      if (cancelled != null && cancelled.getAsBoolean()) {
        break;
      }
      Result result = exec(command, stdin, cwd, cancelled);
      String name = shortName(command.command());
      if (result.problem() != null) {
        problems.add(name + " " + result.problem());
        continue;
      }
      if (result.exit() == 2) {
        if (event.controls) {
          String reason = result.stderr().isBlank() ? "no reason given" : result.stderr().strip();
          return new Outcome(true, reason, context, problems);
        }
        continue;
      }
      if (result.exit() != 0) {
        String said = firstLine(result.stderr().strip());
        problems.add(name + " exited with " + result.exit() + (said.isEmpty() ? "" : ": " + said));
        continue;
      }
      String out = result.stdout().strip();
      if (out.isEmpty()) {
        continue;
      }
      JsonNode answer = null;
      if (out.startsWith("{")) {
        try {
          answer = JSON.readTree(out);
        } catch (IOException e) {
          problems.add(name + " printed JSON that could not be read");
          continue;
        }
      }
      if (answer == null) {
        // Plain text from a prompt hook goes to the model, as in Claude Code; other events ignore it.
        if (event == Event.USER_PROMPT_SUBMIT) {
          context.add(out);
        }
        continue;
      }
      JsonNode specific = answer.path("hookSpecificOutput");
      addText(context, specific.path("additionalContext"));
      addText(context, answer.path("contextModification"));
      String reason = blockReason(event, answer, specific);
      if (reason != null && event.controls) {
        return new Outcome(true, reason, context, problems);
      }
    }
    return new Outcome(false, null, context, problems);
  }

  private static String blockReason(Event event, JsonNode answer, JsonNode specific) {
    String decision = specific.path("permissionDecision").asText("");
    if (event == Event.PRE_TOOL_USE && ("deny".equals(decision) || "ask".equals(decision))) {
      String why = specific.path("permissionDecisionReason").asText("").strip();
      // There is no approval prompt for single tool calls here, so "ask" is refused rather than allowed.
      return why.isEmpty() ? ("ask".equals(decision) ? "the hook asked for approval" : "denied") : why;
    }
    if ("block".equals(answer.path("decision").asText(""))) {
      String why = answer.path("reason").asText("").strip();
      return why.isEmpty() ? "blocked" : why;
    }
    if (answer.path("cancel").asBoolean(false)) {
      String why = answer.path("errorMessage").asText("").strip();
      return why.isEmpty() ? "cancelled" : why;
    }
    return null;
  }

  private static void addText(List<String> to, JsonNode value) {
    if (value.isTextual() && !value.asText().isBlank()) {
      to.add(value.asText().strip());
    }
  }

  record Result(int exit, String stdout, String stderr, String problem) {}

  private static Result exec(Command command, String stdin, Path cwd, BooleanSupplier cancelled) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    ProcessBuilder builder = windows
        ? new ProcessBuilder("cmd.exe", "/c", command.command())
        : new ProcessBuilder("/bin/sh", "-c", command.command());
    Path dir = cwd != null && Files.isDirectory(cwd) ? cwd : Path.of(System.getProperty("user.home"));
    builder.directory(dir.toFile());
    builder.environment().put("MSF_PROJECT_DIR", dir.toString());
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Result(-1, "", "", "could not start: " + e.getMessage());
    }
    CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(process.getInputStream()));
    CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
    try (OutputStream in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // A hook may ignore its input and exit before reading it.
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(command.timeoutSeconds());
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (cancelled != null && cancelled.getAsBoolean()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          return new Result(-1, "", "", "was stopped by Cancel");
        }
        if (System.nanoTime() > deadline) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          return new Result(-1, "", "", "timed out after " + command.timeoutSeconds() + " s");
        }
      }
      return new Result(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), null);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return new Result(-1, "", "", "was interrupted");
    } catch (Exception e) {
      return new Result(-1, "", "", "failed: " + e.getMessage());
    }
  }

  private static String drain(InputStream stream) {
    ByteArrayOutputStream kept = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try (stream) {
      for (int n; (n = stream.read(buffer)) > 0; ) {
        int room = MAX_OUTPUT - kept.size();
        if (room > 0) {
          kept.write(buffer, 0, Math.min(n, room));
        }
      }
    } catch (IOException e) {
      // Keep what arrived.
    }
    return kept.toString(StandardCharsets.UTF_8);
  }

  static String shortName(String command) {
    String first = command.strip().split("\\s+", 2)[0];
    int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
    String name = slash >= 0 ? first.substring(slash + 1) : first;
    return name.length() > 40 ? name.substring(0, 40) + "…" : name;
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "";
    }
    int line = text.indexOf('\n');
    String first = line < 0 ? text : text.substring(0, line);
    return first.length() > 200 ? first.substring(0, 200) + "…" : first;
  }

  // Written by "Open hooks file" when there is none yet: every event, no commands, and two examples to copy.
  public static String template() {
    return """
        {
          "hooks": {
            "UserPromptSubmit": [],
            "PreToolUse": [],
            "PostToolUse": [],
            "Stop": [],
            "Notification": []
          },
          "examples": {
            "about": "Not run. Copy an entry into \\"hooks\\" above. Each command gets the event as JSON on stdin and runs in the project folder. Use full paths: Studio may not see your shell's PATH.",
            "Notification": [
              { "hooks": [ { "type": "command", "command": "osascript -e 'display notification \\"Studio AI needs you\\" with title \\"Anypoint Studio\\"'" } ] }
            ],
            "PreToolUse": [
              { "matcher": "call_endpoint", "hooks": [ { "type": "command", "command": "cat >> \\"$HOME/studio-ai-calls.jsonl\\"; echo >> \\"$HOME/studio-ai-calls.jsonl\\"", "timeout": 10 } ] }
            ]
          }
        }
        """;
  }
}
