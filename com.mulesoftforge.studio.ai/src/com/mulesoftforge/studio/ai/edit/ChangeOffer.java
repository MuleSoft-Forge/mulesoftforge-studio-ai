package com.mulesoftforge.studio.ai.edit;

import java.util.List;

// One change the AI recommended but did not make. The view lists these as a checklist under the answer,
// and the ticked ones become an Agent-mode request.
public record ChangeOffer(String title, List<String> files) {
  public static final int MAX_ITEMS = 20;
  public static final int MAX_TITLE = 200;

  public ChangeOffer {
    title = title.strip();
    if (title.length() > MAX_TITLE) {
      title = title.substring(0, MAX_TITLE) + "…";
    }
    files = files == null ? List.of() : List.copyOf(files);
  }

  public String label() {
    return files.isEmpty() ? title : title + " (" + String.join(", ", files) + ")";
  }

  // The request sent when the user picks some of the offered changes.
  public static String prompt(List<ChangeOffer> picked) {
    StringBuilder out = new StringBuilder("Make these changes you recommended above:\n");
    for (int i = 0; i < picked.size(); i++) {
      out.append(i + 1).append(". ").append(picked.get(i).label()).append('\n');
    }
    return out.append("Propose all of them with the tools. Leave out the other recommendations.").toString();
  }

  // Kept with the answer in the model's memory, so a later turn knows what was offered.
  public static String remembered(List<ChangeOffer> offers) {
    return "[Offered to make: " + String.join("; ", offers.stream().map(ChangeOffer::title).toList()) + "]";
  }
}
