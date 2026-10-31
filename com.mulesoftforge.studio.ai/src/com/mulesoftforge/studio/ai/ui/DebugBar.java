package com.mulesoftforge.studio.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import com.mulesoftforge.studio.ai.debug.DebugSnapshot;
import com.mulesoftforge.studio.ai.debug.DebugStop;

// The row above the message box while Studio's debugger is stopped: where it stopped, and a link that attaches the
// live event to the next message. Hidden the rest of the time.
final class DebugBar {
  private final Composite row;
  private final Label where;

  DebugBar(Composite parent, Runnable ask) {
    row = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginHeight = 2;
    layout.marginWidth = 8;
    row.setLayout(layout);
    GridData data = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
    data.exclude = true;
    row.setLayoutData(data);
    row.setVisible(false);
    ThemeStyles.applyClass(row, "MsfPanel");
    where = new Label(row, SWT.NONE);
    where.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Link link = new Link(row, SWT.NONE);
    link.setText("<a>Ask about this event</a>");
    link.setToolTipText("Attach the live event (payload, attributes, variables and any error) to your next message");
    link.addListener(SWT.Selection, e -> ask.run());
  }

  // stop is null when the debugger is not stopped at a processor.
  void show(DebugStop stop) {
    if (row.isDisposed()) {
      return;
    }
    boolean visible = stop != null;
    if (visible) {
      String text = (stop.error() != null ? "Debugger stopped on an error at " : "Debugger stopped at ")
          + DebugSnapshot.processorLabel(stop) + " in " + stop.flowName();
      where.setText(text.replace("&", "&&"));
      where.setToolTipText(stop.processorPath());
    }
    ((GridData) row.getLayoutData()).exclude = !visible;
    row.setVisible(visible);
    row.getParent().layout(true, true);
  }
}
