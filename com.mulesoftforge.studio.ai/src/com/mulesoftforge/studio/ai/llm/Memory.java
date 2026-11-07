package com.mulesoftforge.studio.ai.llm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// The user's private preferences, kept outside git next to hooks.json. Sent with every chat as SAVED PREFERENCES.
// The "Don't suggest" button appends rules under a ## Do not suggest heading; AGENTS.md uses the same heading for
// project-shared rules.
public final class Memory {
  public static final String FILE_NAME = "memory.md";
  public static final String DO_NOT_SUGGEST = "## Do not suggest";
  public static final String TEMPLATE = """
      # Saved preferences

      Rules for MuleSoftForge Studio AI on this machine. Not shared with the team or committed to git.
      Add lines under "## Do not suggest" so the AI stops offering those changes, or write free-form notes.

      """ + DO_NOT_SUGGEST + "\n";

  private Memory() {}

  // Harness only: point userFile() at a temp path so live machine memory.md does not leak into checks.
  private static volatile Path overrideUserFile;

  public static void overrideUserFile(Path path) {
    overrideUserFile = path;
  }

  // ~/.mulesoftforge/studio-ai/memory.md: per user, every project.
  public static Path userFile() {
    Path override = overrideUserFile;
    return override != null ? override
        : Path.of(System.getProperty("user.home"), ".mulesoftforge", "studio-ai", FILE_NAME);
  }

  // Empty when the file is missing or blank.
  public static String read() {
    return read(userFile());
  }

  public static String read(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return "";
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  // Ensures the file exists with the template header, then opens nothing — the caller opens the editor.
  public static Path ensureUserFile() throws Exception {
    Path file = userFile();
    if (!Files.exists(file)) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
    }
    return file;
  }

  // Prefill for the Don't suggest dialog: "Do not suggest: <title>" without the file list.
  public static String ruleFromOffer(String title) {
    String text = title == null ? "" : title.strip();
    if (text.isEmpty()) {
      return "Do not suggest: ";
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.startsWith("do not suggest")) {
      return text;
    }
    return "Do not suggest: " + text;
  }

  // Appends rule under ## Do not suggest, creating the heading when missing. Skips an exact duplicate. Returns
  // the new file text.
  public static String appendRule(String existing, String rule) {
    String text = existing == null ? "" : existing;
    String line = rule == null ? "" : rule.strip();
    if (line.isEmpty()) {
      return text;
    }
    if (containsRule(text, line)) {
      return text;
    }
    StringBuilder out = new StringBuilder(text);
    while (!out.isEmpty() && out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1);
    }
    if (out.isEmpty()) {
      out.append(TEMPLATE.stripTrailing());
    }
    int heading = indexOfHeading(out.toString());
    if (heading < 0) {
      if (!out.isEmpty()) {
        out.append("\n\n");
      }
      out.append(DO_NOT_SUGGEST).append('\n').append("- ").append(line).append('\n');
      return out.toString();
    }
    int afterHeading = out.indexOf("\n", heading);
    if (afterHeading < 0) {
      out.append('\n').append("- ").append(line).append('\n');
      return out.toString();
    }
    int nextHeading = out.indexOf("\n## ", afterHeading + 1);
    int insertAt = nextHeading < 0 ? out.length() : nextHeading;
    if (insertAt > 0 && out.charAt(insertAt - 1) != '\n') {
      out.insert(insertAt, "\n");
      insertAt++;
    }
    out.insert(insertAt, "- " + line + "\n");
    return out.toString();
  }

  public static void appendRule(Path file, String rule) throws Exception {
    if (file == null) {
      throw new IllegalArgumentException("file");
    }
    String next = appendRule(read(file), rule);
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, next, StandardCharsets.UTF_8);
  }

  static boolean containsRule(String text, String rule) {
    String needle = rule.strip();
    for (String line : text.split("\n")) {
      String body = line.strip();
      if (body.startsWith("- ")) {
        body = body.substring(2).strip();
      }
      if (body.equals(needle)) {
        return true;
      }
    }
    return false;
  }

  private static int indexOfHeading(String text) {
    int from = 0;
    while (from <= text.length()) {
      int at = text.indexOf(DO_NOT_SUGGEST, from);
      if (at < 0) {
        return -1;
      }
      boolean lineStart = at == 0 || text.charAt(at - 1) == '\n';
      int end = at + DO_NOT_SUGGEST.length();
      boolean lineEnd = end >= text.length() || text.charAt(end) == '\n' || text.charAt(end) == '\r';
      if (lineStart && lineEnd) {
        return at;
      }
      from = end;
    }
    return -1;
  }
}
