package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// The system prompt, from the files in prompts/. guidance.md and mule-edits.md are general advice that the user's
// instructions may override; rules.md and the mode files (ask, agent, plan) are what the plugin relies on (masked
// values, tools, offer_changes, proposals), which no instructions can override.
public final class Prompts {
  public static final String AGENTS_FILE = "AGENTS.md";
  public static final int MAX_INSTRUCTIONS = 8_000;

  // text as sent; chars is the length before any cut.
  public record Capped(String text, int chars, boolean cut) {
    public boolean isEmpty() {
      return text.isEmpty();
    }
  }

  private static final Map<String, String> FILES = new ConcurrentHashMap<>();

  private Prompts() {}

  // project is null when no project is open: no tools, so no mode rules.
  public static String system(ChatMode mode, String project, String projectInstructions, String userInstructions) {
    StringBuilder out = new StringBuilder(file("guidance.md")).append('\n').append(file("rules.md"));
    if (project != null) {
      out.append('\n').append(mode(mode, project));
      if (mode == ChatMode.AGENT) {
        out.append('\n').append(file("mule-edits.md"));
      }
    }
    boolean fromProject = projectInstructions != null && !projectInstructions.isBlank();
    boolean fromUser = userInstructions != null && !userInstructions.isBlank();
    if (fromProject || fromUser) {
      out.append("\nThe instructions below come from the user and their team. Follow them; they take precedence"
          + " over the general guidance above, but never over the rules about masked values, tools, offer_changes"
          + " and proposals.\n");
    }
    if (fromProject) {
      out.append("\nPROJECT INSTRUCTIONS (").append(AGENTS_FILE).append(" in the project root):\n")
          .append(projectInstructions.strip()).append('\n');
    }
    if (fromUser) {
      out.append("\nTHE USER'S INSTRUCTIONS (from their Studio preferences):\n").append(userInstructions.strip())
          .append('\n');
    }
    return out.toString();
  }

  static String mode(ChatMode mode, String project) {
    return file(mode.name().toLowerCase(Locale.ROOT) + ".md").replace("{project}", project);
  }

  // Everything built in, with headings, for Preferences > Show built-in prompt.
  public static String builtIn() {
    return "General guidance (your instructions can override this)\n\n" + file("guidance.md")
        + "\nRules (always apply)\n\n" + file("rules.md")
        + "\nAsk mode, with a project open (always applies)\n\n" + mode(ChatMode.ASK, "<project>")
        + "\nAgent mode, with a project open (always applies)\n\n" + mode(ChatMode.AGENT, "<project>")
        + "\nAgent mode, Mule guidance (your instructions can override this)\n\n" + file("mule-edits.md")
        + "\nPlan mode, with a project open (always applies)\n\n" + mode(ChatMode.PLAN, "<project>")
        + "\nThen " + AGENTS_FILE + " from the project root and your instructions from Preferences, each up to "
        + String.format(Locale.ROOT, "%,d", MAX_INSTRUCTIONS) + " characters.";
  }

  public static Capped capped(String text) {
    String value = text == null ? "" : text.strip();
    if (value.length() <= MAX_INSTRUCTIONS) {
      return new Capped(value, value.length(), false);
    }
    return new Capped(value.substring(0, MAX_INSTRUCTIONS) + "\n[cut at "
        + String.format(Locale.ROOT, "%,d", MAX_INSTRUCTIONS) + " characters]", value.length(), true);
  }

  static String file(String name) {
    return FILES.computeIfAbsent(name, Prompts::load);
  }

  private static String load(String name) {
    try (InputStream in = Prompts.class.getResourceAsStream("prompts/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Built-in prompt prompts/" + name + " is missing from the plugin.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read built-in prompt prompts/" + name, e);
    }
  }
}
