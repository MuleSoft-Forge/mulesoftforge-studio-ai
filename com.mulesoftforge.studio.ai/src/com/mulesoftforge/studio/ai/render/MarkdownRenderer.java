package com.mulesoftforge.studio.ai.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.render.Highlighter.Language;
import com.mulesoftforge.studio.ai.render.Span.Kind;

// Renders the Markdown that models reply with into plain text plus style spans for a StyledText.
// Covers what chat answers use: headings, bullets, quotes, tables, bold/italic, inline code, links,
// and fenced code blocks (highlighted, followed by an action line).
public final class MarkdownRenderer {
  public static final String ACTION_COPY = "copy";
  public static final String ACTION_INSERT = "insert";

  public record CodeBlock(int start, int length, Language language, String code) {}

  public record Rendered(String text, List<Span> spans, List<CodeBlock> blocks) {}

  private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*([\\w+#.\\-]*).*$");
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
  private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
  private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
  private static final Pattern TABLE = Pattern.compile("^\\s*\\|.*\\|\\s*$");

  private final StringBuilder out = new StringBuilder();
  private final List<Span> spans = new ArrayList<>();
  private final List<CodeBlock> blocks = new ArrayList<>();
  private final boolean actions;

  private MarkdownRenderer(boolean actions) {
    this.actions = actions;
  }

  public static Rendered render(String markdown, boolean actions) {
    MarkdownRenderer renderer = new MarkdownRenderer(actions);
    renderer.run(markdown == null ? "" : markdown.replace("\r\n", "\n"));
    return new Rendered(renderer.out.toString(), renderer.spans, renderer.blocks);
  }

  private void run(String markdown) {
    String[] lines = markdown.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      Matcher fence = FENCE.matcher(line);
      if (fence.matches()) {
        String marker = fence.group(1);
        StringBuilder code = new StringBuilder();
        int j = i + 1;
        while (j < lines.length && !lines[j].trim().startsWith(marker.substring(0, 3))) {
          if (code.length() > 0) {
            code.append('\n');
          }
          code.append(lines[j]);
          j++;
        }
        codeBlock(fence.group(2), code.toString());
        i = j;
        if (i < lines.length - 1) {
          newline();
        }
        continue;
      }
      renderLine(line);
      if (i < lines.length - 1) {
        newline();
      }
    }
  }

  private void renderLine(String line) {
    Matcher m;
    if ((m = HEADING.matcher(line)).matches()) {
      int start = out.length();
      inline(m.group(2));
      spans.add(new Span(start, out.length() - start, Kind.HEADING, m.group(1).length() <= 2 ? "large" : null));
    } else if (RULE.matcher(line).matches()) {
      out.append("─".repeat(32));
    } else if (TABLE.matcher(line).matches()) {
      int start = out.length();
      out.append(line);
      spans.add(new Span(start, line.length(), Kind.TABLE));
    } else if ((m = BULLET.matcher(line)).matches()) {
      out.append(m.group(1)).append("•  ");
      inline(m.group(2));
    } else if ((m = QUOTE.matcher(line)).matches()) {
      int start = out.length();
      out.append("│ ");
      inline(m.group(1));
      spans.add(new Span(start, out.length() - start, Kind.QUOTE));
    } else {
      inline(line);
    }
  }

  private void codeBlock(String info, String code) {
    Language language = Highlighter.language(info, code);
    int start = out.length();
    out.append(code);
    spans.add(new Span(start, code.length(), Kind.CODE_BLOCK, language.name()));
    for (Span token : Highlighter.highlight(language, code)) {
      if (token.length() > 0 && token.start() + token.length() <= code.length()) {
        spans.add(token.shift(start));
      }
    }
    int index = blocks.size();
    blocks.add(new CodeBlock(start, code.length(), language, code));
    if (actions) {
      newline();
      action("Copy", ACTION_COPY + ":" + index);
      out.append("   ");
      action("Insert at cursor", ACTION_INSERT + ":" + index);
    }
  }

  private void action(String label, String id) {
    int start = out.length();
    out.append(label);
    spans.add(new Span(start, label.length(), Kind.ACTION, id));
  }

  private void inline(String text) {
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '`') {
        int close = text.indexOf('`', i + 1);
        if (close > i + 1) {
          styled(text.substring(i + 1, close), Kind.INLINE_CODE, null);
          i = close + 1;
          continue;
        }
      } else if ((text.startsWith("**", i) || text.startsWith("__", i)) && i + 2 < n) {
        int close = text.indexOf(text.substring(i, i + 2), i + 2);
        if (close > i + 2) {
          int start = out.length();
          inline(text.substring(i + 2, close));
          spans.add(new Span(start, out.length() - start, Kind.BOLD));
          i = close + 2;
          continue;
        }
      } else if (c == '*' && i + 1 < n && !Character.isWhitespace(text.charAt(i + 1))) {
        int close = text.indexOf('*', i + 1);
        if (close > i + 1 && !Character.isWhitespace(text.charAt(close - 1))) {
          styled(text.substring(i + 1, close), Kind.ITALIC, null);
          i = close + 1;
          continue;
        }
      } else if (c == '[') {
        int mid = text.indexOf("](", i + 1);
        int close = mid < 0 ? -1 : text.indexOf(')', mid + 2);
        if (mid > i + 1 && close > mid + 2) {
          styled(text.substring(i + 1, mid), Kind.LINK, text.substring(mid + 2, close));
          i = close + 1;
          continue;
        }
      }
      out.append(c);
      i++;
    }
  }

  private void styled(String text, Kind kind, String data) {
    int start = out.length();
    out.append(text);
    spans.add(new Span(start, text.length(), kind, data));
  }

  private void newline() {
    out.append('\n');
  }
}
