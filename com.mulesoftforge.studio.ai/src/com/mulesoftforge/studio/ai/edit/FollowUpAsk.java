package com.mulesoftforge.studio.ai.edit;

import java.util.List;

// One clarifying question the model asked via ask_followup_question. Choices are complete answers
// the user can click; ChatView holds the latest ask for the open chat only (not saved to disk).
public record FollowUpAsk(String question, List<String> choices) {
  public static final int MIN_CHOICES = 2;
  public static final int MAX_CHOICES = 4;

  public FollowUpAsk {
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("question is required");
    }
    choices = List.copyOf(choices);
    if (choices.size() < MIN_CHOICES || choices.size() > MAX_CHOICES) {
      throw new IllegalArgumentException(
          "choices must have " + MIN_CHOICES + " to " + MAX_CHOICES + " answers");
    }
    for (String choice : choices) {
      if (choice == null || choice.isBlank()) {
        throw new IllegalArgumentException("every choice must be non-empty");
      }
    }
  }
}
