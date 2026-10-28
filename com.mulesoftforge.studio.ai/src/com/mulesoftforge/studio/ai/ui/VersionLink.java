package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.IHandlerService;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.Updates;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// The installed version in the chat view header. Once per Studio session it asks the MuleSoftForge site for the
// latest release and, when newer, offers the update.
final class VersionLink {
  private static final String P2_UPDATE_COMMAND = "org.eclipse.equinox.p2.ui.sdk.update";

  // Shared by every chat view so the site is asked at most once per Studio session.
  private static Updates.Latest latest;
  private static boolean checked;

  private final Link link;
  private final IWorkbenchPage page;
  private final String installed;

  VersionLink(Composite parent, IWorkbenchPage page) {
    this.page = page;
    installed = Activator.getDefault().getBundle().getVersion().toString();
    link = new Link(parent, SWT.NONE);
    link.setFont(TranscriptStyles.italic());
    link.addListener(SWT.Selection, e -> clicked());
    show();
    check();
  }

  private boolean updateAvailable() {
    return latest != null && Updates.isNewer(latest.version(), installed);
  }

  private static boolean inDropins() {
    return Activator.getDefault().getBundle().getLocation().contains("/dropins/");
  }

  private void show() {
    String version = "v" + Updates.shortVersion(installed);
    String how = inDropins() ? "Installed from the dropins folder, so Studio cannot update it: install from "
        + Updates.SITE + " with Help > Install New Software." : "Installed from " + Updates.SITE + ".";
    if (updateAvailable()) {
      link.setText("<a>" + version + " · Update to " + latest.version() + "</a>");
      link.setToolTipText("MuleSoftForge AI " + latest.version() + " is available."
          + (latest.notes().isBlank() ? "" : "\n" + latest.notes()) + "\n\nVersion " + installed + ". " + how);
    } else {
      link.setText("<a>" + version + "</a>");
      link.setToolTipText("MuleSoftForge AI " + installed + ". " + how + "\nClick for release notes."
          + (AiSettings.checkForUpdates() ? "" : "\nUpdate checks are off in Preferences > MuleSoftForge AI."));
    }
    link.getParent().layout();
  }

  private void check() {
    if (checked || !AiSettings.checkForUpdates()) {
      return;
    }
    checked = true;
    Job job = Job.create("Check for MuleSoftForge AI updates", monitor -> {
      try {
        latest = Updates.fetch(Updates.LATEST_URL);
      } catch (Exception e) {
        Activator.logError("Could not check for MuleSoftForge AI updates at " + Updates.LATEST_URL, e);
      }
      Display.getDefault().asyncExec(() -> {
        if (!link.isDisposed()) {
          show();
        }
      });
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  // Studio's own update wizard when it can update this install; the install page otherwise.
  private void clicked() {
    if (updateAvailable() && !inDropins()) {
      try {
        page.getWorkbenchWindow().getService(IHandlerService.class).executeCommand(P2_UPDATE_COMMAND, null);
        return;
      } catch (Exception e) {
        Activator.logError("Could not open Help > Check for Updates", e);
      }
    }
    Program.launch(Updates.PAGE);
  }
}
