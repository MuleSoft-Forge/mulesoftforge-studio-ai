package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.swt.widgets.Control;

import com.mulesoftforge.studio.ai.render.Palette;

// The conversation area. Two implementations: a rich HTML one in Eclipse's browser, and a plain
// StyledText one used when the browser is unavailable or turned off.
interface Transcript {
  // What a click in the transcript asks the view to do.
  interface Actions {
    void copy(String code);

    void insert(String code);

    void open(String url);

    void command(String id);
  }

  Control control();

  void clear();

  // Plain text. context is a short line under the message (attachments, project), or null.
  void message(String speaker, String text, String context);

  void markdown(String speaker, String markdown);

  void step(String text);

  // A button under the last message, e.g. "Build this plan".
  void action(String label, String id);

  // A checklist of changes under the last message, and a button. The button sends
  // "offer:<id>:<ticked indices, comma-separated>" as a command. suggested (Ask mode): collapsed and nothing
  // ticked, since the user asked a question rather than for changes; otherwise all ticked.
  void offer(String id, List<String> items, boolean suggested);

  // One model call of a turn, as shown in a raw block.
  record RawCall(String heading, String request, String response) {}

  // The model calls of a turn, collapsed under its answer. open is a command that opens the whole raw log.
  void raw(String title, List<RawCall> calls, String open);

  // Hides or shows every raw block, including ones added later.
  void showRaw(boolean show);

  // Re-apply colours after a Studio theme switch.
  default void applyTheme(Palette palette, Palette.Rgb background, Palette.Rgb foreground) {}
}
