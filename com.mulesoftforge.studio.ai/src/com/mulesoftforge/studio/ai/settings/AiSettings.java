package com.mulesoftforge.studio.ai.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.http.HttpTools;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.render.LoadingPhrases;

// Base URL and model are kept per provider so switching presets does not lose earlier values.
// Settings are shared by every workspace (UserFolder/settings.properties) unless this workspace keeps its own;
// see SettingsLayers. API keys live in Eclipse secure storage, which is per user, never in a plain file.
public final class AiSettings {
  public static final String FILE_NAME = "settings.properties";
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
  private static final String SHARE_CHATS = "history.sharedAcrossWorkspaces";
  private static final String UPDATE_CHECK_DISABLED = "updates.checkDisabled";
  private static final String MASK_SECRETS_DISABLED = "privacy.maskSecretsDisabled";
  private static final String MASK_PERSONAL_DISABLED = "privacy.maskPersonalDataDisabled";
  private static final String DATAWEAVE_CLI = "dataweave.cli";
  private static final String RAW_LOG = "history.rawLog";
  private static final String INSTRUCTIONS = "chat.instructions";
  private static final String HTTP_INSTRUCTIONS = "http.instructions";
  private static final String DOCS_HOSTS = "http.docsHosts";
  private static final String LOADING_PHRASES = "chat.loadingPhrases";
  private static final String ALERT_DISABLED = "chat.alertDisabled";
  private static final String ALERT_SOUND = "chat.alertSound";
  private static final String HOOKS_ENABLED = "hooks.enabled";
  private static final String BUILT_INS_DISABLED = "extensions.builtInsDisabled";
  private static final String FIX_CHECKS_DISABLED = "chat.fixChecksDisabled";
  private static final String MCP_PLATFORM = "mcp.platformEnabled";
  private static final String GITHUB_LOGIN = "github.login";
  private static final String GITHUB_TOKEN = "github.token";
  public static final int DEFAULT_RETENTION_DAYS = 90;

  private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
  private static SettingsLayers layers;

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

  private static synchronized SettingsLayers layers() {
    if (layers == null) {
      layers = new SettingsLayers(new SettingsFile(sharedFile(), AiSettings::fire), new WorkspacePrefs());
      try {
        layers.importOnce();
      } catch (IOException e) {
        Activator.logFailure("Could not copy this workspace's settings to " + sharedFile(), e);
      }
    }
    return layers;
  }

  public static Path sharedFile() {
    return UserFolder.path().resolve(FILE_NAME);
  }

  private static String text(String key) {
    return layers().active().get(key);
  }

  private static boolean flag(String key) {
    return Boolean.parseBoolean(text(key));
  }

  private static void save(String key, String value) throws IOException {
    Map<String, String> change = new HashMap<>();
    change.put(key, value);
    save(change);
  }

  private static void save(Map<String, String> changes) throws IOException {
    fire(layers().active().update(changes));
  }

  private static void fire(Set<String> keys) {
    for (String key : keys) {
      for (Consumer<String> listener : LISTENERS) {
        listener.accept(key);
      }
    }
  }

  // Whether this workspace keeps its own copy of the settings instead of the shared file. API keys stay shared.
  public static boolean workspaceOnly() {
    return layers().workspaceOnly();
  }

  public static void saveWorkspaceOnly(boolean on) throws IOException {
    fire(layers().setWorkspaceOnly(on));
  }

  public static Provider provider() {
    Provider provider = Provider.fromName(text(PROVIDER));
    // Prefer a visible provider if a hidden one was previously saved.
    return provider.shownInUi() ? provider : Provider.OPENROUTER;
  }

  public static String baseUrl(Provider provider) {
    String value = text(BASE_URL + provider.name());
    return value.isBlank() ? provider.defaultBaseUrl : value;
  }

  public static String model(Provider provider) {
    String value = text(MODEL + provider.name());
    return value.isBlank() ? provider.defaultModel : value;
  }

