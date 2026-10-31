package com.mulesoftforge.studio.ai.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.token.credentials.SdkToken;
import software.amazon.awssdk.auth.token.credentials.StaticTokenProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

public final class ChatModels {
  private static final Duration TIMEOUT = Duration.ofSeconds(180);
  private static final int MAX_OUTPUT_TOKENS = 8192;
  private static final String AZURE_API_VERSION = "2024-10-21";

  private record BedrockKey(String region, String apiKey) {}

  private static final ConcurrentHashMap<BedrockKey, BedrockRuntimeClient> BEDROCK = new ConcurrentHashMap<>();

  private ChatModels() {}

  public static ChatModel create(Connection connection) {
    return create(connection, null, null);
  }

  public static ChatModel create(Connection connection, RawCapture capture) {
    return create(connection, capture, null);
  }

  // capture, when not null, records every call: the HTTP exchange where this client makes it, otherwise the
  // request and response LangChain4j sees. inFlight, when not null, holds the current sendAsync future so a Job
  // can abort it from canceling().
  public static ChatModel create(Connection connection, RawCapture capture,
      AtomicReference<CompletableFuture<?>> inFlight) {
    validate(connection);
    List<ChatModelListener> listeners = capture == null ? List.of() : List.of(capture.listener());
    String apiKey = connection.provider().needsClientCredentials() || connection.apiKey().isBlank()
        ? null
        : connection.apiKey();
    return switch (connection.provider().api) {
      case OPENAI_COMPATIBLE -> {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .httpClientBuilder(http(capture, inFlight))
            .baseUrl(connection.baseUrl())
            .apiKey(apiKey)
            .modelName(connection.model())
            .maxCompletionTokens(MAX_OUTPUT_TOKENS)
            .timeout(TIMEOUT)
            .maxRetries(0)
            .listeners(listeners);
        Map<String, String> headers = gatewayHeaders(connection);
        if (!headers.isEmpty()) {
          builder.customHeaders(headers);
        }
        yield builder.build();
      }
      case ANTHROPIC -> AnthropicChatModel.builder()
          .httpClientBuilder(http(capture, inFlight))
          .baseUrl(connection.baseUrl())
          .apiKey(apiKey)
          .modelName(connection.model())
          .maxTokens(MAX_OUTPUT_TOKENS)
          .timeout(TIMEOUT)
          .maxRetries(0)
          .listeners(listeners)
          .build();
      case GOOGLE_AI_GEMINI -> GoogleAiGeminiChatModel.builder()
          .httpClientBuilder(http(capture, inFlight))
          .baseUrl(connection.baseUrl())
          .apiKey(apiKey)
          .modelName(connection.model())
          .maxOutputTokens(MAX_OUTPUT_TOKENS)
          .timeout(TIMEOUT)
          .maxRetries(0)
          .listeners(listeners)
          .build();
      case AZURE_OPENAI -> AzureOpenAiChatModel.builder()
          .endpoint(trimSlash(connection.baseUrl()))
          .apiKey(apiKey)
          .deploymentName(connection.model())
          .serviceVersion(AZURE_API_VERSION)
          .maxTokens(MAX_OUTPUT_TOKENS)
          .timeout(TIMEOUT)
          .maxRetries(0)
          .listeners(listeners)
          .build();
      case BEDROCK -> bedrock(connection, listeners);
      case OLLAMA -> OllamaChatModel.builder()
          .httpClientBuilder(http(capture, inFlight))
          .baseUrl(connection.baseUrl())
          .modelName(connection.model())
          .timeout(TIMEOUT)
          .maxRetries(0)
          .listeners(listeners)
          .build();
    };
  }

  private static ChatModel bedrock(Connection connection, List<ChatModelListener> listeners) {
    Region region = Region.of(bedrockRegion(connection.baseUrl()));
    String key = connection.apiKey() == null ? "" : connection.apiKey().trim();
    BedrockRuntimeClient client = bedrockClient(region.id(), key);
    return BedrockChatModel.builder()
        .client(client)
        .region(region)
        .modelId(connection.model())
        .timeout(TIMEOUT)
        .defaultRequestParameters(BedrockChatRequestParameters.builder()
            .maxOutputTokens(MAX_OUTPUT_TOKENS)
            .build())
        .listeners(listeners)
        .build();
  }

