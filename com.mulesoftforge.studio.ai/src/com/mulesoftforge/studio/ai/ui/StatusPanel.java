package com.mulesoftforge.studio.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

// The column beside the transcript: the model in use, its list price, and what chats have cost.
final class StatusPanel {
  private final Composite panel;
  private final Label model;
  private final Label price;
  private final Label chatCost;
  private final Label todayCost;
  private final Label monthCost;

  StatusPanel(Composite parent) {
    panel = new Composite(parent, SWT.NONE);
    panel.setBackground(TranscriptStyles.panel(parent));
    panel.setBackgroundMode(SWT.INHERIT_DEFAULT);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 8;
    layout.verticalSpacing = 3;
    panel.setLayout(layout);

    heading("Model");
    model = value(false);
    price = value(true);
    Label gap = new Label(panel, SWT.NONE);
    gap.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    heading("Cost");
    chatCost = value(false);
    todayCost = value(false);
    monthCost = value(false);
  }

  Control control() {
    return panel;
  }

  // price is null while it is being looked up.
  void showModel(String id, String priceText, String priceTable) {
    model.setText(id == null || id.isBlank() ? "(none chosen)" : id);
    model.setToolTipText(priceTable);
    price.setText(priceText == null ? "Checking price…" : priceText);
    price.setToolTipText(priceTable);
    relayout();
  }

  void showCost(String chat, String today, String month, String details) {
    chatCost.setText("This chat: " + chat);
    todayCost.setText("Today: " + today);
    monthCost.setText("This month: " + month);
    for (Label label : new Label[] {chatCost, todayCost, monthCost}) {
      label.setToolTipText(details);
    }
    relayout();
  }

  boolean isDisposed() {
    return panel.isDisposed();
  }

  private void heading(String text) {
    Label label = new Label(panel, SWT.NONE);
    label.setText(text);
    label.setFont(TranscriptStyles.bold());
  }

  // Wrapping labels need a width hint, or GridLayout sizes them to one long line.
  private Label value(boolean italic) {
    Label label = new Label(panel, SWT.WRAP);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 120;
    label.setLayoutData(data);
    if (italic) {
      label.setFont(TranscriptStyles.italic());
    }
    return label;
  }

  private void relayout() {
    panel.layout(true, true);
  }
}
