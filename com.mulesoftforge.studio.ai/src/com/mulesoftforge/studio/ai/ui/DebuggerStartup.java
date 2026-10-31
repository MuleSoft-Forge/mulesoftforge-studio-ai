package com.mulesoftforge.studio.ai.ui;

import org.eclipse.ui.IStartup;

// Starts following Studio's Mule debugger when Studio starts, so the event the debugger is stopped at is known
// before the user asks about it. It only registers listeners; nothing else loads until the chat view is used.
public class DebuggerStartup implements IStartup {
  @Override
  public void earlyStartup() {
    StudioDebugger.get().start();
  }
}
