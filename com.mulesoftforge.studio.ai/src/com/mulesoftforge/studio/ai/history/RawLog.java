package com.mulesoftforge.studio.ai.history;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.RawCapture.Call;

// Opt-in record of every model call in a chat, one JSON object per line in <id>.jsonl. HTTP bodies are stored
// exactly as sent and received; headers are never captured, so API keys and gateway secrets are not written.
// <id>.txt is a readable copy, regenerated whenever it is opened.
public final class RawLog {
  private static final ObjectMapper JSON = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final String LOG = ".jsonl";
  private static final String READABLE = ".txt";

  private final Path dir;

  public RawLog(Path dir) {
    this.dir = dir;
  }

  // Ids come from ChatStore.newId(); anything else could escape the folder.
  public Path file(String id) {
    return dir.resolve(checked(id) + LOG);
  }

  public boolean exists(String id) {
    return id != null && Files.isRegularFile(file(id));
  }

  public synchronized void append(String id, Call call) throws IOException {
    Files.createDirectories(dir);
    restrictOwner(dir);
    Path target = file(id);
    Files.writeString(target, JSON.writeValueAsString(call) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    restrictOwner(target);
  }

  // One call without its bodies; size is the length of its line in the log, in characters.
  public record Entry(int turn, String heading, long size) {}

  private interface Visitor {
    void visit(Call call, long size) throws IOException;
  }

  // Holds every body in memory: only for logs known to be small. A long chat's log can be hundreds of MB.
  public synchronized List<Call> load(String id) throws IOException {
    List<Call> calls = new ArrayList<>();
    each(id, (call, size) -> calls.add(call));
    return calls;
  }

  // The calls without their bodies, read one line at a time, for showing a saved chat.
  public synchronized List<Entry> index(String id) throws IOException {
    List<Entry> entries = new ArrayList<>();
    each(id, (call, size) -> entries.add(new Entry(call.turn(), call.heading(), size)));
    return entries;
  }

  public synchronized Path writeReadable(String id) throws IOException {
    Path target = dir.resolve(checked(id) + READABLE);
    try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      each(id, (call, size) -> out.write(readable(call)));
    }
    return target;
  }

  public static String readable(List<Call> calls) {
    StringBuilder out = new StringBuilder();
    for (Call call : calls) {
      out.append(readable(call));
    }
    return out.toString();
  }

  private static String readable(Call call) {
    return "===== Turn " + call.turn() + " · " + call.heading() + " · " + call.at() + " =====\n"
        + "----- request" + (call.http() == null ? " (as LangChain4j built it)" : " body") + " -----\n"
        + call.requestText() + "\n"
        + "----- response" + (call.http() == null ? " (as LangChain4j read it)" : " body") + " -----\n"
        + call.responseText() + "\n\n";
  }

  // Lines that cannot be read (a crash mid-write) are skipped.
  private void each(String id, Visitor visitor) throws IOException {
    if (!exists(id)) {
      return;
    }
    try (BufferedReader in = Files.newBufferedReader(file(id), StandardCharsets.UTF_8)) {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        Call call;
        try {
          call = JSON.readValue(line, Call.class);
        } catch (IOException e) {
          continue;
        }
        visitor.visit(call, line.length());
      }
    }
  }

  public synchronized void delete(String id) throws IOException {
    Files.deleteIfExists(file(id));
    Files.deleteIfExists(dir.resolve(checked(id) + READABLE));
  }

  // Moves a chat's log to another folder, as its chat moves between this workspace and the shared folder.
  public synchronized void moveTo(RawLog target, String id) throws IOException {
    if (!exists(id)) {
      return;
    }
    Files.createDirectories(target.dir);
    restrictOwner(target.dir);
    Files.move(file(id), target.file(id), StandardCopyOption.REPLACE_EXISTING);
    Files.deleteIfExists(dir.resolve(checked(id) + READABLE));
  }

  public synchronized int deleteAll() throws IOException {
    int deleted = 0;
    for (Path p : files()) {
      Files.deleteIfExists(p);
      deleted += p.getFileName().toString().endsWith(LOG) ? 1 : 0;
    }
    return deleted;
  }

  // Deletes logs not written to within the last `days` days, matching chat retention.
  public synchronized int prune(int days, Instant now) throws IOException {
    if (days <= 0) {
      return 0;
    }
    Instant cutoff = now.minusSeconds(days * 86_400L);
    int removed = 0;
    for (Path p : files()) {
      if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
        Files.deleteIfExists(p);
        removed += p.getFileName().toString().endsWith(LOG) ? 1 : 0;
      }
    }
    return removed;
  }

  private static String checked(String id) {
    if (id == null || !id.matches("[A-Za-z0-9-]+")) {
      throw new IllegalArgumentException("Invalid chat id: " + id);
    }
    return id;
  }

  // Owner-only on POSIX so raw request bodies are not world-readable, in the workspace or the shared folder.
  private static void restrictOwner(Path path) {
    try {
      java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = new java.util.HashSet<>();
      perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
      perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
      if (Files.isDirectory(path)) {
        perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
      }
      Files.setPosixFilePermissions(path, perms);
    } catch (UnsupportedOperationException | IOException e) {
      // Windows and some filesystems have no POSIX permissions.
    }
  }

  private List<Path> files() throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> {
        String name = p.getFileName().toString();
        return name.endsWith(LOG) || name.endsWith(READABLE);
      }).toList();
    }
  }
}
