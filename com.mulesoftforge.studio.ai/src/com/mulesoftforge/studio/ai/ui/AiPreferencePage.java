package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.ModelLists;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.llm.Updates;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;

public class AiPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  public static final String ID = "com.mulesoftforge.studio.ai.prefs";

  private Combo provider;
  private Label hint;
  private Text baseUrl;
  private Combo model;
  private Text apiKey;
  private Label testResult;
  private Button saveChats;
  private Button richTranscript;
  private Button checkUpdates;
  private Button useSystemCerts;
  private Text caPem;
  private Text retentionDays;

  @Override
  public void init(IWorkbench workbench) {
    setDescription("Choose where MuleSoftForge AI sends requests. API keys are kept in Eclipse secure storage.");
  }

  @Override
  protected Control createContents(Composite parent) {
    Composite body = new Composite(parent, SWT.NONE);
    body.setLayout(new GridLayout(2, false));

    label(body, "Provider:");
    provider = new Combo(body, SWT.READ_ONLY);
    for (Provider p : Provider.values()) {
      provider.add(p.label);
    }
    provider.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    provider.addListener(SWT.Selection, e -> load(selected()));

    new Label(body, SWT.NONE);
    hint = new Label(body, SWT.WRAP);
    GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
    hintData.widthHint = 420;
    hint.setLayoutData(hintData);

    label(body, "Base URL:");
    baseUrl = text(body, SWT.BORDER);

    label(body, "Model:");
    model = new Combo(body, SWT.DROP_DOWN);
    model.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    model.setToolTipText(
        "Curated models first. Type any id, or Refresh models for a filtered live list from the provider.");

    label(body, "API key:");
    apiKey = text(body, SWT.BORDER | SWT.PASSWORD);

    Composite actions = new Composite(body, SWT.NONE);
    actions.setLayout(new GridLayout(2, false));
    GridData actionsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    actionsData.horizontalSpan = 2;
    actions.setLayoutData(actionsData);

    Button refresh = new Button(actions, SWT.PUSH);
    refresh.setText("Refresh models");
    refresh.setToolTipText(
        "GET /models (or Ollama /api/tags). Curated suggestions stay on top; noisy ids are filtered out.");
    refresh.addListener(SWT.Selection, e -> refreshModels());

    Button test = new Button(actions, SWT.PUSH);
    test.setText("Test connection");
    test.setToolTipText("Sends a one-word chat request. Does not reload the model list.");
    test.addListener(SWT.Selection, e -> testConnection());

    testResult = new Label(body, SWT.WRAP);
    GridData resultData = new GridData(SWT.FILL, SWT.TOP, true, false);
    resultData.horizontalSpan = 2;
    resultData.widthHint = 420;
    testResult.setLayoutData(resultData);

    createDisplayGroup(body);
    createNetworkGroup(body);
    createHistoryGroup(body);

    Provider current = AiSettings.provider();
    provider.select(current.ordinal());
    load(current);
    return body;
  }

  private void createDisplayGroup(Composite body) {
    Group group = new Group(body, SWT.NONE);
    group.setText("Chat view");
    group.setLayout(new GridLayout(1, false));
    GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
    groupData.horizontalSpan = 2;
    groupData.verticalIndent = 10;
    group.setLayoutData(groupData);
    richTranscript = new Button(group, SWT.CHECK);
    richTranscript.setText("Rich chat view (uses Eclipse's built-in browser; turn off if it shows blank)");
    richTranscript.setSelection(AiSettings.richTranscript());
    label(group, "Takes effect when the MuleSoftForge AI view is reopened.");
    checkUpdates = new Button(group, SWT.CHECK);
    checkUpdates.setText("Check for updates (reads " + Updates.LATEST_URL + " once per Studio session)");
    checkUpdates.setSelection(AiSettings.checkForUpdates());
    label(group, "Installed version " + Activator.getDefault().getBundle().getVersion() + ".");
  }

  private void createNetworkGroup(Composite body) {
    Group group = new Group(body, SWT.NONE);
    group.setText("Network");
    group.setLayout(new GridLayout(3, false));
    GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
    groupData.horizontalSpan = 2;
    groupData.verticalIndent = 10;
    group.setLayoutData(groupData);

    Network.Trust trust = AiSettings.trust();
    useSystemCerts = new Button(group, SWT.CHECK);
    useSystemCerts.setText("Trust the operating system's certificates (needed behind Zscaler and similar proxies)");
    useSystemCerts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
    useSystemCerts.setSelection(trust.useSystem());

    label(group, "Corporate CA (PEM):");
    caPem = new Text(group, SWT.BORDER);
    caPem.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    caPem.setMessage("Optional: file or folder of .pem / .crt files from IT");
    caPem.setText(trust.caPemPath());
    Button browse = new Button(group, SWT.PUSH);
    browse.setText("Browse…");
    browse.addListener(SWT.Selection, e -> {
      FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
      dialog.setFilterExtensions(new String[] {"*.pem;*.crt;*.cer", "*.*"});
      String picked = dialog.open();
      if (picked != null) {
        caPem.setText(picked);
      }
    });

    Button check = new Button(group, SWT.PUSH);
    check.setText("Check certificates");
    check.setToolTipText("Loads the certificates with these settings and reports how many came from each source.");
    Label checkResult = new Label(group, SWT.WRAP);
    GridData resultData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    resultData.widthHint = 380;
    checkResult.setLayoutData(resultData);
    check.addListener(SWT.Selection, e -> {
      Network.configure(trustFromFields());
      checkResult.setText(Network.report().describe());
      group.layout(true, true);
    });
  }

  private Network.Trust trustFromFields() {
    return new Network.Trust(useSystemCerts.getSelection(), caPem.getText().trim());
  }

  private void createHistoryGroup(Composite body) {
    Group group = new Group(body, SWT.NONE);
    group.setText("Chat history");
    group.setLayout(new GridLayout(2, false));
    GridData groupData = new GridData(SWT.FILL, SWT.TOP, true, false);
    groupData.horizontalSpan = 2;
    groupData.verticalIndent = 10;
    group.setLayoutData(groupData);

    saveChats = new Button(group, SWT.CHECK);
    saveChats.setText("Save chats automatically");
    saveChats.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    saveChats.setSelection(AiSettings.saveChats());

    label(group, "Keep chats for (days, 0 = forever):");
    retentionDays = new Text(group, SWT.BORDER);
    GridData daysData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    daysData.widthHint = 60;
    retentionDays.setLayoutData(daysData);
    retentionDays.setText(Integer.toString(AiSettings.retentionDays()));

    Label where = new Label(group, SWT.WRAP);
    GridData whereData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
    whereData.widthHint = 420;
    where.setLayoutData(whereData);
    where.setText("Stored privately in this workspace, never in your Mule projects: " + Stores.root()
        + "\nOpen-file contents and API keys are not saved. The cost ledger (usage.jsonl) keeps only token counts"
        + " and amounts, and is kept when chats are deleted.");

    Button deleteAll = new Button(group, SWT.PUSH);
    deleteAll.setText("Delete all chat history…");
    deleteAll.addListener(SWT.Selection, e -> {
      if (!MessageDialog.openConfirm(getShell(), "Delete all chat history",
          "Delete every saved MuleSoftForge AI chat in this workspace? This cannot be undone.")) {
        return;
      }
      try {
        int deleted = Stores.chats().deleteAll();
        MessageDialog.openInformation(getShell(), "Chat history", "Deleted " + deleted + " chat(s).");
      } catch (Exception ex) {
        setErrorMessage("Could not delete chats: " + Errors.describe(ex));
      }
    });
  }

  @Override
  public boolean performOk() {
    try {
      AiSettings.save(fromFields());
      int days;
      try {
        days = Integer.parseInt(retentionDays.getText().trim());
      } catch (NumberFormatException e) {
        setErrorMessage("Keep chats for: enter a number of days (0 = forever).");
        return false;
      }
      AiSettings.saveHistorySettings(saveChats.getSelection(), days);
      AiSettings.saveTrust(trustFromFields());
      AiSettings.saveRichTranscript(richTranscript.getSelection());
      AiSettings.saveCheckForUpdates(checkUpdates.getSelection());
      return true;
    } catch (Exception e) {
      setErrorMessage("Could not save settings: " + Errors.describe(e));
      return false;
    }
  }

  @Override
  protected void performDefaults() {
    Provider p = selected();
    baseUrl.setText(p.defaultBaseUrl);
    seedSuggestions(p, p.defaultModel, false);
    super.performDefaults();
  }

  private void load(Provider p) {
    hint.setText(p.hint);
    baseUrl.setText(AiSettings.baseUrl(p));
    // Do not keep the previous provider's model text (e.g. openrouter/auto on OpenAI).
    seedSuggestions(p, AiSettings.model(p), false);
    apiKey.setEnabled(p != Provider.OLLAMA);
    try {
      apiKey.setText(AiSettings.apiKey(p));
      setErrorMessage(null);
    } catch (Exception e) {
      apiKey.setText("");
      setErrorMessage("Secure storage unavailable: " + Errors.describe(e));
    }
    testResult.setText("");
    hint.getParent().layout(true, true);
  }

  private void testConnection() {
    Connection connection = fromFields();
    Network.configure(trustFromFields());
    testResult.setText("Testing…");
    Display display = testResult.getDisplay();
    Job.create("Test MuleSoftForge AI connection", monitor -> {
      String result;
      try {
        String reply = ChatModels.create(connection).chat("Reply with the single word: ok");
        result = "Connected. Model replied: " + shorten(reply)
            + "\nUse Refresh models to update the dropdown.";
      } catch (Exception e) {
        result = "Failed:\n" + Errors.describe(e);
      }
      String text = result;
      display.asyncExec(() -> {
        if (!testResult.isDisposed()) {
          testResult.setText(text);
          testResult.getParent().layout(true, true);
        }
      });
      return Status.OK_STATUS;
    }).schedule();
  }

  private void refreshModels() {
    Connection connection = fromFields();
    Network.configure(trustFromFields());
    testResult.setText("Loading models…");
    Display display = testResult.getDisplay();
    Job.create("Refresh MuleSoftForge AI models", monitor -> {
      String result;
      List<String> picker = List.of();
      try {
        List<String> raw = ModelLists.list(connection);
        picker = ModelLists.forPicker(connection.provider(), raw, connection.model());
        result = ModelLists.summarize(connection.provider(), raw.size(), picker);
      } catch (Exception e) {
        result = "Could not load models:\n" + Errors.describe(e);
      }
      String text = result;
      List<String> choices = picker;
      display.asyncExec(() -> {
        if (testResult.isDisposed()) {
          return;
        }
        if (!choices.isEmpty()) {
          applyChoices(choices, connection.model(), true);
        }
        testResult.setText(text);
        testResult.getParent().layout(true, true);
      });
      return Status.OK_STATUS;
    }).schedule();
  }

  private void seedSuggestions(Provider p, String selected, boolean keepTyped) {
    applyChoices(ModelLists.suggestionsOnly(p, selected), selected, keepTyped);
  }

  private void applyChoices(List<String> choices, String selected, boolean keepTyped) {
    String keep = keepTyped ? model.getText().trim() : "";
    if (keep.isBlank()) {
      keep = selected == null ? "" : selected.trim();
    }
    model.removeAll();
    for (String id : choices) {
      model.add(id);
    }
    if (!keep.isBlank()) {
      model.setText(keep);
    } else if (!choices.isEmpty()) {
      model.select(0);
    }
  }

  private Connection fromFields() {
    return new Connection(selected(), baseUrl.getText().trim(), model.getText().trim(), apiKey.getText().trim());
  }

  private Provider selected() {
    int index = provider.getSelectionIndex();
    return index < 0 ? Provider.OPENROUTER : Provider.values()[index];
  }

  private static String shorten(String value) {
    String text = value == null ? "" : value.strip();
    return text.length() > 80 ? text.substring(0, 80) + "…" : text;
  }

  private static void label(Composite parent, String text) {
    new Label(parent, SWT.NONE).setText(text);
  }

  private static Text text(Composite parent, int style) {
    Text field = new Text(parent, style);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return field;
  }
}
