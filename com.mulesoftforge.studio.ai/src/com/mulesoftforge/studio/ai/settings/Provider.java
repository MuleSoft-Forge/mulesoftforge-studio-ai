package com.mulesoftforge.studio.ai.settings;

import java.util.Arrays;
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
  MULESOFT_AI_GATEWAY("MuleSoft AI Gateway (LLM Proxy - OpenAI Format)", Api.OPENAI_COMPATIBLE, "", "openai/gpt-5-mini",
      "Your Flex Gateway LLM Proxy URL including its base path. Uses client_id and client_secret headers (no API key)."
          + " With semantic routing the gateway picks the model.",
      List.of("openai/gpt-5-mini", "openai/gpt-5", "anthropic/claude-sonnet-4.6")),
  OPENAI("OpenAI", Api.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5-mini",
      "Type any model id, or Refresh models for chat-oriented ids from your account.",
      List.of("gpt-5-mini", "gpt-5", "gpt-5-nano", "gpt-4.1", "gpt-4.1-mini", "gpt-4o", "o4-mini")),
  ANTHROPIC("Anthropic", Api.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-4-6",
      "Type any model id, or Refresh models to load what your key can access.",
      List.of("claude-sonnet-4-6", "claude-sonnet-4-5", "claude-opus-4-6", "claude-haiku-4-5")),
  GEMINI("Google Gemini", Api.GOOGLE_AI_GEMINI, "https://generativelanguage.googleapis.com/v1beta",
      "gemini-3-flash-preview",
      "Google AI Studio key (ai.google.dev). Type any model id, or Refresh models for generateContent ids."
          + " New keys no longer get gemini-2.5-*; prefer gemini-3-flash-preview.",
      List.of("gemini-3-flash-preview", "gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-flash-latest")),
  AZURE_OPENAI("Azure OpenAI", Api.AZURE_OPENAI, "https://YOUR_RESOURCE.openai.azure.com/", "gpt-5.4-mini",
      "Endpoint is https://{resource}.openai.azure.com/. Model is the deployment name in Azure"
          + " (often the same as the model id). Refresh models lists chat catalog ids from"
          + " /openai/models — Azure no longer lists deployments on the data plane."
          + " GPT-5.x deployments need max_completion_tokens (the plugin sends that).",
      List.of("gpt-5.4-mini", "gpt-5.5", "gpt-4o", "gpt-4o-mini", "o4-mini")),
  BEDROCK("Amazon Bedrock", Api.BEDROCK, "us-east-1", "global.anthropic.claude-sonnet-5-5",
      "Region is the AWS region (for example us-east-1). Paste a Bedrock API key, or leave empty to use the"
          + " default AWS credential chain. Refresh models loads inference profiles and text foundation"
          + " models (needs an API key). Prefer profile ids such as global.anthropic.… or us.anthropic.…",
      List.of(
          "global.anthropic.claude-sonnet-5-5",
          "global.anthropic.claude-opus-5-5",
          "global.anthropic.claude-haiku-5-5",
          "amazon.nova-lite-v1:0",
          "amazon.nova-pro-v1:0")),
  OLLAMA("Ollama (local)", Api.OLLAMA, "http://localhost:11434", "qwen2.5-coder",
      "Runs on this machine. No API key needed. Refresh models lists local tags.",
      List.of("qwen2.5-coder", "codellama", "llama3.2")),
  CUSTOM("Other OpenAI-compatible", Api.OPENAI_COMPATIBLE, "", "",
      "Any endpoint that speaks the OpenAI Chat Completions API, for example LiteLLM or vLLM.",
      List.of());

  public enum Api {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GOOGLE_AI_GEMINI,
    AZURE_OPENAI,
    BEDROCK,
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

  // The gateway uses client id/secret headers; custom/Ollama may be open inside a corporate network.
  public boolean needsApiKey() {
    return switch (this) {
      case OPENROUTER, OPENAI, ANTHROPIC, GEMINI, AZURE_OPENAI -> true;
      // Bedrock can use a pasted API key or the host's AWS credential chain.
      case BEDROCK, MULESOFT_AI_GATEWAY, OLLAMA, CUSTOM -> false;
    };
  }

  // Bedrock stores its AWS region in the base URL setting; the SDK builds the endpoint from it.
  public String baseUrlLabel() {
    return switch (this) {
      case BEDROCK -> "Region";
      case OPENROUTER, OPENAI, ANTHROPIC, GEMINI, AZURE_OPENAI, MULESOFT_AI_GATEWAY, OLLAMA, CUSTOM -> "Base URL";
    };
  }

  public boolean needsClientCredentials() {
    return this == MULESOFT_AI_GATEWAY;
  }

  // Hidden until the LLM Proxy exposes a usable GET /models (or we ship a fixed model list for it).
  public boolean shownInUi() {
    return this != MULESOFT_AI_GATEWAY;
  }

  public static List<Provider> shownInUiValues() {
    return Arrays.stream(values()).filter(Provider::shownInUi).toList();
  }

  // Null when credentials look usable; otherwise a short note for the preferences page or chat view.
  public String credentialsProblem(String apiKey, String clientId, String clientSecret) {
    if (needsClientCredentials()) {
      String id = clientId == null ? "" : clientId.trim();
      String secret = clientSecret == null ? "" : clientSecret.trim();
      if (id.isEmpty() || secret.isEmpty()) {
        return "Set the client id and client secret for " + label + ".";
      }
      if (id.chars().anyMatch(Character::isWhitespace) || secret.chars().anyMatch(Character::isWhitespace)) {
        return "The client id or secret contains spaces or line breaks. Paste each as one line.";
      }
      return null;
    }
    return keyProblem(apiKey);
  }

  // A key pasted for the wrong provider is the usual cause of "401 User not found" and similar replies.
  public String keyProblem(String key) {
    if (needsClientCredentials()) {
      return null;
    }
    String value = key == null ? "" : key.trim();
    if (value.isEmpty()) {
      return needsApiKey() ? "No API key is saved for " + label + "." : null;
    }
    if (value.chars().anyMatch(Character::isWhitespace)) {
      return "The key contains spaces or line breaks. Paste it again as one line.";
    }
    // Google AI Studio keys are usually AIza… / AQ.…; Gemini proxies may use opaque tokens.
    String other = value.startsWith("sk-or-") ? "an OpenRouter" : value.startsWith("sk-ant-") ? "an Anthropic"
        : value.startsWith("AIza") || value.startsWith("AQ.") ? "a Google AI"
            : value.startsWith("sk-") ? "an OpenAI" : null;
    boolean fits = switch (this) {
      case OPENROUTER -> value.startsWith("sk-or-");
      case ANTHROPIC -> value.startsWith("sk-ant-");
      case OPENAI -> "an OpenAI".equals(other);
      case GEMINI -> other == null || "a Google AI".equals(other);
      case AZURE_OPENAI, BEDROCK, MULESOFT_AI_GATEWAY, OLLAMA, CUSTOM -> true;
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
