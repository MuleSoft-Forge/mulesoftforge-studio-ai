package com.mulesoftforge.studio.ai.llm;

import java.net.http.HttpClient;
import java.time.Duration;

import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public final class ChatModels {
  private static final Duration TIMEOUT = Duration.ofSeconds(180);
  private static final int MAX_OUTPUT_TOKENS = 8192;

  private ChatModels() {}

  public static ChatModel create(Connection connection) {
    validate(connection);
    String apiKey = connection.apiKey().isBlank() ? null : connection.apiKey();
    return switch (connection.provider().api) {
      case OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
          .httpClientBuilder(http())
          .baseUrl(connection.baseUrl())
          .apiKey(apiKey)
          .modelName(connection.model())
          .maxCompletionTokens(MAX_OUTPUT_TOKENS)
          .timeout(TIMEOUT)
          .build();
      case ANTHROPIC -> AnthropicChatModel.builder()
          .httpClientBuilder(http())
          .baseUrl(connection.baseUrl())
          .apiKey(apiKey)
          .modelName(connection.model())
          .maxTokens(MAX_OUTPUT_TOKENS)
          .timeout(TIMEOUT)
          .build();
      case OLLAMA -> OllamaChatModel.builder()
          .httpClientBuilder(http())
          .baseUrl(connection.baseUrl())
          .modelName(connection.model())
          .timeout(TIMEOUT)
          .build();
    };
  }

  // Sync calls use DiagnosingHttpClient so failures keep HTTP status, body and response headers.
  // LangChain4j's ServiceLoader cannot see jars embedded in an OSGi bundle, so the client is passed in.
  private static HttpClientBuilder http() {
    HttpClient.Builder jdkBuilder = Network.builder();
    JdkHttpClientBuilder streamingBuilder = JdkHttpClient.builder().httpClientBuilder(jdkBuilder);
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
        jdkBuilder.connectTimeout(connectTimeout);
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
        return new DiagnosingHttpClient(jdkBuilder.build(), readTimeout, streamingBuilder.build());
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
    if (connection.provider().needsApiKey() && connection.apiKey().isBlank()) {
      throw new IllegalStateException("Set the API key for " + name + " in Preferences > MuleSoftForge AI.");
    }
  }
}