  // The models Refresh models last fetched for the provider, so the chat view's picker can offer them too.
  public static List<String> fetchedModels(Provider provider) {
    String value = text(FETCHED_MODELS + provider.name());
    return value.isBlank() ? List.of() : List.of(value.split("\n"));
  }

  public static void saveFetchedModels(Provider provider, List<String> models) throws Exception {
    save(FETCHED_MODELS + provider.name(), String.join("\n", models));
  }

  // Called on the thread that changed the setting, or the one that first read another window's change.
  public static Runnable onChange(Consumer<String> listener) {
    LISTENERS.add(listener);
    return () -> LISTENERS.remove(listener);
  }

  public static boolean affectsModels(String key) {
    return key == null || key.equals(PROVIDER) || key.startsWith(MODEL) || key.startsWith(FETCHED_MODELS)
        || key.startsWith(AUTO_FAST) || key.startsWith(AUTO_STRONG);
  }

  public static boolean affectsRawLog(String key) {
    return key == null || key.equals(RAW_LOG);
  }

  public static boolean affectsChatFolder(String key) {
    return key == null || key.equals(SHARE_CHATS);
  }

  public static void saveModel(Provider provider, String model) throws Exception {
    save(MODEL + provider.name(), model.trim());
  }

  // The two models Auto picks between for this provider.
  public static AutoRouter.Models autoModels(Provider provider) {
    AutoRouter.Models defaults = AutoRouter.defaults(provider);
    String fast = text(AUTO_FAST + provider.name()).trim();
    String strong = text(AUTO_STRONG + provider.name()).trim();
    return new AutoRouter.Models(fast.isEmpty() ? defaults.fast() : fast, strong.isEmpty() ? defaults.strong() : strong);
  }

  public static void saveAutoModels(Provider provider, AutoRouter.Models models) throws Exception {
    save(Map.of(AUTO_FAST + provider.name(), models.fast().trim(), AUTO_STRONG + provider.name(),
        models.strong().trim()));
  }

  public static ChatMode chatMode() {
    return ChatMode.fromName(text(CHAT_MODE));
  }

  public static void saveChatMode(ChatMode mode) throws Exception {
    save(CHAT_MODE, mode.name());
  }

  // The HTML transcript; off falls back to the plain one. Takes effect when the view is reopened.
  public static boolean richTranscript() {
    return !flag(PLAIN_TRANSCRIPT);
  }

  public static void saveRichTranscript(boolean rich) throws Exception {
    save(PLAIN_TRANSCRIPT, Boolean.toString(!rich));
  }

  // Whether the chat view looks for a newer release on the MuleSoftForge site once per Studio session.
  public static boolean checkForUpdates() {
    return !flag(UPDATE_CHECK_DISABLED);
  }

  public static void saveCheckForUpdates(boolean check) throws Exception {
    save(UPDATE_CHECK_DISABLED, Boolean.toString(!check));
  }

  // Tips or witty lines on the live status line while the model works. Both by default; Off is kept once chosen.
  public static LoadingPhrases.Mode loadingPhrases() {
    return LoadingPhrases.Mode.fromName(text(LOADING_PHRASES));
  }

  // Whether the view's tab and the Dock or taskbar icon show that an answer or a question is waiting while
  // Studio is in the background. On by default.
  public static boolean alertWhenDone() {
    return !flag(ALERT_DISABLED);
  }

  // A system beep with that alert. Off by default.
  public static boolean alertSound() {
    return flag(ALERT_SOUND);
  }

  // Whether the commands in Hooks.userFile() run on chat events. Off until turned on.
  public static boolean hooksEnabled() {
    return flag(HOOKS_ENABLED);
  }

  public static void saveChatExtras(LoadingPhrases.Mode phrases, boolean alert, boolean sound) throws Exception {
    save(Map.of(LOADING_PHRASES, phrases.name(), ALERT_DISABLED, Boolean.toString(!alert), ALERT_SOUND,
        Boolean.toString(sound)));
  }

