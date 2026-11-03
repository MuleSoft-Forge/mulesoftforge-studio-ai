package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// The system prompt, from the files in prompts/. guidance.md and mule-edits.md are general advice that the user's
// instructions may override; rules.md, about.md and the mode files (ask, agent, plan, diagnose) are what the plugin
// relies on (masked values, tools, offer_changes, proposals, and answering "what can you do" as this product), which
// no instructions can override.
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
    return system(mode, project, projectInstructions, userInstructions, false, false, false, false, "");
  }

  // debugging: Studio's debugger is stopped and the debug tools are offered. dataWeave: the DataWeave CLI is.
  // http: the tools that call the local app and read documentation are. munit: the user asked for MUnit tests, or a
  // suite under src/test/munit is attached. httpInstructions are the user's extras for the HTTP tools, from
  // Preferences; the built-in http.md still always applies.
  public static String system(ChatMode mode, String project, String projectInstructions, String userInstructions,
      boolean debugging, boolean dataWeave, boolean http, boolean munit, String httpInstructions) {
    return system(mode, project, projectInstructions, userInstructions, debugging, dataWeave, http, munit, false,
        httpInstructions);
  }

  // metadata: the request is about metadata or Studio's type warnings.
  public static String system(ChatMode mode, String project, String projectInstructions, String userInstructions,
      boolean debugging, boolean dataWeave, boolean http, boolean munit, boolean metadata, String httpInstructions) {
    StringBuilder out = new StringBuilder(file("guidance.md")).append('\n').append(file("rules.md")).append('\n')
        .append(file("about.md"));
    if (project != null) {
      out.append('\n').append(mode(mode, project));
      if (mode == ChatMode.AGENT) {
        out.append('\n').append(file("mule-edits.md"));
      }
    }
    if (debugging) {
      out.append('\n').append(file("debug.md"));
    }
    if (dataWeave) {
      out.append('\n').append(file("dataweave.md"));
    }
    if (http) {
      out.append('\n').append(file("http.md"));
      if (httpInstructions != null && !httpInstructions.isBlank()) {
        out.append("\nHTTP AND DOCS INSTRUCTIONS (from Studio preferences; they add to the guidance above, and"
            + " never override the rules about masked values, tools, localhost-only calls or documentation"
            + " allowlists):\n").append(httpInstructions.strip()).append('\n');
      }
    }
    if (munit) {
      out.append('\n').append(file("munit.md"));
    }
    if (metadata && project != null) {
      out.append('\n').append(metadata());
    }
    boolean fromProject = projectInstructions != null && !projectInstructions.isBlank();
    boolean fromUser = userInstructions != null && !userInstructions.isBlank();
    if (fromProject || fromUser) {
      out.append("\nThe instructions below come from the user and their team. Follow them; they take precedence"
          + " over the general guidance above, but never over the rules about masked values, tools, offer_changes,"
          + " proposals, or answering what MuleSoftForge Studio AI can do.\n");
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
  public static String metadata() {
    return file("metadata.md");
  }

  public static String builtIn() {
    return "General guidance (your instructions can override this)\n\n" + file("guidance.md")
        + "\nRules (always apply)\n\n" + file("rules.md")
        + "\nWhen asked what MuleSoftForge Studio AI can do (always applies)\n\n" + file("about.md")
        + "\nAsk mode, with a project open (always applies)\n\n" + mode(ChatMode.ASK, "<project>")
        + "\nAgent mode, with a project open (always applies)\n\n" + mode(ChatMode.AGENT, "<project>")
        + "\nAgent mode, Mule guidance (your instructions can override this)\n\n" + file("mule-edits.md")
        + "\nPlan mode, with a project open (always applies)\n\n" + mode(ChatMode.PLAN, "<project>")
        + "\nDiagnose mode, with a project open (always applies)\n\n" + mode(ChatMode.DIAGNOSE, "<project>")
        + "\nWhile Studio's debugger is stopped (always applies)\n\n" + file("debug.md")
        + "\nWith the DataWeave CLI (always applies)\n\n" + file("dataweave.md")
        + "\nCalling the local app and reading documentation (always applies)\n\n" + file("http.md")
        + "\nWhen creating or editing MUnit tests (always applies)\n\n" + file("munit.md")
        + "\nWhen the request is about metadata or Studio's type warnings (always applies)\n\n" + metadata()
        + "\nThen any HTTP and docs instructions from Preferences, then " + AGENTS_FILE
        + " from the project root and your instructions from Preferences, each up to "
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
