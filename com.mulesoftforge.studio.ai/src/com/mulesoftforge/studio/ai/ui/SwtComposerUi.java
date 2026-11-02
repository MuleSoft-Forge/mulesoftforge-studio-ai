package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;

import com.mulesoftforge.studio.ai.Activator;

// The composer as native SWT controls, used when the rich chat view is off or the browser cannot start.
final class SwtComposerUi implements ComposerUi {
  private final Composite root;
  private final Composite panel;
  private final Composite attachedRow;
  private final Label attachedLabel;
  private final Text input;
  private final Combo modePicker;
  private final Combo modelPicker;
  private final ColorButton send;
  private final Button includeFile;

  SwtComposerUi(Composite root, Events events) {
    this.root = root;

    // A pale panel behind the message box and its controls; text boxes and pickers keep their own white.
    panel = new Composite(root, SWT.NONE);
    GridLayout panelLayout = new GridLayout(1, false);
    panelLayout.marginWidth = 6;
    panelLayout.marginHeight = 6;
    panel.setLayout(panelLayout);
    panel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    ThemeStyles.applyClass(panel, "MsfPanel");
    panel.setBackgroundMode(SWT.INHERIT_DEFAULT);

    attachedRow = new Composite(panel, SWT.NONE);
    GridLayout attachedLayout = new GridLayout(2, false);
    attachedLayout.marginHeight = 0;
    attachedRow.setLayout(attachedLayout);
    attachedRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    attachedLabel = new Label(attachedRow, SWT.WRAP);
    attachedLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Link removeAttached = new Link(attachedRow, SWT.NONE);
    removeAttached.setText("<a>Remove</a>");
    removeAttached.setToolTipText("Do not send these with the next message");
    removeAttached.addListener(SWT.Selection, e -> events.removeAttached(-1));

    input = new Text(panel, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
    GridData inputData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
    inputData.heightHint = input.getLineHeight() * 4;
    input.setLayoutData(inputData);
    input.setMessage(Composer.PLACEHOLDER);
    input.addListener(SWT.KeyDown, e -> {
      boolean enter = e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR;
      if (enter && (e.stateMask & SWT.SHIFT) == 0) {
        e.doit = false;
        events.enterPressed();
      }
    });

    Composite actions = new Composite(panel, SWT.NONE);
    GridLayout actionsLayout = new GridLayout(8, false);
    actionsLayout.verticalSpacing = 2;
    actions.setLayout(actionsLayout);
    actions.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    modePicker = new Combo(actions, SWT.DROP_DOWN | SWT.READ_ONLY);
    modePicker.addListener(SWT.Selection, e -> events.modeChosen(modePicker.getSelectionIndex()));

    modelPicker = new Combo(actions, SWT.DROP_DOWN);
    GridData pickerData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    pickerData.widthHint = 200;
    modelPicker.setLayoutData(pickerData);
    modelPicker.addListener(SWT.Selection, e -> events.modelChosen(modelPicker.getText()));
    modelPicker.addListener(SWT.DefaultSelection, e -> events.modelChosen(modelPicker.getText()));
    modelPicker.addListener(SWT.FocusOut, e -> events.modelChosen(modelPicker.getText()));

    Button addContext = new Button(actions, SWT.PUSH);
    addContext.setText("+ Context");
    addContext.setToolTipText(Composer.CONTEXT_HELP);
    addContext.addListener(SWT.Selection, e -> {
      Rectangle bounds = addContext.getBounds();
      events.contextMenu(addContext.getParent().toDisplay(bounds.x, bounds.y + bounds.height));
    });
    includeFile = new Button(actions, SWT.CHECK);
    includeFile.setText("Include open file");
    includeFile.setSelection(true);
    includeFile.addListener(SWT.Selection, e -> events.includeFileChanged(includeFile.getSelection()));
    new Label(actions, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button clear = new Button(actions, SWT.PUSH);
    clear.setText("New chat");
    clear.addListener(SWT.Selection, e -> events.newChat());
    Button mic = new Button(actions, SWT.PUSH);
    mic.setImage(Activator.image("icons/mic16.png"));
    mic.setToolTipText(Dictation.hint());
    mic.getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        e.result = "Dictate";
      }
    });
    mic.addListener(SWT.Selection, e -> events.dictate());
    send = new ColorButton(actions);
    send.setText("Send");
    send.setTone(ColorButton.Tone.GO);
    send.addListener(SWT.Selection, e -> events.sendPressed());
  }

  @Override
  public Control control() {
    return panel;
  }

  @Override
  public String text() {
    return input.getText();
  }

  @Override
  public void setText(String text) {
    input.setText(text);
    input.setSelection(text.length());
  }

  @Override
  public void focus() {
    input.setFocus();
    input.setSelection(input.getText().length());
  }

  @Override
  public void paste() {
    Control focus = root.getDisplay().getFocusControl();
    if (focus instanceof Text text) {
      text.paste();
    } else if (focus instanceof Combo combo) {
      combo.paste();
    } else if (focus instanceof StyledText styled) {
      styled.paste();
    } else {
      input.paste();
    }
  }

  @Override
  public void setModes(List<String> labels, int selected, String help) {
    modePicker.setItems(labels.toArray(String[]::new));
    modePicker.select(selected);
    modePicker.setToolTipText(help);
  }

  @Override
  public void selectMode(int index) {
    modePicker.select(index);
  }

  @Override
  public void setModels(List<String> models, String current) {
    modelPicker.setItems(models.toArray(String[]::new));
    modelPicker.setText(current);
  }

  @Override
  public String modelText() {
    return modelPicker.isDisposed() ? "" : modelPicker.getText();
  }

  @Override
  public void setModelText(String model) {
    modelPicker.setText(model);
  }

  @Override
  public void setModelHelp(String help) {
    modelPicker.setToolTipText(help);
  }

  @Override
  public boolean includeFile() {
    return includeFile.getSelection();
  }

  @Override
  public void showAttached(List<String> labels, String help) {
    boolean any = !labels.isEmpty();
    if (any) {
      attachedLabel.setText("Attached: " + String.join(", ", labels));
      attachedLabel.setToolTipText(help);
    }
    ((GridData) attachedRow.getLayoutData()).exclude = !any;
    attachedRow.setVisible(any);
    root.layout(true, true);
  }

  @Override
  public void setBusy(boolean busy) {
    send.setEnabled(true);
    send.setText(busy ? "Cancel" : "Send");
    send.setTone(busy ? ColorButton.Tone.STOP : ColorButton.Tone.GO);
    modelPicker.setEnabled(!busy);
    modePicker.setEnabled(!busy);
  }

  @Override
  public void setCancelling() {
    send.setEnabled(false);
    send.setText("Cancelling…");
  }

  @Override
  public void applyTheme() {
    if (panel.isDisposed()) {
      return;
    }
    ThemeStyles.applyClass(panel, "MsfPanel");
    send.redraw();
  }
}
