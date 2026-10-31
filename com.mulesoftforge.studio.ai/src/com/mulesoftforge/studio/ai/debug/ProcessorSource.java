package com.mulesoftforge.studio.ai.debug;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.edit.ProjectFiles;

// Finds the XML of the processor the debugger stopped at, in the project's Mule files. Studio gives every element a
// doc:id and the debugger reports it, so that is the lookup; elements in comments do not count. When there is no
// doc:id, the flow (or sub-flow) named in the processor path is the fallback.
public final class ProcessorSource {
  public static final int MAX_XML = 8_000;
  private static final String MULE_DIR = "src/main/mule";
  private static final int MAX_FILES = 500;

  // path is project-relative; line is 1-based, where the element starts.
  public record Located(String path, int line, String xml, boolean truncated) {}

  private ProcessorSource() {}

  public static Located find(ProjectFiles files, DebugStop stop) throws Exception {
    if (files == null || stop == null) {
      return null;
    }
    if (stop.docId() != null && !stop.docId().isBlank()) {
      Located byId = byDocId(files, stop.docId());
      if (byId != null) {
        return byId;
      }
    }
    return stop.flowName() == null ? null : byFlowName(files, stop.flowName());
  }

  public static Located byDocId(ProjectFiles files, String docId) throws Exception {
    return search(files, Pattern.compile("\\bdoc:id\\s*=\\s*[\"']" + Pattern.quote(docId) + "[\"']"));
  }

  public static Located byFlowName(ProjectFiles files, String name) throws Exception {
    return search(files, Pattern.compile("<(?:flow|sub-flow)\\b[^>]*\\bname\\s*=\\s*[\"']" + Pattern.quote(name)
        + "[\"']"));
  }

  private static Located search(ProjectFiles files, Pattern attribute) throws Exception {
    List<String> paths = files.list(MULE_DIR, MAX_FILES);
    for (String path : paths) {
      if (!path.endsWith(".xml")) {
        continue;
      }
      String text = files.read(path);
      if (text == null) {
        continue;
      }
      Matcher m = attribute.matcher(text);
      while (m.find()) {
        if (insideComment(text, m.start())) {
          continue;
        }
        int start = text.lastIndexOf('<', m.start());
        int end = start < 0 ? -1 : elementEnd(text, start);
        if (end < 0) {
          continue;
        }
        String xml = text.substring(start, end);
        boolean truncated = xml.length() > MAX_XML;
        return new Located(path, line(text, start), truncated ? xml.substring(0, MAX_XML) : xml, truncated);
      }
    }
    return null;
  }

  static boolean insideComment(String text, int at) {
    int open = text.lastIndexOf("<!--", at);
    return open >= 0 && text.indexOf("-->", open) > at;
  }

  // Index just after the element that starts at start, or -1 when it does not close.
  static int elementEnd(String text, int start) {
    int depth = 0;
    int i = start;
    while (i < text.length()) {
      if (text.startsWith("<!--", i)) {
        i = after(text, "-->", i);
      } else if (text.startsWith("<![CDATA[", i)) {
        i = after(text, "]]>", i);
      } else if (text.startsWith("<?", i)) {
        i = after(text, "?>", i);
      } else if (text.charAt(i) == '<') {
        int close = tagEnd(text, i);
        if (close < 0) {
          return -1;
        }
        if (text.charAt(i + 1) == '/') {
          depth--;
        } else if (text.charAt(close - 1) != '/') {
          depth++;
        }
        i = close + 1;
        if (depth == 0) {
          return i;
        }
        continue;
      } else {
        i++;
      }
      if (i < 0) {
        return -1;
      }
    }
    return -1;
  }

  // The '>' that ends the tag starting at i, skipping quoted attribute values.
  private static int tagEnd(String text, int i) {
    char quote = 0;
    for (int j = i + 1; j < text.length(); j++) {
      char c = text.charAt(j);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return j;
      }
    }
    return -1;
  }

  private static int after(String text, String token, int from) {
    int at = text.indexOf(token, from);
    return at < 0 ? -1 : at + token.length();
  }

  private static int line(String text, int index) {
    int line = 1;
    for (int i = 0; i < index; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
