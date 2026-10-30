package com.mulesoftforge.studio.ai.settings;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.Network;

// Base URL and model are kept per provider so switching presets does not lose earlier values.
// API keys live in Eclipse secure storage, never in the plain preference file.
public final class AiSettings {
  private static final String PROVIDER = "provider";
  private static final String BASE_URL = "baseUrl.";
  private static final String MODEL = "model.";
  private static final String FETCHED_MODELS = "fetchedModels.";
  private static final String AUTO_FAST = "auto.fast.";
  private static final String AUTO_STRONG = "auto.strong.";
  private static final String API_KEY = "apiKey.";
  private static final String CLIENT_ID = "clientId.";
  private static final String CLIENT_SECRET = "clientSecret.";
  private static final String CHAT_MODE = "chat.mode";
  private static final String PLAIN_TRANSCRIPT = "chat.plainTranscript";
  private static final String TLS_SYSTEM_DISABLED = "tls.systemDisabled";
  private static final String TLS_CA_PEM = "tls.caPem";
  private static final String HISTORY_DISABLED = "history.disabled";
  private static final String RETENTION_DAYS = "history.retentionDays";
  private static final String UPDATE_CHECK_DISABLED = "updates.checkDisabled";
  private static final String MASK_SECRETS_DISABLED = "privacy.maskSecretsDisabled";
  private static final String RAW_LOG = "history.rawLog";
  private static final String INSTRUCTIONS = "chat.instructions";
  public static final int DEFAULT_RETENTION_DAYS = 90;

  private static IPreferenceStore store;

  private AiSettings() {}

  // clientId / clientSecret are for MuleSoft AI Gateway (client_id / client_secret headers). Empty for others.
  public record Connection(Provider provider, String baseUrl, String model, String apiKey, String clientId,
      String clientSecret) {
    public Connection(Provider provider, String baseUrl, String model, String apiKey) {
      this(provider, baseUrl, model, apiKey, "", "");
    }

    public Connection withModel(String model) {
      return new Connection(provider, baseUrl, model, apiKey, clientId, clientSecret);
    }
  }

  private static synchronized IPreferenceStore store() {
    if (store == null) {
      store = new ScopedPreferenceStore(InstanceScope.INSTANCE, Activator.PLUGIN_ID);
    }
    return store;
  }

  public static Provider provider() {
    Provider provider = Provider.fromName(store().getString(PROVIDER));
    // Prefer a visible provider if a hidden one was previously saved.
    return provider.shownInUi() ? provider : Provider.OPENROUTER;
  }

  public static String baseUrl(Provider provider) {
    String value = store().getString(BASE_URL + provider.name());
    return value.isBlank() ? provider.defaultBaseUrl : value;
  }

  public static String model(Provider provider) {
    String value = store().getString(MODEL + provider.name());
    return value.isBlank() ? provider.defaultModel : value;
  }

  // The models Refresh models last fetched for the provider, so the chat view's picker can offer them too.
  public static List<String> fetchedModels(Provider provider) {
    String value = store().getString(FETCHED_MODELS + provider.name());
    return value.isBlank() ? List.of() : List.of(value.split("\n"));
  }

