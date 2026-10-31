package com.mulesoftforge.studio.ai.dataweave;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectPaths;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// The model's DataWeave tools, backed by the DataWeave CLI: compile a script, or run it on sample input and compare
// the result with the expected output. Runs are sandboxed (no files or network). Sample files come from the project,
// except files that only hold secrets; what comes back is masked like everything else sent to the model.
public final class DataWeaveTools {
  public static final String VALIDATE = "validate_dataweave";
  public static final String RUN = "run_dataweave";
  private static final int MAX_OUTPUT = 8_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final List<ToolSpecification> SPECIFICATIONS = List.of(
      ToolSpecification.builder()
          .name(RUN)
          .description("Run a DataWeave 2.0 script on sample input with MuleSoft's DataWeave CLI and get the output,"
              + " or the error with its line. Use it to prove a mapping before you propose or present it: give the"
              + " inputs the script reads (payload, and vars or attributes when it uses them) and, when you know"
              + " it, the expected output, which is compared with the result. Runs cannot read files or the"
              + " network; Mule-only p() and lookup() return null.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("script", "The whole DataWeave script")
              .addProperty("inputs", JsonArraySchema.builder()
                  .description("Inputs by name. Give content, or file for a project file such as"
                      + " src/test/resources/sample.json")
                  .items(JsonObjectSchema.builder()
                      .addStringProperty("name", "payload, vars, attributes or another input name")
                      .addStringProperty("mime_type", "e.g. application/json, application/xml, application/csv")
                      .addStringProperty("content", "The sample data")
                      .addStringProperty("file", "Project-relative path of a sample file")
                      .required("name")
                      .build())
                  .build())
              .addStringProperty("expected", "Optional: the output the script should produce")
              .required("script")
              .build())
          .build(),
      ToolSpecification.builder()
          .name(VALIDATE)
          .description("Check that a DataWeave 2.0 script compiles, with Mule's bindings (payload, vars, attributes,"
              + " p() and the rest) declared. Use it when there is no sample to run on.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("script", "The whole DataWeave script or a Mule #[...] expression body")
              .required("script")
              .build())
          .build());

  private final DataWeaveCli cli;
  private final ProjectFiles files;
  private final SecretMask secrets;
  private final Duration timeout;

  // files is null when no project is open: inputs must then be given as content.
  public DataWeaveTools(DataWeaveCli cli, ProjectFiles files, SecretMask secrets) {
    this(cli, files, secrets, DataWeaveCli.TIMEOUT);
  }

  public DataWeaveTools(DataWeaveCli cli, ProjectFiles files, SecretMask secrets, Duration timeout) {
    this.cli = cli;
    this.files = files;
    this.secrets = secrets;
    this.timeout = timeout;
  }

  public static boolean handles(String tool) {
    return RUN.equals(tool) || VALIDATE.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    return RUN.equals(request.name()) ? "Running DataWeave on sample input" : "Checking DataWeave compiles";
  }

