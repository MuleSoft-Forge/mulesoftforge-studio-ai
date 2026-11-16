package com.mulesoftforge.studio.ai.history;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mulesoftforge.studio.ai.llm.CostMeter;

// One saved chat. entries is what the transcript showed; history is the compact model memory used to
// resume. Open-file contents and proposal bodies are deliberately not stored. auto is the model Auto last used
// in this chat, null when it never did. workspace is the Studio workspace folder the chat was started in, null in
// chats saved before chats could be shared across workspaces.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRecord(int schema, String id, String title, String project, String created, String updated,
    List<Entry> entries, List<Message> history, CostMeter.Snapshot cost, int pending, String auto, String workspace) {

  public static final int SCHEMA = 1;

  // speaker is You, AI, Error, Note, Proposed, Review, or Step for progress lines.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(String speaker, String text) {}

  // role is user or ai.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Message(String role, String text) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Summary(String id, String title, String project, String updated, double usd, int calls,
      boolean estimated, String workspace) {}

  public Summary summary() {
    CostMeter.Totals all = cost == null ? null : cost.all();
    return new Summary(id, title, project, updated, all == null ? 0 : all.usd(), all == null ? 0 : all.calls(),
        all != null && all.estimated(), workspace);
  }

  public ChatRecord withTitle(String newTitle) {
    return new ChatRecord(schema, id, newTitle, project, created, updated, entries, history, cost, pending, auto,
        workspace);
  }

  public ChatRecord withWorkspace(String newWorkspace) {
    return new ChatRecord(schema, id, title, project, created, updated, entries, history, cost, pending, auto,
        newWorkspace);
  }
}
