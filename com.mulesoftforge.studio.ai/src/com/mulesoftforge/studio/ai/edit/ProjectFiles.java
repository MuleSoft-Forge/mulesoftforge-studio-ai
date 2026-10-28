package com.mulesoftforge.studio.ai.edit;

import java.util.List;

// Read-only view of one project. Studio backs it with an IProject; the harness backs it with a folder.
public interface ProjectFiles {
  String name();

  // Returns null when the file does not exist.
  String read(String path) throws Exception;

  // Project-relative file paths under directory ("" for the project root).
  List<String> list(String directory, int limit) throws Exception;
}
