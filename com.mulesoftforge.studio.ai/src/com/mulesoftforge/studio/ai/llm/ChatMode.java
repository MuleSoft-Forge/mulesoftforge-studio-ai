package com.mulesoftforge.studio.ai.llm;

import java.util.Locale;
import java.util.regex.Pattern;

// How the AI works on a message. Every mode is offered the same tools, so a mode switch keeps the prompt cache;
// the tools refuse what the mode does not allow (see refused). canEdit lets edit_file and write_file propose
// changes; canChangeApp lets the AI start the app, run MUnit tests and send requests that change data to
// the app running on this machine. Each mode's prompt is prompts/<name>.md.
public enum ChatMode {
  ASK("Ask", "Answers and explains. Never changes files; can suggest fixes for Agent mode to make.", false, false),
  AGENT("Agent", "Reads the project and proposes changes for you to review.", true, true),
  PLAN("Plan", "Reads the project and writes a step-by-step plan. Build it in Agent mode afterwards.", false, false),
  DIAGNOSE("Diagnose", "Reproduces a problem, reads the console and the Mule XML, and finds the cause with evidence."
      + " Never changes files, but can send any request to the app running in Studio. Fix it in Agent mode"
      + " afterwards.", false, true);

  // Starts every result of a call the mode does not allow. It is not a failure of the call, so the model should
  // not retry or rework it (Cline's TOOL_REJECTION_SUFFIX), and it is not counted as one.
  public static final String REFUSED = "NOT RUN IN THIS MODE (not a failure of your call; do not retry it):";
  // Used when a turn stopped at the step limit and the user chose to continue.
  public static final String CONTINUE_PROMPT = "Continue from where you stopped. Do not redo the steps you already"
      + " finished.";
  // Used when a plan came back without offer_changes.
  public static final String BUILD_PLAN_PROMPT = "Build the plan above. Propose all the changes it lists.";
  // Used when an answer suggested project code but did not call offer_changes.
  public static final String MAKE_CHANGES_PROMPT = "Make the changes you suggested above. Propose them with the"
      + " tools.";
  // Used when an answer suggested no changes, to get a checklist of improvements without editing yet.
  public static final String SUGGEST_PROMPT = "From what you saw above, list the improvements you would recommend"
      + " with offer_changes, one item per change. Do not edit files yet, and do not repeat your last answer.";
  // Used after the user accepted a fix that came from a diagnosis and Studio redeployed the app.
  public static final String VERIFY_PROMPT = "I accepted the fix and the app has redeployed. Repeat the requests or"
      + " steps that reproduced the problem and say whether it is fixed, quoting the response and the console.";

  private static final Pattern REPORTS_PROBLEM = Pattern.compile("\\b(errors?|exceptions?|fail(s|ed|ing|ure)?|"
      + "broken|not working|(doesn't|does not|won't|will not|isn't|is not) (work|start|deploy|run)|crash(es|ed)?|"
      + "stack ?trace|time[sd]? ?out|timeout|hangs?|stuck|[45]\\d\\d)\\b");
  // Mule's error-handling vocabulary, which talks about errors without anything going wrong.
  private static final Pattern ERROR_HANDLING = Pattern.compile("\\b(on-error-\\w+|error[- ]?(handl\\w*|types?))\\b");

  public final String label;
  public final String description;
  public final boolean canEdit;
  public final boolean canChangeApp;

  ChatMode(String label, String description, boolean canEdit, boolean canChangeApp) {
    this.label = label;
    this.description = description;
    this.canEdit = canEdit;
    this.canChangeApp = canChangeApp;
  }

  // what: the action and the mode that can do it, e.g. "Ask mode cannot change files; Agent mode can.".
  public static String refused(String what) {
    return REFUSED + " " + what + " Answer within this mode, and tell the user which mode to switch to if they want"
        + " it done.";
  }

  public static ChatMode fromName(String name) {
    for (ChatMode mode : values()) {
      if (mode.name().equals(name)) {
        return mode;
      }
    }
    return AGENT;
  }

  // Whether a message describes something going wrong, so Diagnose mode is worth suggesting.
  public static boolean reportsProblem(String message) {
    if (message == null) {
      return false;
    }
    String text = ERROR_HANDLING.matcher(message.toLowerCase(Locale.ROOT)).replaceAll(" ");
    return REPORTS_PROBLEM.matcher(text).find();
  }
}
