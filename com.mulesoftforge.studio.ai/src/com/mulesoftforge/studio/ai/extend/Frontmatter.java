package com.mulesoftforge.studio.ai.extend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The YAML-style block between two --- lines at the top of a rule, skill or workflow file. Only flat key: value
// pairs are read, which is what Agent Skills, Cline and Roo-Code use (name, description, disabled).
public record Frontmatter(Map<String, String> values, String body) {
  public static Frontmatter parse(String text) {
    String value = text == null ? "" : text.replace("\r\n", "\n");
    if (value.startsWith("\uFEFF")) {
      value = value.substring(1);
    }
    if (!value.startsWith("---\n")) {
      return new Frontmatter(Map.of(), value);
    }
    int end = value.indexOf("\n---", 3);
    if (end < 0) {
      return new Frontmatter(Map.of(), value);
    }
    int after = value.indexOf('\n', end + 4);
    String body = after < 0 ? "" : value.substring(after + 1);
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : value.substring(4, end).split("\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
        continue;
      }
      values.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), unquote(line.substring(colon + 1).strip()));
    }
    return new Frontmatter(values, body);
  }

  public String get(String key) {
    return values.getOrDefault(key, "");
  }

  public boolean disabled() {
    String value = get("disabled").toLowerCase(Locale.ROOT);
    return value.equals("true") || value.equals("yes");
  }

  // The file text with disabled: true added, or the disabled line removed; other lines are kept as written.
  public static String withDisabled(String text, boolean disabled) {
    String value = text == null ? "" : text.replace("\r\n", "\n");
    List<String> header = new ArrayList<>();
    String body = value;
    if (value.startsWith("---\n")) {
      int end = value.indexOf("\n---", 3);
      if (end >= 0) {
        int after = value.indexOf('\n', end + 4);
        body = after < 0 ? "" : value.substring(after + 1);
        for (String line : value.substring(4, end).split("\n")) {
          if (!line.isBlank() && !line.toLowerCase(Locale.ROOT).startsWith("disabled:")) {
            header.add(line);
          }
        }
      }
    }
    if (disabled) {
      header.add("disabled: true");
    }
    return header.isEmpty() ? body : "---\n" + String.join("\n", header) + "\n---\n" + body;
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
        || value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
