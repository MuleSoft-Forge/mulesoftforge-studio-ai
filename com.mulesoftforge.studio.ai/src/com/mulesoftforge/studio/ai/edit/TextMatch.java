/*
 * The line passes in seek() are adapted from OpenAI Codex, codex-rs/apply-patch/src/seek_sequence.rs
 * (Copyright OpenAI), and Roo-Code, src/core/tools/apply-patch/seek-sequence.ts. isPlaceholder() is adapted
 * from gemini-cli, packages/core/src/tools/omissionPlaceholderDetector.ts (Copyright 2025 Google LLC).
 * All three are licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */
package com.mulesoftforge.studio.ai.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

// Finds old_text in a file for edit_file. Exact text first; then whole lines compared with decreasing strictness:
// trailing whitespace ignored, both ends trimmed, typographic quotes, dashes and spaces made plain. A line match
// re-indents new_text by the difference between the indent the model wrote and the one in the file.
final class TextMatch {
  // How old_text was found, for the edit result.
  enum Pass {
    EXACT(null),
    TRAILING_WHITESPACE("ignoring trailing whitespace"),
    INDENT("ignoring indentation"),
    PUNCTUATION("ignoring indentation and typographic quotes and dashes");

    final String note;

    Pass(String note) {
      this.note = note;
    }
  }

  // The file with every match replaced, or the reason there was none.
  record Result(String updated, int count, Pass pass, String error) {
    boolean ok() {
      return error == null;
    }
  }

  private static final List<String> OMITTED = List.of("rest of", "rest unchanged", "unchanged", "existing",
      "remaining", "same as before", "other flows", "other elements", "keep", "as before");
  private static final Pattern DOTS = Pattern.compile("\\.{3,}|…");

  private TextMatch() {}

  // expected is how many times old_text must occur; every occurrence is replaced.
  static Result replace(String content, String oldText, String newText, int expected) {
    int exact = count(content, oldText);
    if (exact > 0) {
      if (exact != expected) {
        return countError(exact, expected);
      }
      return new Result(content.replace(oldText, newText), exact, Pass.EXACT, null);
    }
    List<String> lines = List.of(content.split("\n", -1));
    List<String> pattern = linesOf(oldText);
    if (pattern.isEmpty()) {
      return new Result(null, 0, null, "old_text is only whitespace.");
    }
    for (Pass pass : List.of(Pass.TRAILING_WHITESPACE, Pass.INDENT, Pass.PUNCTUATION)) {
      List<Integer> starts = seek(lines, pattern, normaliser(pass));
      if (starts.isEmpty()) {
        continue;
      }
      if (starts.size() != expected) {
        return countError(starts.size(), expected);
      }
      return new Result(spliced(lines, pattern, starts, newText, content.contains("\r\n")), starts.size(), pass,
          null);
    }
    return new Result(null, 0, null, null);
  }

