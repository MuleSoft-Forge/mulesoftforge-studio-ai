package com.mulesoftforge.studio.ai.dataweave;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// MuleSoft's DataWeave CLI (dw), a native binary: validates scripts and runs them on sample input. Runs are
// untrusted, so a script cannot read files or the network, and they stop at a timeout or when cancelled. Mule's own
// bindings (payload, vars, attributes, p(), lookup() and the rest) do not exist outside Mule: validation declares
// them, and a run gives p() and lookup() stand-ins.
public final class DataWeaveCli {
  public static final Duration TIMEOUT = Duration.ofSeconds(20);
  // What a script inside a Mule app can refer to without declaring it.
  public static final List<String> MULE_BINDINGS = List.of("payload", "attributes", "vars", "message", "error",
      "correlationId", "authentication", "dataType", "flow", "app", "mule", "server", "itemSequenceInfo", "p",
      "lookup", "causedBy");
  private static final int MAX_OUTPUT = 200_000;
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
  private static final Pattern VERSION = Pattern.compile("Command Line\\s*:\\s*V?([\\d.]+)");
  private static final Pattern ERROR = Pattern.compile("^\\[ERROR\\]\\s*(.*)$");
  private static final Pattern INLINE_AT = Pattern.compile("\\(line (\\d+), column (\\d+)\\)");
  private static final Pattern LOCATION_AT = Pattern.compile("\\(line: (\\d+), column: ?(\\d+)\\)");
  private static final String GENERIC = "Error while executing the script:";
  private static final Pattern USES_P = Pattern.compile("(?<![\\w.:])p\\s*\\(");
  private static final Pattern USES_LOOKUP = Pattern.compile("(?<![\\w.:])lookup\\s*\\(");
  private static final Pattern DEFINES = Pattern.compile("\\bfun\\s+(p|lookup)\\s*\\(");

  // line and column are 1-based in the script as given; 0 when the CLI did not say.
  public record Problem(int line, int column, String message) {}

  public record Validation(boolean ok, List<Problem> problems, String output) {}

  // ok with output, or not ok with problems (and output holding the CLI's whole message).
  public record Run(boolean ok, String output, List<Problem> problems, long millis) {}

  // A named input such as payload or vars, with its media type, e.g. application/json.
  public record Input(String name, String mimeType, String content) {}

  private final Path executable;

  private DataWeaveCli(Path executable) {
    this.executable = executable;
  }

  public static DataWeaveCli at(Path executable) {
    return new DataWeaveCli(executable);
  }

  public Path executable() {
    return executable;
  }

