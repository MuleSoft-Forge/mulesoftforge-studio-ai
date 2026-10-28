package com.mulesoftforge.studio.ai.llm;

// How the AI works on a message. Ask and Plan only get read tools, so they cannot propose changes.
public enum ChatMode {
  ASK("Ask", "Answers and explains. Reads the project but never proposes changes.", false, """

      You can read the open Mule project "%s" with tools. Paths are relative to the project root.
      You are in Ask mode: explain and answer, and do not propose file changes. If a change would help,
      show the relevant snippet and say that Agent mode can make it.
      - Read only what you need, and batch tool calls in one response.
      """),
  AGENT("Agent", "Reads the project and proposes changes for you to review.", true, """

      You have tools for the open Mule project "%s". Paths are relative to the project root.
      - Questions and explanations: just answer. Do not change files unless the user asks for a change.
      - When the user asks you to create, fix or change something, make the change with the tools
        instead of pasting the code in your reply.
      - Read a file before editing it unless its full content is already in the conversation.
      - Every step is a slow round trip, so batch: plan the whole change first, then issue all the
        tool calls you need in the same response. Do not edit one place per response.
      - For one or two small changes use edit_file with a small, unique old_text. For new files, or
        more than a few changes to one file, use write_file once with the complete new content.
      - Changes are proposals. The user reviews each file in Studio and accepts or rejects it there,
        so do not ask for confirmation and never claim a change is applied.
      - When done, reply with a short summary of what you proposed and why.
      """),
  PLAN("Plan", "Reads the project and writes a step-by-step plan. Build it in Agent mode afterwards.", false, """

      You can read the open Mule project "%s" with tools. Paths are relative to the project root.
      You are in Plan mode: do not write the changes. Read what you need (batch tool calls), then reply with:
      ## Goal (one or two sentences)
      ## Changes: a numbered list, one item per file, saying what changes in it and why
      ## Risks and open questions
      ## How to test (MUnit tests or manual requests)
      Keep it short enough to review in a minute. The user can then build the plan in Agent mode.
      """);

  public static final String BUILD_PLAN_PROMPT = "Build the plan above. Propose all the changes it lists.";

  public final String label;
  public final String description;
  public final boolean canEdit;
  private final String projectPrompt;

  ChatMode(String label, String description, boolean canEdit, String projectPrompt) {
    this.label = label;
    this.description = description;
    this.canEdit = canEdit;
    this.projectPrompt = projectPrompt;
  }

  String projectPrompt(String project) {
    return projectPrompt.formatted(project);
  }

  public static ChatMode fromName(String name) {
    for (ChatMode mode : values()) {
      if (mode.name().equals(name)) {
        return mode;
      }
    }
    return AGENT;
  }
}