  public static void saveFetchedModels(Provider provider, List<String> models) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(FETCHED_MODELS + provider.name(), String.join("\n", models));
    ((ScopedPreferenceStore) prefs).save();
  }

  // Called on the thread that changed the setting; null keys are possible for bulk changes.
  public static Runnable onChange(Consumer<String> listener) {
    IPropertyChangeListener wrapped = event -> listener.accept(event.getProperty());
    store().addPropertyChangeListener(wrapped);
    return () -> store().removePropertyChangeListener(wrapped);
  }

  public static boolean affectsModels(String key) {
    return key == null || key.equals(PROVIDER) || key.startsWith(MODEL) || key.startsWith(FETCHED_MODELS)
        || key.startsWith(AUTO_FAST) || key.startsWith(AUTO_STRONG);
  }

  public static boolean affectsRawLog(String key) {
    return key == null || key.equals(RAW_LOG);
  }

  public static void saveModel(Provider provider, String model) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(MODEL + provider.name(), model.trim());
    ((ScopedPreferenceStore) prefs).save();
  }

  // The two models Auto picks between for this provider.
  public static AutoRouter.Models autoModels(Provider provider) {
    AutoRouter.Models defaults = AutoRouter.defaults(provider);
    String fast = store().getString(AUTO_FAST + provider.name()).trim();
    String strong = store().getString(AUTO_STRONG + provider.name()).trim();
    return new AutoRouter.Models(fast.isEmpty() ? defaults.fast() : fast, strong.isEmpty() ? defaults.strong() : strong);
  }

  public static void saveAutoModels(Provider provider, AutoRouter.Models models) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(AUTO_FAST + provider.name(), models.fast().trim());
    prefs.setValue(AUTO_STRONG + provider.name(), models.strong().trim());
    ((ScopedPreferenceStore) prefs).save();
  }

  public static ChatMode chatMode() {
    return ChatMode.fromName(store().getString(CHAT_MODE));
  }

  public static void saveChatMode(ChatMode mode) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(CHAT_MODE, mode.name());
    ((ScopedPreferenceStore) prefs).save();
  }

  // The HTML transcript; off falls back to the plain one. Takes effect when the view is reopened.
  public static boolean richTranscript() {
    return !store().getBoolean(PLAIN_TRANSCRIPT);
  }

  public static void saveRichTranscript(boolean rich) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(PLAIN_TRANSCRIPT, !rich);
    ((ScopedPreferenceStore) prefs).save();
  }

  // Whether the chat view looks for a newer release on the MuleSoftForge site once per Studio session.
  public static boolean checkForUpdates() {
    return !store().getBoolean(UPDATE_CHECK_DISABLED);
  }

  public static void saveCheckForUpdates(boolean check) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(UPDATE_CHECK_DISABLED, !check);
    ((ScopedPreferenceStore) prefs).save();
  }

  // Whether passwords, keys and tokens are replaced with placeholders before anything is sent to the model.
  public static boolean maskSecrets() {
    return !store().getBoolean(MASK_SECRETS_DISABLED);
  }

  public static void saveMaskSecrets(boolean mask) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(MASK_SECRETS_DISABLED, !mask);
    ((ScopedPreferenceStore) prefs).save();
  }

  // The user's own instructions, sent with every message in every project.
  public static String instructions() {
    return store().getString(INSTRUCTIONS);
  }

  public static void saveInstructions(String text) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(INSTRUCTIONS, text == null ? "" : text.strip());
    ((ScopedPreferenceStore) prefs).save();
  }

  public static Network.Trust trust() {
    return new Network.Trust(!store().getBoolean(TLS_SYSTEM_DISABLED), store().getString(TLS_CA_PEM));
  }

  public static void saveTrust(Network.Trust trust) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(TLS_SYSTEM_DISABLED, !trust.useSystem());
    prefs.setValue(TLS_CA_PEM, trust.caPemPath() == null ? "" : trust.caPemPath().trim());
    ((ScopedPreferenceStore) prefs).save();
    Network.configure(trust);
  }

  public static boolean saveChats() {
    return !store().getBoolean(HISTORY_DISABLED);
  }

  // 0 keeps chats forever.
  public static int retentionDays() {
    String value = store().getString(RETENTION_DAYS).trim();
    try {
      return value.isEmpty() ? DEFAULT_RETENTION_DAYS : Math.max(0, Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return DEFAULT_RETENTION_DAYS;
    }
  }

  // Whether every request and response is written to a per-chat raw log. Off unless turned on.
  public static boolean rawLog() {
    return store().getBoolean(RAW_LOG);
  }

  public static void saveHistorySettings(boolean saveChats, int retentionDays, boolean rawLog) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(HISTORY_DISABLED, !saveChats);
    prefs.setValue(RETENTION_DAYS, Integer.toString(Math.max(0, retentionDays)));
    prefs.setValue(RAW_LOG, rawLog);
    ((ScopedPreferenceStore) prefs).save();
  }

  // The Raw toggle in the chat view.
  public static void saveRawLog(boolean rawLog) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(RAW_LOG, rawLog);
    ((ScopedPreferenceStore) prefs).save();
  }

  public static String apiKey(Provider provider) throws StorageException {
    return secure().get(API_KEY + provider.name(), "");
  }

  public static String clientId(Provider provider) throws StorageException {
    return secure().get(CLIENT_ID + provider.name(), "");
  }

  public static String clientSecret(Provider provider) throws StorageException {
    return secure().get(CLIENT_SECRET + provider.name(), "");
  }

  public static Connection current() throws StorageException {
    Provider provider = provider();
    return new Connection(provider, baseUrl(provider), model(provider), apiKey(provider), clientId(provider),
        clientSecret(provider));
  }

  public static void save(Connection connection) throws Exception {
    Provider provider = connection.provider();
    IPreferenceStore prefs = store();
    prefs.setValue(PROVIDER, provider.name());
    prefs.setValue(BASE_URL + provider.name(), connection.baseUrl().trim());
    prefs.setValue(MODEL + provider.name(), connection.model().trim());
    ((ScopedPreferenceStore) prefs).save();

    ISecurePreferences secure = secure();
    secure.put(API_KEY + provider.name(), connection.apiKey().trim(), true);
    secure.put(CLIENT_ID + provider.name(), connection.clientId().trim(), true);
    secure.put(CLIENT_SECRET + provider.name(), connection.clientSecret().trim(), true);
    secure.flush();
  }

  private static ISecurePreferences secure() {
    return SecurePreferencesFactory.getDefault().node(Activator.PLUGIN_ID);
  }
}
