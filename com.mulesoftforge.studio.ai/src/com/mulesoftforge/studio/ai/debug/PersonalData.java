package com.mulesoftforge.studio.ai.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hides email addresses and phone numbers in live event data, which is real data rather than code. Each value becomes
// «email-N» or «phone-N», the same placeholder every time within a chat, so the model can still tell values apart.
// Secrets are SecretMask's job; this is only for personal data the debugger shows.
public final class PersonalData {
  private static final Pattern EMAIL = Pattern.compile("(?<![\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b");
  // International numbers, and the (555) 123-4567 style. Plain digit runs are left alone: they are usually ids.
  private static final Pattern PHONE = Pattern.compile(
      "(?<![\\w+])\\+\\d{1,3}[ .-]?(?:\\(\\d{1,4}\\)[ .-]?)?\\d{2,4}(?:[ .-]?\\d{2,4}){1,3}(?![\\w])"
          + "|(?<![\\w(])\\(\\d{3}\\)\\s?\\d{3}-\\d{4}(?!\\w)");

  private final Map<String, String> placeholders = new HashMap<>();
  private int emails;
  private int phones;
  private int total;

  public synchronized String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return replace(replace(text, EMAIL, "email"), PHONE, "phone");
  }

  // Values hidden so far.
  public synchronized int total() {
    return total;
  }

  public synchronized void clear() {
    placeholders.clear();
    emails = 0;
    phones = 0;
    total = 0;
  }

  private String replace(String text, Pattern pattern, String kind) {
    Matcher m = pattern.matcher(text);
    if (!m.find()) {
      return text;
    }
    StringBuilder out = new StringBuilder();
    do {
      String value = m.group();
      String placeholder = placeholders.computeIfAbsent(value,
          v -> "«" + kind + "-" + (kind.equals("email") ? ++emails : ++phones) + "»");
      total++;
      m.appendReplacement(out, Matcher.quoteReplacement(placeholder));
    } while (m.find());
    m.appendTail(out);
    return out.toString();
  }
}
