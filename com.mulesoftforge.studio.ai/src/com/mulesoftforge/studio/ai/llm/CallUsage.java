package com.mulesoftforge.studio.ai.llm;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

// Tokens for one model call, split the way providers bill them. reportedCost is the provider's own
// billed amount when it sends one (OpenRouter), otherwise null.
public record CallUsage(String model, long uncachedInput, long cacheRead, long cacheWrite, long output,
    long reasoning, Double reportedCost) {

  public long input() {
    return uncachedInput + cacheRead + cacheWrite;
  }

  static CallUsage from(ChatResponse response, String requestedModel, Double reportedCost) {
    String model = response.modelName() == null || response.modelName().isBlank()
        ? requestedModel : response.modelName();
    TokenUsage usage = response.tokenUsage();
    if (usage == null) {
      return new CallUsage(model, 0, 0, 0, 0, 0, reportedCost);
    }
    long input = n(usage.inputTokenCount());
    long output = n(usage.outputTokenCount());
    // Anthropic reports cache reads and writes separately from input_tokens.
    if (usage instanceof AnthropicTokenUsage anthropic) {
      return new CallUsage(model, input, n(anthropic.cacheReadInputTokens()),
          n(anthropic.cacheCreationInputTokens()), output, 0, reportedCost);
    }
    // OpenAI-compatible APIs count cached tokens inside prompt_tokens.
    if (usage instanceof OpenAiTokenUsage openAi) {
      long cached = openAi.inputTokensDetails() == null ? 0 : n(openAi.inputTokensDetails().cachedTokens());
      long reasoning = openAi.outputTokensDetails() == null ? 0 : n(openAi.outputTokensDetails().reasoningTokens());
      return new CallUsage(model, Math.max(0, input - cached), Math.min(cached, input), 0, output, reasoning,
          reportedCost);
    }
    return new CallUsage(model, input, 0, 0, output, 0, reportedCost);
  }

  private static long n(Integer value) {
    return value == null ? 0 : value;
  }
}
