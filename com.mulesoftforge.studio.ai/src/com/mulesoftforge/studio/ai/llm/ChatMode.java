package com.mulesoftforge.studio.ai.llm;

// How the AI works on a message. Ask and Plan only get read tools (and offer_changes), so they cannot
// propose changes. Each mode's prompt is prompts/<name>.md.
public enum ChatMode {
  ASK("Ask", "Answers and explains. Never changes files; can suggest fixes for Agent mode to make.", false),
  AGENT("Agent", "Reads the project and proposes changes for you to review.", true),
  PLAN("Plan", "Reads the project and writes a step-by-step plan. Build it in Agent mode afterwards.", false);

  // Used when a plan came back without offer_changes.
  public static final String BUILD_PLAN_PROMPT = "Build the plan above. Propose all the changes it lists.";
  // Used when an answer suggested code but did not call offer_changes.
  public static final String MAKE_CHANGES_PROMPT = "Make the changes you suggested above. Propose them with the"
      + " tools.";

  public final String label;
  public final String description;
  public final boolean canEdit;

  ChatMode(String label, String description, boolean canEdit) {
    this.label = label;
    this.description = description;
    this.canEdit = canEdit;
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
