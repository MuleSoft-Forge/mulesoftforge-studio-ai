package com.mulesoftforge.studio.ai.ui;

import java.nio.file.Path;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.dataweave.CliDownload;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// Preferences > MuleSoftForge AI > DataWeave CLI: where dw is, whether it was found and which version, and a
// download of MuleSoft's release for this computer. Looking and downloading run in jobs.
final class DataWeaveCliGroup {
  private final Text path;
  private final Label status;
  private final Button download;

  DataWeaveCliGroup(Composite body) {
    Group group = new Group(body, SWT.NONE);
    group.setText("DataWeave CLI");
    group.setLayout(new GridLayout(3, false));
    GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
    groupData.horizontalSpan = 2;
    groupData.verticalIndent = 10;
    group.setLayoutData(groupData);

    Label intro = new Label(group, SWT.WRAP);
    intro.setText("With MuleSoft's DataWeave CLI the AI compiles the DataWeave it proposes and runs scripts on"
        + " sample input before you see them. Leave the path empty to look for dw on the PATH, in ~/.dw/bin and in"
        + " Homebrew.");
    GridData introData = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
    introData.widthHint = 480;
    intro.setLayoutData(introData);

    new Label(group, SWT.NONE).setText("Path:");
    path = new Text(group, SWT.BORDER);
    path.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    path.setMessage("Found automatically when empty");
    path.setText(AiSettings.dataWeaveCli());
    Button browse = new Button(group, SWT.PUSH);
    browse.setText("Browse…");
    browse.addListener(SWT.Selection, e -> {
      FileDialog dialog = new FileDialog(group.getShell(), SWT.OPEN);
      String picked = dialog.open();
      if (picked != null) {
        path.setText(picked);
        detect();
      }
    });

    status = new Label(group, SWT.WRAP);
    GridData statusData = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
    statusData.widthHint = 480;
    status.setLayoutData(statusData);

    Composite buttons = new Composite(group, SWT.NONE);
    GridLayout buttonsLayout = new GridLayout(2, false);
    buttonsLayout.marginWidth = 0;
    buttons.setLayout(buttonsLayout);
    buttons.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 3, 1));
    Button check = new Button(buttons, SWT.PUSH);
    check.setText("Check");
    check.setToolTipText("Look for the DataWeave CLI with the path above and report its version");
    check.addListener(SWT.Selection, e -> detect());
    download = new Button(buttons, SWT.PUSH);
    CliDownload.Asset asset = CliDownload.current();
    download.setText("Download " + CliDownload.VERSION);
    download.setEnabled(asset != null);
    download.setToolTipText(asset == null ? "MuleSoft publishes no DataWeave CLI for this computer"
        : "Download MuleSoft's DataWeave CLI for " + asset.platform() + " from GitHub, check its published"
            + " checksum, and use it");
    download.addListener(SWT.Selection, e -> download());
    detect();
  }

  String path() {
    return path.getText().trim();
  }

  private void detect() {
    String configured = path();
    status.setText("Looking for the DataWeave CLI…");
    Display display = status.getDisplay();
    Job.create("Look for the DataWeave CLI", monitor -> {
      String text;
      Path found = DataWeaveCli.find(configured);
      if (found == null) {
        text = !configured.isEmpty() ? "No runnable dw at " + configured + "."
            : CliDownload.current() == null ? "Not found, and MuleSoft publishes no DataWeave CLI for this computer."
                + " Proposals are still checked, without compiling their DataWeave."
            : "Not found. Download it, or install it and give its path.";
      } else {
        String version = null;
        try {
          version = DataWeaveCli.at(found).version();
        } catch (Exception e) {
          Activator.logFailure("Could not read the DataWeave CLI version", e);
        }
        text = "Found dw " + (version == null ? "" : version + " ") + "at " + found + ".";
      }
      Ui.async(display, status, () -> {
        status.setText(text);
        status.getParent().layout(true, true);
      });
      return Status.OK_STATUS;
    }).schedule();
  }

  private void download() {
    CliDownload.Asset asset = CliDownload.current();
    if (asset == null) {
      return;
    }
    download.setEnabled(false);
    status.setText("Downloading DataWeave CLI " + asset.version() + " for " + asset.platform() + "…");
    Display display = status.getDisplay();
    Path target = Activator.getDefault().getStateLocation().toFile().toPath().resolve("dataweave-cli");
    Job job = Job.create("Download the DataWeave CLI", monitor -> {
      try {
        Path binary = CliDownload.install(Network.client(), asset, target, monitor::isCanceled);
        Ui.async(display, path, () -> {
          path.setText(binary.toString());
          download.setEnabled(true);
          detect();
        });
      } catch (Exception e) {
        Activator.logFailure("Could not download the DataWeave CLI", e);
        Ui.async(display, status, () -> {
          status.setText("Download failed: " + Errors.describe(e));
          download.setEnabled(true);
        });
      }
      return Status.OK_STATUS;
    });
    job.setUser(true);
    job.schedule();
  }
}
