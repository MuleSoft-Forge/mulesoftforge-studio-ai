package com.mulesoftforge.studio.ai.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.mulesoftforge.studio.ai.llm.Memory;
import com.mulesoftforge.studio.ai.llm.Prompts;

// Saves a Don't suggest rule from an offered change: either this user's memory.md (every project) or the
// project's AGENTS.md (shared via git).
final class DontSuggestDialog extends TitleAreaDialog {
  enum Target {
    LOCAL, SHARED
  }

  private final String offerTitle;
  private final boolean canShare;
  private Text rule;
  private Button local;
  private Button shared;
  private String savedRule;
  private Target savedTarget;

  DontSuggestDialog(Shell shell, String offerTitle, boolean canShare) {
    super(shell);
    this.offerTitle = offerTitle == null ? "" : offerTitle;
    this.canShare = canShare;
  }

  String rule() {
    return savedRule;
  }

  Target target() {
    return savedTarget;
  }

  @Override
  protected void configureShell(Shell shell) {
    super.configureShell(shell);
    shell.setText("Don't suggest");
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    setTitle("Stop suggesting this");
    setMessage("Saved preferences are sent with every chat so the AI skips this offer. Edit the wording to make"
        + " it as general as you like.");
    Composite area = (Composite) super.createDialogArea(parent);
    Composite body = new Composite(area, SWT.NONE);
    body.setLayout(new GridLayout(1, false));
    body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Label ruleLabel = new Label(body, SWT.NONE);
    ruleLabel.setText("Rule:");
    rule = new Text(body, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
    GridData ruleData = new GridData(SWT.FILL, SWT.FILL, true, true);
    ruleData.widthHint = 420;
    ruleData.heightHint = 70;
    rule.setLayoutData(ruleData);
    rule.setText(Memory.ruleFromOffer(offerTitle));

    local = new Button(body, SWT.RADIO);
    local.setText("Only me, all projects (" + Memory.FILE_NAME + ")");
    local.setSelection(true);
    shared = new Button(body, SWT.RADIO);
    shared.setText("This project, shared via git (" + Prompts.AGENTS_FILE + ")");
    shared.setEnabled(canShare);
    if (!canShare) {
      Label note = new Label(body, SWT.WRAP);
      note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
      note.setText("Open a project to save this rule in " + Prompts.AGENTS_FILE + ".");
    }
    return area;
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    createButton(parent, IDialogConstants.OK_ID, "Save", true);
    createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
  }

  @Override
  protected void okPressed() {
    String text = rule.getText() == null ? "" : rule.getText().strip();
    if (text.isEmpty()) {
      setErrorMessage("Write a rule first.");
      return;
    }
    savedRule = text;
    savedTarget = shared.getSelection() && canShare ? Target.SHARED : Target.LOCAL;
    super.okPressed();
  }
}
