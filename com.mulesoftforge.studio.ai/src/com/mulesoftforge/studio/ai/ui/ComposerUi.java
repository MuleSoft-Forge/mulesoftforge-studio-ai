package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

// What the composer shows: the message box, attachments, mode and model pickers, and the buttons. Composer owns
// the state and logic; this is only the widgets. Two implementations: native SWT controls, and an HTML page in
// Eclipse's browser.
interface ComposerUi {
  // What the user did. Composer decides what each means, for example Send while busy cancels.
  interface Events {
    void sendPressed();

    void enterPressed();

    void newChat();

    // where is the screen point to open the + Context menu at.
    void contextMenu(Point where);

    void modeChosen(int index);

    void modelChosen(String model);

    // Show the model list as a menu at this display point.
    void modelMenu(Point where);

    void includeFileChanged(boolean include);

    void dictate();

    // index -1 removes them all.
    void removeAttached(int index);
  }

  Control control();

  String text();

  // Replaces the message and puts the caret at its end.
  void setText(String text);

  void focus();

  // Cmd/Ctrl+V when the view's paste handler runs and the clipboard holds no image: pastes into the focused
  // control, or into the message box when nothing that can take text has focus.
  void paste();

  void setModes(List<String> labels, int selected, String help);

  void selectMode(int index);

  void setModels(List<String> models, String current);

  String modelText();

  void setModelText(String model);

  void setModelHelp(String help);

  boolean includeFile();

  // Empty hides the attachment row.
  void showAttached(List<String> labels, String help);

  void setBusy(boolean busy);

  void setCancelling();

  void applyTheme();
}
