package com.mulesoftforge.studio.ai.extend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.extend.Extension.Kind;
import com.mulesoftforge.studio.ai.extend.Extension.Source;

// The user's rules, skills and workflows, from three levels: built into the plugin, the user's own in
// ~/.mulesoftforge/studio-ai, and the project's in .mulesoftforge (plus the shared .agents/skills folders other
// agents read). A name defined at more than one level is taken from the project, then the user, then the plugin.
// Every level may be empty.
public final class Extensions {
  public static final String PROJECT_DIR = ".mulesoftforge";
  public static final String AGENTS_SKILLS = ".agents/skills";
  public static final String SKILL_FILE = "SKILL.md";
  public static final List<String> BUILT_IN_SKILLS = List.of("build-mule-integration", "generate-doc-description");
  public static final Extensions NONE = new Extensions(List.of());

  static final int MAX_FILE_CHARS = 64_000;
  private static final int MAX_PER_FOLDER = 100;
  private static final Pattern SLASH = Pattern.compile("^/([A-Za-z0-9][A-Za-z0-9_.-]*)(?:\\s+|$)");

  // Harness only: a temp folder in place of the user's home, so checks do not read this machine's files.
  private static volatile Path overrideHome;

  private final List<Extension> resolved;

  private Extensions(List<Extension> resolved) {
    this.resolved = List.copyOf(resolved);
  }

  public static void overrideHome(Path home) {
    overrideHome = home;
  }

  public static Path home() {
    Path override = overrideHome;
    return override != null ? override : Path.of(System.getProperty("user.home"));
  }

  // ~/.mulesoftforge/studio-ai, next to memory.md and hooks.json.
  public static Path userDir() {
    return home().resolve(".mulesoftforge").resolve("studio-ai");
  }

  // Where files of this kind are read at this level, first the folder "+ New" writes to. Empty for built-ins and
  // for a project level without a project.
  public static List<Path> folders(Kind kind, Source source, Path project) {
    List<Path> out = new ArrayList<>();
    if (source == Source.PROJECT && project != null) {
      out.add(project.resolve(PROJECT_DIR).resolve(kind.folder));
      if (kind == Kind.SKILL) {
        out.add(project.resolve(AGENTS_SKILLS));
      }
    } else if (source == Source.GLOBAL) {
      out.add(userDir().resolve(kind.folder));
      if (kind == Kind.SKILL) {
        out.add(home().resolve(AGENTS_SKILLS));
      }
    }
    return out;
  }

  // Everything at every level, with clashes settled; disabledBuiltIns holds "skill:name" keys turned off in
  // Preferences.
  public static Extensions load(Path project, Set<String> disabledBuiltIns) {
    List<Extension> out = new ArrayList<>();
    for (Kind kind : Kind.values()) {
      Map<String, Extension> byName = new LinkedHashMap<>();
      for (Source source : Source.values()) {
        for (Extension item : list(kind, source, project, disabledBuiltIns)) {
          byName.putIfAbsent(item.name().toLowerCase(Locale.ROOT), item);
        }
      }
      out.addAll(byName.values());
    }
    return new Extensions(out);
  }

  // One level's files, on or off, for Preferences and for load.
  public static List<Extension> list(Kind kind, Source source, Path project, Set<String> disabledBuiltIns) {
    if (source == Source.BUILT_IN) {
      return kind == Kind.SKILL ? builtInSkills(disabledBuiltIns) : List.of();
    }
    List<Extension> out = new ArrayList<>();
    for (Path folder : folders(kind, source, project)) {
      out.addAll(kind == Kind.SKILL ? skills(folder, source) : files(kind, folder, source));
    }
    return out;
  }

  public static String builtInKey(Kind kind, String name) {
    return kind.noun + ":" + name;
  }

  // The enabled ones of this kind, after clashes.
  public List<Extension> enabled(Kind kind) {
    return resolved.stream().filter(e -> e.kind() == kind && e.enabled()).toList();
  }

  public Extension find(Kind kind, String name) {
    if (name == null) {
      return null;
    }
    String key = name.strip().toLowerCase(Locale.ROOT);
    return enabled(kind).stream().filter(e -> e.name().toLowerCase(Locale.ROOT).equals(key)).findFirst()
        .orElse(null);
  }

  // The RULES block's content, one ## heading per rule file; empty without rules. Cline renders rule files the
  // same way.
  public String rulesText() {
    StringBuilder out = new StringBuilder();
    for (Extension rule : enabled(Kind.RULE)) {
      String body = rule.body().strip();
      if (!body.isEmpty()) {
        out.append("## ").append(rule.name()).append(" (").append(rule.source().label).append(")\n").append(body)
            .append("\n\n");
      }
    }
    return out.toString().strip();
  }

  // One line per enabled skill for the SKILLS catalog; the model loads a skill's text with use_skill, as Roo-Code
  // and Codex load theirs on demand. Empty without skills.
  public String skillsCatalog() {
    StringBuilder out = new StringBuilder();
    for (Extension skill : enabled(Kind.SKILL)) {
      out.append("- ").append(skill.name()).append(" (").append(skill.source().label).append("): ")
          .append(skill.description().isBlank() ? "(no description)" : oneLine(skill.description())).append('\n');
    }
    return out.toString().strip();
  }

