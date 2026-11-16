import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mulesoftforge.studio.ai.history.ChatFolders;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.settings.KeyValues;
import com.mulesoftforge.studio.ai.settings.SettingsFile;
import com.mulesoftforge.studio.ai.settings.SettingsLayers;

// check-shared: what follows the user from one Studio workspace to the next. The settings file two windows save at
// once (also from two processes), the first import of a workspace's old settings, "this workspace only", the shared
// cost ledger with its one-off import and lines from other windows, and chats moving in and out of the shared folder.
final class SharedChecks {
  private static final int WRITES = 40;

  private SharedChecks() {}

  static void run() throws Exception {
    Path dir = Files.createTempDirectory("studio-ai-shared");
    int failed = 0;
    failed += settingsFile(dir.resolve("settings"));
    failed += twoProcesses(dir.resolve("processes"));
    failed += layers(dir.resolve("layers"));
    failed += ledger(dir.resolve("ledger"));
    failed += chats(dir.resolve("chats"));
    System.exit(failed == 0 ? 0 : 1);
  }

  // Run by twoProcesses in a second JVM: `writer <file> <prefix>`.
  static void writer(Path file, String prefix) throws IOException {
    SettingsFile settings = new SettingsFile(file, null);
    for (int i = 0; i < WRITES; i++) {
      settings.update(Map.of(prefix + i, Integer.toString(i)));
    }
  }

  private static int settingsFile(Path dir) throws Exception {
    int failed = 0;
    Path file = dir.resolve("settings.properties");
    List<String> seenByA = new CopyOnWriteArrayList<>();
    List<String> seenByB = new CopyOnWriteArrayList<>();
    SettingsFile a = new SettingsFile(file, seenByA::addAll);
    SettingsFile b = new SettingsFile(file, seenByB::addAll);
    failed += check("a missing file reads as empty", a.get("provider").isEmpty() && b.all().isEmpty());
    failed += check("saving reports the key it changed", a.update(Map.of("provider", "CUSTOM")).equals(Set.of("provider")));
    failed += check("the other window reads the change", b.get("provider").equals("CUSTOM"));
    failed += check("the other window is told which key changed", seenByB.equals(List.of("provider")));
    failed += check("the saving window is not told about its own save", seenByA.isEmpty());

    b.update(Map.of("model.CUSTOM", "claude-sonnet-5-5"));
    a.update(Map.of("chat.mode", "ASK"));
    failed += check("a save keeps a key another window saved since this one last read",
        b.get("model.CUSTOM").equals("claude-sonnet-5-5") && b.get("chat.mode").equals("ASK")
            && a.get("model.CUSTOM").equals("claude-sonnet-5-5"));
    failed += check("saving an unchanged value changes nothing", a.update(Map.of("chat.mode", "ASK")).isEmpty());

    String instructions = "Use British English.\nLog with the JSON Logger connector: «café» = ok";
    a.update(Map.of("chat.instructions", instructions));
    failed += check("multi-line and non-ASCII values round-trip",
        new SettingsFile(file, null).get("chat.instructions").equals(instructions));

    Map<String, String> remove = new HashMap<>();
    remove.put("chat.mode", null);
    failed += check("a null value removes the key", a.update(remove).equals(Set.of("chat.mode"))
        && !b.all().containsKey("chat.mode"));
    try (var files = Files.list(dir)) {
      failed += check("no temp files are left behind",
          files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }
    return failed;
  }

  // Two Studio windows are two JVMs; the file lock must keep every save from both.
  private static int twoProcesses(Path dir) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve("settings.properties");
    String java = ProcessHandle.current().info().command().orElse("java");
    String classpath = System.getProperty("java.class.path");
    List<Process> processes = new ArrayList<>();
    for (String prefix : List.of("one.", "two.")) {
      processes.add(new ProcessBuilder(java, "-cp", classpath, "Harness", "shared-writer", file.toString(), prefix)
          .inheritIO().start());
    }
    SettingsFile here = new SettingsFile(file, null);
    for (int i = 0; i < WRITES; i++) {
      here.update(Map.of("three." + i, Integer.toString(i)));
    }
    boolean exited = true;
    for (Process p : processes) {
      exited &= p.waitFor() == 0;
    }
    Map<String, String> all = new SettingsFile(file, null).all();
    return check("three processes saving at once keep all " + (3 * WRITES) + " keys",
        exited && all.size() == 3 * WRITES);
  }

