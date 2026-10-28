package com.mulesoftforge.studio.ai.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.render.Span.Kind;

// Small lexical highlighters for what Mule developers paste: Mule XML, DataWeave, JSON, YAML/RAML,
// properties. Spans are relative to the code string and never overlap.
public final class Highlighter {
  public enum Language {
    XML,
    DATAWEAVE,
    JSON,
    YAML,
    PROPERTIES,
    PLAIN
  }

  private static final Set<String> DW_KEYWORDS = Set.of("fun", "var", "type", "ns", "import", "from", "if",
      "else", "unless", "match", "case", "default", "as", "is", "and", "or", "not", "do", "using", "update",
      "at", "input", "output", "true", "false", "null");

  private static final Pattern DW_TOKENS = Pattern.compile(
      "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
          + "|(?<string>\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'|`[^`\\n]*`)"
          + "|(?<directive>^\\s*%dw\\s+[\\d.]+|^\\s*---\\s*$)"
          + "|(?<number>\\b\\d+(?:\\.\\d+)?\\b)"
          + "|(?<word>\\$\\$?|\\b[A-Za-z_][A-Za-z0-9_]*\\b)",
      Pattern.MULTILINE);

  private static final Pattern JSON_TOKENS = Pattern.compile(
      "(?<string>\"(?:[^\"\\\\\\n]|\\\\.)*\")(?<colon>\\s*:)?"
          + "|(?<number>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
          + "|(?<keyword>\\b(?:true|false|null)\\b)");

  private static final Pattern YAML_TOKENS = Pattern.compile(
      "(?<comment>(?:^|\\s)#[^\\n]*)"
          + "|(?<key>^[ \\t]*(?:- )?[A-Za-z0-9_./{}\\-\"' ]+?(?=:(?:\\s|$)))"
          + "|(?<string>\"(?:[^\"\\\\\\n]|\\\\.)*\"|'[^'\\n]*')"
          + "|(?<directive>^#%RAML[^\\n]*|!include\\b|\\$\\{[^}\\n]*\\})"
          + "|(?<number>\\b\\d+(?:\\.\\d+)?\\b)"
          + "|(?<keyword>\\b(?:true|false|null|yes|no)\\b)",
      Pattern.MULTILINE);

  private static final Pattern PROPERTIES_TOKENS = Pattern.compile(
      "(?<comment>^[ \\t]*[#!][^\\n]*)|(?<key>^[ \\t]*[^=:#!\\s][^=:\\n]*?(?=\\s*[=:]))|(?<directive>\\$\\{[^}\\n]*\\})",
      Pattern.MULTILINE);

  private Highlighter() {}

  public static Language language(String fenceInfo, String code) {
    String id = fenceInfo == null ? "" : fenceInfo.trim().toLowerCase(Locale.ROOT);
    switch (id) {
      case "xml", "mule", "xsd", "wsdl", "html", "xhtml", "pom" -> {
        return Language.XML;
      }
      case "dw", "dwl", "dataweave", "weave" -> {
        return Language.DATAWEAVE;
      }
      case "json", "jsonc", "json5" -> {
        return Language.JSON;
      }
      case "yaml", "yml", "raml" -> {
        return Language.YAML;
      }
      case "properties", "props", "ini", "env" -> {
        return Language.PROPERTIES;
      }
      default -> {
        String head = code.stripLeading();
        if (head.startsWith("<")) {
          return Language.XML;
        }
        if (head.startsWith("%dw")) {
          return Language.DATAWEAVE;
        }
        if (head.startsWith("{") || head.startsWith("[")) {
          return Language.JSON;
        }
        if (head.startsWith("#%RAML")) {
          return Language.YAML;
        }
        return Language.PLAIN;
      }
    }
  }

  public static List<Span> highlight(Language language, String code) {
    return switch (language) {
      case XML -> xml(code);
      case DATAWEAVE -> dataweave(code, 0);
      case JSON -> json(code);
      case YAML -> regex(YAML_TOKENS, code, 0);
      case PROPERTIES -> regex(PROPERTIES_TOKENS, code, 0);
      case PLAIN -> List.of();
    };
  }

  static List<Span> dataweave(String code, int offset) {
    List<Span> spans = new ArrayList<>();
    Matcher m = DW_TOKENS.matcher(code);
    while (m.find()) {
      if (m.group("comment") != null) {
        spans.add(new Span(offset + m.start(), m.end() - m.start(), Kind.COMMENT));
      } else if (m.group("string") != null) {
        spans.add(new Span(offset + m.start(), m.end() - m.start(), Kind.STRING));
      } else if (m.group("directive") != null) {
        spans.add(new Span(offset + m.start(), m.end() - m.start(), Kind.DIRECTIVE));
      } else if (m.group("number") != null) {
        spans.add(new Span(offset + m.start(), m.end() - m.start(), Kind.NUMBER));
      } else if (m.group("word") != null) {
        String word = m.group("word");
        if (word.startsWith("$") || DW_KEYWORDS.contains(word)) {
          spans.add(new Span(offset + m.start(), m.end() - m.start(), Kind.KEYWORD));
        }
      }
    }
    return spans;
  }

