package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.data.message.ImageContent;

// Something sent with a message. Paths are relative to the project; project is null when unknown.
public record Attachment(Kind kind, String project, String path, String lines, String content, boolean truncated) {
  public static final int MAX_CHARS = 40_000;
  public static final int MAX_TOTAL_CHARS = 120_000;

  public enum Kind {
    OPEN_FILE,
    FILE,
    SELECTION,
    FOLDER,
    PROBLEMS,
    CONSOLE,
    IMAGE
  }

  public static final int MAX_IMAGES = 5;
  private static final Pattern DATA_URL = Pattern.compile("data:(image/[a-z+.-]+);base64,(.+)", Pattern.DOTALL);

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
  private static final Pattern FAILURE = Pattern.compile(
      "BUILD FAILURE|\\bERROR\\b|\\bFATAL\\b|Exception\\b|Caused by:|\\bFAILED\\b");

  // One entry from Studio's Problems view. Line is 1-based, or 0 when the problem has no line.
  public record Problem(Severity severity, String path, int line, String message) {
    public enum Severity {
      ERROR,
      WARNING,
      INFO
    }

    String toLine() {
      return severity + " " + (path.isEmpty() ? "/" : path) + (line > 0 ? " line " + line : "") + ": " + message;
    }
  }

  public static final int MAX_PROBLEMS = 200;

  public static Attachment openFile(String project, String path, String content) {
    return capped(Kind.OPEN_FILE, project, path, null, content);
  }

  public static Attachment file(String project, String path, String content) {
    return capped(Kind.FILE, project, path, null, content);
  }

  // Lines are 1-based and inclusive.
  public static Attachment selection(String project, String path, int firstLine, int lastLine, String content) {
    String lines = firstLine == lastLine ? "line " + firstLine : "lines " + firstLine + "–" + lastLine;
    return capped(Kind.SELECTION, project, path, lines, content);
  }

  public static Attachment folder(String project, String path, List<String> files, boolean truncated) {
    return new Attachment(Kind.FOLDER, project, path, null, String.join("\n", files), truncated);
  }

  // Errors first, then by file and line. Scope is what was asked for, e.g. a file path or the project name.
  public static Attachment problems(String project, String scope, List<Problem> problems) {
    List<Problem> sorted = new ArrayList<>(problems);
    sorted.sort(Comparator.comparing(Problem::severity).thenComparing(Problem::path).thenComparingInt(Problem::line));
    boolean truncated = sorted.size() > MAX_PROBLEMS;
    List<String> lines = new ArrayList<>();
    for (Problem problem : truncated ? sorted.subList(0, MAX_PROBLEMS) : sorted) {
      lines.add(problem.toLine());
    }
    long errors = sorted.stream().filter(p -> p.severity() == Problem.Severity.ERROR).count();
    long warnings = sorted.stream().filter(p -> p.severity() == Problem.Severity.WARNING).count();
    List<String> counts = new ArrayList<>();
    if (errors > 0) {
      counts.add(errors + (errors == 1 ? " error" : " errors"));
    }
    if (warnings > 0) {
      counts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
    }
    if (counts.isEmpty()) {
      counts.add(sorted.size() + (sorted.size() == 1 ? " problem" : " problems"));
    }
    return new Attachment(Kind.PROBLEMS, project, scope, String.join(", ", counts), String.join("\n", lines),
        truncated);
  }

  // Console output keeps its end, where errors usually are, starting at a whole line. Colour codes are removed.
  // With selected, it is text the user selected in that console.
  public static Attachment console(String name, String text, boolean selected) {
    String clean = ANSI.matcher(text).replaceAll("").strip();
    boolean truncated = clean.length() > MAX_CHARS;
    if (truncated) {
      clean = clean.substring(clean.length() - MAX_CHARS);
      int line = clean.indexOf('\n');
      if (line >= 0 && line < 2_000) {
        clean = clean.substring(line + 1);
      }
    }
    return new Attachment(Kind.CONSOLE, null, name, selected ? "selection" : null, clean, truncated);
  }

