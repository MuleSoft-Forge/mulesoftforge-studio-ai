package com.mulesoftforge.studio.ai.history;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mulesoftforge.studio.ai.llm.CostMeter;

// One saved chat. entries is what the transcript showed; history is the compact model memory used to
// resume. Open-file contents and proposal bodies are deliberately not stored.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRecord(int schema, String id, String title, String project, String created, String updated,
    List<Entry> entries, List<Message> history, CostMeter.Snapshot cost, int pending) {

  public static final int SCHEMA = 1;

  // speaker is You, AI, Error, Note, Proposed, Review, or Step for progress lines.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(String speaker, String text) {}

  // role is user or ai.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Message(String role, String text) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Summary(String id, String title, String project, String updated, double usd, int calls,
      boolean estimated) {}

  public Summary summary() {
    CostMeter.Totals all = cost == null ? null : cost.all();
    return new Summary(id, title, project, updated, all == null ? 0 : all.usd(), all == null ? 0 : all.calls(),
        all != null && all.estimated());
  }
}
