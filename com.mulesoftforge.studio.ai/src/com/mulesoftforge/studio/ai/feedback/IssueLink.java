package com.mulesoftforge.studio.ai.feedback;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// GitHub's New issue page with the repository's issue form filled in, for people who do not sign in from Studio.
// Fields are filled by their ids in .github/ISSUE_TEMPLATE, so renaming a field there breaks the link for it.
// The report's text is cut to keep the link under maxLength; when it is, the caller puts the whole text on the
// clipboard, as the note at the cut says.
public final class IssueLink {
  public static final int MAX_LENGTH = 8_000;
  // Windows limits URLs to 2,083 characters in places (INTERNET_MAX_URL_LENGTH).
  public static final int WINDOWS_MAX_LENGTH = 2_000;
  public static final String BUG_FORM = "bug_report.yml";
  public static final String FEEDBACK_FORM = "feedback.yml";
  public static final String CUT = "\n\n… cut to fit in a link. The whole text is on your clipboard.";
  // The bug form's provider list; any other provider is left for the user to pick.
  private static final List<String> PROVIDERS = List.of("OpenRouter", "OpenAI", "Anthropic", "Google Gemini",
      "Azure OpenAI", "Amazon Bedrock", "Ollama", "Other OpenAI-compatible");

  public record Link(String url, boolean cut) {}

  private IssueLink() {}

  public static Link of(String owner, String repo, Report report) {
    return of(owner, repo, report, MAX_LENGTH);
  }

  public static Link of(String owner, String repo, Report report, int maxLength) {
    boolean bug = report.kind() == Report.Kind.BUG;
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("template", bug ? BUG_FORM : FEEDBACK_FORM);
    fields.put("title", report.issueTitle());
    Environment env = report.environment();
    if (env != null) {
      if (bug) {
        put(fields, "plugin-version", env.plugin());
        put(fields, "provider", provider(env.provider()));
        put(fields, "model", env.model());
        put(fields, "studio", join(env.studio(), env.os()));
      }
      put(fields, "environment", env.text());
    }
    StringBuilder link = new StringBuilder("https://github.com/").append(owner).append('/').append(repo)
        .append("/issues/new?");
    fields.forEach((name, value) -> link.append(name).append('=').append(encode(value)).append('&'));
    link.append(bug ? "what-happened" : "feedback").append('=');
    String text = fit(report.text(), maxLength - link.length());
    return new Link(link.append(text).toString(), !text.equals(encode(report.text())));
  }

  static String provider(String label) {
    if (label.startsWith("Ollama")) {
      return "Ollama";
    }
    return PROVIDERS.contains(label) ? label : "";
  }

  // The longest start of the text that, encoded and followed by the cut note, fits in room characters.
  private static String fit(String text, int room) {
    String whole = encode(text);
    if (whole.length() <= room) {
      return whole;
    }
    String note = encode(CUT);
    if (note.length() > room) {
      return "";
    }
    int low = 0;
    int high = text.length();
    while (low < high) {
      int mid = (low + high + 1) / 2;
      if (encode(text.substring(0, cutAt(text, mid))).length() + note.length() <= room) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return encode(text.substring(0, cutAt(text, low))) + note;
  }

  // Never between the two halves of an emoji or other surrogate pair.
  private static int cutAt(String text, int index) {
    return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1
        : index;
  }

  private static void put(Map<String, String> fields, String name, String value) {
    if (value != null && !value.isEmpty()) {
      fields.put(name, value);
    }
  }

  private static String join(String... parts) {
    List<String> present = new ArrayList<>();
    for (String part : parts) {
      if (part != null && !part.isEmpty()) {
        present.add(part);
      }
    }
    return String.join(", ", present);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