  // A picture for the model to look at, e.g. "pasted image 1". Content is the image as a base64 data URL.
  public static Attachment image(String name, int width, int height, String mimeType, byte[] bytes) {
    return new Attachment(Kind.IMAGE, null, name, width + "×" + height,
        "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes), false);
  }

  // Whether this shows something failing, so the composer can suggest fixing it.
  public boolean reportsFailure() {
    return switch (kind) {
      case PROBLEMS -> content.startsWith(Problem.Severity.ERROR + " ");
      case CONSOLE -> FAILURE.matcher(content).find();
      case OPEN_FILE, FILE, SELECTION, FOLDER, IMAGE -> false;
    };
  }

  // For IMAGE: the image itself, sent as an image part rather than as text.
  ImageContent toImage() {
    Matcher m = DATA_URL.matcher(content);
    if (kind != Kind.IMAGE || !m.matches()) {
      throw new IllegalStateException("Not an image attachment: " + label());
    }
    return ImageContent.from(m.group(2), m.group(1));
  }

  private static Attachment capped(Kind kind, String project, String path, String lines, String content) {
    boolean truncated = content.length() > MAX_CHARS;
    return new Attachment(kind, project, path, lines, truncated ? content.substring(0, MAX_CHARS) : content,
        truncated);
  }

  // Short form for the transcript and saved chats, e.g. "lines 3–9 of src/main/mule/app.xml".
  public String label() {
    String where = path.isEmpty() ? "/" : path;
    return switch (kind) {
      case OPEN_FILE -> "open file " + where;
      case FILE -> "file " + where;
      case SELECTION -> lines + " of " + where;
      case FOLDER -> {
        int count = content.isEmpty() ? 0 : content.split("\n").length;
        yield "folder " + where + " (" + count + (count == 1 ? " file" : " files")
            + (truncated ? ", more not listed" : "") + ")";
      }
      case PROBLEMS -> "problems in " + where + " (" + lines + ")";
      case CONSOLE -> (lines == null ? "console " : "selected text in console ") + where
          + (truncated ? " (last part)" : "");
      case IMAGE -> path + " (" + lines + ")";
    };
  }

  // The block the model sees.
  String toPrompt() {
    String in = project == null ? "" : " (project " + project + ")";
    String cut = truncated ? " (truncated)" : "";
    String heading = switch (kind) {
      case OPEN_FILE -> "OPEN FILE: " + path + in + cut;
      case FILE -> "ATTACHED FILE: " + path + in + cut;
      case SELECTION -> "SELECTED " + lines.toUpperCase() + " OF " + path + in + cut;
      case FOLDER -> "FILES IN FOLDER " + (path.isEmpty() ? "/" : path) + in
          + (truncated ? " (list cut short)" : "");
      case PROBLEMS -> "PROBLEMS STUDIO REPORTS IN " + (path.isEmpty() ? "/" : path) + in + ", " + lines
          + (truncated ? " (only the first " + MAX_PROBLEMS + " listed)" : "")
          + "\nEach is severity, file, line, message. Many problems often share one cause, such as a single"
          + " malformed tag or quote. Read the files to find it before proposing a fix.";
      case CONSOLE -> (lines == null ? "CONSOLE OUTPUT: " : "SELECTED TEXT FROM CONSOLE: ") + path
          + (truncated ? " (only the last part)" : "")
          + "\nThis is output from Studio's Console view, such as a Maven build or a running Mule app. File"
          + " paths in it are absolute; map them to project paths before reading files.";
      case IMAGE -> "ATTACHED IMAGE: " + label() + ", pasted by the user. The image itself is sent with this"
          + " message only; later messages keep just this line, so describe what matters in it when you answer.";
    };
    return kind == Kind.IMAGE ? heading + "\n\n" : heading + "\n```\n" + content + "\n```\n\n";
  }
}
