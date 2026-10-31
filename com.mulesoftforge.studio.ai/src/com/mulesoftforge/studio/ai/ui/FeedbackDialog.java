package com.mulesoftforge.studio.ai.ui;

import java.net.URI;
import java.util.concurrent.CancellationException;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.feedback.Environment;
import com.mulesoftforge.studio.ai.feedback.GitHub;
import com.mulesoftforge.studio.ai.feedback.IssueLink;
import com.mulesoftforge.studio.ai.feedback.Report;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.llm.Updates;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.Provider;

// Feedback in the chat view's header: a bug report or feedback, posted as a public issue on the plugin's GitHub
// repository. Signed in with GitHub, Send opens the issue as the user and shows it in the browser. Without
// signing in, or while the plugin has no GitHub App client ID, it opens GitHub's New issue page filled in.
final class FeedbackDialog extends TitleAreaDialog {
  private static final int OPEN_ON_GITHUB = IDialogConstants.CLIENT_ID + 1;
  private static final String INTRO = "This becomes a public issue on github.com/" + GitHub.OWNER + "/"
      + GitHub.REPO + ". Secrets are masked; leave out customer data.";
  private static final String SIGN_IN = "signin";
  private static final String SIGN_OUT = "signout";
  private static final String OPEN_CODE_PAGE = "open";
  private static final String CANCEL_SIGN_IN = "cancel";

  private final Environment environment = environment();
  private final GitHub github = new GitHub(Network.client(), "MuleSoftForge-Studio-AI/" + pluginVersion());
  private Button bug;
  private Text title;
  private Label textLabel;
  private Text text;
  private Button includeDetails;
  private Text details;
  private Label accountText;
  private Label code;
  private Link accountLink;
  private Job signingIn;
  private GitHub.SignIn shownCode;
  // Kept for this popup when secure storage could not save it.
  private String unsavedToken;
  private boolean sending;
  private GitHub.Issue sentIssue;

  FeedbackDialog(Shell shell) {
    super(shell);
    setShellStyle(getShellStyle() | SWT.RESIZE);
    setHelpAvailable(false);
  }

