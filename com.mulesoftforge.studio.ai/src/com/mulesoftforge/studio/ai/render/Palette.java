package com.mulesoftforge.studio.ai.render;

// Transcript colours, shared by the plain and HTML transcripts. Light colours follow Eclipse's XML
// editor; dark ones follow the common dark-theme palette. Panel is the pale Forge blue (light) or the site's navy (dark) behind answers,
// the message box and the cost column; divider is the bar between regions.
// Studio themes override these through colorDefinitions (see ThemeStyles.fromRegistry).
public record Palette(Rgb codeBackground, Rgb inlineCodeBackground, Rgb muted, Rgb link, Rgb tag, Rgb attrName,
    Rgb attrValue, Rgb comment, Rgb string, Rgb number, Rgb keyword, Rgb key, Rgb expression, Rgb directive,
    Rgb you, Rgb ai, Rgb error, Rgb note, Rgb panel, Rgb divider) {

  public record Rgb(int red, int green, int blue) {
    public String css() {
      return String.format("#%02x%02x%02x", red, green, blue);
    }

    public boolean isDark() {
      return (red * 299 + green * 587 + blue * 114) / 1000 < 128;
    }
  }

  public static final Palette LIGHT = new Palette(rgb(244, 245, 247), rgb(232, 234, 238), rgb(110, 110, 110),
      rgb(0, 102, 204), rgb(63, 127, 127), rgb(127, 0, 127), rgb(42, 0, 255), rgb(63, 95, 191), rgb(163, 21, 21),
      rgb(9, 134, 88), rgb(127, 0, 85), rgb(4, 81, 165), rgb(181, 82, 11), rgb(128, 128, 128), rgb(0, 95, 184),
      rgb(0, 128, 64), rgb(200, 30, 30), rgb(176, 104, 0), rgb(245, 250, 252), rgb(208, 216, 224));

  public static final Palette DARK = new Palette(rgb(11, 15, 24), rgb(28, 35, 52), rgb(148, 163, 184),
      rgb(110, 170, 255), rgb(86, 156, 214), rgb(156, 220, 254), rgb(206, 145, 120), rgb(106, 153, 85),
      rgb(206, 145, 120), rgb(181, 206, 168), rgb(197, 134, 192), rgb(156, 220, 254), rgb(220, 220, 170),
      rgb(197, 134, 192), rgb(110, 170, 255), rgb(115, 201, 145), rgb(244, 112, 103), rgb(229, 192, 123),
      rgb(7, 11, 20), rgb(30, 38, 56));

  public static Palette forBackground(Rgb background) {
    return background.isDark() ? DARK : LIGHT;
  }

  public Rgb speaker(String name) {
    return switch (name) {
      case "You" -> you;
      case "AI" -> ai;
      case "Error" -> error;
      default -> note;
    };
  }

  public static Rgb rgb(int red, int green, int blue) {
    return new Rgb(red, green, blue);
  }
}
