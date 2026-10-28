package com.mulesoftforge.studio.ai.check;

// One finding of the static checks. Line is 0 when it applies to the whole file.
public record Problem(String path, int line, String message) {
  // Identity across before/after snapshots: line numbers shift when a proposal adds lines above.
  String key() {
    return path + "\n" + message;
  }

  @Override
  public String toString() {
    return path + (line > 0 ? " line " + line : "") + ": " + message;
  }
}
