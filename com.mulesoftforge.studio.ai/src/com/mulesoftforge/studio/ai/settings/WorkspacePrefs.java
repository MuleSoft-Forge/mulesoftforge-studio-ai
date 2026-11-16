package com.mulesoftforge.studio.ai.settings;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.mulesoftforge.studio.ai.Activator;

// This workspace's preferences (InstanceScope), in <workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/.
final class WorkspacePrefs implements KeyValues {
  private final IEclipsePreferences node = InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);

  @Override
  public String get(String key) {
    return node.get(key, "");
  }

  @Override
  public Map<String, String> all() {
    Map<String, String> values = new HashMap<>();
    try {
      for (String key : node.keys()) {
        values.put(key, node.get(key, ""));
      }
    } catch (BackingStoreException e) {
      // Unreadable node: nothing to report.
    }
    return values;
  }

  @Override
  public Set<String> update(Map<String, String> changes) throws IOException {
    Set<String> changed = new HashSet<>();
    for (Map.Entry<String, String> change : changes.entrySet()) {
      String old = node.get(change.getKey(), null);
      if (change.getValue() == null) {
        node.remove(change.getKey());
      } else {
        node.put(change.getKey(), change.getValue());
      }
      if (!Objects.equals(old, change.getValue())) {
        changed.add(change.getKey());
      }
    }
    try {
      node.flush();
    } catch (BackingStoreException e) {
      throw new IOException("Could not save this workspace's settings", e);
    }
    return changed;
  }
}
