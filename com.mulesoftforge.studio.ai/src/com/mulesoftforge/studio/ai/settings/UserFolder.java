package com.mulesoftforge.studio.ai.settings;

import java.nio.file.Path;

// ~/.mulesoftforge/studio-ai: this user's folder, shared by every Studio install, workspace and window.
// Settings, the cost ledger and shared chats live here, next to memory.md and hooks.json.
public final class UserFolder {
  private static volatile Path override;

  private UserFolder() {}

  // Harness only: keep checks away from the real folder.
  public static void override(Path path) {
    override = path;
  }

  public static Path path() {
    Path folder = override;
    return folder != null ? folder : Path.of(System.getProperty("user.home"), ".mulesoftforge", "studio-ai");
  }
}