  public static void saveHooksEnabled(boolean hooks) throws Exception {
    save(HOOKS_ENABLED, Boolean.toString(hooks));
  }

  // The hosted MuleSoft Platform MCP server, signed in with Studio's Anypoint login; off until turned on.
  public static boolean mcpPlatformEnabled() {
    return flag(MCP_PLATFORM);
  }

  public static void saveMcpPlatformEnabled(boolean on) throws Exception {
    save(MCP_PLATFORM, Boolean.toString(on));
  }

  // Built-in skills turned off in Preferences, as Extensions.builtInKey values.
  public static Set<String> disabledBuiltIns() {
    String value = text(BUILT_INS_DISABLED);
    return value.isBlank() ? Set.of() : Set.of(value.split("\n"));
  }

  public static void saveBuiltInEnabled(String key, boolean enabled) throws Exception {
    Set<String> keys = new TreeSet<>(disabledBuiltIns());
    if (enabled) {
      keys.remove(key);
    } else {
      keys.add(key);
    }
    save(BUILT_INS_DISABLED, String.join("\n", keys));
  }

  // Whether a turn that proposed changes goes back to the AI while the project checks fail on them. On by default.
  public static boolean fixChecksBeforeFinishing() {
    return !flag(FIX_CHECKS_DISABLED);
  }

  public static void saveFixChecksBeforeFinishing(boolean on) throws Exception {
    save(FIX_CHECKS_DISABLED, Boolean.toString(!on));
  }

  // Whether passwords, keys and tokens are replaced with placeholders before anything is sent to the model.
  public static boolean maskSecrets() {
    return !flag(MASK_SECRETS_DISABLED);
  }

  public static void saveMaskSecrets(boolean mask) throws Exception {
    save(MASK_SECRETS_DISABLED, Boolean.toString(!mask));
  }

  // Where the DataWeave CLI is; empty to look for dw on the PATH, in ~/.dw/bin and in Homebrew.
  public static String dataWeaveCli() {
    return text(DATAWEAVE_CLI);
  }

  public static void saveDataWeaveCli(String path) throws Exception {
    save(DATAWEAVE_CLI, path == null ? "" : path.strip());
  }

  // Whether email addresses and phone numbers in live debug events are replaced with placeholders.
  public static boolean maskPersonalData() {
    return !flag(MASK_PERSONAL_DISABLED);
  }

  public static void saveMaskPersonalData(boolean mask) throws Exception {
    save(MASK_PERSONAL_DISABLED, Boolean.toString(!mask));
  }

  // The GitHub account bug reports and feedback are sent as. The token is in secure storage; the account name,
  // shown in the Feedback popup, is not secret.
  public static String gitHubLogin() {
    return text(GITHUB_LOGIN);
  }

  public static String gitHubToken() throws StorageException {
    return secure().get(GITHUB_TOKEN, "");
  }

  public static void saveGitHubSignIn(String token, String login) throws Exception {
    ISecurePreferences secure = secure();
    secure.put(GITHUB_TOKEN, token, true);
    secure.flush();
    save(GITHUB_LOGIN, login);
  }

  public static void clearGitHubSignIn() throws Exception {
    save(GITHUB_LOGIN, null);
    ISecurePreferences secure = secure();
    secure.remove(GITHUB_TOKEN);
    secure.flush();
  }

  // The user's own instructions, sent with every message in every project.
  public static String instructions() {
    return text(INSTRUCTIONS);
  }

  public static void saveInstructions(String text) throws Exception {
    save(INSTRUCTIONS, text == null ? "" : text.strip());
  }

  // Extra guidance for call_endpoint and fetch_docs, appended after the built-in HTTP prompt.
  public static String httpInstructions() {
    return text(HTTP_INSTRUCTIONS);
  }

  public static void saveHttpInstructions(String text) throws Exception {
    save(HTTP_INSTRUCTIONS, text == null ? "" : text.strip());
  }

