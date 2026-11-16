package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Pricing;

// Append-only cost ledger, one JSON line per model call, shared by every workspace and tagged with the one the call
// came from. Never stores prompts or replies, and is kept when chats are deleted so spending totals stay honest.
// Running totals are kept in memory; each read first takes in lines other Studio windows appended since.
public final class UsageLog {
  public static final String FILE_NAME = "usage.jsonl";
  private static final String IMPORTED = ".imported";
  private static final ObjectMapper JSON = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  // workspace is null in lines written before the ledger was shared.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Line(String ts, String chatId, String project, String provider, String model, long input,
      long cacheRead, long output, Double usd, String basis, String workspace) {
    Line withWorkspace(String newWorkspace) {
      return new Line(ts, chatId, project, provider, model, input, cacheRead, output, usd, basis, newWorkspace);
    }
  }

  private final Path file;
  private final String workspace;
  private long offset;
  private CostMeter.Totals today = CostMeter.Totals.ZERO;
  private CostMeter.Totals thisMonth = CostMeter.Totals.ZERO;
  private CostMeter.Totals allTime = CostMeter.Totals.ZERO;
  private final Map<String, CostMeter.Totals> byProject = new HashMap<>();
  private final Map<String, CostMeter.Totals> byWorkspace = new HashMap<>();
  private LocalDate loadedDay;

  public UsageLog(Path file) {
    this(file, null);
  }

  // workspace tags the lines this instance appends.
  public UsageLog(Path file, String workspace) {
    this.file = file;
    this.workspace = workspace;
  }

  public synchronized void append(String chatId, String project, String provider, Pricing.CallCost cost)
      throws IOException {
    CallUsage u = cost.usage();
    write(List.of(new Line(Instant.now().toString(), chatId, project, provider, u.model(), u.input(), u.cacheRead(),
        u.output(), cost.usd(), cost.basis().name(), workspace)));
  }

  // Copies a workspace's ledger from before it was shared into this one, tagged with that workspace, then renames
  // it so it is not read again. Lines already here are skipped, so an import cut short can simply run again.
  public synchronized int importLegacy(Path legacy, String legacyWorkspace) throws IOException {
    if (!Files.isRegularFile(legacy) || legacy.equals(file)) {
      return 0;
    }
    Set<Line> existing = new HashSet<>(readAll(file));
    List<Line> missing = new ArrayList<>();
    for (Line line : readAll(legacy)) {
      Line tagged = line.workspace() == null ? line.withWorkspace(legacyWorkspace) : line;
      if (existing.add(tagged)) {
        missing.add(tagged);
      }
    }
    if (!missing.isEmpty()) {
      write(missing);
    }
    Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + IMPORTED), StandardCopyOption.REPLACE_EXISTING);
    return missing.size();
  }

  public synchronized CostMeter.Totals today() {
    catchUp();
    return today;
  }

  public synchronized CostMeter.Totals thisMonth() {
    catchUp();
    return thisMonth;
  }

  public synchronized CostMeter.Totals allTime() {
    catchUp();
    return allTime;
  }

  public synchronized CostMeter.Totals project(String project) {
    catchUp();
    return byProject.getOrDefault(project, CostMeter.Totals.ZERO);
  }

  // Lines from before the ledger was shared count for no workspace until they are imported.
  public synchronized CostMeter.Totals workspace(String name) {
    catchUp();
    return byWorkspace.getOrDefault(name, CostMeter.Totals.ZERO);
  }

  // One write per batch with O_APPEND, so lines from two Studio windows do not interleave.
  private void write(List<Line> lines) throws IOException {
    StringBuilder out = new StringBuilder();
    for (Line line : lines) {
      out.append(JSON.writeValueAsString(line)).append('\n');
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    catchUp();
  }

  // Reads the complete lines added since the last read. A day or month boundary, or a file that shrank, rebuilds
  // the totals from the start.
  private void catchUp() {
    LocalDate todayDate = LocalDate.now();
    long size;
    try {
      size = Files.isRegularFile(file) ? Files.size(file) : 0;
    } catch (IOException e) {
      return;
    }
    if (!todayDate.equals(loadedDay) || size < offset) {
      reset(todayDate);
    }
    if (size == offset) {
      return;
    }
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 8, size - offset));
      while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) {}
      byte[] bytes = new byte[buffer.position()];
      buffer.flip().get(bytes);
      int end = lastNewline(bytes);
      if (end < 0) {
        return;
      }
      for (String raw : new String(bytes, 0, end + 1, StandardCharsets.UTF_8).split("\n")) {
        Line line = parse(raw);
        if (line != null) {
          add(line);
        }
      }
      offset += end + 1;
    } catch (IOException e) {
      // Unreadable ledger: keep the totals read so far.
    }
  }

  private void reset(LocalDate day) {
    offset = 0;
    loadedDay = day;
    today = CostMeter.Totals.ZERO;
    thisMonth = CostMeter.Totals.ZERO;
    allTime = CostMeter.Totals.ZERO;
    byProject.clear();
    byWorkspace.clear();
  }

  private static int lastNewline(byte[] bytes) {
    for (int i = bytes.length - 1; i >= 0; i--) {
      if (bytes[i] == '\n') {
        return i;
      }
    }
    return -1;
  }

  private static List<Line> readAll(Path path) throws IOException {
    List<Line> lines = new ArrayList<>();
    if (!Files.isRegularFile(path)) {
      return lines;
    }
    for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      Line line = parse(raw);
      if (line != null) {
        lines.add(line);
      }
    }
    return lines;
  }

  // A torn last line after a crash must not hide the rest of the ledger.
  private static Line parse(String raw) {
    if (raw.isBlank()) {
      return null;
    }
    try {
      return JSON.readValue(raw, Line.class);
    } catch (IOException e) {
      return null;
    }
  }

  private void add(Line line) {
    CostMeter.Totals delta = totalsOf(line);
    allTime = plus(allTime, delta);
    LocalDate date = LocalDate.ofInstant(Instant.parse(line.ts()), ZoneId.systemDefault());
    if (date.equals(loadedDay)) {
      today = plus(today, delta);
    }
    if (date.getYear() == loadedDay.getYear() && date.getMonth() == loadedDay.getMonth()) {
      thisMonth = plus(thisMonth, delta);
    }
    if (line.project() != null) {
      byProject.merge(line.project(), delta, UsageLog::plus);
    }
    if (line.workspace() != null) {
      byWorkspace.merge(line.workspace(), delta, UsageLog::plus);
    }
  }

  private static CostMeter.Totals totalsOf(Line line) {
    return new CostMeter.Totals(1, line.input(), line.output(), line.usd() == null ? 0 : line.usd(),
        line.usd() == null ? 1 : 0, Pricing.Basis.ESTIMATED.name().equals(line.basis()));
  }

  private static CostMeter.Totals plus(CostMeter.Totals a, CostMeter.Totals b) {
    return new CostMeter.Totals(a.calls() + b.calls(), a.input() + b.input(), a.output() + b.output(),
        a.usd() + b.usd(), a.unpriced() + b.unpriced(), a.estimated() || b.estimated());
  }
}