  private static List<Span> json(String code) {
    List<Span> spans = new ArrayList<>();
    Matcher m = JSON_TOKENS.matcher(code);
    while (m.find()) {
      if (m.group("string") != null) {
        Kind kind = m.group("colon") != null ? Kind.KEY : Kind.STRING;
        spans.add(new Span(m.start("string"), m.end("string") - m.start("string"), kind));
      } else if (m.group("number") != null) {
        spans.add(new Span(m.start(), m.end() - m.start(), Kind.NUMBER));
      } else {
        spans.add(new Span(m.start(), m.end() - m.start(), Kind.KEYWORD));
      }
    }
    return spans;
  }

  private static List<Span> regex(Pattern pattern, String code, int offset) {
    List<Span> spans = new ArrayList<>();
    Matcher m = pattern.matcher(code);
    while (m.find()) {
      for (Kind kind : new Kind[] {Kind.COMMENT, Kind.KEY, Kind.STRING, Kind.DIRECTIVE, Kind.NUMBER, Kind.KEYWORD}) {
        String group = kind == Kind.DIRECTIVE ? "directive" : kind.name().toLowerCase(Locale.ROOT);
        if (pattern.pattern().contains("?<" + group + ">") && m.group(group) != null) {
          int start = m.start(group);
          int end = m.end(group);
          // A leading space belongs to a YAML comment match only to anchor it.
          while (start < end && kind == Kind.COMMENT && Character.isWhitespace(code.charAt(start))) {
            start++;
          }
          spans.add(new Span(offset + start, end - start, kind));
          break;
        }
      }
    }
    return spans;
  }

  private static List<Span> xml(String code) {
    List<Span> spans = new ArrayList<>();
    int i = 0;
    int n = code.length();
    while (i < n) {
      if (code.startsWith("<!--", i)) {
        int end = indexOrEnd(code, "-->", i + 4, 3);
        spans.add(new Span(i, end - i, Kind.COMMENT));
        i = end;
      } else if (code.startsWith("<![CDATA[", i)) {
        int bodyStart = i + 9;
        int close = code.indexOf("]]>", bodyStart);
        int bodyEnd = close < 0 ? n : close;
        spans.add(new Span(i, 9, Kind.TAG));
        String body = code.substring(bodyStart, bodyEnd);
        // CDATA in Mule XML is almost always a DataWeave script.
        spans.addAll(dataweave(body, bodyStart));
        if (close >= 0) {
          spans.add(new Span(close, 3, Kind.TAG));
        }
        i = close < 0 ? n : close + 3;
      } else if (code.startsWith("<?", i)) {
        int end = indexOrEnd(code, "?>", i + 2, 2);
        spans.add(new Span(i, end - i, Kind.DIRECTIVE));
        i = end;
      } else if (code.charAt(i) == '<' && i + 1 < n && (Character.isLetter(code.charAt(i + 1))
          || code.charAt(i + 1) == '/' || code.charAt(i + 1) == '_')) {
        i = xmlTag(code, i, spans);
      } else if (code.startsWith("#[", i)) {
        int end = expressionEnd(code, i);
        spans.add(new Span(i, end - i, Kind.EXPRESSION));
        i = end;
      } else {
        i++;
      }
    }
    return spans;
  }

  private static int xmlTag(String code, int start, List<Span> spans) {
    int n = code.length();
    int i = start + 1;
    if (i < n && code.charAt(i) == '/') {
      i++;
    }
    while (i < n && isNameChar(code.charAt(i))) {
      i++;
    }
    spans.add(new Span(start, i - start, Kind.TAG));
    while (i < n) {
      char c = code.charAt(i);
      if (c == '>' || code.startsWith("/>", i)) {
        int len = c == '>' ? 1 : 2;
        spans.add(new Span(i, len, Kind.TAG));
        return i + len;
      }
      if (isNameChar(c)) {
        int nameStart = i;
        while (i < n && isNameChar(code.charAt(i))) {
          i++;
        }
        spans.add(new Span(nameStart, i - nameStart, Kind.ATTR_NAME));
      } else if (c == '"' || c == '\'') {
        int close = code.indexOf(c, i + 1);
        int end = close < 0 ? n : close + 1;
        attributeValue(code, i, end, spans);
        i = end;
      } else {
        i++;
      }
    }
    return n;
  }

  // Mule attribute values often hold #[ DataWeave ] expressions.
  private static void attributeValue(String code, int start, int end, List<Span> spans) {
    int i = start;
    int plain = start;
    while (i < end) {
      if (code.startsWith("#[", i)) {
        if (i > plain) {
          spans.add(new Span(plain, i - plain, Kind.ATTR_VALUE));
        }
        int exprEnd = Math.min(expressionEnd(code, i), end);
        spans.add(new Span(i, exprEnd - i, Kind.EXPRESSION));
        i = exprEnd;
        plain = i;
      } else {
        i++;
      }
    }
    if (end > plain) {
      spans.add(new Span(plain, end - plain, Kind.ATTR_VALUE));
    }
  }

  private static int expressionEnd(String code, int start) {
    int depth = 0;
    for (int i = start + 1; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '[') {
        depth++;
      } else if (c == ']' && --depth == 0) {
        return i + 1;
      } else if (c == '\n') {
        return i;
      }
    }
    return code.length();
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '.';
  }

  private static int indexOrEnd(String code, String token, int from, int tokenLength) {
    int index = code.indexOf(token, from);
    return index < 0 ? code.length() : index + tokenLength;
  }
}
