package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mulesoftforge.studio.ai.history.ChatRecord.Entry;
import com.mulesoftforge.studio.ai.history.ChatRecord.Summary;
import com.mulesoftforge.studio.ai.llm.CostMeter;

// One JSON file per chat plus index.json for the History menu. Writes go to a temp file and are moved
// into place, so a crash cannot leave a half-written chat.
public final class ChatStore {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final String INDEX = "index.json";
  private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path dir;

  public ChatStore(Path dir) {
    this.dir = dir;
  }

  public Path directory() {
    return dir;
  }

  public static String newId() {
    byte[] suffix = new byte[2];
    RANDOM.nextBytes(suffix);
    return LocalDateTime.now().format(ID_TIME) + "-" + HexFormat.of().formatHex(suffix);
  }

  public static String titleFrom(String prompt) {
    String line = prompt.strip().replaceAll("\\s+", " ");
    return line.length() <= 60 ? line : line.substring(0, 57).stripTrailing() + "…";
  }

  public synchronized void save(ChatRecord record) throws IOException {
    Files.createDirectories(dir);
    write(file(record.id()), JSON.writeValueAsString(record));
    List<Summary> index = new ArrayList<>(list());
    index.removeIf(s -> s.id().equals(record.id()));
    index.add(record.summary());
    writeIndex(index);
  }

  // Newest first. When the folder is shared, another Studio window may have saved or deleted a chat while this
  // one rewrote the index, so an index that does not match the chat files is rebuilt from them.
  public synchronized List<Summary> list() throws IOException {
    Path index = dir.resolve(INDEX);
    List<Summary> summaries;
    if (Files.isRegularFile(index)) {
      try {
        summaries = JSON.readValue(index.toFile(), new TypeReference<List<Summary>>() {});
        if (!ids(summaries).equals(chatIds())) {
          summaries = rebuildIndex();
        }
      } catch (IOException e) {
        summaries = rebuildIndex();
      }
    } else {
      summaries = rebuildIndex();
    }
    List<Summary> sorted = new ArrayList<>(summaries);
    sorted.sort(Comparator.comparing(Summary::updated, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
    return sorted;
  }

  public synchronized ChatRecord load(String id) throws IOException {
    return JSON.readValue(file(id).toFile(), ChatRecord.class);
  }

  public synchronized void rename(String id, String title) throws IOException {
    save(load(id).withTitle(title.strip()));
  }

  public synchronized void delete(String id) throws IOException {
    Files.deleteIfExists(file(id));
    List<Summary> index = new ArrayList<>(list());
    index.removeIf(s -> s.id().equals(id));
    writeIndex(index);
  }

  public synchronized int deleteAll() throws IOException {
    int deleted = 0;
    for (Summary s : list()) {
      Files.deleteIfExists(file(s.id()));
      deleted++;
    }
    Files.deleteIfExists(dir.resolve(INDEX));
    return deleted;
  }

  // Returns the ids deleted.
  public synchronized List<String> deleteWhere(Predicate<Summary> which) throws IOException {
    List<String> deleted = new ArrayList<>();
    for (Summary s : list()) {
      if (which.test(s)) {
        Files.deleteIfExists(file(s.id()));
        deleted.add(s.id());
      }
    }
    if (!deleted.isEmpty()) {
      rebuildIndex();
    }
    return deleted;
  }

  // Moves the chats that match into target, tagging any saved before chats had a workspace. Returns the ids moved.
  public List<String> moveTo(ChatStore target, Predicate<Summary> which, String workspace) throws IOException {
    List<String> moved = new ArrayList<>();
    for (Summary s : list()) {
      if (!which.test(s)) {
        continue;
      }
      ChatRecord record = load(s.id());
      target.save(record.workspace() == null ? record.withWorkspace(workspace) : record);
      delete(s.id());
      moved.add(s.id());
    }
    return moved;
  }

  // Deletes chats not updated within the last `days` days. Returns how many were removed.
  public synchronized int prune(int days, Instant now) throws IOException {
    if (days <= 0) {
      return 0;
    }
    Instant cutoff = now.minusSeconds(days * 86_400L);
    int removed = 0;
    for (Summary s : list()) {
      if (s.updated() != null && Instant.parse(s.updated()).isBefore(cutoff)) {
        delete(s.id());
        removed++;
      }
    }
    return removed;
  }

  public static String toMarkdown(ChatRecord record) {
    StringBuilder out = new StringBuilder("# ").append(record.title()).append("\n\n");
    out.append("- Project: ").append(record.project() == null ? "(none)" : record.project()).append('\n');
    out.append("- Started: ").append(local(record.created())).append('\n');
    out.append("- Last updated: ").append(local(record.updated())).append('\n');
    if (record.cost() != null && record.cost().all() != null && record.cost().all().calls() > 0) {
      out.append("- Cost: ").append(CostMeter.describe(record.cost().all())).append('\n');
    }
    for (Entry entry : record.entries()) {
      if ("Step".equals(entry.speaker())) {
        out.append("> ").append(entry.text()).append('\n');
      } else {
        out.append("\n## ").append(entry.speaker()).append("\n\n").append(entry.text()).append("\n\n");
      }
    }
    return out.toString();
  }

  private static String local(String instant) {
    return instant == null ? "?" : LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  private List<Summary> rebuildIndex() throws IOException {
    List<Summary> summaries = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return summaries;
    }
    for (Path p : chatFiles()) {
      try {
        summaries.add(JSON.readValue(p.toFile(), ChatRecord.class).summary());
      } catch (IOException e) {
        // Skip unreadable chat files rather than losing the whole history.
      }
    }
    writeIndex(summaries);
    return summaries;
  }

  private List<Path> chatFiles() throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().equals(INDEX)).toList();
    }
  }

  private Set<String> chatIds() throws IOException {
    Set<String> ids = new HashSet<>();
    for (Path p : chatFiles()) {
      String name = p.getFileName().toString();
      ids.add(name.substring(0, name.length() - ".json".length()));
    }
    return ids;
  }

  private static Set<String> ids(List<Summary> summaries) {
    Set<String> ids = new HashSet<>();
    summaries.forEach(s -> ids.add(s.id()));
    return ids;
  }

  private void writeIndex(List<Summary> index) throws IOException {
    Files.createDirectories(dir);
    write(dir.resolve(INDEX), JSON.writeValueAsString(index));
  }

  // Ids come from newId(); anything else could escape the folder.
  private Path file(String id) {
    if (id == null || !id.matches("[A-Za-z0-9-]+")) {
      throw new IllegalArgumentException("Invalid chat id: " + id);
    }
    return dir.resolve(id + ".json");
  }

  // A temp name of its own, so two Studio windows writing the same file cannot interleave in one temp file.
  private static void write(Path target, String content) throws IOException {
    Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
