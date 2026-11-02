package com.mulesoftforge.studio.ai.ui;

import static com.mulesoftforge.studio.ai.ui.PageBrowser.js;
import static com.mulesoftforge.studio.ai.ui.PageBrowser.parse;

import java.util.List;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;

import com.mulesoftforge.studio.ai.render.ComposerPage;

// The composer as a page in Eclipse's browser. Java keeps a copy of the message, model and include-file state,
// updated by the page's events, so reads never wait on the browser.
final class HtmlComposerUi implements ComposerUi {
  private final Composite root;
  private final PageBrowser page;
  private String text = "";
  private String model = "";
  private boolean includeFile = true;

  // Throws when the browser engine cannot start; Composer then uses the SWT controls.
  HtmlComposerUi(Composite root, Events events) {
    this.root = root;
    page = new PageBrowser(root, "msfComposer", 96, 320, (kind, value) -> handle(kind, value, events));
    page.load(ComposerPage.page(PageBrowser.theme(), Composer.PLACEHOLDER, Composer.CONTEXT_HELP, Dictation.hint()));
  }

  private void handle(String kind, String value, Events events) {
    switch (kind) {
      case ComposerPage.TEXT -> text = value;
      case ComposerPage.ENTER -> {
        text = value;
        events.enterPressed();
      }
      case ComposerPage.SEND -> {
        text = value;
        events.sendPressed();
      }
      case ComposerPage.MODE -> {
        int index = parse(value);
        if (index >= 0) {
          events.modeChosen(index);
        }
      }
      case ComposerPage.MODEL_MENU -> {
        Point where = page.toDisplay(value);
        if (where != null) {
          events.modelMenu(where);
        }
      }
      case ComposerPage.INCLUDE -> {
        includeFile = Boolean.parseBoolean(value);
        events.includeFileChanged(includeFile);
      }
      case ComposerPage.CONTEXT -> {
        Point where = page.toDisplay(value);
        if (where != null) {
          events.contextMenu(where);
        }
      }
      case ComposerPage.NEW_CHAT -> events.newChat();
      case ComposerPage.MIC -> events.dictate();
      case ComposerPage.REMOVE -> events.removeAttached(parse(value));
      default -> {
        // The page only sends the kinds above.
      }
    }
  }

  @Override
  public Control control() {
    return page.browser();
  }

  @Override
  public String text() {
    return text;
  }

  @Override
  public void setText(String value) {
    text = value == null ? "" : value;
    page.run("msfSetText(" + js(text) + ")");
  }

  @Override
  public void focus() {
    page.browser().setFocus();
    page.run("msfFocus()");
  }

  @Override
  public void paste() {
    Control focus = root.getDisplay().getFocusControl();
    if (focus instanceof Text other) {
      other.paste();
      return;
    }
    if (focus instanceof Combo combo) {
      combo.paste();
      return;
    }
    if (focus instanceof StyledText styled) {
      styled.paste();
      return;
    }
    Clipboard clipboard = new Clipboard(root.getDisplay());
    try {
      if (clipboard.getContents(TextTransfer.getInstance()) instanceof String pasted && !pasted.isEmpty()) {
        page.browser().setFocus();
        page.run("msfInsert(" + js(pasted) + ")");
      }
    } finally {
      clipboard.dispose();
    }
  }

  @Override
  public void setModes(List<String> labels, int selected, String help) {
    page.run("msfModes(" + js(labels) + "," + selected + "," + js(help) + ")");
  }

  @Override
  public void selectMode(int index) {
    page.run("msfSelectMode(" + index + ")");
  }

  @Override
  public void setModels(List<String> models, String current) {
    model = current;
    page.run("msfModels(" + js(models) + "," + js(current) + ")");
  }

  @Override
  public String modelText() {
    return model;
  }

  @Override
  public void setModelText(String value) {
    model = value;
    page.run("msfModelText(" + js(value) + ")");
  }

  @Override
  public void setModelHelp(String help) {
    page.run("msfModelHelp(" + js(help) + ")");
  }

  @Override
  public boolean includeFile() {
    return includeFile;
  }

  @Override
  public void showAttached(List<String> labels, String help) {
    page.run("msfAttached(" + js(labels) + "," + js(help) + ")");
  }

  @Override
  public void setBusy(boolean busy) {
    page.run("msfBusy(" + busy + ")");
  }

  @Override
  public void setCancelling() {
    page.run("msfCancelling()");
  }

  @Override
  public void applyTheme() {
    page.run("msfTheme(" + js(ComposerPage.themeVars(PageBrowser.theme())) + ")");
  }
}
