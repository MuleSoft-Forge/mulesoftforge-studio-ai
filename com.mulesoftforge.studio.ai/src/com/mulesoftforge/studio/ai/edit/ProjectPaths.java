package com.mulesoftforge.studio.ai.edit;

import java.util.Locale;
import java.util.Set;

public final class ProjectPaths {
  // Never listed, read or written: build output, VCS and tooling state.
  private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".mule", ".settings", "target", "bin",
      "node_modules", ".idea", ".vscode");

  private ProjectPaths() {}

  // Model output is untrusted: only plain project-relative paths that stay inside the project.
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Path is empty.");
    }
    String path = raw.trim().replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.contains("://")) {
      throw new IllegalArgumentException("Refusing absolute path: " + raw);
    }
    StringBuilder out = new StringBuilder();
    for (String segment : path.split("/")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        throw new IllegalArgumentException("Refusing path outside the project: " + raw);
      }
      if (out.length() > 0) {
        out.append('/');
      }
      out.append(segment);
    }
    if (out.length() == 0) {
      throw new IllegalArgumentException("Path is empty.");
    }
    return out.toString();
  }

  public static boolean isSkipped(String path) {
    for (String segment : path.split("/")) {
      if (SKIPPED_DIRS.contains(segment.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  public static String requireWritable(String raw) {
    String path = normalize(raw);
    if (isSkipped(path)) {
      throw new IllegalArgumentException("Refusing to write under a build or tooling folder: " + path);
    }
    return path;
  }
}
