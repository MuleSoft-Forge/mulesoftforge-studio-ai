package com.mulesoftforge.studio.ai.settings;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.Network;

// Base URL and model are kept per provider so switching presets does not lose earlier values.
// API keys live in Eclipse secure storage, never in the plain preference file.
public final class AiSettings {
  private static final String PROVIDER = "provider";
  private static final String BASE_URL = "baseUrl.";
  private static final String MODEL = "model.";
  private static final String API_KEY = "apiKey.";
  private static final String CHAT_MODE = "chat.mode";
  private static final String PLAIN_TRANSCRIPT = "chat.plainTranscript";
  private static final String TLS_SYSTEM_DISABLED = "tls.systemDisabled";
  private static final String TLS_CA_PEM = "tls.caPem";
  private static final String HISTORY_DISABLED = "history.disabled";
  private static final String RETENTION_DAYS = "history.retentionDays";
  private static final String UPDATE_CHECK_DISABLED = "updates.checkDisabled";
  public static final int DEFAULT_RETENTION_DAYS = 90;

  private static IPreferenceStore store;

  private AiSettings() {}

  public record Connection(Provider provider, String baseUrl, String model, String apiKey) {}

  private static synchronized IPreferenceStore store() {
    if (store == null) {
      store = new ScopedPreferenceStore(InstanceScope.INSTANCE, Activator.PLUGIN_ID);
    }
    return store;
  }

  public static Provider provider() {
    return Provider.fromName(store().getString(PROVIDER));
  }

  public static String baseUrl(Provider provider) {
    String value = store().getString(BASE_URL + provider.name());
    return value.isBlank() ? provider.defaultBaseUrl : value;
  }

  public static String model(Provider provider) {
    String value = store().getString(MODEL + provider.name());
    return value.isBlank() ? provider.defaultModel : value;
  }

  public static void saveModel(Provider provider, String model) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(MODEL + provider.name(), model.trim());
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

  public static void saveHistorySettings(boolean saveChats, int retentionDays) throws Exception {
    IPreferenceStore prefs = store();
    prefs.setValue(HISTORY_DISABLED, !saveChats);
    prefs.setValue(RETENTION_DAYS, Integer.toString(Math.max(0, retentionDays)));
    ((ScopedPreferenceStore) prefs).save();
  }

  public static String apiKey(Provider provider) throws StorageException {
    return secure().get(API_KEY + provider.name(), "");
  }

  public static Connection current() throws StorageException {
    Provider provider = provider();
    return new Connection(provider, baseUrl(provider), model(provider), apiKey(provider));
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
    secure.flush();
  }

  private static ISecurePreferences secure() {
    return SecurePreferencesFactory.getDefault().node(Activator.PLUGIN_ID);
  }
}
