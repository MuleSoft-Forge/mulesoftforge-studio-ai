package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.handlers.HandlerUtil;

// Opens the AI view and attaches what was selected: editor lines, files and folders in an explorer, rows in the
// Problems view, or Console output.
public class AskAiHandler extends AbstractHandler {
  @Override
  public Object execute(ExecutionEvent event) throws ExecutionException {
    IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
    IWorkbenchPage page = window.getActivePage();
    if (page == null) {
      return null;
    }
    IWorkbenchPart part = HandlerUtil.getActivePart(event);
    ISelection selection = HandlerUtil.getActiveMenuSelection(event);
    if (selection == null) {
      selection = HandlerUtil.getCurrentSelection(event);
    }
    IEditorPart editor = part instanceof IEditorPart activeEditor ? activeEditor : null;
    Attachments.Result result;
    if (part instanceof ChatView) {
      result = new Attachments.Result(List.of(), List.of());
    } else if (part instanceof IConsoleView console) {
      result = Consoles.attach(console, selection);
    } else {
      result = Attachments.fromSelection(selection, editor);
    }
    try {
      ChatView view = (ChatView) page.showView(ChatView.ID);
      view.attach(result);
    } catch (PartInitException e) {
      throw new ExecutionException("Could not open the MuleSoftForge AI view", e);
    }
    return null;
  }
}