  // "2 rules (team, naming) and 3 skills", or null with neither.
  public String summary() {
    List<String> parts = new ArrayList<>();
    List<Extension> rules = enabled(Kind.RULE);
    if (!rules.isEmpty()) {
      parts.add(rules.size() + (rules.size() == 1 ? " rule file (" : " rule files (")
          + String.join(", ", rules.stream().map(Extension::name).toList()) + ")");
    }
    int skills = enabled(Kind.SKILL).size();
    if (skills > 0) {
      parts.add(skills + (skills == 1 ? " skill" : " skills"));
    }
    return parts.isEmpty() ? null : String.join(" and ", parts);
  }

  // What a message starting with /name is sent as, and the step that says so; null when it does not name an
  // enabled workflow or skill. A workflow's text goes into the message, as Roo-Code expands its slash commands;
  // a skill is only named, and the model loads it with use_skill.
  public record Expanded(String prompt, String step) {}

  public Expanded expand(String prompt) {
    if (prompt == null) {
      return null;
    }
    Matcher match = SLASH.matcher(prompt.strip());
    if (!match.find()) {
      return null;
    }
    String name = match.group(1);
    String rest = prompt.strip().substring(match.end()).strip();
    Extension workflow = find(Kind.WORKFLOW, name);
    if (workflow != null) {
      String intro = "Workflow '" + workflow.name() + "' (see below for its steps)";
      String text = (rest.isEmpty() ? intro : intro + "\n\n" + rest) + "\n\n<command name=\"" + workflow.name()
          + "\">\n" + (workflow.description().isBlank() ? "" : "Description: " + oneLine(workflow.description())
          + "\n\n") + workflow.body().strip() + "\n</command>";
      return new Expanded(text, "Running the " + workflow.source().label + " workflow " + workflow.name());
    }
    Extension skill = find(Kind.SKILL, name);
    if (skill != null) {
      String text = (rest.isEmpty() ? "Use the skill '" + skill.name() + "'." : rest) + "\n\nUse the skill '"
          + skill.name() + "' for this: call use_skill with name \"" + skill.name() + "\" first and follow it.";
      return new Expanded(text, "Using the " + skill.source().label + " skill " + skill.name());
    }
    return null;
  }

  private static List<Extension> files(Kind kind, Path folder, Source source) {
    List<Extension> out = new ArrayList<>();
    for (Path file : children(folder)) {
      String fileName = file.getFileName().toString();
      String lower = fileName.toLowerCase(Locale.ROOT);
      boolean text = lower.endsWith(".md") || kind == Kind.RULE && lower.endsWith(".txt");
      if (!text || fileName.startsWith(".") || !Files.isRegularFile(file)) {
        continue;
      }
      Frontmatter parsed = Frontmatter.parse(read(file));
      String name = fileName.substring(0, fileName.lastIndexOf('.'));
      out.add(new Extension(kind, name, describe(parsed), source, file, !parsed.disabled(), parsed.body()));
    }
    return out;
  }

  private static List<Extension> skills(Path folder, Source source) {
    List<Extension> out = new ArrayList<>();
    for (Path dir : children(folder)) {
      Path file = dir.resolve(SKILL_FILE);
      if (dir.getFileName().toString().startsWith(".") || !Files.isRegularFile(file)) {
        continue;
      }
      Frontmatter parsed = Frontmatter.parse(read(file));
      out.add(new Extension(Kind.SKILL, dir.getFileName().toString(), describe(parsed), source, file,
          !parsed.disabled(), parsed.body()));
    }
    return out;
  }

  private static List<Extension> builtInSkills(Set<String> disabled) {
    List<Extension> out = new ArrayList<>();
    for (String name : BUILT_IN_SKILLS) {
      Frontmatter parsed = Frontmatter.parse(resource("skills/" + name + "/" + SKILL_FILE));
      boolean on = disabled == null || !disabled.contains(builtInKey(Kind.SKILL, name));
      // The license notice stays in the file and is not sent to the model.
      String body = parsed.body().replaceFirst("(?s)^\\s*<!--.*?-->\\s*", "");
      out.add(new Extension(Kind.SKILL, name, describe(parsed), Source.BUILT_IN, null, on, body));
    }
    return out;
  }

  static String resource(String path) {
    try (InputStream in = Extensions.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Built-in " + path + " is missing from the plugin.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read built-in " + path, e);
    }
  }

  private static List<Path> children(Path folder) {
    if (folder == null || !Files.isDirectory(folder)) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path entry : entries) {
        out.add(entry);
      }
    } catch (IOException e) {
      return List.of();
    }
    out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
    return out.size() > MAX_PER_FOLDER ? out.subList(0, MAX_PER_FOLDER) : out;
  }

  static String read(Path file) {
    try {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      return text.length() > MAX_FILE_CHARS ? text.substring(0, MAX_FILE_CHARS) + "\n[cut at "
          + String.format(Locale.ROOT, "%,d", MAX_FILE_CHARS) + " characters]" : text;
    } catch (Exception e) {
      return "";
    }
  }

  // The description from the frontmatter, else the first line of the body that is not a heading.
  private static String describe(Frontmatter parsed) {
    String description = parsed.get("description");
    if (!description.isBlank()) {
      return description;
    }
    return parsed.body().lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst()
        .orElse("");
  }

  private static String oneLine(String text) {
    String flat = text.replaceAll("\\s+", " ").strip();
    return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
  }
}
