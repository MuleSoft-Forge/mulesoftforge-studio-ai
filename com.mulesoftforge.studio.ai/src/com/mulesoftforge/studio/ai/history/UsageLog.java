package com.mulesoftforge.studio.ai.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Pricing;

// Append-only cost ledger, one JSON line per model call. Never stores prompts or replies, and is kept
// when chats are deleted so spending totals stay honest.
public final class UsageLog {
  private static final ObjectMapper JSON = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Line(String ts, String chatId, String project, String provider, String model, long input,
      long cacheRead, long output, Double usd, String basis) {}

  private final Path file;
  private List<Line> lines;

  public UsageLog(Path file) {
    this.file = file;
  }

  public synchronized void append(String chatId, String project, String provider, Pricing.CallCost cost)
      throws IOException {
    List<Line> cached = loaded();
    CallUsage u = cost.usage();
    Line line = new Line(Instant.now().toString(), chatId, project, provider, u.model(), u.input(), u.cacheRead(),
        u.output(), cost.usd(), cost.basis().name());
    Files.createDirectories(file.getParent());
    Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    cached.add(line);
  }

  public synchronized CostMeter.Totals today() {
    LocalDate today = LocalDate.now();
    return sum(l -> date(l).equals(today));
  }

  public synchronized CostMeter.Totals thisMonth() {
    LocalDate today = LocalDate.now();
    return sum(l -> {
      LocalDate d = date(l);
      return d.getYear() == today.getYear() && d.getMonth() == today.getMonth();
    });
  }

  public synchronized CostMeter.Totals allTime() {
    return sum(l -> true);
  }

  public synchronized CostMeter.Totals project(String project) {
    return sum(l -> project != null && project.equals(l.project()));
  }

  public synchronized CostMeter.Totals sum(Predicate<Line> filter) {
    int calls = 0;
    long input = 0;
    long output = 0;
    double usd = 0;
    int unpriced = 0;
    boolean estimated = false;
    for (Line l : loaded()) {
      if (!filter.test(l)) {
        continue;
      }
      calls++;
      input += l.input();
      output += l.output();
      if (l.usd() == null) {
        unpriced++;
      } else {
        usd += l.usd();
      }
      estimated |= Pricing.Basis.ESTIMATED.name().equals(l.basis());
    }
    return new CostMeter.Totals(calls, input, output, usd, unpriced, estimated);
  }

  private static LocalDate date(Line line) {
    return LocalDate.ofInstant(Instant.parse(line.ts()), ZoneId.systemDefault());
  }

  private List<Line> loaded() {
    if (lines != null) {
      return lines;
    }
    lines = new ArrayList<>();
    if (Files.isRegularFile(file)) {
      try {
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          if (raw.isBlank()) {
            continue;
          }
          try {
            lines.add(JSON.readValue(raw, Line.class));
          } catch (IOException e) {
            // A torn last line after a crash must not hide the rest of the ledger.
          }
        }
      } catch (IOException e) {
        // Unreadable ledger: start counting from now.
      }
    }
    return lines;
  }
}
