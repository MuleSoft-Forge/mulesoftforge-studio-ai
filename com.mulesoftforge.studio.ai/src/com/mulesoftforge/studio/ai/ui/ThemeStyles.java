package com.mulesoftforge.studio.ai.ui;

import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;

import com.mulesoftforge.studio.ai.render.Palette;

// Resolves colours from the workbench theme registry and applies CSS class names via IStylingEngine.
final class ThemeStyles {
  static final String PANEL = "com.mulesoftforge.studio.ai.panel";
  static final String DIVIDER = "com.mulesoftforge.studio.ai.divider";
  static final String CODE_BACKGROUND = "com.mulesoftforge.studio.ai.codeBackground";
  static final String INLINE_CODE = "com.mulesoftforge.studio.ai.inlineCode";
  static final String MUTED = "com.mulesoftforge.studio.ai.muted";
  static final String LINK = "com.mulesoftforge.studio.ai.link";
  static final String TAG = "com.mulesoftforge.studio.ai.tag";
  static final String ATTR_NAME = "com.mulesoftforge.studio.ai.attrName";
  static final String ATTR_VALUE = "com.mulesoftforge.studio.ai.attrValue";
  static final String COMMENT = "com.mulesoftforge.studio.ai.comment";
  static final String STRING = "com.mulesoftforge.studio.ai.string";
  static final String NUMBER = "com.mulesoftforge.studio.ai.number";
  static final String KEYWORD = "com.mulesoftforge.studio.ai.keyword";
  static final String KEY = "com.mulesoftforge.studio.ai.key";
  static final String EXPRESSION = "com.mulesoftforge.studio.ai.expression";
  static final String DIRECTIVE = "com.mulesoftforge.studio.ai.directive";
  static final String YOU = "com.mulesoftforge.studio.ai.you";
  static final String AI = "com.mulesoftforge.studio.ai.ai";
  static final String ERROR = "com.mulesoftforge.studio.ai.error";
  static final String NOTE = "com.mulesoftforge.studio.ai.note";
  static final String GO = "com.mulesoftforge.studio.ai.go";
  static final String STOP = "com.mulesoftforge.studio.ai.stop";
  static final String BUTTON_TEXT = "com.mulesoftforge.studio.ai.buttonText";
  static final String BUTTON_DISABLED = "com.mulesoftforge.studio.ai.buttonDisabled";

  private ThemeStyles() {}

  static ColorRegistry colors() {
    ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    return theme.getColorRegistry();
  }

  static Palette palette() {
    ColorRegistry colors = colors();
    Palette fallback = Palette.LIGHT;
    Color panel = colors.get(PANEL);
    if (panel != null && rgb(panel.getRGB()).isDark()) {
      fallback = Palette.DARK;
    }
    return new Palette(
        color(colors, CODE_BACKGROUND, fallback.codeBackground()),
        color(colors, INLINE_CODE, fallback.inlineCodeBackground()),
        color(colors, MUTED, fallback.muted()),
        color(colors, LINK, fallback.link()),
        color(colors, TAG, fallback.tag()),
        color(colors, ATTR_NAME, fallback.attrName()),
        color(colors, ATTR_VALUE, fallback.attrValue()),
        color(colors, COMMENT, fallback.comment()),
        color(colors, STRING, fallback.string()),
        color(colors, NUMBER, fallback.number()),
        color(colors, KEYWORD, fallback.keyword()),
        color(colors, KEY, fallback.key()),
        color(colors, EXPRESSION, fallback.expression()),
        color(colors, DIRECTIVE, fallback.directive()),
        color(colors, YOU, fallback.you()),
        color(colors, AI, fallback.ai()),
        color(colors, ERROR, fallback.error()),
        color(colors, NOTE, fallback.note()),
        color(colors, PANEL, fallback.panel()),
        color(colors, DIVIDER, fallback.divider()));
  }

  static Palette.Rgb background() {
    return palette().panel();
  }

  static Palette.Rgb foreground() {
    return background().isDark() ? Palette.rgb(220, 220, 220) : Palette.rgb(30, 30, 30);
  }

  static Color swt(String id) {
    Color color = colors().get(id);
    if (color != null) {
      return color;
    }
    Palette.Rgb rgb = palette().panel();
    ColorRegistry registry = colors();
    String key = "com.mulesoftforge.studio.ai.fallback." + id;
    if (registry.get(key) == null) {
      registry.put(key, new RGB(rgb.red(), rgb.green(), rgb.blue()));
    }
    return registry.get(key);
  }

  static Color swt(Palette.Rgb rgb) {
    ColorRegistry registry = colors();
    String key = "com.mulesoftforge.studio.ai.rgb." + rgb.red() + "." + rgb.green() + "." + rgb.blue();
    if (registry.get(key) == null) {
      registry.put(key, new RGB(rgb.red(), rgb.green(), rgb.blue()));
    }
    return registry.get(key);
  }

  static void applyClass(Control control, String cssClass) {
    IStylingEngine styling = PlatformUI.getWorkbench().getService(IStylingEngine.class);
    if (styling != null) {
      styling.setClassname(control, cssClass);
      styling.style(control);
    }
  }

  static Palette.Rgb rgb(RGB rgb) {
    return Palette.rgb(rgb.red, rgb.green, rgb.blue);
  }

  private static Palette.Rgb color(ColorRegistry colors, String id, Palette.Rgb fallback) {
    Color color = colors.get(id);
    return color == null ? fallback : rgb(color.getRGB());
  }
}
