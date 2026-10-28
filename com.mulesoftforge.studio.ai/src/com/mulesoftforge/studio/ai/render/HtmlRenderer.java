package com.mulesoftforge.studio.ai.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import com.mulesoftforge.studio.ai.render.MarkdownRenderer.CodeBlock;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer.Rendered;

// Markdown to an HTML fragment for the rich transcript. Everything from the model is escaped; links
// carry their URL in a data attribute and are opened by Studio, never by the page.
public final class HtmlRenderer {
  public record Html(String html, List<String> codeBlocks) {}

  private record Open(Span span, int end) {}

  private final String text;
  private final List<Span> spans;
  private final List<CodeBlock> blocks;
  private final int firstBlock;
  private final StringBuilder out = new StringBuilder();
  private final Deque<Open> open = new ArrayDeque<>();
  private int blockIndex;

  private HtmlRenderer(Rendered rendered, int firstBlock) {
    this.text = rendered.text();
    this.blocks = rendered.blocks();
    this.firstBlock = firstBlock;
    List<Span> sorted = new ArrayList<>();
    for (Span span : rendered.spans()) {
      if (span.length() > 0) {
        sorted.add(span);
      }
    }
    sorted.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::length).reversed()));
    this.spans = sorted;
  }

  // firstBlock numbers this message's code blocks after the ones already in the transcript.
  public static Html markdown(String markdown, int firstBlock) {
    Rendered rendered = MarkdownRenderer.render(markdown, false);
    HtmlRenderer renderer = new HtmlRenderer(rendered, firstBlock);
    renderer.run();
    return new Html(renderer.out.toString(), rendered.blocks().stream().map(CodeBlock::code).toList());
  }

  // Plain text, e.g. a user message or an error dump.
  public static String plain(String text) {
    return escape(text).replace("\n", "<br>");
  }

  public static String escape(String text) {
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&#39;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private void run() {
    int next = 0;
    int p = 0;
    while (p < text.length()) {
      if (closeEndingAt(p)) {
        p = skipNewline(p);
        if (p >= text.length()) {
          break;
        }
      }
      if (open.isEmpty() && next < spans.size() && spans.get(next).start() == p
          && spans.get(next).kind() == Span.Kind.TABLE) {
        int end = p;
        List<String> rows = new ArrayList<>();
        while (next < spans.size() && spans.get(next).kind() == Span.Kind.TABLE && spans.get(next).start() <= end + 1) {
          Span row = spans.get(next++);
          rows.add(text.substring(row.start(), row.start() + row.length()));
          end = row.start() + row.length();
        }
        table(rows);
        p = skipNewline(end);
        continue;
      }
      while (next < spans.size() && spans.get(next).start() == p) {
        Span span = spans.get(next++);
        if (span.kind() == Span.Kind.TABLE) {
          continue;
        }
        int end = span.start() + span.length();
        if (!open.isEmpty()) {
          end = Math.min(end, open.peek().end());
        }
        openTag(span);
        open.push(new Open(span, end));
      }
      char c = text.charAt(p);
      if (c == '\n' && !insideCode() && startsBlockAt(p + 1, next)) {
        p++;
        continue;
      }
      out.append(c == '\n' && !insideCode() ? "<br>" : escape(String.valueOf(c)));
      p++;
      if (closeEndingAt(p)) {
        p = skipNewline(p);
      }
    }
    closeEndingAt(Integer.MAX_VALUE);
  }

  // Returns true when a code block closed, so the line break after it can be dropped.
  private boolean closeEndingAt(int p) {
    boolean closedBlock = false;
    while (!open.isEmpty() && open.peek().end() <= p) {
      Span span = open.pop().span();
      closedBlock |= span.kind() == Span.Kind.CODE_BLOCK;
      closeTag(span);
    }
    return closedBlock;
  }

  private int skipNewline(int p) {
    return p < text.length() && text.charAt(p) == '\n' ? p + 1 : p;
  }

  private boolean insideCode() {
    return open.stream().anyMatch(o -> o.span().kind() == Span.Kind.CODE_BLOCK);
  }

  private boolean startsBlockAt(int p, int next) {
    for (int i = next; i < spans.size() && spans.get(i).start() <= p; i++) {
      Span span = spans.get(i);
      if (span.start() == p && (span.kind() == Span.Kind.CODE_BLOCK || span.kind() == Span.Kind.TABLE)) {
        return true;
      }
    }
    return false;
  }

  private void openTag(Span span) {
    switch (span.kind()) {
      case BOLD -> out.append("<strong>");
      case ITALIC -> out.append("<em>");
      case HEADING -> out.append("large".equals(span.data()) ? "<span class=\"h1\">" : "<span class=\"h2\">");
      case INLINE_CODE -> out.append("<code class=\"inline\">");
      case CODE_BLOCK -> {
        int index = firstBlock + blockIndex;
        String language = blockIndex < blocks.size() ? blocks.get(blockIndex).language().name().toLowerCase() : "";
        blockIndex++;
        out.append("<div class=\"block\"><div class=\"bar\"><span class=\"lang\">")
            .append("plain".equals(language) ? "" : escape(language))
            .append("</span><a class=\"act\" data-a=\"copy:").append(index).append("\">Copy</a>")
            .append("<a class=\"act\" data-a=\"insert:").append(index).append("\">Insert at cursor</a></div><pre>");
      }
      case LINK -> {
        String url = span.data() == null ? "" : span.data();
        if (url.startsWith("https://") || url.startsWith("http://")) {
          out.append("<a class=\"link\" title=\"").append(escape(url)).append("\" data-a=\"url:")
              .append(escape(url)).append("\">");
        } else {
          out.append("<span>");
        }
      }
      case QUOTE -> out.append("<span class=\"quote\">");
      case TAG, ATTR_NAME, ATTR_VALUE, COMMENT, STRING, NUMBER, KEYWORD, KEY, EXPRESSION, DIRECTIVE ->
          out.append("<span class=\"t-").append(span.kind().name().toLowerCase().replace('_', '-')).append("\">");
      case ACTION, TABLE -> out.append("<span>");
      default -> throw new IllegalStateException("Unhandled span kind " + span.kind());
    }
  }

  private void closeTag(Span span) {
    switch (span.kind()) {
      case BOLD -> out.append("</strong>");
      case ITALIC -> out.append("</em>");
      case INLINE_CODE -> out.append("</code>");
      case CODE_BLOCK -> out.append("</pre></div>");
      case LINK -> {
        String url = span.data() == null ? "" : span.data();
        out.append(url.startsWith("https://") || url.startsWith("http://") ? "</a>" : "</span>");
      }
      case HEADING, QUOTE, TAG, ATTR_NAME, ATTR_VALUE, COMMENT, STRING, NUMBER, KEYWORD, KEY, EXPRESSION, DIRECTIVE,
          ACTION, TABLE -> out.append("</span>");
      default -> throw new IllegalStateException("Unhandled span kind " + span.kind());
    }
  }

  // Markdown table rows ("| a | b |"); the |---| separator row marks the header.
  private void table(List<String> rows) {
    out.append("<table>");
    boolean header = rows.size() > 1 && rows.get(1).matches("^\\s*\\|?\\s*:?-+.*");
    for (int i = 0; i < rows.size(); i++) {
      if (header && i == 1) {
        continue;
      }
      String tag = header && i == 0 ? "th" : "td";
      out.append("<tr>");
      for (String cell : cells(rows.get(i))) {
        out.append('<').append(tag).append('>').append(escape(cell.trim())).append("</").append(tag).append('>');
      }
      out.append("</tr>");
    }
    out.append("</table>");
  }

  private static List<String> cells(String row) {
    String trimmed = row.trim();
    if (trimmed.startsWith("|")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.endsWith("|")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return List.of(trimmed.split("\\|", -1));
  }
}
