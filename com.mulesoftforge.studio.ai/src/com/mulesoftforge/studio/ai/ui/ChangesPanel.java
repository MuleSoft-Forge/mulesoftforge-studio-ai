package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.CompareUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IWorkbenchPage;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.llm.Errors;

// Proposed file changes waiting for review. Hidden while there are none.
final class ChangesPanel {
  interface Listener {
    void note(String speaker, String text);

    // Told to the AI with the next message, e.g. "ACCEPTED and written: src/main/mule/api.xml".
    void reviewed(String note);

    // A change was accepted or rejected.
    void resolved();
  }

  private record Pending(EclipseProject project, FileChange change) {}

  private final Composite root;
  private final IWorkbenchPage page;
  private final Listener listener;
  private final List<Pending> pending = new ArrayList<>();
  private final Group group;
  private final Table table;

  ChangesPanel(Composite root, IWorkbenchPage page, Listener listener) {
    this.root = root;
    this.page = page;
    this.listener = listener;
    group = new Group(root, SWT.NONE);
    group.setText("Proposed changes — nothing is written until you accept");
    group.setLayout(new GridLayout(1, false));
    group.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

    table = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
    table.setHeaderVisible(true);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, false);
    tableData.heightHint = table.getItemHeight() * 4;
    table.setLayoutData(tableData);
    TableColumn file = new TableColumn(table, SWT.LEFT);
    file.setText("File");
    file.setWidth(320);
    TableColumn change = new TableColumn(table, SWT.LEFT);
    change.setText("Change");
    change.setWidth(110);
    table.addListener(SWT.DefaultSelection, e -> review());

    Composite buttons = new Composite(group, SWT.NONE);
    buttons.setLayout(new GridLayout(5, false));
    button(buttons, "Review diff", this::review);
    button(buttons, "Accept", () -> resolve(selected(), true));
    button(buttons, "Reject", () -> resolve(selected(), false));
    button(buttons, "Accept all", () -> resolve(new ArrayList<>(pending), true));
    button(buttons, "Reject all", () -> resolve(new ArrayList<>(pending), false));
    refresh();
  }

  int size() {
    return pending.size();
  }

  void clear() {
    pending.clear();
    refresh();
  }

  void add(EclipseProject project, List<FileChange> changes) {
    if (changes.isEmpty()) {
      return;
    }
    StringBuilder text = new StringBuilder();
    for (FileChange change : changes) {
      // A newer proposal for the same file replaces the older, unreviewed one.
      pending.removeIf(p -> p.project().name().equals(project.name()) && p.change().path().equals(change.path()));
      pending.add(new Pending(project, change));
      text.append("\n  ").append(change.summary());
    }
    listener.note("Proposed", changes.size() + " file(s), review below:" + text);
    refresh();
  }

  private static void button(Composite parent, String text, Runnable action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(text);
    button.addListener(SWT.Selection, e -> action.run());
  }

  private List<Pending> selected() {
    List<Pending> out = new ArrayList<>();
    for (int index : table.getSelectionIndices()) {
      out.add(pending.get(index));
    }
    if (out.isEmpty() && pending.size() == 1) {
      out.add(pending.get(0));
    }
    return out;
  }

  private void review() {
    List<Pending> chosen = selected();
    if (chosen.isEmpty()) {
      listener.note("Note", "Select a proposed file to review.");
      return;
    }
    for (Pending p : chosen) {
      CompareUI.openCompareEditor(ChangeCompareInput.of(p.change()));
    }
  }

  private void resolve(List<Pending> chosen, boolean accept) {
    if (chosen.isEmpty()) {
      listener.note("Note", "Select a proposed file first.");
      return;
    }
    List<String> done = new ArrayList<>();
    for (Pending p : chosen) {
      String path = p.change().path();
      if (accept) {
        try {
          p.project().apply(p.change(), page);
          p.change().markAccepted();
          listener.reviewed("ACCEPTED and written: " + path);
          done.add("Accepted " + path);
        } catch (Exception e) {
          Activator.logError("MuleSoftForge AI could not write " + path, e);
          listener.note("Error", "Not written: " + Errors.describe(e));
          continue;
        }
      } else {
        p.change().markRejected();
        listener.reviewed("REJECTED, not written: " + path);
        done.add("Rejected " + path);
      }
      pending.remove(p);
    }
    if (!done.isEmpty()) {
      listener.note("Review", String.join("\n", done)
          + (accept ? "\nUndo with right-click > Replace With > Local History." : ""));
    }
    refresh();
    listener.resolved();
  }

  private void refresh() {
    table.removeAll();
    for (Pending p : pending) {
      TableItem item = new TableItem(table, SWT.NONE);
      FileChange change = p.change();
      String what = change.isNew() ? "new file" : "+" + change.stats().added() + " -" + change.stats().removed();
      item.setText(new String[] {change.path(), what});
    }
    boolean visible = !pending.isEmpty();
    ((GridData) group.getLayoutData()).exclude = !visible;
    group.setVisible(visible);
    root.layout(true, true);
  }
}
