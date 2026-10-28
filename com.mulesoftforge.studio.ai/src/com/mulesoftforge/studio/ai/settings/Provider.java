package com.mulesoftforge.studio.ai.settings;

import java.util.List;

public enum Provider {
  OPENROUTER("OpenRouter", Api.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "openrouter/auto",
      "One key for most models. Prefer openrouter/auto, or Refresh models for the full catalog.",
      List.of(
          "openrouter/auto",
          "anthropic/claude-sonnet-4.6",
          "anthropic/claude-sonnet-4.5",
          "anthropic/claude-opus-4.6",
          "openai/gpt-5-mini",
          "openai/gpt-5",
          "google/gemini-2.5-flash")),
  MULESOFT_AI_GATEWAY("MuleSoft AI Gateway (LLM Proxy)", Api.OPENAI_COMPATIBLE, "", "openai/gpt-5-mini",
      "Your Flex Gateway LLM Proxy URL including its base path. With semantic routing the gateway picks the model.",
      List.of("openai/gpt-5-mini", "openai/gpt-5", "anthropic/claude-sonnet-4.6")),
  OPENAI("OpenAI", Api.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5-mini",
      "Type any model id, or Refresh models for chat-oriented ids from your account.",
      List.of("gpt-5-mini", "gpt-5", "gpt-5-nano", "gpt-4.1", "gpt-4.1-mini", "gpt-4o", "o4-mini")),
  ANTHROPIC("Anthropic", Api.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-4-6",
      "Type any model id, or Refresh models to load what your key can access.",
      List.of("claude-sonnet-4-6", "claude-sonnet-4-5", "claude-opus-4-6", "claude-haiku-4-5")),
  OLLAMA("Ollama (local)", Api.OLLAMA, "http://localhost:11434", "qwen2.5-coder",
      "Runs on this machine. No API key needed. Refresh models lists local tags.",
      List.of("qwen2.5-coder", "codellama", "llama3.2")),
  CUSTOM("Other OpenAI-compatible", Api.OPENAI_COMPATIBLE, "", "",
      "Any endpoint that speaks the OpenAI Chat Completions API, for example LiteLLM or vLLM.",
      List.of());

  public enum Api {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    OLLAMA
  }

  public final String label;
  public final Api api;
  public final String defaultBaseUrl;
  public final String defaultModel;
  public final String hint;
  public final List<String> suggestedModels;

  Provider(String label, Api api, String defaultBaseUrl, String defaultModel, String hint,
      List<String> suggestedModels) {
    this.label = label;
    this.api = api;
    this.defaultBaseUrl = defaultBaseUrl;
    this.defaultModel = defaultModel;
    this.hint = hint;
    this.suggestedModels = List.copyOf(suggestedModels);
  }

  // The gateway and custom endpoints may be open inside a corporate network, so their key is optional.
  public boolean needsApiKey() {
    return switch (this) {
      case OPENROUTER, OPENAI, ANTHROPIC -> true;
      case MULESOFT_AI_GATEWAY, OLLAMA, CUSTOM -> false;
    };
  }

  // A key pasted for the wrong provider is the usual cause of "401 User not found" and similar replies.
  public String keyProblem(String key) {
    String value = key == null ? "" : key.trim();
    if (value.isEmpty()) {
      return needsApiKey() ? "No API key is saved for " + label + "." : null;
    }
    if (value.chars().anyMatch(Character::isWhitespace)) {
      return "The key contains spaces or line breaks. Paste it again as one line.";
    }
    String other = value.startsWith("sk-or-") ? "an OpenRouter" : value.startsWith("sk-ant-") ? "an Anthropic"
        : value.startsWith("sk-") ? "an OpenAI" : null;
    boolean fits = switch (this) {
      case OPENROUTER -> value.startsWith("sk-or-");
      case ANTHROPIC -> value.startsWith("sk-ant-");
      case OPENAI -> "an OpenAI".equals(other);
      case MULESOFT_AI_GATEWAY, OLLAMA, CUSTOM -> true;
    };
    if (fits) {
      return null;
    }
    String mine = ("AEIOU".indexOf(label.charAt(0)) >= 0 ? "an " : "a ") + label + " key";
    return other == null ? "This does not look like " + mine + "."
        : "This looks like " + other + " key, not " + mine + ".";
  }

  // Enough to tell keys apart without showing them: sk-or-…a1b2, 73 characters.
  public static String describeKey(String key) {
    String value = key == null ? "" : key.trim();
    if (value.isEmpty()) {
      return "none";
    }
    if (value.length() < 12) {
      return value.length() + " characters";
    }
    int dash = value.indexOf('-', 3);
    String start = dash > 0 && dash < 8 ? value.substring(0, dash + 1) : value.substring(0, 3);
    return start + "…" + value.substring(value.length() - 4) + ", " + value.length() + " characters";
  }

  public static Provider fromName(String name) {
    for (Provider provider : values()) {
      if (provider.name().equals(name)) {
        return provider;
      }
    }
    return OPENROUTER;
  }
}
