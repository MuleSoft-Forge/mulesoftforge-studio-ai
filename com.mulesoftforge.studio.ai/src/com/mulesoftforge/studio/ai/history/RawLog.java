package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Files.writeString(file(id), JSON.writeValueAsString(call) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  // Lines that cannot be read (a crash mid-write) are skipped.
  public synchronized List<Call> load(String id) throws IOException {
    if (!exists(id)) {
      return List.of();
    }
    List<Call> calls = new ArrayList<>();
    for (String line : Files.readAllLines(file(id), StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      try {
        calls.add(JSON.readValue(line, Call.class));
      } catch (IOException e) {
        // Partial line.
      }
    }
    return calls;
  }

  public Path writeReadable(String id) throws IOException {
    Path target = dir.resolve(checked(id) + READABLE);
    Files.writeString(target, readable(load(id)), StandardCharsets.UTF_8);
    return target;
  }

  public static String readable(List<Call> calls) {
    StringBuilder out = new StringBuilder();
    for (Call call : calls) {
      out.append("===== Turn ").append(call.turn()).append(" · ").append(call.heading()).append(" · ")
          .append(call.at()).append(" =====\n");
      out.append("----- request").append(call.http() == null ? " (as LangChain4j built it)" : " body")
          .append(" -----\n").append(call.requestText()).append("\n");
      out.append("----- response").append(call.http() == null ? " (as LangChain4j read it)" : " body")
          .append(" -----\n").append(call.responseText()).append("\n\n");
    }
    return out.toString();
  }

  public synchronized void delete(String id) throws IOException {
    Files.deleteIfExists(file(id));
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
