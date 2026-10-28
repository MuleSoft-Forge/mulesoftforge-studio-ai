package com.mulesoftforge.studio.ai.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// "History ▾": recent saved chats, each with Open, Rename, Export as Markdown and Delete.
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
    action(menu, "New chat", !busy, host::newChat);
    new MenuItem(menu, SWT.SEPARATOR);
    if (chats.isEmpty()) {
      action(menu, AiSettings.saveChats() ? "No saved chats yet" : "Saving chats is off (Settings)", false, null);
    }
    DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM HH:mm");
    for (ChatRecord.Summary chat : chats.subList(0, Math.min(chats.size(), SHOWN))) {
      MenuItem item = new MenuItem(menu, SWT.CASCADE);
      String date = chat.updated() == null ? "" : LocalDateTime.ofInstant(Instant.parse(chat.updated()),
          ZoneId.systemDefault()).format(when);
      String cost = chat.calls() == 0 ? "" : "  ·  " + (chat.estimated() ? "~" : "") + CostMeter.usd(chat.usd());
      boolean isCurrent = chat.id().equals(current);
      item.setText((isCurrent ? "● " : "") + chat.title() + "  —  "
          + (chat.project() == null ? "no project" : chat.project()) + "  ·  " + date + cost);
      Menu actions = new Menu(item);
      item.setMenu(actions);
      action(actions, "Open", !busy, () -> host.open(chat.id()));
      action(actions, "Rename…", true, () -> rename(chat));
      action(actions, "Export as Markdown…", true, () -> export(chat));
      new MenuItem(actions, SWT.SEPARATOR);
      action(actions, "Delete", !busy || !isCurrent, () -> delete(chat));
    }
    if (chats.size() > SHOWN) {
      action(menu, "(" + (chats.size() - SHOWN) + " older chats not shown)", false, null);
    }
    Point location = anchor.toDisplay(0, anchor.getSize().y);
    menu.setLocation(location);
    menu.setVisible(true);
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

  private void delete(ChatRecord.Summary chat) {
    if (!MessageDialog.openConfirm(shell, "Delete chat", "Delete \"" + chat.title() + "\"?"
        + "\nIts cost stays in the spending totals.")) {
      return;
    }
    try {
      Stores.chats().delete(chat.id());
      host.deleted(chat.id());
    } catch (Exception e) {
      host.note("Error", "Could not delete chat: " + Errors.describe(e));
    }
  }
}