  // The configured path when it is a runnable file, otherwise dw on the PATH, in ~/.dw/bin or from Homebrew; null
  // when there is none.
  public static Path find(String configured) {
    if (configured != null && !configured.isBlank()) {
      Path path = Path.of(configured.strip());
      if (Files.isDirectory(path)) {
        path = path.resolve(binaryName());
      }
      return Files.isExecutable(path) ? path : null;
    }
    List<Path> candidates = new ArrayList<>();
    String pathEnv = System.getenv("PATH");
    if (pathEnv != null) {
      for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
        if (!dir.isBlank()) {
          candidates.add(Path.of(dir, binaryName()));
        }
      }
    }
    String home = System.getProperty("user.home");
    candidates.add(Path.of(home, ".dw", "bin", binaryName()));
    candidates.add(Path.of("/opt/homebrew/bin", binaryName()));
    candidates.add(Path.of("/usr/local/bin", binaryName()));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  static String binaryName() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "dw.exe" : "dw";
  }

  // e.g. "2.12.3", or null when the CLI does not say.
  public String version() throws Exception {
    Output out = exec(List.of(executable.toString(), "--version"), null, Duration.ofSeconds(15), () -> false);
    Matcher m = VERSION.matcher(out.text());
    return m.find() ? m.group(1) : null;
  }

  // Checks the script compiles; names are the inputs it may use without declaring them.
  public Validation validate(String script, List<String> names, Duration timeout, BooleanSupplier cancelled)
      throws Exception {
    Path dir = Files.createTempDirectory("msf-dw-");
    try {
      Path file = dir.resolve("script.dwl");
      Files.writeString(file, script, StandardCharsets.UTF_8);
      List<String> command = new ArrayList<>(List.of(executable.toString(), "validate"));
      for (String name : names) {
        command.add("-i");
        command.add(name);
      }
      command.add("-f");
      command.add(file.toString());
      Output out = exec(command, dir, timeout, cancelled);
      String text = clean(out.text());
      List<Problem> problems = problems(text);
      boolean ok = out.exit() == 0 && text.contains("No errors found");
      return new Validation(ok, ok ? List.of() : problems.isEmpty() ? List.of(new Problem(0, 0, text)) : problems,
          text);
    } finally {
      delete(dir);
    }
  }

  // Runs the script on the inputs and returns what it wrote.
  public Run run(String script, List<Input> inputs, Duration timeout, BooleanSupplier cancelled) throws Exception {
    Path dir = Files.createTempDirectory("msf-dw-");
    long started = System.nanoTime();
    try {
      Stubbed stubbed = stub(script);
      Path file = dir.resolve("script.dwl");
      Files.writeString(file, stubbed.script(), StandardCharsets.UTF_8);
      List<String> command = new ArrayList<>(List.of(executable.toString(), "run", "-s", "--untrusted"));
      for (Input input : inputs) {
        Path data = dir.resolve(input.name() + extension(input.mimeType()));
        Files.writeString(data, input.content(), StandardCharsets.UTF_8);
        command.add("-i");
        command.add(input.name() + "=" + data);
      }
      command.add("-f");
      command.add(file.toString());
      Output out = exec(command, dir, timeout, cancelled);
      String text = clean(out.text());
      long millis = (System.nanoTime() - started) / 1_000_000;
      if (out.exit() == 0 && !text.contains("[ERROR]")) {
        return new Run(true, text, List.of(), millis);
      }
      List<Problem> problems = new ArrayList<>();
      for (Problem p : problems(text)) {
        problems.add(new Problem(Math.max(0, p.line() - (p.line() > stubbed.addedLines() ? stubbed.addedLines() : 0)),
            p.column(), p.message()));
      }
      return new Run(false, text, problems.isEmpty() ? List.of(new Problem(0, 0, text)) : problems, millis);
    } finally {
      delete(dir);
    }
  }

  private record Stubbed(String script, int addedLines) {}

  // p() and lookup() exist only inside Mule; a run gets stand-ins that return null, after the %dw line.
  static Stubbed stub(String script) {
    if (DEFINES.matcher(script).find()) {
      return new Stubbed(script, 0);
    }
    StringBuilder stubs = new StringBuilder();
    if (USES_P.matcher(script).find()) {
      stubs.append("fun p(name: String) = null\n");
    }
    if (USES_LOOKUP.matcher(script).find()) {
      stubs.append("fun lookup(flow: String, value: Any = null) = null\n");
    }
    if (stubs.isEmpty()) {
      return new Stubbed(script, 0);
    }
    int added = (int) stubs.chars().filter(c -> c == '\n').count();
    String trimmed = script.stripLeading();
    if (trimmed.startsWith("%dw")) {
      int end = trimmed.indexOf('\n');
      String first = end < 0 ? trimmed : trimmed.substring(0, end + 1);
      String rest = end < 0 ? "" : trimmed.substring(end + 1);
      return new Stubbed(first + (end < 0 ? "\n" : "") + stubs + rest, added);
    }
    if (!script.contains("---")) {
      return new Stubbed(stubs + "---\n" + script, added + 1);
    }
    return new Stubbed(stubs + script, added);
  }

  // [ERROR] lines with the line and column they name, from validate or run output.
  public static List<Problem> problems(String text) {
    List<Problem> out = new ArrayList<>();
    String[] lines = clean(text).split("\n");
    for (int i = 0; i < lines.length; i++) {
      Matcher error = ERROR.matcher(lines[i].strip());
      if (!error.matches() || error.group(1).equals(GENERIC)) {
        continue;
      }
      String message = error.group(1).strip();
      int line = 0;
      int column = 0;
      Matcher inline = INLINE_AT.matcher(message);
      if (inline.find()) {
        line = Integer.parseInt(inline.group(1));
        column = Integer.parseInt(inline.group(2));
        message = message.substring(0, inline.start()).strip();
      } else {
        for (int j = i + 1; j < lines.length && !ERROR.matcher(lines[j].strip()).matches(); j++) {
          Matcher at = LOCATION_AT.matcher(lines[j]);
          if (at.find()) {
            line = Integer.parseInt(at.group(1));
            column = Integer.parseInt(at.group(2));
            break;
          }
        }
      }
      if (message.endsWith(":")) {
        message = message.substring(0, message.length() - 1).strip();
      }
      out.add(new Problem(line, column, message));
    }
    return out;
  }

  // Without colour codes, JVM warnings and the CLI's progress lines.
  public static String clean(String text) {
    StringBuilder out = new StringBuilder();
    for (String line : ANSI.matcher(text).replaceAll("").split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("WARNING: ") || trimmed.startsWith("Compiling `")) {
        continue;
      }
      out.append(line).append('\n');
    }
    return out.toString().strip();
  }

  static String extension(String mimeType) {
    String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (type.contains("json")) {
      return ".json";
    }
    if (type.contains("xml")) {
      return ".xml";
    }
    if (type.contains("csv")) {
      return ".csv";
    }
    if (type.contains("yaml") || type.contains("yml")) {
      return ".yaml";
    }
    return ".txt";
  }

  private record Output(int exit, String text) {}

  private static Output exec(List<String> command, Path dir, Duration timeout, BooleanSupplier cancelled)
      throws Exception {
    Path log = Files.createTempFile("msf-dw-", ".out");
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
      if (dir != null) {
        builder.directory(dir.toFile());
      }
      Process process = builder.start();
      long deadline = System.nanoTime() + timeout.toNanos();
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (cancelled.getAsBoolean()) {
          process.destroyForcibly();
          throw new java.util.concurrent.CancellationException("DataWeave run cancelled");
        }
        if (System.nanoTime() > deadline) {
          process.destroyForcibly();
          throw new java.util.concurrent.TimeoutException("DataWeave did not finish within " + timeout.toSeconds()
              + " s");
        }
      }
      String text = Files.readString(log, StandardCharsets.UTF_8);
      return new Output(process.exitValue(), text.length() > MAX_OUTPUT ? text.substring(0, MAX_OUTPUT) : text);
    } finally {
      Files.deleteIfExists(log);
    }
  }

  private static void delete(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // A temp file left behind is harmless.
        }
      });
    } catch (IOException ignored) {
      // Same.
    }
  }
}