  private static int layers(Path dir) throws Exception {
    int failed = 0;
    Path file = dir.resolve("settings.properties");
    SettingsFile shared = new SettingsFile(file, null);
    MapValues first = new MapValues(Map.of("provider", "OPENAI", "model.OPENAI", "gpt-5.5", "chat.mode", "PLAN"));
    SettingsLayers one = new SettingsLayers(shared, first);
    one.importOnce();
    failed += check("the first workspace's settings seed an empty shared file",
        shared.get("provider").equals("OPENAI") && shared.get("model.OPENAI").equals("gpt-5.5"));
    failed += check("the import marker stays out of the shared file", !shared.all().containsKey("settings.importedToShared"));
    failed += check("old settings stay in the workspace for an older plugin", first.get("provider").equals("OPENAI"));
    failed += check("the workspace then reads the shared file", one.active() == shared);
    failed += check("its notice says its settings were copied", one.notice() == SettingsLayers.Notice.COPIED);
    one.clearNotice();
    failed += check("a notice is shown once", one.notice() == null);

    MapValues differs = new MapValues(Map.of("provider", "OLLAMA", "chat.instructions", "Use British English."));
    SettingsLayers kept = new SettingsLayers(new SettingsFile(file, null), differs);
    kept.importOnce();
    failed += check("a workspace set up differently keeps its own settings on update", kept.workspaceOnly()
        && kept.active().get("provider").equals("OLLAMA")
        && kept.active().get("chat.instructions").equals("Use British English."));
    failed += check("and does not change the shared settings", shared.get("provider").equals("OPENAI")
        && shared.get("chat.instructions").isEmpty());
    failed += check("its notice says it kept its own", kept.notice() == SettingsLayers.Notice.KEPT);
    failed += check("markers never reach the shared file",
        shared.all().keySet().stream().noneMatch(k -> k.startsWith("settings.")));

    MapValues empty = new MapValues(Map.of());
    SettingsLayers fresh = new SettingsLayers(new SettingsFile(file, null), empty);
    fresh.importOnce();
    failed += check("a workspace that set nothing joins the shared settings",
        !fresh.workspaceOnly() && fresh.active().get("provider").equals("OPENAI"));
    failed += check("its notice says it joined", fresh.notice() == SettingsLayers.Notice.JOINED);
    SettingsLayers firstEver = new SettingsLayers(new SettingsFile(dir.resolve("new-user.properties"), null),
        new MapValues(Map.of()));
    firstEver.importOnce();
    failed += check("a brand-new user gets no notice", firstEver.notice() == null && !firstEver.workspaceOnly());

    MapValues same = new MapValues(Map.of("provider", "OPENAI", "chat.mode", "ASK", "fetchedModels.OPENAI", "a\nb"));
    SettingsLayers joins = new SettingsLayers(new SettingsFile(file, null), same);
    joins.importOnce();
    failed += check("one that differs only in mode or cached model lists joins too",
        !joins.workspaceOnly() && joins.active().get("model.OPENAI").equals("gpt-5.5"));

    MapValues second = new MapValues(Map.of());
    SettingsLayers two = new SettingsLayers(new SettingsFile(file, null), second);
    two.importOnce();
    second.update(Map.of("provider", "BEDROCK"));
    two.importOnce();
    failed += check("the import runs once per workspace", !two.workspaceOnly() && shared.get("provider").equals("OPENAI"));
    second.update(Map.of("provider", ""));

    Set<String> changed = two.setWorkspaceOnly(true);
    failed += check("ticking workspace-only starts from the shared values", changed.isEmpty()
        && two.workspaceOnly() && two.active() == second && second.get("provider").equals("OPENAI")
        && second.get("chat.mode").equals("PLAN"));
    two.active().update(Map.of("provider", "CUSTOM"));
    failed += check("a workspace-only edit leaves the shared file alone", shared.get("provider").equals("OPENAI"));
    Set<String> back = two.setWorkspaceOnly(false);
    failed += check("unticking returns to the shared values and reports what changed",
        back.equals(Set.of("provider")) && two.active().get("provider").equals("OPENAI"));
    failed += check("setting the same scope again changes nothing", two.setWorkspaceOnly(false).isEmpty());
    return failed;
  }