  private static BedrockRuntimeClient bedrockClient(String regionId, String apiKey) {
    BedrockKey key = new BedrockKey(regionId, apiKey);
    return BEDROCK.computeIfAbsent(key, k -> {
      BedrockRuntimeClientBuilder client = BedrockRuntimeClient.builder().region(Region.of(k.region()));
      if (!k.apiKey().isEmpty()) {
        SdkToken token = () -> k.apiKey();
        client.tokenProvider(StaticTokenProvider.create(token));
      } else {
        client.credentialsProvider(DefaultCredentialsProvider.create());
      }
      return client.build();
    });
  }

  // Closes cached Bedrock clients. Called when network settings change and on plugin stop.
  public static void closeClients() {
    for (BedrockRuntimeClient client : BEDROCK.values()) {
      try {
        client.close();
      } catch (Exception e) {
        // Best effort on shutdown.
      }
    }
    BEDROCK.clear();
  }

  // Preferences "Base URL" holds the region for Bedrock (us-east-1), or a full bedrock-runtime URL.
  public static String bedrockRegion(String baseUrl) {
    String value = baseUrl == null ? "" : baseUrl.trim();
    if (value.isEmpty()) {
      return Region.US_EAST_1.id();
    }
    if (!value.contains("://")) {
      return value;
    }
    // https://bedrock-runtime.us-west-2.amazonaws.com
    String host = value.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    String[] parts = host.split("\\.");
    if (parts.length >= 3 && parts[0].startsWith("bedrock")) {
      return parts[1];
    }
    return value;
  }

  // Flex Gateway Client ID Enforcement expects these header names (docs.mulesoft.com LLM Proxy requests).
  public static Map<String, String> gatewayHeaders(Connection connection) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (connection.provider() != Provider.MULESOFT_AI_GATEWAY) {
      return headers;
    }
    String id = connection.clientId() == null ? "" : connection.clientId().trim();
    String secret = connection.clientSecret() == null ? "" : connection.clientSecret().trim();
    if (!id.isEmpty()) {
      headers.put("client_id", id);
    }
    if (!secret.isEmpty()) {
      headers.put("client_secret", secret);
    }
    return headers;
  }

  // Sync calls use DiagnosingHttpClient so failures keep HTTP status, body and response headers.
  // LangChain4j's ServiceLoader cannot see jars embedded in an OSGi bundle, so the client is passed in.
  private static HttpClientBuilder http(RawCapture capture, AtomicReference<CompletableFuture<?>> inFlight) {
    HttpClient shared = Network.client();
    JdkHttpClientBuilder streamingBuilder = JdkHttpClient.builder()
        .httpClientBuilder(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(java.net.ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(20))
            .sslContext(shared.sslContext()));
    return new HttpClientBuilder() {
      private Duration connectTimeout = Duration.ofSeconds(20);
      private Duration readTimeout = TIMEOUT;

      @Override
      public Duration connectTimeout() {
        return connectTimeout;
      }

      @Override
      public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        streamingBuilder.connectTimeout(connectTimeout);
        return this;
      }

      @Override
      public Duration readTimeout() {
        return readTimeout;
      }

      @Override
      public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        streamingBuilder.readTimeout(readTimeout);
        return this;
      }

      @Override
      public dev.langchain4j.http.client.HttpClient build() {
        return new DiagnosingHttpClient(shared, readTimeout, streamingBuilder.build(), capture, inFlight);
      }
    };
  }

  private static void validate(Connection connection) {
    String name = connection.provider().label;
    if (connection.baseUrl().isBlank()) {
      throw new IllegalStateException("Set the base URL for " + name + " in Preferences > MuleSoftForge AI.");
    }
    if (connection.model().isBlank()) {
      throw new IllegalStateException("Set the model for " + name + " in Preferences > MuleSoftForge AI.");
    }
    if (AutoRouter.isAuto(connection.model())) {
      throw new IllegalStateException("Auto picks a model per message in the chat view. Choose a model here,"
          + " or set Auto's fast and strong models in Preferences > MuleSoftForge AI.");
    }
    String credentials = connection.provider().credentialsProblem(connection.apiKey(), connection.clientId(),
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
}
