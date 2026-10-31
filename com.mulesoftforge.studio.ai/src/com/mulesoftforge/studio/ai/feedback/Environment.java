package com.mulesoftforge.studio.ai.feedback;

import java.util.ArrayList;
import java.util.List;

// The setup a report describes. Issues are public, so it holds versions and names only: no paths, host names,
// base URLs, project names or keys.
public record Environment(String plugin, String studio, String java, String os, String provider, String model,
    String dataWeave) {
  private static final int MAX_VALUE = 160;

  public Environment {
    plugin = clean(plugin);
    studio = clean(studio);
    java = clean(java);
    os = clean(os);
    provider = clean(provider);
    model = clean(model);
    dataWeave = clean(dataWeave);
  }

  // Java and the operating system come from this JVM; the rest from Studio and the plugin's settings.
  public static Environment of(String plugin, String studio, String provider, String model, String dataWeave) {
    String vendor = System.getProperty("java.vendor", "");
    String java = System.getProperty("java.version", "") + (vendor.isBlank() ? "" : " (" + vendor + ")");
    String os = System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " ("
        + System.getProperty("os.arch", "") + ")";
    return new Environment(plugin, studio, java, os, provider, model, dataWeave);
  }

  public List<String> lines() {
    List<String> lines = new ArrayList<>();
    add(lines, "Plugin", plugin);
    add(lines, "Studio", studio);
    add(lines, "Java", java);
    add(lines, "OS", os);
    add(lines, "Provider", provider);
    add(lines, "Model", model);
    add(lines, "DataWeave CLI", dataWeave);
    return lines;
  }

  public String text() {
    return String.join("\n", lines());
  }

  private static void add(List<String> lines, String label, String value) {
    if (!value.isEmpty()) {
      lines.add(label + ": " + value);
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    String line = value.replaceAll("\\s+", " ").strip();
    return line.length() <= MAX_VALUE ? line : line.substring(0, MAX_VALUE) + "…";
  }
}
