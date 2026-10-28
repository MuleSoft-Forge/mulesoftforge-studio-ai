package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import com.mulesoftforge.studio.ai.render.MarkdownRenderer;

// Turns a clicked element's data ("copy:2", "insert:0", "url:https://…", "cmd:build-plan") into an action.
final class Clicks {
  private Clicks() {}

  static void dispatch(String data, List<String> codeBlocks, Transcript.Actions actions) {
    int colon = data.indexOf(':');
    if (colon < 0) {
      return;
    }
    String kind = data.substring(0, colon);
    String value = data.substring(colon + 1);
    switch (kind) {
      case MarkdownRenderer.ACTION_COPY, MarkdownRenderer.ACTION_INSERT -> {
        int index;
        try {
          index = Integer.parseInt(value);
        } catch (NumberFormatException e) {
          return;
        }
        if (index < 0 || index >= codeBlocks.size()) {
          return;
        }
        if (kind.equals(MarkdownRenderer.ACTION_COPY)) {
          actions.copy(codeBlocks.get(index));
        } else {
          actions.insert(codeBlocks.get(index));
        }
      }
      case "url" -> {
        if (value.startsWith("https://") || value.startsWith("http://")) {
          actions.open(value);
        }
      }
      case "cmd" -> actions.command(value);
      default -> {
        // Unknown clicks are ignored; the page only produces the kinds above.
      }
    }
  }
}
