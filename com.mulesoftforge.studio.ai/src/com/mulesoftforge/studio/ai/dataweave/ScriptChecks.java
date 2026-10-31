package com.mulesoftforge.studio.ai.dataweave;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.check.Problem;

// DataWeave inside proposals, compiled with the DataWeave CLI: Transform Message scripts (ee:set-payload,
// ee:set-variable, ee:set-attributes), whole #[...] values and .dwl files. Only scripts a proposal changed are
// checked, so problems that were already there are not blamed on it and a file with many scripts stays quick.
// Names the CLI cannot know are not reported: the project's own modules, and runtime modules such as Soap::.
public final class ScriptChecks {
  public static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int MAX_SCRIPTS = 40;
  private static final String EXPRESSION_HEADER = "%dw 2.0\noutput application/java\n---\n";
  private static final Pattern TRANSFORM_PART = Pattern.compile(
      "<ee:(set-payload|set-variable|set-attributes)\\b([^>]*)>(.*?)</ee:\\1>", Pattern.DOTALL);
  private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
  private static final Pattern ATTRIBUTE_EXPRESSION = Pattern.compile("=\\s*\"(#\\[[^\"]*])\"|=\\s*'(#\\[[^']*])'");
  private static final Pattern TEXT_EXPRESSION = Pattern.compile(">\\s*(#\\[[^<]*])\\s*<");
  private static final Pattern HEADER = Pattern.compile("^\\s*%dw\\b|^\\s*(?:output|import|var|fun|ns|type|input)\\b[\\s\\S]*?^\\s*---",
      Pattern.MULTILINE);
  private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\b.*?\\bfrom\\s+([\\w:]+)|^\\s*import\\s+([\\w:]+)\\s*$");
  private static final Pattern QUALIFIED = Pattern.compile("Unable to resolve reference of: `([A-Za-z_][\\w]*)::");
  private static final Pattern MODULE = Pattern.compile("Unable to resolve module with identifier ([\\w:]+)");
  private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|quot|apos|lt|gt|amp);");

  // line is where line 1 of the script is in the file; header is how many lines were added in front of it.
  public record Script(int line, String text, int header) {}

  private ScriptChecks() {}

  public static boolean watches(String path) {
    String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
    return lower.endsWith(".dwl") || (lower.endsWith(".xml")
        && (lower.startsWith("src/main/mule/") || lower.startsWith("src/test/munit/")));
  }

  public static List<Script> scripts(String path, String content) {
    List<Script> out = new ArrayList<>();
    if (content == null) {
      return out;
    }
    if (path.toLowerCase(Locale.ROOT).endsWith(".dwl")) {
      out.add(new Script(1, content.contains("---") ? content : content + "\n---\nnull", 0));
      return out;
    }
    List<int[]> skipped = skippedRanges(content);
    Matcher part = TRANSFORM_PART.matcher(content);
    while (part.find()) {
      if (inside(skipped, part.start()) || part.group(2).contains("resource=")) {
        continue;
      }
      Matcher cdata = CDATA.matcher(part.group(3));
      String text;
      int start;
      if (cdata.find()) {
        text = cdata.group(1);
        start = part.start(3) + cdata.start(1);
      } else {
        text = unescape(part.group(3));
        start = part.start(3);
      }
      if (!text.isBlank()) {
        out.add(new Script(line(content, start), text, 0));
      }
    }
    expressions(content, ATTRIBUTE_EXPRESSION, skipped, out);
    expressions(content, TEXT_EXPRESSION, skipped, out);
    return out;
  }

  private static void expressions(String content, Pattern pattern, List<int[]> skipped, List<Script> out) {
    Matcher m = pattern.matcher(content);
    while (m.find()) {
      int group = m.group(1) != null ? 1 : 2;
      if (inside(skipped, m.start(group))) {
        continue;
      }
      String value = unescape(m.group(group));
      String body = value.substring(2, value.length() - 1);
      if (body.isBlank()) {
        continue;
      }
      boolean hasHeader = HEADER.matcher(body).find();
      out.add(new Script(line(content, m.start(group)), hasHeader ? body : EXPRESSION_HEADER + body,
          hasHeader ? 0 : 3));
    }
  }

  // Problems in after's scripts that before did not have; before is null for a new file.
  public static List<Problem> introduced(DataWeaveCli cli, String path, String before, String after, Duration timeout,
      BooleanSupplier cancelled) throws Exception {
    Set<String> old = new HashSet<>();
    for (Script script : scripts(path, before)) {
      old.add(script.text());
    }
    List<Problem> out = new ArrayList<>();
    int checked = 0;
    for (Script script : scripts(path, after)) {
      if (old.contains(script.text()) || checked == MAX_SCRIPTS) {
        continue;
      }
      checked++;
      DataWeaveCli.Validation result = cli.validate(script.text(), DataWeaveCli.MULE_BINDINGS, timeout, cancelled);
      if (result.ok()) {
        continue;
      }
      for (DataWeaveCli.Problem problem : reportable(script.text(), result.problems())) {
        int scriptLine = Math.max(1, problem.line() - script.header());
        out.add(new Problem(path, script.line() + scriptLine - 1, "DataWeave: " + problem.message()
            + (problem.column() > 0 ? " (column " + problem.column() + ")" : "")));
      }
    }
    return out;
  }

  // Leaves out what the CLI cannot know about outside the project and Mule runtime.
  static List<DataWeaveCli.Problem> reportable(String script, List<DataWeaveCli.Problem> problems) {
    boolean projectModules = false;
    Matcher imports = IMPORT.matcher(script);
    while (imports.find()) {
      String module = imports.group(1) != null ? imports.group(1) : imports.group(2);
      if (module != null && !module.startsWith("dw::")) {
        projectModules = true;
      }
    }
    List<DataWeaveCli.Problem> out = new ArrayList<>();
    for (DataWeaveCli.Problem problem : problems) {
      String message = problem.message();
      Matcher module = MODULE.matcher(message);
      if (module.find() && !module.group(1).startsWith("dw::")) {
        continue;
      }
      Matcher qualified = QUALIFIED.matcher(message);
      if (qualified.find() && !qualified.group(1).equals("dw")) {
        continue;
      }
      if (projectModules && message.startsWith("Unable to resolve reference of")) {
        continue;
      }
      out.add(problem);
    }
    return out;
  }

  // Comments and the CDATA of Transform Message parts, where #[ is not an expression.
  private static List<int[]> skippedRanges(String content) {
    List<int[]> ranges = new ArrayList<>();
    for (int at = content.indexOf("<!--"); at >= 0; at = content.indexOf("<!--", at + 4)) {
      int end = content.indexOf("-->", at);
      ranges.add(new int[] {at, end < 0 ? content.length() : end + 3});
      if (end < 0) {
        break;
      }
    }
    Matcher cdata = CDATA.matcher(content);
    while (cdata.find()) {
      ranges.add(new int[] {cdata.start(), cdata.end()});
    }
    return ranges;
  }

  private static boolean inside(List<int[]> ranges, int at) {
    for (int[] range : ranges) {
      if (at >= range[0] && at < range[1]) {
        return true;
      }
    }
    return false;
  }

  static String unescape(String text) {
    Matcher m = ENTITY.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String entity = m.group(1);
      String replacement = switch (entity) {
        case "quot" -> "\"";
        case "apos" -> "'";
        case "lt" -> "<";
        case "gt" -> ">";
        case "amp" -> "&";
        default -> String.valueOf((char) (entity.startsWith("#x") ? Integer.parseInt(entity.substring(2), 16)
            : Integer.parseInt(entity.substring(1))));
      };
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static int line(String text, int index) {
    int line = 1;
    for (int i = 0; i < index && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
