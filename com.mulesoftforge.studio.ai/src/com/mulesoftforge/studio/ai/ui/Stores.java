package com.mulesoftforge.studio.ai.ui;

import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.history.ChatFolders;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.RawLog;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.UserFolder;

// The cost ledger is shared by every workspace (UserFolder/usage.jsonl), each line tagged with this workspace.
// Saved chats and raw logs stay in <workspace>/.metadata/.plugins/com.mulesoftforge.studio.ai/, Eclipse's private
// per-workspace folder for this plugin, unless the user shares them; then they move to the user folder. Never
// inside a Mule project.
final class Stores {
  private static ChatFolders folders;
  private static Boolean placed;
  private static UsageLog usage;

  private Stores() {}

  static synchronized Path workspaceRoot() {
    return Activator.getDefault().getStateLocation().toFile().toPath();
  }

  // The Studio workspace folder, which tags chats and ledger lines.
  static String workspace() {
    return ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
  }

  // Where chats are kept now.
  static synchronized Path root() {
    return folders().root(placed());
  }

  static synchronized ChatStore chats() {
    return folders().chats(placed());
  }

  static synchronized RawLog raw() {
    return folders().raw(placed());
  }

  static synchronized boolean mine(ChatRecord.Summary chat) {
    return folders().mine(chat);
  }

  static synchronized UsageLog usage() {
    if (usage == null) {
      usage = new UsageLog(UserFolder.path().resolve(UsageLog.FILE_NAME), workspace());
      try {
        usage.importLegacy(workspaceRoot().resolve(UsageLog.FILE_NAME), workspace());
      } catch (Exception e) {
        Activator.logFailure("Could not copy this workspace's cost ledger to " + UserFolder.path(), e);
      }
    }
    return usage;
  }

  private static ChatFolders folders() {
    if (folders == null) {
      folders = new ChatFolders(workspaceRoot(), UserFolder.path(), workspace());
    }
    return folders;
  }

  // Moves this workspace's chats when sharing was turned on or off, here or in another window.
  private static boolean placed() {
    boolean shared = AiSettings.shareChats();
    if (placed == null || placed != shared) {
      try {
        folders().place(shared);
        placed = shared;
      } catch (Exception e) {
        Activator.logFailure("Could not move this workspace's saved chats", e);
        return placed == null ? shared : placed;
      }
    }
    return shared;
  }
}
