package com.mulesoftforge.studio.ai.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mulesoftforge.studio.ai.llm.Pricing.CallCost;

// Running cost totals. One per chat, one per answer, and SESSION for everything since Studio started.
public final class CostMeter {
  public static final CostMeter SESSION = new CostMeter();

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Totals(int calls, long input, long output, double usd, int unpriced, boolean estimated) {
    public static final Totals ZERO = new Totals(0, 0, 0, 0, 0, false);

    Totals plus(CallCost cost) {
      return new Totals(calls + 1, input + cost.usage().input(), output + cost.usage().output(),
          usd + (cost.usd() == null ? 0 : cost.usd()), unpriced + (cost.usd() == null ? 1 : 0),
          estimated || cost.basis() == Pricing.Basis.ESTIMATED);
    }
  }

  // Saved with a chat so its totals survive a restart.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Snapshot(Totals all, Map<String, Totals> byModel) {}

  private Totals all = Totals.ZERO;
  private final Map<String, Totals> byModel = new LinkedHashMap<>();
  private int chats;

  public synchronized void add(CallCost cost) {
    all = all.plus(cost);
    byModel.merge(cost.usage().model(), Totals.ZERO.plus(cost), (a, b) -> a.plus(cost));
  }

  public synchronized void countChat() {
    chats++;
  }

  public synchronized int chats() {
    return chats;
  }

  public synchronized boolean isEmpty() {
    return all.calls() == 0;
  }

  public synchronized void reset() {
    all = Totals.ZERO;
    byModel.clear();
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(all, new LinkedHashMap<>(byModel));
  }

  public synchronized void restore(Snapshot snapshot) {
    reset();
    if (snapshot != null && snapshot.all() != null) {
      all = snapshot.all();
      if (snapshot.byModel() != null) {
        byModel.putAll(snapshot.byModel());
      }
    }
  }

  // e.g. "~$0.0213 · 45.2k tokens"
  public synchronized String summary() {
    return describe(all);
  }

  // One line per model, for tooltips.
  public synchronized String breakdown() {
    StringBuilder out = new StringBuilder();
    byModel.forEach((model, totals) -> out.append(model).append(": ").append(describe(totals))
        .append(" in ").append(totals.calls()).append(totals.calls() == 1 ? " call" : " calls")
        .append(" (").append(tokens(totals.input())).append(" in / ").append(tokens(totals.output()))
        .append(" out)\n"));
    return out.toString().stripTrailing();
  }

  public static String describe(Totals totals) {
    String money = (totals.estimated() ? "~" : "") + usd(totals.usd());
    String text = money + " · " + tokens(totals.input() + totals.output()) + " tokens";
    return totals.unpriced() == 0 ? text : text + " (+" + totals.unpriced() + " unpriced)";
  }

  public static String usd(double value) {
    if (value == 0) {
      return "$0.00";
    }
    if (value < 0.0001) {
      return "<$0.0001";
    }
    return String.format(Locale.ROOT, value < 1 ? "$%.4f" : "$%.2f", value);
  }

  public static String tokens(long count) {
    if (count < 1000) {
      return Long.toString(count);
    }
    return String.format(Locale.ROOT, count < 1_000_000 ? "%.1fk" : "%.2fM",
        count < 1_000_000 ? count / 1000.0 : count / 1_000_000.0);
  }

  public static String describe(CallCost cost) {
    CallUsage u = cost.usage();
    String money = cost.usd() == null ? "price unknown"
        : (cost.basis() == Pricing.Basis.ESTIMATED ? "~" : "") + usd(cost.usd());
    return tokens(u.input()) + " in" + (u.cacheRead() > 0 ? " (" + tokens(u.cacheRead()) + " cached)" : "")
        + " / " + tokens(u.output()) + " out" + (u.reasoning() > 0 ? " (" + tokens(u.reasoning()) + " reasoning)" : "")
        + " · " + money;
  }
}
