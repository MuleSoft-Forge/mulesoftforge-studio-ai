package com.mulesoftforge.studio.ai.extend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.extend.Extension.Kind;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// use_skill: the full text of a skill from the SKILLS catalog, or one file from its folder. Read-only, so every
// mode offers it. Files outside the skill's folder are refused, and what is returned is masked like any file.
public final class SkillTools {
  public static final String USE = "use_skill";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_LISTED = 50;

  public static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
      .name(USE)
      .description("Load a skill listed under SKILLS in the system prompt: its step-by-step instructions, and the"
          + " reference files in its folder. Call it before you start a task that matches a skill's description,"
          + " then follow what it returns. With file, returns one of the skill's reference files instead; read one"
          + " only when the step you are on needs it.")
      .parameters(JsonObjectSchema.builder()
          .addStringProperty("name", "The skill's name, exactly as listed under SKILLS")
          .addStringProperty("file", "Optional. A reference file's path inside the skill's folder, as listed when"
              + " the skill was loaded, for example references/error-handling.md")
          .required("name")
          .build())
      .build();

  private final Extensions extensions;
  private final SecretMask secrets;

  public SkillTools(Extensions extensions, SecretMask secrets) {
    this.extensions = extensions;
    this.secrets = secrets;
  }

  public static boolean handles(String tool) {
    return USE.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    JsonNode args = arguments(request);
    String name = args.path("name").asText("");
    String file = args.path("file").asText("");
    return file.isBlank() ? "Loading the skill " + name : "Reading " + file + " from the skill " + name;
  }

  public String execute(ToolExecutionRequest request) {
    JsonNode args = arguments(request);
    String name = args.path("name").asText("").strip();
    String file = args.path("file").asText("").strip();
    Extension skill = extensions.find(Kind.SKILL, name);
    if (skill == null) {
      List<String> names = extensions.enabled(Kind.SKILL).stream().map(Extension::name).toList();
      return "ERROR: there is no skill named \"" + name + "\". "
          + (names.isEmpty() ? "No skills are available." : "Available: " + String.join(", ", names) + ".");
    }
    return file.isEmpty() ? skillText(skill) : referenceFile(skill, file);
  }

  private String skillText(Extension skill) {
    StringBuilder out = new StringBuilder("SKILL ").append(skill.name()).append(" (")
        .append(skill.source().label).append(")\n");
    if (!skill.description().isBlank()) {
      out.append(skill.description().strip()).append("\n");
    }
    out.append('\n').append(secrets.mask(null, skill.body().strip()).text()).append('\n');
    List<String> files = referenceFiles(skill);
    if (!files.isEmpty()) {
      out.append("\nReference files in this skill (read one with use_skill and file):\n");
      files.forEach(f -> out.append("- ").append(f).append('\n'));
    }
    return out.toString();
  }

  private String referenceFile(Extension skill, String file) {
    Path folder = skill.folder();
    if (folder == null) {
      return "ERROR: the skill " + skill.name() + " has no reference files.";
    }
    try {
      Path root = folder.toRealPath();
      Path target = root.resolve(file).normalize();
      if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root)) {
        return "ERROR: " + file + " is not a file in the skill " + skill.name() + ". Use a path from the list"
            + " use_skill returned for it.";
      }
      String relative = root.relativize(target).toString().replace('\\', '/');
      String lower = relative.toLowerCase(Locale.ROOT);
      // Notes are free text: masked like Preferences instructions, where key=value lines count only without a path.
      String maskAs = lower.endsWith(".md") || lower.endsWith(".txt") ? null : relative;
      return "FILE " + relative + " (skill " + skill.name() + ")\n"
          + secrets.mask(maskAs, Extensions.read(target)).text();
    } catch (IOException e) {
      return "ERROR: could not read " + file + " from the skill " + skill.name() + ": " + e.getMessage();
    }
  }

  private static List<String> referenceFiles(Extension skill) {
    Path folder = skill.folder();
    if (folder == null || !Files.isDirectory(folder)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(folder, 3)) {
      walk.filter(Files::isRegularFile)
          .map(p -> folder.relativize(p).toString().replace('\\', '/'))
          .filter(p -> !p.equals(Extensions.SKILL_FILE) && !p.startsWith(".") && !p.contains("/."))
          .sorted()
          .limit(MAX_LISTED)
          .forEach(out::add);
    } catch (IOException e) {
      return List.of();
    }
    return out;
  }

  private static JsonNode arguments(ToolExecutionRequest request) {
    try {
      return JSON.readTree(request.arguments() == null || request.arguments().isBlank() ? "{}" : request.arguments());
    } catch (Exception e) {
      return JSON.createObjectNode();
    }
  }
}
