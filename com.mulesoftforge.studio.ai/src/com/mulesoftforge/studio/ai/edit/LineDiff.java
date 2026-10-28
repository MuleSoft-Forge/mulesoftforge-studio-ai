package com.mulesoftforge.studio.ai.edit;

import java.util.ArrayList;
import java.util.List;

// Small line diff for change summaries and the harness. Studio's compare editor shows the real diff.
public final class LineDiff {
  // Above this many line pairs the LCS table gets too large; fall back to an approximate count.
  private static final long MAX_CELLS = 4_000_000L;

  private LineDiff() {}

  public record Stats(int added, int removed) {}

  public static int lineCount(String text) {
    return text == null || text.isEmpty() ? 0 : lines(text).size();
  }

  public static Stats stats(String before, String after) {
    List<String> ops = ops(lines(before), lines(after));
    if (ops == null) {
      return approximate(lines(before), lines(after));
    }
    int added = 0;
    int removed = 0;
    for (String op : ops) {
      if (op.startsWith("+")) {
        added++;
      } else if (op.startsWith("-")) {
        removed++;
      }
    }
    return new Stats(added, removed);
  }

  // Changed lines with up to `context` unchanged lines around them.
  public static String unified(String path, String before, String after, int context) {
    List<String> ops = ops(lines(before), lines(after));
    StringBuilder out = new StringBuilder("--- ").append(before == null ? "/dev/null" : path)
        .append("\n+++ ").append(path).append('\n');
    if (ops == null) {
      Stats stats = approximate(lines(before), lines(after));
      return out.append("(file too large for inline diff: +").append(stats.added()).append(" -")
          .append(stats.removed()).append(")\n").toString();
    }
    boolean[] keep = new boolean[ops.size()];
    for (int i = 0; i < ops.size(); i++) {
      if (!ops.get(i).startsWith(" ")) {
        for (int j = Math.max(0, i - context); j <= Math.min(ops.size() - 1, i + context); j++) {
          keep[j] = true;
        }
      }
    }
    boolean skipped = false;
    for (int i = 0; i < ops.size(); i++) {
      if (keep[i]) {
        if (skipped) {
          out.append("@@\n");
          skipped = false;
        }
        out.append(ops.get(i)).append('\n');
      } else {
        skipped = true;
      }
    }
    return out.toString();
  }

  private static List<String> ops(List<String> a, List<String> b) {
    if ((long) a.size() * b.size() > MAX_CELLS) {
      return null;
    }
    int[][] lcs = new int[a.size() + 1][b.size() + 1];
    for (int i = a.size() - 1; i >= 0; i--) {
      for (int j = b.size() - 1; j >= 0; j--) {
        lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    List<String> ops = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      if (a.get(i).equals(b.get(j))) {
        ops.add(" " + a.get(i));
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        ops.add("-" + a.get(i++));
      } else {
        ops.add("+" + b.get(j++));
      }
    }
    while (i < a.size()) {
      ops.add("-" + a.get(i++));
    }
    while (j < b.size()) {
      ops.add("+" + b.get(j++));
    }
    return ops;
  }

  private static Stats approximate(List<String> a, List<String> b) {
    List<String> remaining = new ArrayList<>(b);
    int removed = 0;
    for (String line : a) {
      if (!remaining.remove(line)) {
        removed++;
      }
    }
    return new Stats(remaining.size(), removed);
  }

  private static List<String> lines(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    for (String line : text.split("\r?\n", -1)) {
      out.add(line);
    }
    if (text.endsWith("\n")) {
      out.remove(out.size() - 1);
    }
    return out;
  }
}
