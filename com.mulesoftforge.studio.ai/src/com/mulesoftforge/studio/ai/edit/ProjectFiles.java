package com.mulesoftforge.studio.ai.edit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

// Read-only view of one project. Studio backs it with an IProject; the harness backs it with a folder.
public interface ProjectFiles {
  String name();

  // Returns null when the file does not exist.
  String read(String path) throws Exception;

  // The file as stored, for sending it unchanged (a PDF, an image). Returns null when the file does not exist.
  default byte[] readBytes(String path) throws Exception {
    String text = read(path);
    return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
  }

  // Project-relative file paths under directory ("" for the project root).
  List<String> list(String directory, int limit) throws Exception;

  // The project folder on disk, where hooks run; null when it has none.
  default Path location() {
    return null;
  }

  // Where Studio keeps the connector models it generated for this workspace; null when unknown.
  default Path connectorModels() {
    return null;
  }
}
