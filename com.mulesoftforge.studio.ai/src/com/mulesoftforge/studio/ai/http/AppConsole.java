package com.mulesoftforge.studio.ai.http;

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
