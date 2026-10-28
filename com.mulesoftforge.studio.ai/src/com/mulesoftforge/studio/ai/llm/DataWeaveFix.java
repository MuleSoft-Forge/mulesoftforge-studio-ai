package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "Fix with MuleSoftForge AI" in a DataWeave editor: the request, and the corrected script in the answer.
public final class DataWeaveFix {
  private static final Pattern FENCE = Pattern.compile("(?ms)^[ \\t]*```([^\\n`]*)\\n(.*?)^[ \\t]*```[ \\t]*$");
  private static final Set<String> LANGUAGES = Set.of("dw", "dwl", "dataweave", "weave");

  private DataWeaveFix() {}

  // Lines are the selection to focus on, e.g. "lines 4–9", or null for the whole script.
  public static String prompt(String lines, boolean withProblems) {
    return "Fix this DataWeave script."
        + (withProblems ? " The problems Studio reports in it are attached." : "")
        + (lines == null ? "" : " Focus on " + lines + ".")
        + "\nReply with the complete corrected script, from %dw 2.0 to the end, in one ```dataweave code block,"
        + " then a short list of what you changed and why. If nothing needs fixing, say so and include no code"
        + " block.";
  }

  // The last DataWeave code block in the answer, or its only code block when that has no language. Null when
  // there is nothing to apply.
  public static String script(String answer) {
    List<String> dataWeave = new ArrayList<>();
    List<String> untagged = new ArrayList<>();
    int blocks = 0;
    Matcher m = FENCE.matcher(answer);
    while (m.find()) {
      blocks++;
      String language = m.group(1).trim().toLowerCase(Locale.ROOT);
      String code = m.group(2).stripTrailing();
      if (LANGUAGES.contains(language)) {
        dataWeave.add(code);
      } else if (language.isEmpty()) {
        untagged.add(code);
      }
    }
    if (!dataWeave.isEmpty()) {
      return dataWeave.get(dataWeave.size() - 1);
    }
    return blocks == 1 && untagged.size() == 1 ? untagged.get(0) : null;
  }
}
