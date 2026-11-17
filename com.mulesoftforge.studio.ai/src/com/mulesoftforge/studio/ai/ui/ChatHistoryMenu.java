package com.mulesoftforge.studio.ai.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// "History ▾": recent saved chats, each with Open, Rename, Export as Markdown, Open raw log and Delete.
final class ChatHistoryMenu {
  interface Host {
    // Id of the chat on screen, or null before its first message.
    String currentChatId();

    boolean busy();

    void newChat();

    void open(String id);

    void renamed(String id, String title);

    void deleted(String id);

    void note(String speaker, String text);
  }

  private static final int SHOWN = 25;
  // Studio's text editor holds a document several times over, and Studio's default heap is 1 GB.
  private static final long EDITOR_LIMIT = 20_000_000;

  private final Shell shell;
  private final Host host;

  ChatHistoryMenu(Shell shell, Host host) {
    this.shell = shell;
    this.host = host;
  }

  void show(Control anchor) {
    List<ChatRecord.Summary> chats;
    try {
      chats = Stores.chats().list();
    } catch (Exception e) {
      host.note("Error", "Could not read saved chats: " + Errors.describe(e));
      return;
    }
    String current = host.currentChatId();
    boolean busy = host.busy();
    Menu menu = new Menu(anchor);
    Menus.disposeOnHide(menu);
    action(menu, "New chat", !busy, host::newChat);
    if (current != null && Stores.raw().exists(current)) {
      action(menu, "Open raw log of this chat", true, () -> openRaw(current));
    }
    new MenuItem(menu, SWT.SEPARATOR);
    List<ChatRecord.Summary> mine = chats.stream().filter(Stores::mine).toList();
    List<ChatRecord.Summary> others = chats.stream().filter(c -> !Stores.mine(c)).toList();
    if (mine.isEmpty()) {
      action(menu, AiSettings.saveChats() ? "No saved chats in this workspace yet" : "Saving chats is off (Settings)",
          false, null);
    }
    addChats(menu, mine, current, busy, false);
    if (!others.isEmpty()) {
      new MenuItem(menu, SWT.SEPARATOR);
      MenuItem item = new MenuItem(menu, SWT.CASCADE);
      item.setText("Other workspaces (" + others.size() + ")");
      Menu sub = new Menu(item);
      item.setMenu(sub);
      addChats(sub, others, current, busy, true);
    }
    Point location = anchor.toDisplay(0, anchor.getSize().y);
    menu.setLocation(location);
    menu.setVisible(true);
  }

  private void addChats(Menu menu, List<ChatRecord.Summary> chats, String current, boolean busy,
      boolean showWorkspace) {
    DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM HH:mm");
    for (ChatRecord.Summary chat : chats.subList(0, Math.min(chats.size(), SHOWN))) {
      MenuItem item = new MenuItem(menu, SWT.CASCADE);
      String date = chat.updated() == null ? "" : LocalDateTime.ofInstant(Instant.parse(chat.updated()),
          ZoneId.systemDefault()).format(when);
      String cost = chat.calls() == 0 ? "" : "  ·  " + (chat.estimated() ? "~" : "") + CostMeter.usd(chat.usd());
      String where = showWorkspace && chat.workspace() != null
          ? Path.of(chat.workspace()).getFileName() + " / " : "";
      boolean isCurrent = chat.id().equals(current);
      item.setText((isCurrent ? "● " : "") + chat.title() + "  —  " + where
          + (chat.project() == null ? "no project" : chat.project()) + "  ·  " + date + cost);
      Menu actions = new Menu(item);
      item.setMenu(actions);
      action(actions, "Open", !busy, () -> host.open(chat.id()));
      action(actions, "Rename…", true, () -> rename(chat));
      action(actions, "Export as Markdown…", true, () -> export(chat));
      action(actions, Stores.raw().exists(chat.id()) ? "Open raw log" : "No raw log (Raw was off)",
          Stores.raw().exists(chat.id()), () -> openRaw(chat.id()));
      new MenuItem(actions, SWT.SEPARATOR);
      action(actions, "Delete", !busy || !isCurrent, () -> delete(chat));
    }
    if (chats.size() > SHOWN) {
      action(menu, "(" + (chats.size() - SHOWN) + " older chats not shown)", false, null);
    }
  }

  private static void action(Menu menu, String text, boolean enabled, Runnable action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(text);
    item.setEnabled(enabled);
    if (action != null) {
      item.addListener(SWT.Selection, e -> action.run());
    }
  }

  private void rename(ChatRecord.Summary chat) {
    InputDialog dialog = new InputDialog(shell, "Rename chat", "Title:", chat.title(),
        value -> value == null || value.isBlank() ? "Enter a title." : null);
    if (dialog.open() != Window.OK) {
      return;
    }
    try {
      Stores.chats().rename(chat.id(), dialog.getValue());
      host.renamed(chat.id(), dialog.getValue().strip());
    } catch (Exception e) {
      host.note("Error", "Could not rename chat: " + Errors.describe(e));
    }
  }

  private void export(ChatRecord.Summary chat) {
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setText("Export chat as Markdown");
    dialog.setFilterExtensions(new String[] {"*.md"});
    dialog.setFileName(chat.title().replaceAll("[^A-Za-z0-9 _-]", "").strip().replace(' ', '-') + ".md");
    dialog.setOverwrite(true);
    String target = dialog.open();
    if (target == null) {
      return;
    }
    try {
      Files.writeString(Path.of(target), ChatStore.toMarkdown(Stores.chats().load(chat.id())));
      host.note("Note", "Exported \"" + chat.title() + "\" to " + target);
    } catch (Exception e) {
      host.note("Error", "Could not export chat: " + Errors.describe(e));
    }
  }

  private void openRaw(String id) {
    openRawLog(id, shell, host::note);
  }

  // Writes a readable copy off the UI thread, then opens it in Studio's text editor; the file is outside the
  // workspace projects. A copy too large for Studio's heap opens in the system's app for .txt files instead.
  static void openRawLog(String id, Control anchor, BiConsumer<String, String> note) {
    Display display = anchor.getDisplay();
    Job job = Job.create("Write MuleSoftForge Studio AI raw log", monitor -> {
      try {
        Path readable = Stores.raw().writeReadable(id);
        long size = Files.size(readable);
        Ui.async(display, anchor, () -> showRawLog(readable, size, note));
      } catch (Exception e) {
        Ui.async(display, anchor, () -> note.accept("Error", "Could not open the raw log: " + Errors.describe(e)));
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  private static void showRawLog(Path readable, long size, BiConsumer<String, String> note) {
    try {
      if (size > EDITOR_LIMIT) {
        if (!Program.launch(readable.toString())) {
          note.accept("Note", "The raw log is " + size / 1_000_000 + " MB, too large to open in Studio: "
              + readable);
        }
        return;
      }
      IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
      IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(readable.toUri()));
    } catch (Exception e) {
      note.accept("Error", "Could not open the raw log: " + Errors.describe(e));
    }
  }

  private void delete(ChatRecord.Summary chat) {
    if (!MessageDialog.openConfirm(shell, "Delete chat", "Delete \"" + chat.title() + "\"?"
        + "\nIts cost stays in the spending totals.")) {
      return;
    }
    try {
      Stores.chats().delete(chat.id());
      Stores.raw().delete(chat.id());
      host.deleted(chat.id());
    } catch (Exception e) {
      host.note("Error", "Could not delete chat: " + Errors.describe(e));
    }
  }
}