  private static int ledger(Path dir) throws Exception {
    int failed = 0;
    Files.createDirectories(dir);
    Path legacy = dir.resolve("workspace-a").resolve(UsageLog.FILE_NAME);
    Files.createDirectories(legacy.getParent());
    String now = Instant.now().toString();
    // The format written before the ledger was shared: no workspace field.
    Files.writeString(legacy, line(now, "c1", 0.01) + "\n" + line(now, "c2", 0.02) + "\n", StandardCharsets.UTF_8);
    Path sharedFile = dir.resolve("user").resolve(UsageLog.FILE_NAME);
    UsageLog a = new UsageLog(sharedFile, "/ws/a");
    failed += check("an old workspace ledger is imported", a.importLegacy(legacy, "/ws/a") == 2);
    failed += check("the old ledger is renamed so it is not read again", !Files.exists(legacy)
        && Files.exists(legacy.resolveSibling(UsageLog.FILE_NAME + ".imported")));
    failed += check("imported lines count for their workspace", a.workspace("/ws/a").calls() == 2
        && Math.abs(a.allTime().usd() - 0.03) < 1e-9);
    Files.copy(legacy.resolveSibling(UsageLog.FILE_NAME + ".imported"), legacy);
    failed += check("an import cut short can run again without double counting",
        a.importLegacy(legacy, "/ws/a") == 0 && a.allTime().calls() == 2);

    UsageLog b = new UsageLog(sharedFile, "/ws/b");
    b.append("c3", "orders-api", "CUSTOM", cost(0.5));
    failed += check("a line another window appends shows in this window's totals",
        a.allTime().calls() == 3 && a.today().calls() == 3 && a.thisMonth().calls() == 3);
    failed += check("per-workspace totals keep workspaces apart",
        a.workspace("/ws/a").calls() == 2 && a.workspace("/ws/b").calls() == 1
            && a.workspace("/ws/c").calls() == 0);
    failed += check("per-project totals span workspaces", a.project("orders-api").calls() == 3);
    Files.writeString(sharedFile, "{\"ts\":\"" + now + "\",\"chat", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    failed += check("a torn last line is not counted", a.allTime().calls() == 3);
    failed += check("a fresh reader sees the same totals", new UsageLog(sharedFile).allTime().calls() == 3);
    return failed;
  }

  private static int chats(Path dir) throws Exception {
    int failed = 0;
    Path shared = dir.resolve("user");
    ChatFolders a = new ChatFolders(dir.resolve("ws-a"), shared, "/ws/a");
    ChatFolders b = new ChatFolders(dir.resolve("ws-b"), shared, "/ws/b");
    ChatRecord oldChat = chat("old chat in a", null);
    a.chats(false).save(oldChat);
    ChatRecord bChat = chat("chat in b", "/ws/b");
    b.chats(false).save(bChat);
    Files.createDirectories(dir.resolve("ws-b").resolve(ChatFolders.RAW));
    Files.writeString(b.raw(false).file(bChat.id()), "{}\n", StandardCharsets.UTF_8);

    failed += check("nothing is shared until sharing is turned on", a.chats(true).list().isEmpty());
    failed += check("sharing moves this workspace's chats", a.place(true) == 1 && a.chats(false).list().isEmpty()
        && a.chats(true).list().size() == 1);
    failed += check("a chat saved before workspaces were tagged is tagged with this one",
        "/ws/a".equals(a.chats(true).load(oldChat.id()).workspace()));
    failed += check("placing again moves nothing", a.place(true) == 0);
    b.place(true);
    failed += check("each workspace's chats end up in the shared folder", a.chats(true).list().size() == 2);
    failed += check("raw logs move with their chat", b.raw(true).exists(bChat.id()) && !b.raw(false).exists(bChat.id()));
    List<ChatRecord.Summary> all = a.chats(true).list();
    failed += check("each workspace tells its chats from the others'",
        all.stream().filter(a::mine).count() == 1 && all.stream().filter(b::mine).count() == 1);

    ChatRecord third = chat("saved by another window", "/ws/b");
    Path index = shared.resolve(ChatFolders.CHATS).resolve("index.json");
    String before = Files.readString(index);
    b.chats(true).save(third);
    Files.writeString(index, before);
    failed += check("an index that lost another window's save is rebuilt", a.chats(true).list().size() == 3);

    failed += check("turning sharing off moves only this workspace's chats back",
        a.place(false) == 1 && a.chats(false).list().size() == 1 && b.chats(true).list().size() == 2);
    List<String> deleted = b.chats(true).deleteWhere(b::mine);
    failed += check("deleting this workspace's history keeps other workspaces' chats",
        deleted.size() == 2 && b.chats(true).list().isEmpty() && a.chats(false).list().size() == 1);
    return failed;
  }

  private static ChatRecord chat(String title, String workspace) {
    String now = Instant.now().toString();
    return new ChatRecord(ChatRecord.SCHEMA, ChatStore.newId(), title, "orders-api", now, now,
        List.of(new ChatRecord.Entry("You", title)), List.of(), new CostMeter().snapshot(), 0, null, workspace);
  }

  private static String line(String ts, String chatId, double usd) {
    return "{\"ts\":\"" + ts + "\",\"chatId\":\"" + chatId + "\",\"project\":\"orders-api\",\"provider\":\"CUSTOM\","
        + "\"model\":\"claude-sonnet-5-5\",\"input\":100,\"cacheRead\":0,\"output\":10,\"usd\":" + usd
        + ",\"basis\":\"BILLED\"}";
  }

  private static Pricing.CallCost cost(double usd) {
    return new Pricing.CallCost(new CallUsage("claude-sonnet-5-5", 100, 10, 0, 0, 0, null), usd, Pricing.Basis.BILLED);
  }

  // A workspace's preferences, in memory.
  private static final class MapValues implements KeyValues {
    private final Map<String, String> values;

    MapValues(Map<String, String> initial) {
      values = new HashMap<>(initial);
    }

    @Override
    public String get(String key) {
      return values.getOrDefault(key, "");
    }

    @Override
    public Map<String, String> all() {
      return Map.copyOf(values);
    }

    @Override
    public Set<String> update(Map<String, String> changes) {
      Set<String> changed = new HashSet<>();
      changes.forEach((key, value) -> {
        String old = value == null ? values.remove(key) : values.put(key, value);
        if (!Objects.equals(old, value)) {
          changed.add(key);
        }
      });
      return changed;
    }
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
