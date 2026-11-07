package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.swt.widgets.Control;

import com.mulesoftforge.studio.ai.edit.TodoItem;
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

    // Put text in the composer without sending (shift-click on a follow-up choice, quote, starter).
    default void fillComposer(String text) {}

    // Prepend a quoted block to the composer.
    default void quote(String text) {}
  }

  Control control();

  void clear();

  // Plain text. context is a short line under the message (attachments, project), or null.
  void message(String speaker, String text, String context);

  void markdown(String speaker, String markdown);

  void step(String text);

  // A model call's reasoning among the steps: the summary line ("Thought for 2.3 s"), with the reasoning text
  // folded under it when there is any.
  default void thought(String summary, String text) {
    step(summary);
  }

  // One line under the steps, replaced on each call, for the model call in progress, with the answer text so far
  // under it. A null status removes it; so does the next step or message.
  void live(String status, String preview);

  // A button under the last message, e.g. "Build this plan".
  void action(String label, String id);

  // A checklist of changes under the last message, and a button. The button sends
  // "offer:<id>:<ticked indices, comma-separated>" as a command. suggested (Ask mode): collapsed and nothing
  // ticked, since the user asked a question rather than for changes; otherwise all ticked.
  void offer(String id, List<String> items, boolean suggested);

  // Greys out one offer row after the user saved a Don't suggest rule for it.
  default void ignoreOffer(String id, int index) {}

  // Clarifying question with clickable answers. Click sends ask:<id>:<index>; shift-click fills the composer.
  void ask(String id, String question, List<String> choices);

  // Live task list from update_todo_list. Empty clears it.
  void todos(List<TodoItem> items);

  // One model call of a turn, as shown in a raw block.
  record RawCall(String heading, String request, String response) {}

  // The model calls of a turn, collapsed under its answer. open is a command that opens the whole raw log.
  void raw(String title, List<RawCall> calls, String open);

  // Hides or shows every raw block, including ones added later.
  void showRaw(boolean show);

  // Re-apply colours after a Studio theme switch.
  default void applyTheme(Palette palette, Palette.Rgb background, Palette.Rgb foreground) {}

  // The name shown above a message. Speakers stay short ids because saved chats store them.
  static String label(String speaker) {
    return "AI".equals(speaker) ? "MuleSoftForge Studio AI" : speaker;
  }
}
