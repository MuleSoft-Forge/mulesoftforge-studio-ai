package com.mulesoftforge.studio.ai.ui;

import org.eclipse.swt.widgets.Control;

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
}
