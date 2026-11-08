package com.mulesoftforge.studio.ai.extend;

import java.nio.file.Path;

// One rule, skill or workflow. file is the rule or workflow file, or the skill's SKILL.md; null for a built-in.
// body is the text after the frontmatter.
public record Extension(Kind kind, String name, String description, Source source, Path file, boolean enabled,
    String body) {

  public enum Kind {
    RULE("rules", "rule"),
    SKILL("skills", "skill"),
    WORKFLOW("workflows", "workflow");

    public final String folder;
    public final String noun;

    Kind(String folder, String noun) {
      this.folder = folder;
      this.noun = noun;
    }
  }

  // In the order a name clash is settled: the project's file wins over the user's, which wins over a built-in.
  public enum Source {
    PROJECT("project"),
    GLOBAL("global"),
    BUILT_IN("built-in");

    public final String label;

    Source(String label) {
      this.label = label;
    }
  }

  // The skill's folder, where its reference files are; null for a built-in or for rules and workflows.
  public Path folder() {
    return kind == Kind.SKILL && file != null ? file.getParent() : null;
  }
}
