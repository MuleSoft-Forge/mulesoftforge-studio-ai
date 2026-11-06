package com.mulesoftforge.studio.ai.http;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

// The console of the Mule app the user runs in Studio, as the HTTP tools see it. Studio backs it with its Console
// view; the harness with a fake.
public interface AppConsole {
  // name is the console's title in Studio; text is everything it holds now.
  record Text(String name, String text) {}

  // The console of the Mule app running project, or null when there is none. project may be null.
  Text read(String project);

  Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
  int ANCHOR = 200;

  // The text after appears to have gained since before; all of after when before is null or another console.
  // Consoles drop their oldest lines past a size limit, so the end of before is looked for in after rather than
  // trusting its length.
  static String added(Text before, Text after) {
    if (after == null) {
      return "";
    }
    String now = after.text();
    if (before == null || !before.name().equals(after.name()) || before.text().isEmpty()) {
      return now;
    }
    String old = before.text();
    String anchor = old.substring(Math.max(0, old.length() - ANCHOR));
    if (now.length() >= old.length() && now.startsWith(anchor, old.length() - anchor.length())) {
      return now.substring(old.length());
    }
    int at = now.lastIndexOf(anchor);
    return at >= 0 ? now.substring(at + anchor.length()) : now;
  }

  static String clean(String text) {
    return ANSI.matcher(text).replaceAll("").strip();
  }

  // Lines of Mule's error report (the block between rows of asterisks), exceptions and failure messages. Stack
  // frames are left out: they name the runtime's classes, not the flow.
  Pattern ERROR_LINE = Pattern.compile("^\\s*(Message|Element|Element DSL|Error type|Caused by|Cause|FlowStack)\\s*:"
      + "|Exception|\\bERROR\\b|\\bWARN(ING)?\\b|FAILED|Failed to|Could not|Cannot |Unable to|not found|Address already in use"
      + "|Timeout exceeded|Connection refused");
  Pattern STACK_FRAME = Pattern.compile("^\\s*(at |\\.\\.\\. \\d+ more)");

  // What a failure report needs from a console: the error lines it holds, then its end, within max characters.
  // Errors often sit mid-way (a listener that could not bind, a spec that did not load) before a long tail of
  // packaging or shutdown lines, so the end alone can miss them.
  static String digest(String text, int max) {
    String clean = clean(text);
    if (clean.length() <= max) {
      return clean;
    }
    StringBuilder errors = new StringBuilder();
    Set<String> seen = new HashSet<>();
    int room = max * 3 / 5;
    for (String line : clean.split("\\R")) {
      String row = line.strip();
      if (row.isEmpty() || STACK_FRAME.matcher(line).find() || !ERROR_LINE.matcher(line).find() || !seen.add(row)) {
        continue;
      }
      String kept = row.length() > 400 ? row.substring(0, 400) + "…" : row;
      if (errors.length() + kept.length() + 1 > room) {
        errors.append("… (more error lines cut)\n");
        break;
      }
      errors.append(kept).append('\n');
    }
    if (errors.length() == 0) {
      return tail(clean, max);
    }
    return "ERRORS the console logged (stack frames left out):\n" + errors
        + "\nEND of the console:\n" + tail(clean, Math.max(1_000, max - errors.length()));
  }

  // An exception's message and its causes, without the stack frames between them.
  static String withoutFrames(String trace) {
    StringBuilder out = new StringBuilder();
    int dropped = 0;
    for (String line : trace.split("\\R")) {
      if (STACK_FRAME.matcher(line).find()) {
        dropped++;
        continue;
      }
      out.append(line.strip()).append('\n');
    }
    return (dropped > 0 ? out.append("(").append(dropped).append(" stack frames left out)") : out).toString().strip();
  }

  // The end of text, from a whole line, where errors usually are.
  static String tail(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    String end = text.substring(text.length() - max);
    int line = end.indexOf('\n');
    if (line >= 0 && line < 500) {
      end = end.substring(line + 1);
    }
    return "… (earlier lines cut)\n" + end;
  }
}
