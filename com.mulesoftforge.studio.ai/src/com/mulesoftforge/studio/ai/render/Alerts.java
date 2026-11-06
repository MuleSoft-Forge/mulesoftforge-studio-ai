package com.mulesoftforge.studio.ai.render;

// What the view tells someone who is in another app when a turn ends, as Codex and gemini-cli notify only when the
// terminal is unfocused: the Dock or taskbar badge and the text a Notification hook gets.
public final class Alerts {
  public enum Kind {
    ANSWER("answer", "MuleSoftForge Studio AI has answered"),
    QUESTION("question", "MuleSoftForge Studio AI is waiting for your answer"),
    ERROR("error", "MuleSoftForge Studio AI stopped with an error");

    public final String type;
    public final String message;

    Kind(String type, String message) {
      this.type = type;
      this.message = message;
    }
  }

  private Alerts() {}

  // Unseen answers as a count, or "?" while a question waits, which matters more; "" clears the badge.
  public static String badge(int unseen, boolean question) {
    if (question) {
      return "?";
    }
    if (unseen <= 0) {
      return "";
    }
    return unseen > 9 ? "9+" : Integer.toString(unseen);
  }
}
