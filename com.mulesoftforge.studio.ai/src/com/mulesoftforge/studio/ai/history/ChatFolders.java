package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

// Where this workspace's saved chats and raw logs live: its private plugin folder, or the user folder every
// workspace reads. place() moves this workspace's chats to match the setting, so turning sharing on or off loses
// nothing. Chats other workspaces put in the shared folder stay there.
public final class ChatFolders {
  public static final String CHATS = "chats";
  public static final String RAW = "raw";

  private final Path privateRoot;
  private final Path sharedRoot;
  private final String workspace;
  private final ChatStore privateChats;
  private final ChatStore sharedChats;
  private final RawLog privateRaw;
  private final RawLog sharedRaw;

  public ChatFolders(Path privateRoot, Path sharedRoot, String workspace) {
    this.privateRoot = privateRoot;
    this.sharedRoot = sharedRoot;
    this.workspace = workspace;
    privateChats = new ChatStore(privateRoot.resolve(CHATS));
    sharedChats = new ChatStore(sharedRoot.resolve(CHATS));
    privateRaw = new RawLog(privateRoot.resolve(RAW));
    sharedRaw = new RawLog(sharedRoot.resolve(RAW));
  }

  public String workspace() {
    return workspace;
  }

  public Path root(boolean shared) {
    return shared ? sharedRoot : privateRoot;
  }

  public ChatStore chats(boolean shared) {
    return shared ? sharedChats : privateChats;
  }

  public RawLog raw(boolean shared) {
    return shared ? sharedRaw : privateRaw;
  }

  // Returns how many chats moved.
  public synchronized int place(boolean shared) throws IOException {
    List<String> moved = shared
        ? privateChats.moveTo(sharedChats, s -> true, workspace)
        : sharedChats.moveTo(privateChats, s -> workspace.equals(s.workspace()), workspace);
    for (String id : moved) {
      raw(!shared).moveTo(raw(shared), id);
    }
    return moved.size();
  }

  public boolean mine(ChatRecord.Summary chat) {
    return chat.workspace() == null || workspace.equals(chat.workspace());
  }
}
