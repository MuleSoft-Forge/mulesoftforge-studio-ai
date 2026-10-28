package com.mulesoftforge.studio.ai.edit;

// One proposed file. "before" is the content when first proposed (null for a new file), so applying can
// refuse to overwrite a file that changed on disk in the meantime.
public final class FileChange {
  public enum State {
    PENDING,
    ACCEPTED,
    REJECTED
  }

  private final String path;
  private final String before;
  private String after;
  private State state = State.PENDING;

  FileChange(String path, String before, String after) {
    this.path = path;
    this.before = before;
    this.after = after;
  }

  public String path() {
    return path;
  }

  public String before() {
    return before;
  }

  public String after() {
    return after;
  }

  void update(String content) {
    this.after = content;
  }

  public boolean isNew() {
    return before == null;
  }

  public State state() {
    return state;
  }

  public void markAccepted() {
    state = State.ACCEPTED;
  }

  public void markRejected() {
    state = State.REJECTED;
  }

  public LineDiff.Stats stats() {
    return LineDiff.stats(before == null ? "" : before, after);
  }

  public String summary() {
    if (isNew()) {
      return path + " (new, " + LineDiff.lineCount(after) + " lines)";
    }
    LineDiff.Stats stats = stats();
    return path + " (+" + stats.added() + " -" + stats.removed() + ")";
  }
}