  // Never throws: failures go back to the model as text.
  public String execute(ToolExecutionRequest request) {
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank() ? "{}"
          : request.arguments());
      String script = args.path("script").asText("");
      if (script.isBlank()) {
        return "ERROR: script is empty.";
      }
      return RUN.equals(request.name()) ? run(script, args) : VALIDATE.equals(request.name()) ? validate(script)
          : "ERROR: unknown tool " + request.name();
    } catch (TimeoutException e) {
      return "ERROR: the script did not finish within " + timeout.toSeconds() + " s. Look for a loop over a huge"
          + " range, or try a smaller sample.";
    } catch (Exception e) {
      return "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private String validate(String script) throws Exception {
    boolean bare = !script.contains("---") && !script.stripLeading().startsWith("%dw");
    String text = bare ? "%dw 2.0\noutput application/java\n---\n" + script : script;
    DataWeaveCli.Validation result = cli.validate(text, DataWeaveCli.MULE_BINDINGS, timeout, () -> false);
    List<DataWeaveCli.Problem> problems = ScriptChecks.reportable(text, result.problems());
    if (result.ok() || problems.isEmpty()) {
      return "VALID: the script compiles.";
    }
    StringBuilder out = new StringBuilder("PROBLEMS:\n");
    for (DataWeaveCli.Problem problem : problems) {
      int line = bare ? Math.max(1, problem.line() - 3) : problem.line();
      out.append("- ").append(at(line, problem.column())).append(problem.message()).append('\n');
    }
    return mask(out.toString().strip());
  }

  private String run(String script, JsonNode args) throws Exception {
    List<DataWeaveCli.Input> inputs = new ArrayList<>();
    for (JsonNode input : args.path("inputs")) {
      String name = input.path("name").asText("").strip();
      if (!name.matches("[A-Za-z_][\\w]*")) {
        return "ERROR: input name \"" + name + "\" is not a DataWeave identifier.";
      }
      String content = input.hasNonNull("content") ? input.get("content").asText() : null;
      String file = input.hasNonNull("file") ? input.get("file").asText() : null;
      if (content == null && file != null) {
        if (files == null) {
          return "ERROR: no project is open, so give the sample as content.";
        }
        String path = ProjectPaths.normalize(file);
        if (SecretMask.isBlocked(path)) {
          return "ERROR: " + SecretMask.withheld(path);
        }
        content = files.read(path);
        if (content == null) {
          return "ERROR: no file " + path + " in the project.";
        }
      }
      if (content == null) {
        return "ERROR: input " + name + " needs content or file.";
      }
      String mimeType = input.path("mime_type").asText("");
      inputs.add(new DataWeaveCli.Input(name, mimeType.isBlank() ? guess(content) : mimeType, content));
    }
    DataWeaveCli.Run result = cli.run(script, inputs, timeout, () -> false);
    if (!result.ok()) {
      StringBuilder out = new StringBuilder("DATAWEAVE ERROR (" + result.millis() + " ms):\n");
      for (DataWeaveCli.Problem problem : result.problems()) {
        out.append("- ").append(at(problem.line(), problem.column())).append(problem.message()).append('\n');
      }
      return mask(cap(out.toString().strip()));
    }
    StringBuilder out = new StringBuilder("OUTPUT (" + result.millis() + " ms):\n").append(cap(result.output()));
    if (args.hasNonNull("expected")) {
      out.append("\n\n").append(compare(args.get("expected").asText(), result.output()));
    }
    return mask(out.toString());
  }

  // MATCHES, or where the output first differs from what was expected.
  static String compare(String expected, String actual) {
    String want = expected.strip();
    String got = actual.strip();
    if (want.equals(got)) {
      return "MATCHES the expected output.";
    }
    try {
      JsonNode wantJson = JSON.readTree(want);
      JsonNode gotJson = JSON.readTree(got);
      String difference = difference("$", wantJson, gotJson);
      return difference == null ? "MATCHES the expected output (same JSON, different spacing)."
          : "DIFFERS from the expected output at " + difference;
    } catch (Exception notJson) {
      String a = want.replaceAll(">\\s+<", "><").replaceAll("\\s+", " ");
      String b = got.replaceAll(">\\s+<", "><").replaceAll("\\s+", " ");
      if (a.equals(b)) {
        return "MATCHES the expected output (different spacing).";
      }
      int at = 0;
      while (at < a.length() && at < b.length() && a.charAt(at) == b.charAt(at)) {
        at++;
      }
      return "DIFFERS from the expected output from character " + at + ": expected \"" + excerpt(a, at)
          + "\", got \"" + excerpt(b, at) + "\".";
    }
  }

  private static String difference(String path, JsonNode want, JsonNode got) {
    if (want.isNumber() && got.isNumber()) {
      return want.decimalValue().compareTo(got.decimalValue()) == 0 ? null : path + ": expected " + want + ", got "
          + got + ".";
    }
    if (want.getNodeType() != got.getNodeType()) {
      return path + ": expected " + describe(want) + ", got " + describe(got) + ".";
    }
    if (want.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = want.fields(); it.hasNext();) {
        Map.Entry<String, JsonNode> entry = it.next();
        if (!got.has(entry.getKey())) {
          return path + "." + entry.getKey() + ": missing from the output.";
        }
        String inner = difference(path + "." + entry.getKey(), entry.getValue(), got.get(entry.getKey()));
        if (inner != null) {
          return inner;
        }
      }
      for (Iterator<String> it = got.fieldNames(); it.hasNext();) {
        String key = it.next();
        if (!want.has(key)) {
          return path + "." + key + ": not expected, but in the output.";
        }
      }
      return null;
    }
    if (want.isArray()) {
      if (want.size() != got.size()) {
        return path + ": expected " + want.size() + " items, got " + got.size() + ".";
      }
      for (int i = 0; i < want.size(); i++) {
        String inner = difference(path + "[" + i + "]", want.get(i), got.get(i));
        if (inner != null) {
          return inner;
        }
      }
      return null;
    }
    return want.equals(got) ? null : path + ": expected " + want + ", got " + got + ".";
  }

  private static String describe(JsonNode node) {
    return switch (node.getNodeType()) {
      case OBJECT -> "an object";
      case ARRAY -> "an array";
      case STRING -> "the string " + node;
      case NUMBER -> "the number " + node;
      case BOOLEAN -> node.asText();
      case NULL -> "null";
      case BINARY, MISSING, POJO -> node.getNodeType().name().toLowerCase(Locale.ROOT);
    };
  }

  private static String excerpt(String text, int at) {
    int end = Math.min(text.length(), at + 40);
    return at >= text.length() ? "(end)" : text.substring(at, end);
  }

  // JSON, XML or CSV, from how the sample looks.
  static String guess(String content) {
    String start = content.stripLeading();
    if (start.startsWith("{") || start.startsWith("[")) {
      return "application/json";
    }
    if (start.startsWith("<")) {
      return "application/xml";
    }
    return start.contains(",") && start.contains("\n") ? "application/csv" : "text/plain";
  }

  private static String at(int line, int column) {
    return line > 0 ? "line " + line + (column > 0 ? ", column " + column : "") + ": " : "";
  }

  private String mask(String text) {
    return secrets.mask(null, text).text();
  }

  private static String cap(String text) {
    return text.length() <= MAX_OUTPUT ? text : text.substring(0, MAX_OUTPUT) + " … (cut, "
        + (text.length() - MAX_OUTPUT) + " more characters)";
  }
}
