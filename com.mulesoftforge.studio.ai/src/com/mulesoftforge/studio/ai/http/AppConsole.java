package com.mulesoftforge.studio.ai.http;

// The console of the Mule app the user runs in Studio, as the HTTP tools see it. Studio backs it with its Console
// view; the harness with a fake.
public interface AppConsole {
  // name is the console's title in Studio; text is everything it holds now.
  record Text(String name, String text) {}

  // The console of the Mule app running project, or null when there is none. project may be null.
  Text read(String project);
}
