package com.mulesoftforge.studio.ai.ui;

import java.nio.file.Path;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.RawLog;
import com.mulesoftforge.studio.ai.history.UsageLog;

// Saved chats, raw logs and the cost ledger live in <workspace>/.metadata/.plugins/com.mulesoftforge.studio.ai/,
// Eclipse's private per-workspace folder for this plugin, never inside a Mule project.
final class Stores {
  private static ChatStore chats;
  private static RawLog raw;
  private static UsageLog usage;

  private Stores() {}

  static synchronized Path root() {
    return Activator.getDefault().getStateLocation().toFile().toPath();
  }

  static synchronized ChatStore chats() {
    if (chats == null) {
      chats = new ChatStore(root().resolve("chats"));
    }
    return chats;
  }

  static synchronized RawLog raw() {
    if (raw == null) {
      raw = new RawLog(root().resolve("raw"));
    }
    return raw;
  }

  static synchronized UsageLog usage() {
    if (usage == null) {
      usage = new UsageLog(root().resolve("usage.jsonl"));
    }
    return usage;
  }
}
