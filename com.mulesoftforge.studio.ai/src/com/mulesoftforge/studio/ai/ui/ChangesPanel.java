package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.CompareUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IWorkbenchPage;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.render.ChangesPage;
import com.mulesoftforge.studio.ai.render.HtmlPage;

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
  // The card in Eclipse's browser, or null when the plain table is shown instead.
  private final PageBrowser card;
  private Group group;
  private Table table;

  // html: show the changes as a card in Eclipse's browser, falling back to a table if it cannot start.
  ChangesPanel(Composite root, IWorkbenchPage page, boolean html, Listener listener) {
    this.root = root;
    this.page = page;
    this.listener = listener;
    card = html ? createCard(root) : null;
    if (card == null) {
      createTable();
    }
    refresh();
  }

  private PageBrowser createCard(Composite parent) {
    try {
      PageBrowser browser = new PageBrowser(parent, "msfChanges", 60, 260, this::cardClicked);
      browser.load(ChangesPage.page(PageBrowser.theme()));
      return browser;
    } catch (Throwable e) {
      Activator.logError("Rich proposed-changes panel unavailable; using the plain one", e);
      return null;
    }
  }

  private void cardClicked(String kind, String value) {
    int index = PageBrowser.parse(value);
    List<Pending> one = index >= 0 && index < pending.size() ? List.of(pending.get(index)) : List.of();
    switch (kind) {
      case ChangesPage.REVIEW -> one.forEach(p -> CompareUI.openCompareEditor(ChangeCompareInput.of(p.change())));
      case ChangesPage.ACCEPT -> resolve(one, true);
      case ChangesPage.REJECT -> resolve(one, false);
      case ChangesPage.ACCEPT_ALL -> resolve(new ArrayList<>(pending), true);
      case ChangesPage.REJECT_ALL -> resolve(new ArrayList<>(pending), false);
      default -> {
        // The page only sends the kinds above.
      }
    }
  }

  void applyTheme() {
    if (card != null) {
      card.run("msfTheme(" + PageBrowser.js(HtmlPage.themeVars(PageBrowser.theme())) + ")");
    }
  }

  private void createTable() {
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
    if (!accept) {
      List<String> done = new ArrayList<>();
      for (Pending p : chosen) {
        p.change().markRejected();
        listener.reviewed("REJECTED, not written: " + p.change().path());
        done.add("Rejected " + p.change().path());
        pending.remove(p);
      }
      listener.note("Review", String.join("\n", done));
      refresh();
      listener.resolved();
      return;
    }

    // Preflight on the UI thread (dirty editors, baseline mismatch), then one workspace job for all writes.
    List<Pending> ready = new ArrayList<>();
    for (Pending p : chosen) {
      try {
        String problem = p.project().preflight(p.change(), page);
        if (problem != null) {
          listener.note("Error", "Not written: " + problem);
          continue;
        }
        ready.add(p);
      } catch (Exception e) {
        Activator.logFailure("MuleSoftForge Studio AI could not check " + p.change().path(), e);
        listener.note("Error", "Not written: " + Errors.describe(e));
      }
    }
    if (ready.isEmpty()) {
      return;
    }
    Control control = card != null ? card.browser() : table;
    Display display = control.getDisplay();
    // One workspace job per project so scheduling rules stay correct.
    java.util.Map<EclipseProject, List<Pending>> byProject = new java.util.LinkedHashMap<>();
    for (Pending p : ready) {
      byProject.computeIfAbsent(p.project(), k -> new ArrayList<>()).add(p);
    }
    for (var entry : byProject.entrySet()) {
      List<Pending> batch = entry.getValue();
      List<FileChange> changes = batch.stream().map(Pending::change).toList();
      entry.getKey().applyAsync(changes, page, error -> Ui.async(display, control, () -> {
        if (error != null) {
          Activator.logFailure("MuleSoftForge Studio AI could not write changes", error);
          listener.note("Error", "Not written: " + Errors.describe(error));
          return;
        }
        List<String> done = new ArrayList<>();
        for (Pending p : batch) {
          p.change().markAccepted();
          listener.reviewed("ACCEPTED and written: " + p.change().path());
          done.add("Accepted " + p.change().path());
          pending.remove(p);
        }
        listener.note("Review", String.join("\n", done)
            + "\nUndo with right-click > Replace With > Local History.");
        refresh();
        listener.resolved();
      }));
    }
  }

  private void refresh() {
    boolean visible = !pending.isEmpty();
    if (card != null) {
      List<ChangesPage.Row> rows = new ArrayList<>();
      for (Pending p : pending) {
        FileChange change = p.change();
        String path = change.path();
        int slash = path.lastIndexOf('/');
        rows.add(new ChangesPage.Row(path.substring(slash + 1), slash < 0 ? "" : path.substring(0, slash + 1),
            change.isNew() ? 0 : change.stats().added(), change.isNew() ? 0 : change.stats().removed(),
            change.isNew()));
      }
      card.run("msfRows(" + PageBrowser.js(rows) + ")");
      card.setShown(visible);
      return;
    }
    table.removeAll();
    for (Pending p : pending) {
      TableItem item = new TableItem(table, SWT.NONE);
      FileChange change = p.change();
      String what = change.isNew() ? "new file" : "+" + change.stats().added() + " -" + change.stats().removed();
      item.setText(new String[] {change.path(), what});
    }
    ((GridData) group.getLayoutData()).exclude = !visible;
    group.setVisible(visible);
    root.layout(true, true);
  }
}
