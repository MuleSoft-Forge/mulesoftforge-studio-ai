package com.mulesoftforge.studio.ai.ui;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;

import com.mulesoftforge.studio.ai.render.Palette;
import com.mulesoftforge.studio.ai.render.Span;

// SWT colours and fonts for the plain transcript. The theme is read from the widget background, not the OS.
final class TranscriptStyles {
  final Palette palette;
  final Color codeBackground;
  final Color muted;
  private final Color inlineCodeBackground;
  private final Color link;

  TranscriptStyles(Control control) {
    palette = Palette.forBackground(rgb(control.getBackground().getRGB()));
    codeBackground = color(palette.codeBackground());
    inlineCodeBackground = color(palette.inlineCodeBackground());
    muted = color(palette.muted());
    link = color(palette.link());
  }

  static Palette.Rgb rgb(RGB rgb) {
    return new Palette.Rgb(rgb.red, rgb.green, rgb.blue);
  }

  private static Color color(Palette.Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  Color speaker(String name) {
    return color(palette.speaker(name));
  }

  static Font mono() {
    return JFaceResources.getTextFont();
  }

  static Font bold() {
    return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
  }

  static Font italic() {
    return JFaceResources.getFontRegistry().getItalic(JFaceResources.DEFAULT_FONT);
  }

  StyleRange style(Span span, int offset) {
    StyleRange range = new StyleRange();
    range.start = offset + span.start();
    range.length = span.length();
    switch (span.kind()) {
      case BOLD -> range.font = bold();
      case ITALIC -> range.font = italic();
      case HEADING -> range.font = "large".equals(span.data()) ? JFaceResources.getHeaderFont() : bold();
      case INLINE_CODE -> {
        range.font = mono();
        range.background = inlineCodeBackground;
      }
      case CODE_BLOCK, TABLE -> range.font = mono();
      case LINK -> {
        range.foreground = link;
        range.underline = true;
        range.underlineStyle = SWT.UNDERLINE_LINK;
        range.data = span.data();
      }
      case ACTION -> {
        range.foreground = link;
        range.underline = true;
        range.data = span.data();
      }
      case QUOTE -> {
        range.foreground = muted;
        range.font = italic();
      }
      case TAG -> token(range, palette.tag());
      case ATTR_NAME -> token(range, palette.attrName());
      case ATTR_VALUE -> token(range, palette.attrValue());
      case COMMENT -> token(range, palette.comment());
      case STRING -> token(range, palette.string());
      case NUMBER -> token(range, palette.number());
      case KEYWORD -> {
        token(range, palette.keyword());
        range.font = JFaceResources.getFontRegistry().getBold(JFaceResources.TEXT_FONT);
      }
      case KEY -> token(range, palette.key());
      case EXPRESSION -> token(range, palette.expression());
      case DIRECTIVE -> token(range, palette.directive());
      default -> throw new IllegalStateException("Unhandled span kind " + span.kind());
    }
    return range;
  }

  private static void token(StyleRange range, Palette.Rgb rgb) {
    range.font = mono();
    range.foreground = color(rgb);
  }
}
