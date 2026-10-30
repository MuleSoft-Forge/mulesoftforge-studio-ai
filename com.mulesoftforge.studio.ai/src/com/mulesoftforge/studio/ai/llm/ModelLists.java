package com.mulesoftforge.studio.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;

/**
 * Lists models from each provider ({@code GET /models} or Ollama {@code /api/tags}).
 * Preference UI uses {@link #forPicker} so curated suggestions stay on top and noisy ids are filtered.
 */
public final class ModelLists {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ID_FALLBACK = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private ModelLists() {}

  public static List<String> list(Connection connection) throws Exception {
    validate(connection);
    return switch (connection.provider().api) {
      case OPENAI_COMPATIBLE -> openAiCompatible(connection);
      case ANTHROPIC -> anthropic(connection);
      case GOOGLE_AI_GEMINI -> gemini(connection);
      case AZURE_OPENAI -> azureOpenAi(connection);
      case BEDROCK -> List.of(); // Bedrock has no simple OpenAI-style /models; use the curated list.
      case OLLAMA -> ollama(connection);
    };
  }

  /**
   * Dropdown contents: curated suggestions first, then filtered API results, then the current value
   * so a typed custom id is never lost.
   */
  public static List<String> forPicker(Provider provider, List<String> fromApi, String current) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    out.add(AutoRouter.AUTO);
    for (String id : provider.suggestedModels) {
      if (id != null && !id.isBlank()) {
        out.add(id.trim());
      }
    }
    if (provider.defaultModel != null && !provider.defaultModel.isBlank()) {
      out.add(provider.defaultModel.trim());
    }
    for (String id : filterForChat(provider, fromApi)) {
      out.add(id);
    }
    if (current != null && !current.isBlank()) {
      // Keep the user's choice visible even if filters would drop it.
      LinkedHashSet<String> withCurrent = new LinkedHashSet<>();
      withCurrent.add(current.trim());
      withCurrent.addAll(out);
      return new ArrayList<>(withCurrent);
    }
    return new ArrayList<>(out);
  }

  public static List<String> suggestionsOnly(Provider provider, String current) {
    return forPicker(provider, List.of(), current);
  }

  // Drop embeddings / audio / image / batch noise; keep coding and chat-oriented ids.
  public static List<String> filterForChat(Provider provider, List<String> models) {
    List<String> out = new ArrayList<>();
    for (String id : models) {
      if (id == null || id.isBlank()) {
        continue;
      }
      String value = id.trim();
      if (isNoisy(provider, value)) {
        continue;
      }
      out.add(value);
    }
    return out;
  }

  private static boolean isNoisy(Provider provider, String id) {
    String lower = id.toLowerCase(Locale.ROOT);
    if (lower.contains(":batch")) {
      return true;
    }
    if (lower.startsWith("tts-") || lower.startsWith("whisper-") || lower.startsWith("davinci-")
        || lower.startsWith("babbage-") || lower.contains("embedding") || lower.contains("-embed")
        || lower.contains("moderation") || lower.contains("realtime") || lower.contains("transcribe")
        || lower.contains("dall-e") || lower.contains("sora") || lower.contains("gpt-image")
        || lower.contains("chatgpt-image") || lower.contains("text-embedding")) {
      return true;
    }
    if (provider == Provider.OPENAI) {
      return !(lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3")
          || lower.startsWith("o4") || lower.startsWith("chatgpt-4o"));
    }
    if (provider == Provider.OPENROUTER) {
      return !(lower.startsWith("anthropic/") || lower.startsWith("openai/") || lower.startsWith("google/")
          || lower.startsWith("meta-llama/") || lower.startsWith("qwen/") || lower.startsWith("mistralai/")
          || lower.startsWith("deepseek/") || lower.startsWith("x-ai/") || lower.startsWith("openrouter/"));
    }
    if (provider == Provider.GEMINI) {
      return !(lower.startsWith("gemini-") || lower.startsWith("models/gemini-"));
    }
    return false;
  }

  private static List<String> openAiCompatible(Connection connection) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder().header("Accept", "application/json");
    Map<String, String> gateway = ChatModels.gatewayHeaders(connection);
    if (!gateway.isEmpty()) {
      for (Map.Entry<String, String> header : gateway.entrySet()) {
        builder.header(header.getKey(), header.getValue());
      }
    } else if (!connection.apiKey().isBlank()) {
      builder.header("Authorization", "Bearer " + connection.apiKey());
    }
    JsonNode root = getJson(trimSlash(connection.baseUrl()) + "/models", builder);
    List<String> ids = new ArrayList<>();
    JsonNode data = root.get("data");
    if (data != null && data.isArray()) {
      for (JsonNode item : data) {
        JsonNode id = item.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
          ids.add(id.asText());
        }
      }
    }
    if (ids.isEmpty()) {
      ids.addAll(fallbackIds(root.toString()));
    }
    return uniqueSorted(ids);
  }

  private static List<String> anthropic(Connection connection) throws Exception {
    JsonNode root = getJson(trimSlash(connection.baseUrl()) + "/models",
        HttpRequest.newBuilder()
            .header("Accept", "application/json")
            .header("x-api-key", connection.apiKey())
            .header("anthropic-version", "2023-06-01"));
    List<String> ids = new ArrayList<>();
    JsonNode data = root.get("data");
    if (data != null && data.isArray()) {
      for (JsonNode item : data) {
        JsonNode id = item.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
          ids.add(id.asText());
        }
      }
    }
    if (ids.isEmpty()) {
      ids.addAll(fallbackIds(root.toString()));
    }
    return uniqueSorted(ids);
  }

  private static List<String> gemini(Connection connection) throws Exception {
    // Same auth as LangChain4j's Gemini client (x-goog-api-key). Query ?key= fails on some proxies.
    String url = trimSlash(connection.baseUrl()) + "/models?pageSize=1000";
    JsonNode root = getJson(url, HttpRequest.newBuilder()
        .header("Accept", "application/json")
        .header("x-goog-api-key", connection.apiKey().trim()));
    List<String> ids = new ArrayList<>();
    JsonNode models = root.get("models");
    if (models != null && models.isArray()) {
      for (JsonNode item : models) {
        if (!supportsGenerateContent(item)) {
          continue;
        }
        JsonNode name = item.get("name");
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
          ids.add(stripModelsPrefix(name.asText()));
        }
      }
    }
    return uniqueSorted(ids);
  }

  private static boolean supportsGenerateContent(JsonNode model) {
    JsonNode methods = model.get("supportedGenerationMethods");
    if (methods == null || !methods.isArray()) {
      methods = model.get("supportedActions");
    }
    if (methods == null || !methods.isArray()) {
      return true;
    }
    for (JsonNode method : methods) {
      if (method.isTextual() && "generateContent".equals(method.asText())) {
        return true;
      }
    }
    return false;
  }

  private static String stripModelsPrefix(String name) {
    String value = name.trim();
    return value.startsWith("models/") ? value.substring("models/".length()) : value;
  }

  private static List<String> azureOpenAi(Connection connection) throws Exception {
    String url = trimSlash(connection.baseUrl()) + "/openai/deployments?api-version=2024-10-21";
    JsonNode root = getJson(url, HttpRequest.newBuilder()
        .header("Accept", "application/json")
        .header("api-key", connection.apiKey().trim()));
    List<String> ids = new ArrayList<>();
    JsonNode data = root.get("data");
    if (data != null && data.isArray()) {
      for (JsonNode item : data) {
        JsonNode id = item.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
          ids.add(id.asText());
        }
      }
    }
    if (ids.isEmpty()) {
      ids.addAll(fallbackIds(root.toString()));
    }
    return uniqueSorted(ids);
  }

  private static List<String> ollama(Connection connection) throws Exception {
    JsonNode root = getJson(trimSlash(connection.baseUrl()) + "/api/tags",
        HttpRequest.newBuilder().header("Accept", "application/json"));
    List<String> ids = new ArrayList<>();
    JsonNode models = root.get("models");
    if (models != null && models.isArray()) {
      for (JsonNode item : models) {
        JsonNode name = item.get("name");
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
          ids.add(name.asText());
        }
      }
    }
    return uniqueSorted(ids);
  }

  private static JsonNode getJson(String url, HttpRequest.Builder builder) throws Exception {
    HttpClient client = Network.builder().build();
    HttpRequest request = builder.uri(URI.create(url)).timeout(TIMEOUT).GET().build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted calling GET " + url, e);
    } catch (Exception e) {
      throw new RuntimeException("Transport failure calling GET " + url + ": " + Errors.describe(e), e);
    }
    String body = response.body() == null ? "" : response.body();
    if (response.statusCode() >= 400) {
      throw new ProviderHttpException("GET", url, response.statusCode(), response.headers().map(), body);
    }
    return JSON.readTree(body.isBlank() ? "{}" : body);
  }

  private static List<String> fallbackIds(String json) {
    List<String> ids = new ArrayList<>();
    Matcher matcher = ID_FALLBACK.matcher(json);
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return ids;
  }

  private static List<String> uniqueSorted(List<String> ids) {
    Set<String> unique = new LinkedHashSet<>();
    for (String id : ids) {
      if (id != null && !id.isBlank()) {
        unique.add(id.trim());
      }
    }
    List<String> sorted = new ArrayList<>(unique);
    sorted.sort(String.CASE_INSENSITIVE_ORDER);
    return sorted;
  }

  private static void validate(Connection connection) {
    Provider provider = connection.provider();
    if (connection.baseUrl().isBlank()) {
      throw new IllegalStateException("Set the base URL for " + provider.label
          + " in Preferences > MuleSoftForge AI.");
    }
    String credentials = provider.credentialsProblem(connection.apiKey(), connection.clientId(),
        connection.clientSecret());
    if (credentials != null) {
      throw new IllegalStateException(credentials);
    }
  }

  private static String trimSlash(String url) {
    String value = url.trim();
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  public static String summarize(Provider provider, int rawCount, List<String> picker) {
    if (provider == Provider.BEDROCK) {
      return "Amazon Bedrock has no Refresh models catalog here; showing " + picker.size()
          + " curated model ids. Type any Bedrock model id or inference profile to override.";
    }
    return "Loaded " + rawCount + " from " + provider.label
        + "; showing " + picker.size() + " in the list (curated + chat-oriented). Type any id to override.";
  }
}
