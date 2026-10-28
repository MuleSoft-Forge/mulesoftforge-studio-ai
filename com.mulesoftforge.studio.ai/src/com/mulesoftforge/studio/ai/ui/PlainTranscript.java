package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.mulesoftforge.studio.ai.render.MarkdownRenderer;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer.Rendered;
import com.mulesoftforge.studio.ai.render.Span;

// StyledText transcript: works everywhere, including where no browser engine is available.
final class PlainTranscript implements Transcript {
  private record Clickable(int start, int length, String data) {}

  private final StyledText text;
  private final TranscriptStyles styles;
  private final Actions actions;
  private final List<String> codeBlocks = new ArrayList<>();
  private final List<Clickable> clickables = new ArrayList<>();

  PlainTranscript(Composite parent, Actions actions) {
    this.actions = actions;
    text = new StyledText(parent, SWT.BORDER | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
    text.setMargins(6, 6, 6, 6);
    styles = new TranscriptStyles(text);
    Cursor hand = text.getDisplay().getSystemCursor(SWT.CURSOR_HAND);
    text.addListener(SWT.MouseMove, e -> text.setCursor(clickableAt(e.x, e.y) == null ? null : hand));
    text.addListener(SWT.MouseUp, e -> {
      Clickable clickable = e.button == 1 ? clickableAt(e.x, e.y) : null;
      if (clickable != null && text.getSelectionCount() == 0) {
        Clicks.dispatch(clickable.data(), codeBlocks, actions);
      }
    });
  }

  @Override
  public Control control() {
    return text;
  }

  @Override
  public void clear() {
    codeBlocks.clear();
    clickables.clear();
    text.setText("");
  }

  @Override
  public void message(String speaker, String body, String context) {
    label(speaker, false);
    int start = text.getCharCount();
    text.append(body);
    // Provider error dumps (status, headers, JSON body) read better in a fixed-width font.
    if ("Error".equals(speaker) && !body.isEmpty()) {
      StyleRange style = new StyleRange();
      style.start = start;
      style.length = body.length();
      style.font = TranscriptStyles.mono();
      text.setStyleRange(style);
    }
    if (context != null) {
      muted("\n[" + context + "]");
    }
    scrollToEnd();
  }

  @Override
  public void markdown(String speaker, String markdown) {
    int base = label(speaker, true);
    Rendered rendered = MarkdownRenderer.render(markdown, true);
    int firstBlock = codeBlocks.size();
    rendered.blocks().forEach(block -> codeBlocks.add(block.code()));
    text.append(rendered.text());

    List<Span> spans = new ArrayList<>(rendered.spans());
    // Containers first so nested spans (tokens in a block, code in bold) override them.
    spans.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::length).reversed()));
    for (Span span : spans) {
      if (span.length() == 0) {
        continue;
      }
      if (span.kind() == Span.Kind.CODE_BLOCK) {
        int first = text.getLineAtOffset(base + span.start());
        int last = text.getLineAtOffset(base + span.start() + span.length() - 1);
        text.setLineBackground(first, last - first + 1, styles.codeBackground);
      }
      String data = span.data();
      if (span.kind() == Span.Kind.ACTION) {
        String[] parts = data.split(":");
        data = parts[0] + ":" + (firstBlock + Integer.parseInt(parts[1]));
      }
      if (span.kind() == Span.Kind.ACTION || span.kind() == Span.Kind.LINK) {
        clickables.add(new Clickable(base + span.start(), span.length(), span.kind() == Span.Kind.LINK
            ? "url:" + data : data));
      }
      text.setStyleRange(styles.style(span, base));
    }
    scrollToEnd();
  }

  @Override
  public void step(String line) {
    muted("\n    · " + line);
    scrollToEnd();
  }

  @Override
  public void action(String label, String id) {
    int start = text.getCharCount() + 2;
    text.append("\n\n" + label);
    StyleRange style = new StyleRange();
    style.start = start;
    style.length = label.length();
    style.foreground = styles.speaker("You");
    style.font = TranscriptStyles.bold();
    style.underline = true;
    text.setStyleRange(style);
    clickables.add(new Clickable(start, label.length(), "cmd:" + id));
    scrollToEnd();
  }

  // Returns the offset where the body starts.
  private int label(String speaker, boolean bodyOnNewLine) {
    int start = text.getCharCount();
    String label = speaker + ":";
    text.append((start == 0 ? "" : "\n\n") + label + (bodyOnNewLine ? "\n" : " "));
    StyleRange style = new StyleRange();
    style.start = start == 0 ? 0 : start + 2;
    style.length = label.length();
    style.font = TranscriptStyles.bold();
    style.foreground = styles.speaker(speaker);
    text.setStyleRange(style);
    return text.getCharCount();
  }

  private void muted(String line) {
    int start = text.getCharCount();
    text.append(line);
    StyleRange style = new StyleRange();
    style.start = start;
    style.length = line.length();
    style.foreground = styles.muted;
    style.font = TranscriptStyles.italic();
    text.setStyleRange(style);
  }

  private void scrollToEnd() {
    text.setTopIndex(text.getLineCount() - 1);
  }

  private Clickable clickableAt(int x, int y) {
    int offset = text.getOffsetAtPoint(new Point(x, y));
    if (offset < 0) {
      return null;
    }
    for (Clickable c : clickables) {
      if (offset >= c.start() && offset < c.start() + c.length()) {
        return c;
      }
    }
    return null;
  }
}
