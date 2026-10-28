package com.mulesoftforge.studio.ai.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Proposals from one chat turn. Several edits to the same file collapse into one change.
public final class ChangeSet {
  private final Map<String, FileChange> changes = new LinkedHashMap<>();

  public synchronized void stage(String path, String before, String after) {
    FileChange existing = changes.get(path);
    if (existing == null) {
      changes.put(path, new FileChange(path, before, after));
    } else {
      existing.update(after);
    }
  }

  // Latest proposed content, so reads within the same turn see earlier edits.
  public synchronized String staged(String path) {
    FileChange change = changes.get(path);
    return change == null ? null : change.after();
  }

  public synchronized boolean contains(String path) {
    return changes.containsKey(path);
  }

  public synchronized List<FileChange> all() {
    return new ArrayList<>(changes.values());
  }

  public synchronized boolean isEmpty() {
    return changes.isEmpty();
  }
}