  // Non-overlapping starts of pattern in lines, each line compared after normalise.
  private static List<Integer> seek(List<String> lines, List<String> pattern, UnaryOperator<String> normalise) {
    List<String> want = pattern.stream().map(normalise).toList();
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i + pattern.size() <= lines.size(); i++) {
      boolean ok = true;
      for (int j = 0; j < pattern.size() && ok; j++) {
        ok = normalise.apply(lines.get(i + j)).equals(want.get(j));
      }
      if (ok) {
        starts.add(i);
        i += pattern.size() - 1;
      }
    }
    return starts;
  }

  private static UnaryOperator<String> normaliser(Pass pass) {
    return switch (pass) {
      case EXACT -> s -> s;
      case TRAILING_WHITESPACE -> TextMatch::stripEnd;
      case INDENT -> String::strip;
      case PUNCTUATION -> s -> plain(s).strip();
    };
  }

  private static String spliced(List<String> lines, List<String> pattern, List<Integer> starts, String newText,
      boolean crlf) {
    List<String> out = new ArrayList<>();
    int next = 0;
    for (int start : starts) {
      out.addAll(lines.subList(next, start));
      String last = lines.get(start + pattern.size() - 1);
      List<String> replacement = reindented(linesOf(newText), pattern, lines.subList(start, start + pattern.size()));
      for (int k = 0; k < replacement.size(); k++) {
        String line = replacement.get(k);
        out.add(crlf && (k < replacement.size() - 1 || last.endsWith("\r")) ? line + "\r" : line);
      }
      next = start + pattern.size();
    }
    out.addAll(lines.subList(next, lines.size()));
    return String.join("\n", out);
  }

  // A line of new_text that is also in old_text takes the indent that line has in the file; any other line keeps
  // its offset from the line before it, so a flush-left snippet still nests as the file does.
  private static List<String> reindented(List<String> lines, List<String> pattern, List<String> found) {
    Map<String, String> fileIndent = new HashMap<>();
    for (int j = 0; j < pattern.size(); j++) {
      if (!pattern.get(j).isBlank()) {
        fileIndent.putIfAbsent(plain(pattern.get(j)).strip(), indent(found.get(j)));
      }
    }
    String wrotePrevious = indent(firstNonBlank(pattern));
    String filePrevious = indent(firstNonBlank(found));
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank()) {
        out.add(line);
        continue;
      }
      String body = line.stripLeading();
      String wrote = indent(line);
      String known = fileIndent.get(plain(body).strip());
      String target = known != null ? known
          : wrote.startsWith(wrotePrevious) ? filePrevious + wrote.substring(wrotePrevious.length())
          : filePrevious;
      out.add(target + body);
      wrotePrevious = wrote;
      filePrevious = target;
    }
    return out;
  }

  // The lines of a snippet without line ends, dropping one final newline and surrounding blank lines.
  private static List<String> linesOf(String text) {
    List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").split("\n", -1)));
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    while (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    return lines;
  }

  private static String firstNonBlank(List<String> lines) {
    return lines.stream().filter(l -> !l.isBlank()).findFirst().orElse("");
  }

  private static String indent(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  private static String stripEnd(String s) {
    int end = s.length();
    while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(0, end);
  }

  private static String plain(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      out.append(switch (c) {
        case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
        case '\u2018', '\u2019', '\u201A', '\u201B' -> '\'';
        case '\u201C', '\u201D', '\u201E', '\u201F' -> '"';
        case '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
            '\u202F', '\u205F', '\u3000' -> ' ';
        default -> c;
      });
    }
    return out.toString();
  }

  static int count(String content, String text) {
    if (text.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (int i = content.indexOf(text); i >= 0; i = content.indexOf(text, i + text.length())) {
      n++;
    }
    return n;
  }

  private static Result countError(int found, int expected) {
    String error = expected == 1
        ? "old_text occurs " + found + " times. Include more surrounding lines to pick one, or set"
            + " expected_replacements to " + found + " to replace them all."
        : "old_text occurs " + found + " times, not the " + expected + " in expected_replacements.";
    return new Result(null, found, null, error);
  }

  // The file's lines most like old_text, numbered, for the error when nothing matched; null when none is close.
  static String closest(String content, String oldText) {
    List<String> lines = List.of(content.split("\n", -1));
    List<String> pattern = linesOf(oldText);
    if (pattern.isEmpty() || lines.size() > 20_000 || pattern.size() > 200) {
      return null;
    }
    int size = Math.min(pattern.size(), lines.size());
    double best = 0;
    int at = -1;
    for (int i = 0; i + size <= lines.size(); i++) {
      double score = 0;
      for (int j = 0; j < size; j++) {
        score += similarity(lines.get(i + j).strip(), pattern.get(j).strip());
      }
      if (score > best) {
        best = score;
        at = i;
      }
    }
    if (at < 0 || best / size < 0.5) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int j = 0; j < size; j++) {
      out.append(at + j + 1).append(": ").append(stripEnd(lines.get(at + j))).append('\n');
    }
    return out.toString();
  }

  // 1 for equal lines, falling towards 0 with the share of characters that differ.
  private static double similarity(String a, String b) {
    if (a.equals(b)) {
      return 1;
    }
    String x = a.length() > 200 ? a.substring(0, 200) : a;
    String y = b.length() > 200 ? b.substring(0, 200) : b;
    int longest = Math.max(x.length(), y.length());
    return longest == 0 ? 1 : 1 - (double) distance(x, y) / longest;
  }

  private static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] row = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      row[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        row[j] = Math.min(Math.min(row[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = row;
      row = swap;
    }
    return previous[b.length()];
  }

  // A line in new_text that stands for code left out, such as "<!-- ... rest unchanged ... -->" or
  // "// rest of flows ...", which would delete that code when the edit is applied.
  static String placeholder(String oldText, String newText) {
    for (String line : linesOf(newText)) {
      if (isPlaceholder(line) && !oldText.contains(line.strip())) {
        return line.strip();
      }
    }
    return null;
  }

  private static boolean isPlaceholder(String line) {
    String text = line.strip();
    if (text.startsWith("<!--") && text.endsWith("-->")) {
      text = text.substring(4, text.length() - 3).strip();
    } else if (text.startsWith("//") || text.startsWith("#")) {
      text = text.substring(text.startsWith("//") ? 2 : 1).strip();
    } else if (!DOTS.matcher(text).find() || text.startsWith("<")) {
      return false;
    }
    if (text.startsWith("(") && text.endsWith(")")) {
      text = text.substring(1, text.length() - 1).strip();
    }
    if (!DOTS.matcher(text).find()) {
      return false;
    }
    String words = DOTS.matcher(text).replaceAll(" ").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    return words.isEmpty() || OMITTED.stream().anyMatch(words::startsWith);
  }
}
