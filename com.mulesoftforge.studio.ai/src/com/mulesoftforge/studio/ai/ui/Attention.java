package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.hooks.Hooks;
import com.mulesoftforge.studio.ai.render.Alerts;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// Tells the user a turn ended when they are not looking: the view's tab is highlighted when it is hidden, and while
// Studio is in the background the Dock or taskbar icon gets a badge, with an optional beep and the user's
// Notification hooks. Coming back to Studio clears the badge. Runs on the UI thread.
final class Attention {
  private final IViewPart view;
  private final Shell shell;
  private final Listener activated = event -> clear();
  private int unseen;
  private boolean question;

  Attention(IViewPart view) {
    this.view = view;
    this.shell = view.getSite().getShell();
    shell.addListener(SWT.Activate, activated);
  }

  void ended(Alerts.Kind kind, String sessionId) {
    if (shell.isDisposed()) {
      return;
    }
    boolean away = shell.getDisplay().getActiveShell() == null;
    boolean hidden = !view.getSite().getPage().isPartVisible(view);
    if (!away && !hidden) {
      return;
    }
    if (AiSettings.alertWhenDone()) {
      IWorkbenchSiteProgressService progress = view.getSite().getService(IWorkbenchSiteProgressService.class);
      if (progress != null) {
        progress.warnOfContentChange();
      }
      if (away) {
        unseen++;
        question |= kind == Alerts.Kind.QUESTION;
        badge(Alerts.badge(unseen, question));
        if (AiSettings.alertSound()) {
          shell.getDisplay().beep();
        }
      }
    }
    if (away) {
      notifyHooks(kind, sessionId);
    }
  }

  void dispose() {
    if (!shell.isDisposed()) {
      shell.removeListener(SWT.Activate, activated);
      clear();
    }
  }

  private void clear() {
    if (unseen > 0 || question) {
      unseen = 0;
      question = false;
      badge("");
    }
  }

  private void badge(String text) {
    TaskItem item = taskItem(shell);
    if (item != null && !item.isDisposed()) {
      item.setOverlayText(text);
    }
  }

  // Windows has an item per window; macOS has one for the application.
  private static TaskItem taskItem(Shell shell) {
    TaskBar bar = shell.getDisplay().getSystemTaskBar();
    if (bar == null) {
      return null;
    }
    TaskItem item = bar.getItem(shell);
    return item != null ? item : bar.getItem(null);
  }

  private static void notifyHooks(Alerts.Kind kind, String sessionId) {
    if (!AiSettings.hooksEnabled()) {
      return;
    }
    Job job = new Job("MuleSoftForge Studio AI notification hooks") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        Hooks hooks = Hooks.load(Hooks.userFile());
        if (hooks.has(Hooks.Event.NOTIFICATION)) {
          ObjectNode input = Hooks.input(Hooks.Event.NOTIFICATION, sessionId, null);
          input.put("message", kind.message);
          input.put("title", "Anypoint Studio");
          input.put("notification_type", kind.type);
          hooks.run(Hooks.Event.NOTIFICATION, null, input, null, monitor::isCanceled);
        }
        return Status.OK_STATUS;
      }
    };
    job.setSystem(true);
    job.schedule();
  }
}
