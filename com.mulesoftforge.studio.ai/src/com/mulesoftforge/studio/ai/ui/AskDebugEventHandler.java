package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

// The button on Studio's Mule Debugger view: opens the AI view with the event the debugger is stopped at attached.
public class AskDebugEventHandler extends AbstractHandler {
  @Override
  public Object execute(ExecutionEvent event) throws ExecutionException {
    IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
    if (page == null) {
      return null;
    }
    try {
      ((ChatView) page.showView(ChatView.ID)).askAboutDebugEvent();
    } catch (PartInitException e) {
      throw new ExecutionException("Could not open the MuleSoftForge AI view", e);
    }
    return null;
  }
}
