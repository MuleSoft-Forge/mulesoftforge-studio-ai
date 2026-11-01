package com.mulesoftforge.studio.ai.llm;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Whether an answer's fenced code blocks hold project code (Mule XML, DataWeave, config, Java) the user could want
// as reviewable changes, rather than commands such as curl or example payloads.
public final class CodeBlocks {
  private static final Pattern FENCE = Pattern.compile("(?s)```([^\\n`]*)\\n(.*?)```");
  private static final Set<String> PROJECT = Set.of("xml", "mule", "dwl", "dw", "dataweave", "weave", "properties",
      "yaml", "yml", "java", "raml");

  private CodeBlocks() {}

  public static boolean hasProjectCode(String text) {
    if (text == null || !text.contains("```")) {
      return false;
    }
    Matcher m = FENCE.matcher(text);
    while (m.find()) {
      String language = m.group(1).strip().toLowerCase(Locale.ROOT);
      String body = m.group(2).stripLeading();
      if (PROJECT.contains(language)) {
        return true;
      }
      if (language.isEmpty() && (body.startsWith("<") || body.startsWith("%dw"))) {
        return true;
      }
    }
    return false;
  }
}
