package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.mulesoftforge.studio.ai.render.HtmlPage;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer.Rendered;
import com.mulesoftforge.studio.ai.render.Span;

// StyledText transcript: works everywhere, including where no browser engine is available.
final class PlainTranscript implements Transcript {
  private record Clickable(int start, int length, String data) {}

  // One checklist from offer(): where each "[x]" box is, and which are ticked.
  private record Offer(String id, int[] boxes, boolean[] ticked) {}

  private static final String BOX = "box:";
  private static final String SUBMIT = "submit:";

  private final StyledText text;
  private final TranscriptStyles styles;
  private final Actions actions;
  private final List<String> codeBlocks = new ArrayList<>();
  private final List<Clickable> clickables = new ArrayList<>();
  private final List<Offer> offers = new ArrayList<>();
  private boolean showRaw = true;

  PlainTranscript(Composite parent, Actions actions) {
    this.actions = actions;
    text = new StyledText(parent, SWT.BORDER | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
    text.setMargins(6, 6, 6, 6);
    styles = new TranscriptStyles(text);
    Cursor hand = text.getDisplay().getSystemCursor(SWT.CURSOR_HAND);
    text.addListener(SWT.MouseMove, e -> text.setCursor(clickableAt(e.x, e.y) == null ? null : hand));
    text.addListener(SWT.MouseUp, e -> {
      Clickable clickable = e.button == 1 ? clickableAt(e.x, e.y) : null;
      if (clickable == null || text.getSelectionCount() != 0) {
        return;
      }
      if (clickable.data().startsWith(BOX)) {
        toggle(clickable.data().substring(BOX.length()));
      } else if (clickable.data().startsWith(SUBMIT)) {
        submit(Integer.parseInt(clickable.data().substring(SUBMIT.length())));
      } else {
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
    offers.clear();
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
    button(label, "cmd:" + id);
  }

  private void button(String label, String data) {
    int start = text.getCharCount() + 2;
    text.append("\n\n" + label);
    StyleRange style = new StyleRange();
    style.start = start;
    style.length = label.length();
    style.foreground = styles.speaker("You");
    style.font = TranscriptStyles.bold();
    style.underline = true;
    text.setStyleRange(style);
    clickables.add(new Clickable(start, label.length(), data));
    scrollToEnd();
  }

  @Override
  public void offer(String id, List<String> items, boolean suggested) {
    muted(suggested
        ? "\n\nPossible fixes Agent mode can draft for review (click a box to tick it):"
        : "\n\nChanges it can make (click a box to untick it):");
    int[] boxes = new int[items.size()];
    int index = offers.size();
    for (int i = 0; i < items.size(); i++) {
      text.append("\n  ");
      boxes[i] = text.getCharCount();
      text.append((suggested ? "[ ] " : "[x] ") + items.get(i));
      clickables.add(new Clickable(boxes[i], 3, BOX + index + ":" + i));
    }
    boolean[] ticked = new boolean[items.size()];
    Arrays.fill(ticked, !suggested);
    offers.add(new Offer(id, boxes, ticked));
    button(HtmlPage.OFFER_BUTTON, SUBMIT + index);
  }

  // No collapsible sections here: one line per turn, and the full exchange opens in an editor.
  @Override
  public void raw(String title, List<RawCall> calls, String open) {
    if (!showRaw) {
      return;
    }
    muted("\n    · " + title + " ");
    if (open != null) {
      int start = text.getCharCount();
      String label = "Open raw log";
      text.append(label);
      StyleRange style = new StyleRange();
      style.start = start;
      style.length = label.length();
      style.foreground = styles.speaker("You");
      style.underline = true;
      text.setStyleRange(style);
      clickables.add(new Clickable(start, label.length(), "cmd:" + open));
    }
    scrollToEnd();
  }

  @Override
  public void showRaw(boolean show) {
    showRaw = show;
  }

  private void toggle(String where) {
    String[] parts = where.split(":");
    Offer offer = offers.get(Integer.parseInt(parts[0]));
    int i = Integer.parseInt(parts[1]);
    offer.ticked()[i] = !offer.ticked()[i];
    text.replaceTextRange(offer.boxes()[i], 3, offer.ticked()[i] ? "[x]" : "[ ]");
  }

  private void submit(int index) {
    Offer offer = offers.get(index);
    List<String> picked = new ArrayList<>();
    for (int i = 0; i < offer.ticked().length; i++) {
      if (offer.ticked()[i]) {
        picked.add(String.valueOf(i));
      }
    }
    actions.command("offer:" + offer.id() + ":" + String.join(",", picked));
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
