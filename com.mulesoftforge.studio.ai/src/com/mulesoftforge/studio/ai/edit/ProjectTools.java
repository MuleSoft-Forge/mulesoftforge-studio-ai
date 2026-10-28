package com.mulesoftforge.studio.ai.edit;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// Tools the model uses on the project. Reads are immediate; writes only stage proposals in the ChangeSet.
public final class ProjectTools {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int LIST_LIMIT = 300;
  private static final int READ_LIMIT = 60_000;

  public static final List<ToolSpecification> SPECIFICATIONS = List.of(
      ToolSpecification.builder()
          .name("list_files")
          .description("List project files under a directory, recursively. Use \"\" for the project root.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("directory", "Project-relative directory, e.g. src/main/mule")
              .required("directory")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("read_file")
          .description("Read a project file. Returns your pending proposal for it if you already changed it.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("path", "Project-relative file path, e.g. src/main/mule/api.xml")
              .required("path")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("edit_file")
          .description("Propose replacing one exact snippet in an existing file. old_text must occur exactly"
              + " once; include enough surrounding lines to make it unique. Prefer this for small changes.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("path", "Project-relative file path")
              .addStringProperty("old_text", "Exact current text to replace, including whitespace")
              .addStringProperty("new_text", "Replacement text")
              .required("path", "old_text", "new_text")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("write_file")
          .description("Propose the complete content of a new file, or a full rewrite of an existing one.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("path", "Project-relative file path")
              .addStringProperty("content", "Complete file content")
              .required("path", "content")
              .build())
          .build());

  public static final List<ToolSpecification> READ_ONLY_SPECIFICATIONS = SPECIFICATIONS.stream()
      .filter(spec -> !isEdit(spec.name()))
      .toList();

  private final ProjectFiles files;
  private final ChangeSet changes;
  private final boolean canEdit;

  public ProjectTools(ProjectFiles files, ChangeSet changes) {
    this(files, changes, true);
  }

  public ProjectTools(ProjectFiles files, ChangeSet changes, boolean canEdit) {
    this.files = files;
    this.changes = changes;
    this.canEdit = canEdit;
  }

  public List<ToolSpecification> specifications() {
    return canEdit ? SPECIFICATIONS : READ_ONLY_SPECIFICATIONS;
  }

  private static boolean isEdit(String tool) {
    return tool.equals("edit_file") || tool.equals("write_file");
  }

  // One-line description of a call for the progress display.
  public static String describe(ToolExecutionRequest request) {
    String target;
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank()
          ? "{}" : request.arguments());
      JsonNode value = args.has("path") ? args.get("path") : args.get("directory");
      target = value == null || value.asText().isBlank() ? "project root" : value.asText();
    } catch (Exception e) {
      target = "?";
    }
    return switch (request.name()) {
      case "list_files" -> "Listing " + target;
      case "read_file" -> "Reading " + target;
      case "edit_file" -> "Drafting an edit to " + target;
      case "write_file" -> "Drafting " + target;
      default -> request.name() + " " + target;
    };
  }

  // Never throws: failures go back to the model as text so it can correct itself.
  public String execute(ToolExecutionRequest request) {
    if (!canEdit && isEdit(request.name())) {
      return "ERROR: this mode cannot change files. Describe the change instead; the user can switch to Agent mode.";
    }
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank()
          ? "{}" : request.arguments());
      return switch (request.name()) {
        case "list_files" -> list(args);
        case "read_file" -> read(args);
        case "edit_file" -> edit(args);
        case "write_file" -> write(args);
        default -> "ERROR: unknown tool " + request.name();
      };
    } catch (Exception e) {
      return "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private String list(JsonNode args) throws Exception {
    String raw = text(args, "directory", false);
    String directory = raw == null || raw.isBlank() || ".".equals(raw.trim()) ? "" : ProjectPaths.normalize(raw);
    List<String> paths = files.list(directory, LIST_LIMIT + 1);
    if (paths.isEmpty()) {
      return "(no files)";
    }
    StringBuilder out = new StringBuilder();
    paths.stream().limit(LIST_LIMIT).forEach(p -> out.append(p).append('\n'));
    if (paths.size() > LIST_LIMIT) {
      out.append("(more files not shown; list a subdirectory)\n");
    }
    return out.toString();
  }

  private String read(JsonNode args) throws Exception {
    String path = ProjectPaths.normalize(text(args, "path", true));
    if (ProjectPaths.isSkipped(path)) {
      return "ERROR: " + path + " is in a build or tooling folder.";
    }
    String content = current(path);
    if (content == null) {
      return "ERROR: file not found: " + path;
    }
    if (content.length() > READ_LIMIT) {
      return content.substring(0, READ_LIMIT) + "\n(truncated at " + READ_LIMIT + " characters)";
    }
    return content;
  }

  private String edit(JsonNode args) throws Exception {
    String path = ProjectPaths.requireWritable(text(args, "path", true));
    String oldText = text(args, "old_text", true);
    String newText = text(args, "new_text", true);
    String content = current(path);
    if (content == null) {
      return "ERROR: file not found: " + path + ". Use write_file to create it.";
    }
    if (oldText.isEmpty()) {
      return "ERROR: old_text is empty.";
    }
    int first = content.indexOf(oldText);
    if (first < 0) {
      return "ERROR: old_text not found in " + path + ". Read the file and copy the text exactly.";
    }
    if (content.indexOf(oldText, first + 1) >= 0) {
      return "ERROR: old_text occurs more than once in " + path + ". Include more surrounding lines.";
    }
    String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
    return stage(path, updated);
  }

  private String write(JsonNode args) throws Exception {
    String path = ProjectPaths.requireWritable(text(args, "path", true));
    return stage(path, text(args, "content", true));
  }

  private String stage(String path, String content) throws Exception {
    String before = changes.contains(path) ? null : files.read(path);
    boolean isNew = !changes.contains(path) && before == null;
    changes.stage(path, before, content);
    return "Proposed " + (isNew ? "new file " : "change to ") + path
        + ". It is pending the user's review and is not written yet.";
  }

  private String current(String path) throws Exception {
    String staged = changes.staged(path);
    return staged != null ? staged : files.read(path);
  }

  private static String text(JsonNode args, String name, boolean required) {
    JsonNode value = args.get(name);
    if (value == null || value.isNull()) {
      if (required) {
        throw new IllegalArgumentException("missing argument \"" + name + "\"");
      }
      return null;
    }
    return value.asText();
  }
}
