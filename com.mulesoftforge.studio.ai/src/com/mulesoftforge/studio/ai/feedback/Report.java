package com.mulesoftforge.studio.ai.feedback;

import java.util.Locale;

import com.mulesoftforge.studio.ai.llm.SecretMask;

// A bug report or feedback as it is posted: an issue title such as "Bug: Accept fails for a new file", and a
// body laid out like the repository's issue forms, so issues read the same whether they came from Studio or the
// website. Environment is null when the user chose not to include it.
public record Report(Kind kind, String title, String text, Environment environment) {
  public static final int MAX_TITLE = 200;
  public static final int MAX_TEXT = 30_000;
  public static final String FOOTER = "<sub>Sent from MuleSoftForge Studio AI</sub>";

  public enum Kind {
    BUG("Bug", "What happened"),
    FEEDBACK("Feedback", "Feedback");

    public final String prefix;
    public final String heading;

    Kind(String prefix, String heading) {
      this.prefix = prefix;
      this.heading = heading;
    }
  }

  public record Masked(Report report, int count) {}

  public Report {
    title = title == null ? "" : title;
    text = text == null ? "" : text;
  }

  // What the user typed: the title on one line, and both cut to what an issue should hold.
  public static Report of(Kind kind, String title, String text, Environment environment) {
    String line = title == null ? "" : title.replaceAll("\\s+", " ").strip();
    if (line.length() > MAX_TITLE) {
      line = line.substring(0, MAX_TITLE) + "…";
    }
    String body = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    if (body.length() > MAX_TEXT) {
      body = body.substring(0, MAX_TEXT) + "\n\n… cut at " + String.format(Locale.ROOT, "%,d", MAX_TEXT)
          + " characters";
    }
    return new Report(kind, line, body, environment);
  }

  public boolean complete() {
    return !title.isEmpty() && !text.isEmpty();
  }

  public String issueTitle() {
    return kind.prefix + ": " + title;
  }

  public String issueBody() {
    StringBuilder body = new StringBuilder("### ").append(kind.heading).append("\n\n").append(text).append("\n");
    if (environment != null && !environment.lines().isEmpty()) {
      body.append("\n### Environment\n\n```text\n").append(environment.text()).append("\n```\n");
    }
    return body.append("\n").append(FOOTER).append("\n").toString();
  }

  // Issues are public, so everything is masked, whatever the chat's masking setting is.
  public Masked masked() {
    SecretMask mask = new SecretMask();
    String maskedTitle = mask.mask(null, title).text();
    String maskedText = mask.mask(null, text).text();
    Environment env = environment == null ? null : new Environment(mask(mask, environment.plugin()),
        mask(mask, environment.studio()), mask(mask, environment.java()), mask(mask, environment.os()),
        mask(mask, environment.provider()), mask(mask, environment.model()), mask(mask, environment.dataWeave()));
    return new Masked(new Report(kind, maskedTitle, maskedText, env), mask.total());
  }

  private static String mask(SecretMask mask, String value) {
    return mask.mask(null, value).text();
  }
}
