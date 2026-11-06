package com.mulesoftforge.studio.ai.render;

import java.util.List;
import java.util.Locale;

// Words on the live status line while the model works, as gemini-cli's loading phrases: tips change every
// 10 s and witty lines every 5 s. Off unless turned on in Preferences.
public final class LoadingPhrases {
  public enum Mode {
    OFF("Off"), TIPS("Tips"), WITTY("Witty lines"), ALL("Tips and witty lines");

    public final String label;

    Mode(String label) {
      this.label = label;
    }

    public static Mode fromName(String name) {
      try {
        return name == null || name.isBlank() ? OFF : valueOf(name.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return OFF;
      }
    }
  }

  static final int TIP_SECONDS = 10;
  static final int WITTY_SECONDS = 5;

  public static final List<String> TIPS = List.of(
      "Tip: nothing is written to the project until you accept the diffs",
      "Tip: type your next message now and it is queued",
      "Tip: select text in an answer and click Quote to reply to it",
      "Tip: shift-click an answer choice to edit it before sending",
      "Tip: Retry and Edit last message are under each answer",
      "Tip: Plan mode drafts a plan first; Agent mode drafts the changes",
      "Tip: Diagnose mode reproduces a failure and reads the console",
      "Tip: Auto picks a fast or a strong model for each message",
      "Tip: passwords and keys are masked before anything is sent",
      "Tip: paste a screenshot into the message box to ask about it",
      "Tip: turn on Raw to see every request and response",
      "Tip: add your own instructions under Preferences > MuleSoftForge Studio AI");

  public static final List<String> WITTY = List.of(
      "Untangling flow-refs…",
      "Asking DataWeave nicely…",
      "Reticulating scatter-gathers…",
      "Polishing the APIkit router…",
      "Herding message processors…",
      "Coaxing the payload into JSON…",
      "Consulting the error handlers…",
      "Warming up the Mule runtime…",
      "Brushing the Mule…",
      "Checking the Mule's horseshoes…",
      "Mapping fields, one by one…",
      "Asking the Object Store what it remembers…",
      "Untying a Try scope…",
      "Rehearsing an MUnit spy…",
      "Teaching DataWeave a new trick…",
      "Looking for that missing namespace…",
      "Retrying, Until Successful…",
      "Making a For Each feel special…",
      "Reading the console so you don't have to…",
      "Validating the XML against its schema…",
      "Folding the flow diagram neatly…",
      "Cross-referencing global elements…",
      "Counting the Choice routes…",
      "Convincing the connector to connect…",
      "Re-reading the RAML, slowly…");

  private LoadingPhrases() {}

  // The phrase for a turn that has run this many seconds; seed varies where each turn starts. null when off.
  public static String at(Mode mode, long seconds, int seed) {
    long elapsed = Math.max(0, seconds);
    return switch (mode) {
      case OFF -> null;
      case TIPS -> pick(TIPS, elapsed / TIP_SECONDS, seed);
      case WITTY -> pick(WITTY, elapsed / WITTY_SECONDS, seed);
      case ALL -> {
        long slot = elapsed / WITTY_SECONDS;
        yield slot % 2 == 0 ? pick(WITTY, slot / 2, seed) : pick(TIPS, slot / 2, seed);
      }
    };
  }

  private static String pick(List<String> phrases, long slot, int seed) {
    return phrases.get((int) Math.floorMod(slot + seed, (long) phrases.size()));
  }
}
