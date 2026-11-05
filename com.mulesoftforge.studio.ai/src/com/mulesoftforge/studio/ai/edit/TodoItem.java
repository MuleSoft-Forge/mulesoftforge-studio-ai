package com.mulesoftforge.studio.ai.edit;

import java.util.Locale;

// One item from update_todo_list. Status strings match Roo-Code / gemini-cli.
public record TodoItem(String id, String content, Status status) {
  public enum Status {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    public static Status parse(String value) {
      if (value == null || value.isBlank()) {
        return PENDING;
      }
      return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
        case "in_progress", "inprogress", "active" -> IN_PROGRESS;
        case "completed", "done", "complete" -> COMPLETED;
        default -> PENDING;
      };
    }

    public String wire() {
      return switch (this) {
        case PENDING -> "pending";
        case IN_PROGRESS -> "in_progress";
        case COMPLETED -> "completed";
      };
    }
  }

  public TodoItem {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id is required");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    status = status == null ? Status.PENDING : status;
  }

  public static TodoItem of(String id, String content, String status) {
    return new TodoItem(id, content, Status.parse(status));
  }
}
