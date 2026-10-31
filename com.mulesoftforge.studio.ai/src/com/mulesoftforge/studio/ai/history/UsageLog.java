package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Pricing;

// Append-only cost ledger, one JSON line per model call. Never stores prompts or replies, and is kept
// when chats are deleted so spending totals stay honest. Running totals are kept in memory so the chat
// view does not re-parse the file on every refresh.
public final class UsageLog {
  private static final ObjectMapper JSON = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Line(String ts, String chatId, String project, String provider, String model, long input,
      long cacheRead, long output, Double usd, String basis) {}

  private final Path file;
  private boolean loaded;
  private CostMeter.Totals today = CostMeter.Totals.ZERO;
  private CostMeter.Totals thisMonth = CostMeter.Totals.ZERO;
  private CostMeter.Totals allTime = CostMeter.Totals.ZERO;
  private final Map<String, CostMeter.Totals> byProject = new HashMap<>();
  private LocalDate loadedDay;

  public UsageLog(Path file) {
    this.file = file;
  }

  public synchronized void append(String chatId, String project, String provider, Pricing.CallCost cost)
      throws IOException {
    ensureLoaded();
    CallUsage u = cost.usage();
    Line line = new Line(Instant.now().toString(), chatId, project, provider, u.model(), u.input(), u.cacheRead(),
        u.output(), cost.usd(), cost.basis().name());
    Files.createDirectories(file.getParent());
    Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    add(line);
  }

  public synchronized CostMeter.Totals today() {
    ensureLoaded();
    rolloverIfNeeded();
    return today;
  }

  public synchronized CostMeter.Totals thisMonth() {
    ensureLoaded();
    rolloverIfNeeded();
    return thisMonth;
  }

  public synchronized CostMeter.Totals allTime() {
    ensureLoaded();
    return allTime;
  }

  public synchronized CostMeter.Totals project(String project) {
    ensureLoaded();
    return byProject.getOrDefault(project, CostMeter.Totals.ZERO);
  }

  private void ensureLoaded() {
    if (loaded) {
      return;
    }
    loaded = true;
    loadedDay = LocalDate.now();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (raw.isBlank()) {
          continue;
        }
        try {
          add(JSON.readValue(raw, Line.class));
        } catch (IOException e) {
          // A torn last line after a crash must not hide the rest of the ledger.
        }
      }
    } catch (IOException e) {
      // Unreadable ledger: start counting from now.
    }
  }

  private void rolloverIfNeeded() {
    LocalDate todayDate = LocalDate.now();
    if (loadedDay != null && loadedDay.equals(todayDate)) {
      return;
    }
    // Day or month boundary: rebuild totals from the file once.
    today = CostMeter.Totals.ZERO;
    thisMonth = CostMeter.Totals.ZERO;
    allTime = CostMeter.Totals.ZERO;
    byProject.clear();
    loaded = false;
    ensureLoaded();
  }

  private void add(Line line) {
    CostMeter.Totals delta = totalsOf(line);
    allTime = plus(allTime, delta);
    LocalDate date = LocalDate.ofInstant(Instant.parse(line.ts()), ZoneId.systemDefault());
    LocalDate todayDate = LocalDate.now();
    if (date.equals(todayDate)) {
      today = plus(today, delta);
    }
    if (date.getYear() == todayDate.getYear() && date.getMonth() == todayDate.getMonth()) {
      thisMonth = plus(thisMonth, delta);
    }
    if (line.project() != null) {
      byProject.merge(line.project(), delta, UsageLog::plus);
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
