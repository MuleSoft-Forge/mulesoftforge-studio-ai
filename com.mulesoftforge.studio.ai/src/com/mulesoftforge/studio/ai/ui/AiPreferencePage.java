package com.mulesoftforge.studio.ai.ui;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.security.storage.StorageException;
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
  private Label apiKeyLabel;
  private Text apiKey;
  private Label clientIdLabel;
  private Text clientId;
  private Label clientSecretLabel;
  private Text clientSecret;
  private Label keyInfo;
  private Label testResult;
  // Edits to providers other than the one on screen, kept until Apply so switching does not lose them.
  private final Map<Provider, Connection> drafts = new EnumMap<>(Provider.class);
  private Provider shown;
  private Button saveChats;
  private Button rawLog;
  private Button richTranscript;
  private Button checkUpdates;
  private Button maskSecrets;
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
    for (Provider p : Provider.shownInUiValues()) {
      provider.add(p.label);
    }
    provider.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    provider.addListener(SWT.Selection, e -> {
      drafts.put(shown, fromFields());
      load(selected());
    });

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

    apiKeyLabel = label(body, "API key:");
    apiKey = text(body, SWT.BORDER | SWT.PASSWORD);
    clientIdLabel = label(body, "Client id:");
    clientId = text(body, SWT.BORDER);
    clientSecretLabel = label(body, "Client secret:");
    clientSecret = text(body, SWT.BORDER | SWT.PASSWORD);
    new Label(body, SWT.NONE);
    keyInfo = new Label(body, SWT.WRAP);
    keyInfo.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    apiKey.addListener(SWT.Modify, e -> showKeyInfo());
    clientId.addListener(SWT.Modify, e -> showKeyInfo());
    clientSecret.addListener(SWT.Modify, e -> showKeyInfo());

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
    if (!current.shownInUi()) {
      current = Provider.OPENROUTER;
    }
    provider.select(Provider.shownInUiValues().indexOf(current));
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
    maskSecrets = new Button(group, SWT.CHECK);
    maskSecrets.setText("Hide passwords, keys and tokens from the model (recommended)");
    maskSecrets.setSelection(AiSettings.maskSecrets());
    label(group, "Values in properties and YAML files, Mule XML, URLs, console output and your messages are sent"
        + " as «masked-N». Edits the AI proposes get the real values back. Files such as .env and private keys are"
        + " never sent, whatever this is set to. Screenshots are sent as they are.");
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

    rawLog = new Button(group, SWT.CHECK);
    rawLog.setText("Keep a raw log of every request and response");
    rawLog.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    rawLog.setSelection(AiSettings.rawLog());
    Label rawInfo = new Label(group, SWT.WRAP);
    GridData rawData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
    rawData.widthHint = 420;
    rawInfo.setLayoutData(rawData);
    rawInfo.setText("Same as the Raw toggle in the chat view. Records the exact HTTP request and response bodies,"
        + " URL and status of every model call (after secrets are masked), shown under each answer and kept one"
        + " file per chat. Azure OpenAI and Bedrock use their own clients, so for them it records the request and"
        + " response as LangChain4j sees them. Headers and API keys are never written. Files can be large; they are"
        + " deleted with their chat.");

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
          "Delete every saved MuleSoftForge AI chat and raw log in this workspace? This cannot be undone.")) {
        return;
      }
      try {
        int deleted = Stores.chats().deleteAll();
        Stores.raw().deleteAll();
        MessageDialog.openInformation(getShell(), "Chat history", "Deleted " + deleted + " chat(s).");
      } catch (Exception ex) {
        setErrorMessage("Could not delete chats: " + Errors.describe(ex));
      }
    });
  }

  @Override
  public boolean performOk() {
    try {
      for (Connection draft : drafts.values()) {
        if (draft.provider() != shown && changed(draft)) {
          AiSettings.save(draft);
        }
      }
      AiSettings.save(fromFields());
      drafts.clear();
      int days;
      try {
        days = Integer.parseInt(retentionDays.getText().trim());
      } catch (NumberFormatException e) {
        setErrorMessage("Keep chats for: enter a number of days (0 = forever).");
        return false;
      }
      AiSettings.saveHistorySettings(saveChats.getSelection(), days, rawLog.getSelection());
      AiSettings.saveTrust(trustFromFields());
      AiSettings.saveRichTranscript(richTranscript.getSelection());
      AiSettings.saveCheckForUpdates(checkUpdates.getSelection());
      AiSettings.saveMaskSecrets(maskSecrets.getSelection());
      return true;
    } catch (StorageException e) {
      setErrorMessage(e.getErrorCode() == StorageException.NO_PASSWORD
          ? "Could not save credentials: Eclipse secure storage has no master password. Press Apply "
              + "again and accept the password or Keychain prompt, or set one up in "
              + "General > Security > Secure Storage > Password."
          : "Could not save credentials: " + Errors.describe(e));
      return false;
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
    shown = p;
    Connection draft = drafts.get(p);
    hint.setText(p.hint);
    baseUrl.setText(draft != null ? draft.baseUrl() : AiSettings.baseUrl(p));
    // Do not keep the previous provider's model text (e.g. openrouter/auto on OpenAI).
    seedSuggestions(p, draft != null ? draft.model() : AiSettings.model(p), false);
    showCredentialFields(p);
    try {
      apiKey.setText(draft != null ? draft.apiKey() : AiSettings.apiKey(p));
      clientId.setText(draft != null ? draft.clientId() : AiSettings.clientId(p));
      clientSecret.setText(draft != null ? draft.clientSecret() : AiSettings.clientSecret(p));
      setErrorMessage(null);
    } catch (Exception e) {
      apiKey.setText("");
      clientId.setText("");
      clientSecret.setText("");
      setErrorMessage("Secure storage unavailable: " + Errors.describe(e));
    }
    showKeyInfo();
    testResult.setText("");
    hint.getParent().layout(true, true);
  }

  private void showCredentialFields(Provider p) {
    boolean gateway = p.needsClientCredentials();
    boolean ollama = p == Provider.OLLAMA;
    setRowVisible(apiKeyLabel, apiKey, !gateway && !ollama);
    setRowVisible(clientIdLabel, clientId, gateway);
    setRowVisible(clientSecretLabel, clientSecret, gateway);
  }

  private static void setRowVisible(Label label, Text field, boolean visible) {
    label.setVisible(visible);
    field.setVisible(visible);
    ((GridData) label.getLayoutData()).exclude = !visible;
    ((GridData) field.getLayoutData()).exclude = !visible;
  }

  private static String keyHint(Connection connection) {
    String problem = connection.provider().credentialsProblem(connection.apiKey(), connection.clientId(),
        connection.clientSecret());
    return problem == null ? "" : problem + "\n\n";
  }

  private boolean changed(Connection draft) throws StorageException {
    Provider p = draft.provider();
    return !draft.baseUrl().equals(AiSettings.baseUrl(p)) || !draft.model().equals(AiSettings.model(p))
        || !draft.apiKey().equals(AiSettings.apiKey(p)) || !draft.clientId().equals(AiSettings.clientId(p))
        || !draft.clientSecret().equals(AiSettings.clientSecret(p));
  }

  // Shows the start and end of the credentials on screen, so a value saved under the wrong provider is easy to spot.
  private void showKeyInfo() {
    if (shown == null || shown == Provider.OLLAMA) {
      keyInfo.setText("");
      return;
    }
    String problem = shown.credentialsProblem(apiKey.getText(), clientId.getText(), clientSecret.getText());
    if (shown.needsClientCredentials()) {
      keyInfo.setText("Client id for " + shown.label + ": " + Provider.describeKey(clientId.getText())
          + "\nClient secret: " + Provider.describeKey(clientSecret.getText())
          + (problem == null ? "" : "\n" + problem));
    } else {
      keyInfo.setText("Key for " + shown.label + ": " + Provider.describeKey(apiKey.getText())
          + (problem == null ? "" : "\n" + problem));
    }
    keyInfo.getParent().layout(true, true);
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
        result = "Failed:\n" + keyHint(connection) + Errors.describe(e);
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
        AiSettings.saveFetchedModels(connection.provider(), ModelLists.filterForChat(connection.provider(), raw));
      } catch (Exception e) {
        result = "Could not load models:\n" + keyHint(connection) + Errors.describe(e);
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

  // The fields always show the provider last loaded, which is the selected one except mid-switch.
  private Connection fromFields() {
    return new Connection(shown, baseUrl.getText().trim(), model.getText().trim(), apiKey.getText().trim(),
        clientId.getText().trim(), clientSecret.getText().trim());
  }

  private Provider selected() {
    List<Provider> choices = Provider.shownInUiValues();
    int index = provider.getSelectionIndex();
    return index < 0 || index >= choices.size() ? Provider.OPENROUTER : choices.get(index);
  }

  private static String shorten(String value) {
    String text = value == null ? "" : value.strip();
    return text.length() > 80 ? text.substring(0, 80) + "…" : text;
  }

  private static Label label(Composite parent, String text) {
    Label label = new Label(parent, SWT.NONE);
    label.setText(text);
    label.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    return label;
  }

  private static Text text(Composite parent, int style) {
    Text field = new Text(parent, style);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return field;
  }
}
