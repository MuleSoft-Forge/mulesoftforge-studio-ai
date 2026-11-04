package com.mulesoftforge.studio.ai.llm;

import java.util.Locale;
import java.util.regex.Pattern;

// How many tokens a model reads in one request, from the vendors' published limits by model family. A model not
// listed gets DEFAULT, Cline's default for an unknown model; a gateway that serves a larger one only compacts early.
public final class ContextWindow {
  public static final int DEFAULT = 128_000;
  private static final Pattern MILLION = Pattern.compile("gemini|gpt-4\\.1|llama-4");
  private static final Pattern GPT_5 = Pattern.compile("gpt-5");
  private static final Pattern LARGE = Pattern.compile("claude|\\bo[134]\\b|o[134]-|nova-pro|nova-lite");

  private ContextWindow() {}

  public static int of(String model) {
    String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
    if (MILLION.matcher(name).find()) {
      return 1_000_000;
    }
    if (GPT_5.matcher(name).find()) {
      return 400_000;
    }
    if (LARGE.matcher(name).find()) {
      return 200_000;
    }
    return DEFAULT;
  }

  // "34k of 200k (17%)".
  public static String describe(long tokens, int window) {
    long percent = window <= 0 ? 0 : Math.round(100.0 * tokens / window);
    return tokens(tokens) + " of " + tokens(window) + " (" + percent + "%)";
  }

  // "34k", "1.2M".
  public static String tokens(long tokens) {
    if (tokens >= 1_000_000) {
      return String.format(Locale.ROOT, "%.1fM", tokens / 1e6).replace(".0M", "M");
    }
    return tokens >= 1_000 ? Math.round(tokens / 1000.0) + "k" : String.valueOf(tokens);
  }
}
