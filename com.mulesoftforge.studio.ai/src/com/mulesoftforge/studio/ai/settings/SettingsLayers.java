package com.mulesoftforge.studio.ai.settings;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Which settings the plugin uses: the user's shared file by default, or this workspace's own copy once
// "Use these settings in this workspace only" is ticked, like Eclipse's project-specific settings. Ticking it
// copies the shared values into the workspace, so nothing changes until the user edits something.
// The first time a workspace runs this version, nothing it was set up with changes: its settings seed the shared
// file if that is still empty; a workspace set up differently from the shared file keeps its own settings, as if
// "this workspace only" were ticked; one that set nothing, or only what the shared file already has, joins it.
// Workspace settings are left in place, so an older plugin version still finds them.
public final class SettingsLayers {
  private static final String MARKERS = "settings.";
  static final String WORKSPACE_ONLY = MARKERS + "workspaceOnly";
  static final String IMPORTED = MARKERS + "importedToShared";
  private static final String NOTICE = MARKERS + "updateNotice";

  // COPIED: this workspace's settings became the shared ones. KEPT: it was set up differently, so it keeps its own.
  // JOINED: it now uses the settings saved from another workspace.
  public enum Notice { COPIED, KEPT, JOINED }

  // UI state and caches, not choices, so they do not make a workspace count as set up differently.
  private static final String CHAT_MODE = "chat.mode";
  private static final String FETCHED_MODELS = "fetchedModels.";

  private final KeyValues shared;
  private final KeyValues workspace;

  public SettingsLayers(KeyValues shared, KeyValues workspace) {
    this.shared = shared;
    this.workspace = workspace;
  }

  public KeyValues active() {
    return workspaceOnly() ? workspace : shared;
  }

  public boolean workspaceOnly() {
    return Boolean.parseBoolean(workspace.get(WORKSPACE_ONLY));
  }

  // Returns the keys whose value in use changed.
  public Set<String> setWorkspaceOnly(boolean on) throws IOException {
    if (on == workspaceOnly()) {
      return Set.of();
    }
    Map<String, String> before = settings(active());
    Map<String, String> changes = new HashMap<>();
    if (on) {
      settings(workspace).keySet().forEach(key -> changes.put(key, null));
      changes.putAll(settings(shared));
    }
    changes.put(WORKSPACE_ONLY, Boolean.toString(on));
    workspace.update(changes);
    Map<String, String> after = settings(active());
    Set<String> changed = new HashSet<>(before.keySet());
    changed.addAll(after.keySet());
    changed.removeIf(key -> Objects.equals(before.get(key), after.get(key)));
    return changed;
  }

  public void importOnce() throws IOException {
    if (Boolean.parseBoolean(workspace.get(IMPORTED))) {
      return;
    }
    Map<String, String> mine = settings(workspace);
    Map<String, String> common = settings(shared);
    Map<String, String> marks = new HashMap<>();
    marks.put(IMPORTED, "true");
    if (!mine.isEmpty() && common.isEmpty()) {
      shared.update(mine);
      marks.put(NOTICE, Notice.COPIED.name());
    } else if (setUpDifferently(mine, common)) {
      marks.put(WORKSPACE_ONLY, "true");
      marks.put(NOTICE, Notice.KEPT.name());
    } else if (!common.isEmpty()) {
      marks.put(NOTICE, Notice.JOINED.name());
    }
    workspace.update(marks);
  }

  // What importOnce did to this workspace, until the chat view has told the user; null when there is nothing to say.
  public Notice notice() {
    try {
      String value = workspace.get(NOTICE);
      return value.isEmpty() ? null : Notice.valueOf(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public void clearNotice() throws IOException {
    Map<String, String> clear = new HashMap<>();
    clear.put(NOTICE, null);
    workspace.update(clear);
  }

  // Only keys the workspace set count: Eclipse dropped a key when it went back to its default.
  private static boolean setUpDifferently(Map<String, String> mine, Map<String, String> common) {
    for (Map.Entry<String, String> entry : mine.entrySet()) {
      String key = entry.getKey();
      if (key.equals(CHAT_MODE) || key.startsWith(FETCHED_MODELS)) {
        continue;
      }
      if (!Objects.equals(entry.getValue(), common.getOrDefault(key, ""))) {
        return true;
      }
    }
    return false;
  }

  // Without this class's own markers, which all start with "settings.".
  private static Map<String, String> settings(KeyValues store) {
    Map<String, String> values = new HashMap<>(store.all());
    values.keySet().removeIf(key -> key.startsWith(MARKERS));
    return values;
  }
}