  @Override
  protected void configureShell(Shell shell) {
    super.configureShell(shell);
    shell.setText("MuleSoftForge AI Feedback");
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite area = (Composite) super.createDialogArea(parent);
    Composite body = new Composite(area, SWT.NONE);
    body.setLayout(new GridLayout(2, false));
    body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    new Label(body, SWT.NONE).setText("Type:");
    Composite kinds = new Composite(body, SWT.NONE);
    GridLayout kindsLayout = new GridLayout(2, false);
    kindsLayout.marginWidth = 0;
    kindsLayout.marginHeight = 0;
    kinds.setLayout(kindsLayout);
    bug = new Button(kinds, SWT.RADIO);
    bug.setText("Bug");
    bug.setSelection(true);
    bug.addListener(SWT.Selection, e -> kindChanged());
    Button feedback = new Button(kinds, SWT.RADIO);
    feedback.setText("Feedback");
    feedback.addListener(SWT.Selection, e -> kindChanged());

    new Label(body, SWT.NONE).setText("Title:");
    title = new Text(body, SWT.BORDER | SWT.SINGLE);
    title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    title.addListener(SWT.Modify, e -> changed());

    textLabel = new Label(body, SWT.WRAP);
    textLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
    text = new Text(body, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
    GridData textData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    textData.widthHint = 560;
    textData.heightHint = 170;
    text.setLayoutData(textData);
    text.addListener(SWT.Modify, e -> changed());

    includeDetails = new Button(body, SWT.CHECK);
    includeDetails.setText("Include these details");
    includeDetails.setToolTipText("Versions and names only: no paths, URLs, project names or keys");
    includeDetails.setSelection(true);
    includeDetails.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
    includeDetails.addListener(SWT.Selection, e -> {
      details.setEnabled(includeDetails.getSelection());
      changed();
    });
    details = new Text(body, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
    GridData detailsData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
    detailsData.widthHint = 560;
    details.setLayoutData(detailsData);
    details.setText(environment.text());

    if (GitHub.signInAvailable()) {
      Composite account = new Composite(body, SWT.NONE);
      GridLayout accountLayout = new GridLayout(3, false);
      accountLayout.marginWidth = 0;
      accountLayout.marginTop = 6;
      account.setLayout(accountLayout);
      account.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
      accountText = new Label(account, SWT.NONE);
      code = new Label(account, SWT.NONE);
      code.setFont(TranscriptStyles.bold());
      accountLink = new Link(account, SWT.NONE);
      accountLink.addListener(SWT.Selection, e -> accountClicked(e.text));
      showAccount();
    }

    setTitle("Report a bug or send feedback");
    kindChanged();
    return area;
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    if (GitHub.signInAvailable()) {
      createButton(parent, OPEN_ON_GITHUB, "Open on GitHub instead", false);
      createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
      createButton(parent, IDialogConstants.OK_ID, "Send", true);
    } else {
      createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
      createButton(parent, OPEN_ON_GITHUB, "Open on GitHub", true);
    }
    updateButtons();
  }

  @Override
  protected void buttonPressed(int id) {
    if (id == OPEN_ON_GITHUB) {
      openOnGitHub();
    } else if (id == IDialogConstants.OK_ID) {
      send();
    } else {
      super.buttonPressed(id);
    }
  }

  @Override
  public boolean close() {
    if (signingIn != null) {
      signingIn.cancel();
    }
    return super.close();
  }

  private void kindChanged() {
    boolean isBug = bug.getSelection();
    textLabel.setText(isBug ? "What happened: what you did, what you expected, and what happened instead."
        : "Your feedback: what works, what does not, and what you would like it to do.");
    title.setMessage(isBug ? "Accept fails for a new file" : "A shortcut to send the selection");
    textLabel.getParent().layout(true, true);
    changed();
  }

  private Report report() {
    return Report.of(bug.getSelection() ? Report.Kind.BUG : Report.Kind.FEEDBACK, title.getText(), text.getText(),
        includeDetails.getSelection() ? environment : null);
  }

  private void changed() {
    int masked = report().masked().count();
    if (masked > 0) {
      setMessage(masked + (masked == 1 ? " value that looks like a secret is" : " values that look like secrets are")
          + " posted as «masked-N». Leave out customer data too.", IMessageProvider.WARNING);
    } else {
      setMessage(INTRO);
    }
    updateButtons();
  }

  private void updateButtons() {
    Button send = getButton(IDialogConstants.OK_ID);
    if (send != null) {
      send.setEnabled(!sending && sentIssue == null && signedIn() && report().complete());
    }
    Button open = getButton(OPEN_ON_GITHUB);
    if (open != null) {
      open.setEnabled(!sending && sentIssue == null);
    }
  }

  private boolean signedIn() {
    return unsavedToken != null || !AiSettings.gitHubLogin().isEmpty();
  }

  private void openOnGitHub() {
    Report masked = report().masked().report();
    IssueLink.Link link = IssueLink.of(GitHub.OWNER, GitHub.REPO, masked,
        Platform.OS_WIN32.equals(Platform.getOS()) ? IssueLink.WINDOWS_MAX_LENGTH : IssueLink.MAX_LENGTH);
    if (link.cut()) {
      copy(masked.text());
    }
    if (open(link.url())) {
      close();
    }
  }

  private void send() {
    String token;
    try {
      token = unsavedToken != null ? unsavedToken : AiSettings.gitHubToken();
    } catch (Exception e) {
      setErrorMessage("Could not read the GitHub sign-in: " + Errors.describe(e));
      return;
    }
    if (token.isEmpty()) {
      forgetSignIn();
      setErrorMessage("Sign in with GitHub first, or open it on GitHub instead.");
      return;
    }
    Report masked = report().masked().report();
    sending = true;
    updateButtons();
    setErrorMessage(null);
    setMessage("Sending to GitHub…");
    Shell shell = getShell();
    Job job = Job.create("Send feedback to GitHub", monitor -> {
      try {
        GitHub.Issue issue = github.createIssue(token, GitHub.OWNER, GitHub.REPO, masked.issueTitle(),
            masked.issueBody());
        Ui.async(shell, () -> sent(issue));
      } catch (GitHub.GitHubException e) {
        Ui.async(shell, () -> sendFailed(e.getMessage(), e.signedOut()));
      } catch (Exception e) {
        Activator.logFailure("Could not send feedback to GitHub", e);
        Ui.async(shell, () -> sendFailed(Errors.describe(e), false));
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  // The issue opens in the browser, where screenshots can be added to it.
  private void sent(GitHub.Issue issue) {
    sending = false;
    sentIssue = issue;
    if (open(issue.url())) {
      close();
    } else {
      setErrorMessage(null);
      setMessage("Sent as issue #" + issue.number() + ": " + issue.url());
      updateButtons();
    }
  }

  private void sendFailed(String message, boolean signedOut) {
    sending = false;
    if (signedOut) {
      forgetSignIn();
    }
    setErrorMessage(message);
    updateButtons();
  }

  private void accountClicked(String which) {
    switch (which) {
      case SIGN_IN -> signIn();
      case SIGN_OUT -> {
        forgetSignIn();
        setErrorMessage(null);
      }
      case OPEN_CODE_PAGE -> {
        if (shownCode != null) {
          open(shownCode.verificationUri());
        }
      }
      case CANCEL_SIGN_IN -> {
        if (signingIn != null) {
          signingIn.cancel();
        }
      }
      default -> {
      }
    }
  }

  private void signIn() {
    setErrorMessage(null);
    Shell shell = getShell();
    Job job = Job.create("Sign in to GitHub", monitor -> {
      try {
        GitHub.SignIn signIn = github.startSignIn();
        Ui.async(shell, () -> showCode(signIn));
        String token = github.finishSignIn(signIn, monitor::isCanceled);
        String login = github.login(token);
        Ui.async(shell, () -> signedIn(token, login));
      } catch (CancellationException e) {
        Ui.async(shell, () -> signInEnded(null));
      } catch (GitHub.GitHubException e) {
        Ui.async(shell, () -> signInEnded(e.getMessage()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Ui.async(shell, () -> signInEnded(null));
      } catch (Exception e) {
        Activator.logFailure("Could not sign in to GitHub", e);
        Ui.async(shell, () -> signInEnded(Errors.describe(e)));
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    signingIn = job;
    showAccount();
    job.schedule();
  }

  // The code goes on the clipboard and GitHub's page opens, so signing in is paste and Authorize.
  private void showCode(GitHub.SignIn signIn) {
    if (signingIn == null) {
      return;
    }
    shownCode = signIn;
    copy(signIn.userCode());
    showAccount();
    setMessage("The code is on your clipboard. Paste it on GitHub and click Authorize; this updates by itself.");
    open(signIn.verificationUri());
  }

  private void signedIn(String token, String login) {
    signingIn = null;
    shownCode = null;
    try {
      AiSettings.saveGitHubSignIn(token, login);
      unsavedToken = null;
      setMessage("Signed in as @" + login + ".");
    } catch (Exception e) {
      unsavedToken = token;
      setErrorMessage("Signed in as @" + login + ", but Studio could not save the sign-in, so you will sign in"
          + " again next time: " + Errors.describe(e));
    }
    showAccount();
    updateButtons();
  }

  private void signInEnded(String error) {
    signingIn = null;
    shownCode = null;
    showAccount();
    if (error == null) {
      changed();
    } else {
      setErrorMessage(error);
    }
  }

  private void forgetSignIn() {
    unsavedToken = null;
    try {
      AiSettings.clearGitHubSignIn();
    } catch (Exception e) {
      Activator.logFailure("Could not remove the GitHub sign-in", e);
    }
    showAccount();
    updateButtons();
  }

  private void showAccount() {
    if (accountLink == null || accountLink.isDisposed()) {
      return;
    }
    String login = AiSettings.gitHubLogin();
    code.setText("");
    accountLink.setToolTipText(null);
    if (signingIn != null && shownCode != null) {
      accountText.setText("Enter this code on GitHub:");
      code.setText(shownCode.userCode());
      accountLink.setText("<a href=\"" + OPEN_CODE_PAGE + "\">Open " + shownCode.verificationUri()
          .replaceFirst("^https://", "") + "</a>   <a href=\"" + CANCEL_SIGN_IN + "\">Cancel</a>");
    } else if (signingIn != null) {
      accountText.setText("Asking GitHub for a code…");
      accountLink.setText("<a href=\"" + CANCEL_SIGN_IN + "\">Cancel</a>");
    } else if (unsavedToken == null && login.isEmpty()) {
      accountText.setText("Send from here as your GitHub account:");
      accountLink.setText("<a href=\"" + SIGN_IN + "\">Sign in with GitHub</a>");
      accountLink.setToolTipText("You enter a short code on github.com. The sign-in can only open issues in "
          + GitHub.OWNER + "/" + GitHub.REPO + ", and is kept in Eclipse secure storage.");
    } else {
      accountText.setText("Sending as" + (login.isEmpty() ? " your GitHub account" : " @" + login));
      accountLink.setText("<a href=\"" + SIGN_OUT + "\">Sign out</a>");
      accountLink.setToolTipText("Forgets the sign-in in Studio. To revoke it on GitHub as well, open "
          + GitHub.AUTHORIZATIONS_PAGE);
    }
    accountLink.getParent().layout(true, true);
    getShell().layout(true, true);
  }

  private void copy(String value) {
    Clipboard clipboard = new Clipboard(getShell().getDisplay());
    try {
      clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
    } finally {
      clipboard.dispose();
    }
  }

  private boolean open(String url) {
    try {
      PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
      return true;
    } catch (Exception e) {
      if (Program.launch(url)) {
        return true;
      }
      Activator.logFailure("Could not open " + url, e);
      setErrorMessage("Could not open the browser. Open this link yourself: " + url);
      return false;
    }
  }

  private static String pluginVersion() {
    return Updates.shortVersion(Activator.getDefault().getBundle().getVersion().toString());
  }

  private static Environment environment() {
    Provider provider = AiSettings.provider();
    String model = AiSettings.model(provider);
    if (AutoRouter.isAuto(model)) {
      AutoRouter.Models auto = AiSettings.autoModels(provider);
      model = AutoRouter.AUTO + " (" + auto.fast() + ", " + auto.strong() + ")";
    }
    String dataWeave = DataWeaveCli.find(AiSettings.dataWeaveCli()) == null ? "not set up" : "set up";
    return Environment.of(pluginVersion(), studio(), provider.label, model, dataWeave);
  }

  // "Anypoint Studio 7.28.0 (Eclipse 4.31.100)".
  private static String studio() {
    IProduct product = Platform.getProduct();
    StringBuilder out = new StringBuilder(product == null ? "Eclipse" : product.getName());
    if (product != null && product.getDefiningBundle() != null) {
      out.append(' ').append(Updates.shortVersion(product.getDefiningBundle().getVersion().toString()));
    }
    Bundle platform = Platform.getBundle("org.eclipse.platform");
    if (platform != null) {
      out.append(" (Eclipse ").append(Updates.shortVersion(platform.getVersion().toString())).append(')');
    }
    return out.toString();
  }
}