  // Hosts fetch_docs may read, one per line. Empty preference means the built-in defaults.
  public static String docsHostsText() {
    String text = text(DOCS_HOSTS);
    return text.isBlank() ? HttpTools.formatDocsHosts(HttpTools.DEFAULT_DOCS_HOSTS) : text;
  }

  public static List<String> docsHosts() {
    String text = text(DOCS_HOSTS);
    if (text.isBlank()) {
      return HttpTools.DEFAULT_DOCS_HOSTS;
    }
    try {
      return HttpTools.parseDocsHosts(text);
    } catch (IllegalArgumentException e) {
      return HttpTools.DEFAULT_DOCS_HOSTS;
    }
  }

  // Saves the normalised host list. text must parse, or throws IllegalArgumentException.
  public static void saveDocsHosts(String text) throws Exception {
    List<String> hosts = HttpTools.parseDocsHosts(text);
    String normalised = HttpTools.formatDocsHosts(hosts);
    save(DOCS_HOSTS, normalised.equals(HttpTools.formatDocsHosts(HttpTools.DEFAULT_DOCS_HOSTS)) ? null : normalised);
  }

  public static Network.Trust trust() {
    return new Network.Trust(!flag(TLS_SYSTEM_DISABLED), text(TLS_CA_PEM));
  }

  public static void saveTrust(Network.Trust trust) throws Exception {
    save(Map.of(TLS_SYSTEM_DISABLED, Boolean.toString(!trust.useSystem()), TLS_CA_PEM,
        trust.caPemPath() == null ? "" : trust.caPemPath().trim()));
    Network.configure(trust);
  }

  public static boolean saveChats() {
    return !flag(HISTORY_DISABLED);
  }

  // 0 keeps chats forever.
  public static int retentionDays() {
    String value = text(RETENTION_DAYS).trim();
    try {
      return value.isEmpty() ? DEFAULT_RETENTION_DAYS : Math.max(0, Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return DEFAULT_RETENTION_DAYS;
    }
  }

  // Whether every request and response is written to a per-chat raw log. Off unless turned on.
  public static boolean rawLog() {
    return flag(RAW_LOG);
  }

  // Whether saved chats and raw logs live in the user folder, seen from every workspace, instead of in this
  // workspace. Off by default: people often keep one workspace per client to keep their work apart.
  public static boolean shareChats() {
    return flag(SHARE_CHATS);
  }

  public static void saveHistorySettings(boolean saveChats, int retentionDays, boolean rawLog, boolean shareChats)
      throws Exception {
    save(Map.of(HISTORY_DISABLED, Boolean.toString(!saveChats), RETENTION_DAYS,
        Integer.toString(Math.max(0, retentionDays)), RAW_LOG, Boolean.toString(rawLog), SHARE_CHATS,
        Boolean.toString(shareChats)));
  }

  // The Raw toggle in the chat view.
  public static void saveRawLog(boolean rawLog) throws Exception {
    save(RAW_LOG, Boolean.toString(rawLog));
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
    save(Map.of(PROVIDER, provider.name(), BASE_URL + provider.name(), connection.baseUrl().trim(),
        MODEL + provider.name(), connection.model().trim()));

    ISecurePreferences secure = secure();
    secure.put(API_KEY + provider.name(), connection.apiKey().trim(), true);
    secure.put(CLIENT_ID + provider.name(), connection.clientId().trim(), true);
    secure.put(CLIENT_SECRET + provider.name(), connection.clientSecret().trim(), true);
    secure.flush();
  }

  private static ISecurePreferences secure() throws StorageException {
    ISecurePreferences root = SecurePreferencesFactory.getDefault();
    if (root == null) {
      throw new StorageException(StorageException.NO_SECURE_MODULE,
          "Eclipse secure storage is not available. Set up a master password under"
              + " General > Security > Secure Storage > Password.");
    }
    return root.node(Activator.PLUGIN_ID);
  }
}
