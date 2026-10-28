package com.mulesoftforge.studio.ai.edit;

import java.nio.charset.CharacterCodingException;
import java.util.List;

import com.mulesoftforge.studio.ai.llm.Attachment;

// Turns a project path into an attachment: a file's text, or a folder's file list.
public final class AttachmentReader {
  public static final int MAX_FOLDER_FILES = 300;

  private AttachmentReader() {}

  // Throws IllegalArgumentException with a message fit for the user when the path cannot be attached.
  public static Attachment read(ProjectFiles project, String path) throws Exception {
    String normalized = path.isBlank() || path.equals("/") ? "" : ProjectPaths.normalize(path);
    if (!normalized.isEmpty() && ProjectPaths.isSkipped(normalized)) {
      throw new IllegalArgumentException(normalized + " is build output or tool settings, so it is not attached.");
    }
    String content;
    try {
      content = normalized.isEmpty() ? null : project.read(normalized);
    } catch (CharacterCodingException e) {
      content = "\0";
    }
    if (content != null) {
      if (content.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(normalized + " looks like a binary file, so it is not attached.");
      }
      return Attachment.file(project.name(), normalized, content);
    }
    List<String> files = project.list(normalized, MAX_FOLDER_FILES + 1);
    if (files.isEmpty()) {
      throw new IllegalArgumentException("Nothing to attach at " + (normalized.isEmpty() ? "/" : normalized) + ".");
    }
    boolean truncated = files.size() > MAX_FOLDER_FILES;
    return Attachment.folder(project.name(), normalized, truncated ? files.subList(0, MAX_FOLDER_FILES) : files,
        truncated);
  }

  // Lines are 1-based and inclusive.
  public static Attachment lines(ProjectFiles project, String path, int first, int last) throws Exception {
    String normalized = ProjectPaths.normalize(path);
    String content = project.read(normalized);
    if (content == null) {
      throw new IllegalArgumentException("No file at " + normalized + ".");
    }
    String[] all = content.split("\n", -1);
    int from = Math.max(1, first);
    int to = Math.min(all.length, Math.max(from, last));
    String selected = String.join("\n", List.of(all).subList(from - 1, to));
    return Attachment.selection(project.name(), normalized, from, to, selected);
  }
}
