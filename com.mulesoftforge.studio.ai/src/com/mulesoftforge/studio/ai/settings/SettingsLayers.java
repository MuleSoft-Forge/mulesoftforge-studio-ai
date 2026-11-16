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
// The first time a workspace runs this version, its existing settings seed the shared file if that is still
// empty. They are left in the workspace too, so an older plugin version still finds them.
public final class SettingsLayers {
  static final String WORKSPACE_ONLY = "settings.workspaceOnly";
  static final String IMPORTED = "settings.importedToShared";

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
    if (!mine.isEmpty() && settings(shared).isEmpty()) {
      shared.update(mine);
    }
    workspace.update(Map.of(IMPORTED, "true"));
  }

  private static Map<String, String> settings(KeyValues store) {
    Map<String, String> values = new HashMap<>(store.all());
    values.remove(WORKSPACE_ONLY);
    values.remove(IMPORTED);
    return values;
  }
}
