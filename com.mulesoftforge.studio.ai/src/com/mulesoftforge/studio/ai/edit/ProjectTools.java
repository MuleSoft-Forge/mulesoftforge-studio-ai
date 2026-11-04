package com.mulesoftforge.studio.ai.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.connector.ConnectorCatalog;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.dataweave.ScriptChecks;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

// Tools the model uses on the project. Reads are immediate; writes only stage proposals in the ChangeSet,
// and offer_changes only records what to show the user.
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
          .name("connector_reference")
          .description("Look up the connectors and modules in this project's pom.xml, for the exact versions it"
              + " uses: their operations, sources, configs and connections, each component's parameters (and"
              + " whether each is an attribute or a child element), and the error types. Use it before writing"
              + " or changing a connector element instead of guessing names.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("component", "\"\" to list the connectors, a prefix such as \"db\" for one"
                  + " connector's components, or \"db:select\" for one component's parameters and errors")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("check_project")
          .description("Run static checks on the Mule XML, pom.xml and properties files, including your pending"
              + " proposals: well-formed XML, unbound prefixes, xsi:schemaLocation, a pom.xml dependency for each"
              + " namespace, config-ref and flow-ref targets, undefined ${properties}, error types, error-handler"
              + " placement, and connector elements against the connector versions in pom.xml."
              + " Use it to diagnose a project that will not deploy.")
          .parameters(JsonObjectSchema.builder().build())
          .build(),
      ToolSpecification.builder()
          .name("edit_file")
          .description("Propose changes to an existing file: put every change to one file in one call, as a"
              + " list of edits applied in order, each seeing the ones before it. Each old_text is copied from"
              + " the file with a few surrounding lines so it occurs once; indentation differences are"
              + " tolerated. Never write placeholders such as <!-- ... --> or \"rest unchanged\" in new_text:"
              + " the edit replaces exactly old_text. Agent mode only.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("path", "Project-relative file path")
              .addProperty("edits", JsonArraySchema.builder()
                  .description("The changes to this file, in order")
                  .items(JsonObjectSchema.builder()
                      .addStringProperty("old_text", "Current text to replace, copied from the file")
                      .addStringProperty("new_text", "Replacement text")
                      .addIntegerProperty("expected_replacements", "How many times old_text occurs, all"
                          + " replaced; default 1. Use it for a rename across the file")
                      .required("old_text", "new_text")
                      .build())
                  .build())
              .required("path", "edits")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("write_file")
          .description("Propose the complete content of a new file. Rewrite an existing file this way only when"
              + " it is short (under about 200 lines): a response is cut off at the output limit, and a cut-off"
              + " call is lost. Agent mode only.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("path", "Project-relative file path")
              .addStringProperty("content", "Complete file content")
              .required("path", "content")
              .build())
          .build(),
      ToolSpecification.builder()
          .name("offer_changes")
          .description("Offer the user changes you recommend but have not made. They appear as a checklist under"
              + " your answer, and the user ticks which ones to have you make. Call it once, at the end, with one"
              + " item per independent change, instead of asking in your reply whether to make them. Only when"
              + " the user asked for a review, problems, suggestions or a change; not for questions and"
              + " explanations.")
          .parameters(JsonObjectSchema.builder()
              .addProperty("items", JsonArraySchema.builder()
                  .description("The changes, most important first")
                  .items(JsonObjectSchema.builder()
                      .addStringProperty("title", "Short imperative title, e.g. Move the OAuth credentials to"
                          + " secure properties")
                      .addProperty("files", JsonArraySchema.builder()
                          .description("Project-relative files the change touches")
                          .items(new JsonStringSchema())
                          .build())
                      .required("title")
                      .build())
                  .build())
              .required("items")
              .build())
          .build());

  private final ProjectFiles files;
  private final ChangeSet changes;
  private final boolean canEdit;
  private final SecretMask secrets;
  private final ConnectorCatalog connectors;
  private final List<ChangeOffer> offers = new ArrayList<>();
  private final Map<String, List<Problem>> dataWeaveChecked = new HashMap<>();
  private List<Problem> baseline;
  private DataWeaveCli dataWeave;

  public ProjectTools(ProjectFiles files, ChangeSet changes) {
    this(files, changes, true);
  }

  public ProjectTools(ProjectFiles files, ChangeSet changes, boolean canEdit) {
    this(files, changes, canEdit, new SecretMask());
  }

  // secrets hides values in what the model reads and restores them in what it writes.
  public ProjectTools(ProjectFiles files, ChangeSet changes, boolean canEdit, SecretMask secrets) {
    this.files = files;
    this.changes = changes;
    this.canEdit = canEdit;
    this.secrets = secrets;
    this.connectors = ConnectorCatalog.of(files);
  }

  // With the DataWeave CLI, proposals' DataWeave is compiled too; null leaves it to the static checks.
  public void useDataWeave(DataWeaveCli cli) {
    dataWeave = cli;
  }

  // The same in every mode, so switching modes keeps the prompt cache; execute refuses edits where they are not
  // allowed.
  public List<ToolSpecification> specifications() {
    return SPECIFICATIONS;
  }

  public static boolean isEdit(String tool) {
    return tool.equals("edit_file") || tool.equals("write_file");
  }

  // What offer_changes recorded this turn; a later call replaces an earlier one.
  public List<ChangeOffer> offers() {
    return List.copyOf(offers);
  }

  // One-line description of a call for the progress display.
  public static String describe(ToolExecutionRequest request) {
    String target;
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank()
          ? "{}" : request.arguments());
      JsonNode value = args.has("path") ? args.get("path") : args.has("component") ? args.get("component")
          : args.get("directory");
      target = value == null || value.asText().isBlank() ? "project root" : value.asText();
    } catch (Exception e) {
      target = "?";
    }
    return switch (request.name()) {
      case "list_files" -> "Listing " + target;
      case "read_file" -> "Reading " + target;
      case "check_project" -> "Checking the project";
      case "connector_reference" -> "Looking up " + (target.equals("project root") ? "the project's connectors" : target);
      case "edit_file" -> "Drafting an edit to " + target;
      case "write_file" -> "Drafting " + target;
      case "offer_changes" -> "Listing the changes it can make";
      default -> request.name() + " " + target;
    };
  }

  // Never throws: failures go back to the model as text so it can correct itself.
  public String execute(ToolExecutionRequest request) {
    if (!canEdit && isEdit(request.name())) {
      return ChatMode.refused("This mode cannot change files; describe the change instead. Agent mode can make it.");
    }
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank()
          ? "{}" : request.arguments());
      return switch (request.name()) {
        case "list_files" -> list(args);
        case "read_file" -> read(args);
        case "check_project" -> checkProject();
        case "connector_reference" -> connectorReference(args);
        case "edit_file" -> edit(args);
        case "write_file" -> write(args);
        case "offer_changes" -> offer(args);
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
    if (SecretMask.isBlocked(path)) {
      return SecretMask.withheld(path);
    }
    String content = current(path);
    if (content == null) {
      return "ERROR: file not found: " + path;
    }
    String shown = secrets.mask(path, content).text();
    if (shown.length() > READ_LIMIT) {
      return shown.substring(0, READ_LIMIT) + "\n(truncated at " + READ_LIMIT + " characters)";
    }
    return shown;
  }

  // edits is a list of {old_text, new_text, expected_replacements}; a single old_text and new_text at the top
  // level is one edit. The edits that apply are kept even when others fail.
  private String edit(JsonNode args) throws Exception {
    String path = ProjectPaths.requireWritable(text(args, "path", true));
    String content = current(path);
    if (content == null) {
      return "ERROR: file not found: " + path + ". Use write_file to create it.";
    }
    List<JsonNode> edits = new ArrayList<>();
    JsonNode list = args.get("edits");
    if (list != null && list.isArray()) {
      list.forEach(edits::add);
    } else if (args.has("old_text")) {
      edits.add(args);
    }
    if (edits.isEmpty()) {
      return "ERROR: edits must be a non-empty list of {old_text, new_text}.";
    }
    String updated = content;
    List<String> results = new ArrayList<>();
    int applied = 0;
    for (int i = 0; i < edits.size(); i++) {
      String label = edits.size() == 1 ? "" : "Edit " + (i + 1) + ": ";
      try {
        String outcome = applyEdit(path, updated, edits.get(i));
        if (outcome.startsWith("ERROR: ")) {
          results.add(label + outcome);
          continue;
        }
        updated = outcome.substring(outcome.indexOf('\n') + 1);
        results.add(label + outcome.substring(0, outcome.indexOf('\n')));
        applied++;
      } catch (IllegalArgumentException e) {
        results.add(label + "ERROR: " + e.getMessage());
      }
    }
    if (applied == 0) {
      return (edits.size() == 1 ? "" : "ERROR: none of the " + edits.size() + " edits applied.\n")
          + String.join("\n", results);
    }
    String staged = stage(path, updated);
    if (edits.size() == 1) {
      return staged;
    }
    return String.join("\n", results) + "\n" + applied + " of " + edits.size() + " edits applied"
        + (applied < edits.size() ? "; do not resend the applied ones, send only corrected versions of the failed"
            + " ones." : ".") + "\n" + staged;
  }

  // "applied …" then a newline and the updated file, or an ERROR line.
  private String applyEdit(String path, String content, JsonNode edit) {
    String oldText = restored(text(edit, "old_text", true));
    String newText = restored(text(edit, "new_text", true));
    int expected = edit.path("expected_replacements").asInt(1);
    if (oldText.isEmpty()) {
      return "ERROR: old_text is empty. To add text, include the lines it goes next to in old_text and new_text.";
    }
    if (expected < 1) {
      return "ERROR: expected_replacements must be 1 or more.";
    }
    String placeholder = TextMatch.placeholder(oldText, newText);
    if (placeholder != null) {
      return "ERROR: new_text contains the placeholder \"" + placeholder + "\". The edit replaces exactly"
          + " old_text, so the code it stands for would be deleted. Write the real lines, or make old_text"
          + " smaller.";
    }
    TextMatch.Result match = TextMatch.replace(content, oldText, newText, expected);
    if (match.error() != null) {
      return "ERROR: " + match.error();
    }
    if (match.updated() == null) {
      StringBuilder error = new StringBuilder("ERROR: old_text not found in " + path + ".");
      if (!newText.isEmpty() && TextMatch.count(content, newText) > 0) {
        error.append(" new_text is already in the file, so this edit may already be made.");
      }
      String closest = TextMatch.closest(content, oldText);
      error.append(closest == null ? " Read the file and copy the text."
          : " The most similar lines are:\n" + closest + "Copy old_text from these lines.");
      return error.toString();
    }
    String how = match.pass().note == null ? "" : " (" + match.pass().note + ")";
    return "applied" + (match.count() > 1 ? " " + match.count() + " times" : "") + how + "\n" + match.updated();
  }

  private String write(JsonNode args) throws Exception {
    String path = ProjectPaths.requireWritable(text(args, "path", true));
    return stage(path, restored(text(args, "content", true)));
  }

  // The model's text with real values back in place of the placeholders it was shown.
  private String restored(String text) {
    String real = secrets.unmask(text);
    if (SecretMask.hasPlaceholder(real)) {
      throw new IllegalArgumentException("a «masked-N» placeholder in your text was not one you were shown."
          + " Read the file again and copy the placeholders exactly.");
    }
    return real;
  }

  private String offer(JsonNode args) {
    JsonNode items = args.get("items");
    if (items == null || !items.isArray() || items.isEmpty()) {
      return "ERROR: items must be a non-empty list of {title, files}.";
    }
    List<ChangeOffer> parsed = new ArrayList<>();
    for (JsonNode item : items) {
      String title = item.isTextual() ? item.asText() : text(item, "title", false);
      if (title == null || title.isBlank()) {
        return "ERROR: every item needs a title.";
      }
      List<String> paths = new ArrayList<>();
      JsonNode list = item.get("files");
      if (list != null && list.isArray()) {
        list.forEach(p -> paths.add(p.asText()));
      }
      parsed.add(new ChangeOffer(title, paths));
    }
    if (parsed.size() > ChangeOffer.MAX_ITEMS) {
      return "ERROR: offer at most " + ChangeOffer.MAX_ITEMS + " changes; group the small ones.";
    }
    offers.clear();
    offers.addAll(parsed);
    return "Offered " + parsed.size() + (parsed.size() == 1 ? " change" : " changes") + ". The user sees them as"
        + " a checklist below your answer. Finish now; do not list them again or ask whether to make them.";
  }

  private String stage(String path, String content) throws Exception {
    String before = changes.contains(path) ? null : files.read(path);
    boolean isNew = !changes.contains(path) && before == null;
    changes.stage(path, before, content);
    String result = "Proposed " + (isNew ? "new file " : "change to ") + path
        + ". It is pending the user's review and is not written yet.";
    boolean dataWeaveWatched = dataWeave != null && ScriptChecks.watches(path);
    if (!MuleChecks.watches(path) && !dataWeaveWatched) {
      return result;
    }
    List<Problem> problems = problemsIntroduced();
    if (problems.isEmpty()) {
      return result + " Checks passed.";
    }
    return result + "\n\nCHECKS: with your proposals applied, the project has " + problems.size()
        + (problems.size() == 1 ? " problem" : " problems") + " that it did not have before:\n"
        + secrets.mask(null, MuleChecks.describe(problems)).text()
        + "Fix them with another edit before you finish, or tell the user why they are fine.";
  }

  // Problems the pending proposals add to the project. Empty when the checks cannot run.
  public List<Problem> problemsIntroduced() {
    List<Problem> problems = new ArrayList<>();
    try {
      if (baseline == null) {
        baseline = MuleChecks.check(MuleChecks.snapshot(files, null), connectors);
      }
      problems.addAll(MuleChecks.introduced(baseline, MuleChecks.check(MuleChecks.snapshot(files, changes),
          connectors)));
    } catch (Exception e) {
      // The static checks could not run; the DataWeave ones still may.
    }
    if (dataWeave != null) {
      for (FileChange change : changes.all()) {
        if (ScriptChecks.watches(change.path())) {
          problems.addAll(dataWeaveProblems(change));
        }
      }
    }
    return problems;
  }

  // Cached by content, so a file is compiled once per version however often the problems are asked for.
  private List<Problem> dataWeaveProblems(FileChange change) {
    String key = change.path() + "\n" + Objects.hashCode(change.before()) + "\n" + change.after().hashCode();
    return dataWeaveChecked.computeIfAbsent(key, k -> {
      try {
        return ScriptChecks.introduced(dataWeave, change.path(), change.before(), change.after(),
            ScriptChecks.TIMEOUT, () -> false);
      } catch (Exception e) {
        return List.of();
      }
    });
  }

  private String connectorReference(JsonNode args) throws Exception {
    if (connectors == null) {
      return "Connector models are not available: Studio has not generated any in this workspace yet (it does"
          + " when it resolves a project's dependencies). Do not guess operations or parameters; say you could"
          + " not check them.";
    }
    return connectors.reference(current("pom.xml"), text(args, "component", false));
  }

  private String checkProject() throws Exception {
    List<Problem> problems = MuleChecks.check(MuleChecks.snapshot(files, changes), connectors);
    String scope = changes.isEmpty() ? "" : ", including your pending proposals";
    if (problems.isEmpty()) {
      return "No problems found" + scope + ". These are static checks of the files; they do not run the app.";
    }
    return problems.size() + (problems.size() == 1 ? " problem" : " problems") + " found" + scope + ":\n"
        + secrets.mask(null, MuleChecks.describe(problems)).text()
        + "These are static checks of the files; they do not run the app.";
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
