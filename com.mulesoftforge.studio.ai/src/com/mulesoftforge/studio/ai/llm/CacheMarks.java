package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

// Where a request is marked for Claude's prompt cache, which keeps a prefix only up to a marked block, at most four
// per request. Every API gets the same four: the system prompt (Claude orders tools before it, so they are cached
// with it); the history up to the latest user message, which the next turn sends unchanged; the latest user message,
// which every tool round of this turn sends again; and the latest tool result, so the next round reads this round's
// calls and results from the cache too. Roo-Code and Continue mark the system prompt and the last two user
// messages, where tool results count as user messages; this is the same prefix, named per message.
public final class CacheMarks {
  public enum Kind { SYSTEM, USER, TOOL, OTHER }

  private static final CachePointBlock CACHE_POINT = CachePointBlock.builder().type(CachePointType.DEFAULT).build();

  private CacheMarks() {}

  // The indexes to mark, given what each message is. Index 0 is the system prompt when there is one.
  public static Set<Integer> chosen(List<Kind> kinds) {
    Set<Integer> marks = new TreeSet<>();
    if (kinds.isEmpty()) {
      return marks;
    }
    if (kinds.get(0) == Kind.SYSTEM) {
      marks.add(0);
    }
    int latestUser = kinds.lastIndexOf(Kind.USER);
    if (latestUser >= 0) {
      int settled = latestUser - 1;
      if (settled > 0 && kinds.get(settled) != Kind.SYSTEM) {
        marks.add(settled);
      }
      marks.add(latestUser);
    }
    int last = kinds.size() - 1;
    if (kinds.get(last) == Kind.TOOL && last > latestUser) {
      marks.add(last);
    }
    return marks;
  }

  // Native Anthropic: LangChain4j marks the system prompt itself (cacheSystemMessages) and any message carrying the
  // cache_control attribute. The request's messages are copied, so the history keeps them unmarked.
  public static ChatRequest anthropic(ChatRequest request) {
    List<ChatMessage> messages = request.messages();
    List<Kind> kinds = new ArrayList<>();
    for (ChatMessage message : messages) {
      kinds.add(message instanceof SystemMessage ? Kind.SYSTEM : message instanceof UserMessage ? Kind.USER
          : message instanceof ToolExecutionResultMessage ? Kind.TOOL : Kind.OTHER);
    }
    List<ChatMessage> marked = new ArrayList<>(messages);
    boolean changed = false;
    for (int i : chosen(kinds)) {
      ChatMessage message = messages.get(i);
      ChatMessage copy = message instanceof UserMessage user
          ? user.toBuilder().attributes(withCache(user.attributes())).build()
          : message instanceof ToolExecutionResultMessage result
              ? result.toBuilder().attributes(withCache(result.attributes())).build()
          : message instanceof AiMessage ai ? ai.toBuilder().attributes(withCache(ai.attributes())).build()
          : message;
      changed |= copy != message;
      marked.set(i, copy);
    }
    return changed ? request.toBuilder().messages(marked).build() : request;
  }

  private static Map<String, Object> withCache(Map<String, Object> attributes) {
    Map<String, Object> out = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
    out.put(AnthropicMapper.CACHE_CONTROL, "ephemeral");
    return out;
  }

  // Bedrock's Converse API takes cachePoint blocks. LangChain4j places at most one in the messages, after the last
  // typed user message, which leaves each tool round uncached; this marks the Converse request just before it is
  // sent instead.
  public static ExecutionInterceptor bedrock() {
    return new ExecutionInterceptor() {
      @Override
      public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes attributes) {
        return context.request() instanceof ConverseRequest converse ? bedrock(converse) : context.request();
      }
    };
  }

  public static ConverseRequest bedrock(ConverseRequest request) {
    if (!bedrockCaches(request.modelId()) || hasCachePoint(request)) {
      return request;
    }
    // Bedrock sends the system prompt apart from the messages; it stands at index 0 here.
    List<Kind> kinds = new ArrayList<>();
    kinds.add(request.hasSystem() && !request.system().isEmpty() ? Kind.SYSTEM : Kind.OTHER);
    for (Message message : request.messages()) {
      kinds.add(message.role() != ConversationRole.USER ? Kind.OTHER
          : message.content().stream().anyMatch(block -> block.toolResult() != null) ? Kind.TOOL : Kind.USER);
    }
    Set<Integer> marks = chosen(kinds);
    if (marks.isEmpty()) {
      return request;
    }
    ConverseRequest.Builder out = request.toBuilder();
    if (marks.contains(0) && kinds.get(0) == Kind.SYSTEM) {
      List<SystemContentBlock> system = new ArrayList<>(request.system());
      system.add(SystemContentBlock.fromCachePoint(CACHE_POINT));
      out.system(system);
    }
    List<Message> messages = new ArrayList<>(request.messages());
    for (int i : marks) {
      if (i > 0) {
        Message message = messages.get(i - 1);
        List<ContentBlock> content = new ArrayList<>(message.content());
        content.add(ContentBlock.fromCachePoint(CACHE_POINT));
        messages.set(i - 1, message.toBuilder().content(content).build());
      }
    }
    return out.messages(messages).build();
  }

  // Claude 3.5 Haiku, 3.7 Sonnet and later, and Nova: the models AWS lists as supporting prompt caching.
  static boolean bedrockCaches(String modelId) {
    String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
    if (id.contains("amazon.nova")) {
      return true;
    }
    if (!id.contains("anthropic.claude")) {
      return false;
    }
    return !id.contains("claude-3-") || id.contains("claude-3-5-haiku") || id.contains("claude-3-7");
  }

  private static boolean hasCachePoint(ConverseRequest request) {
    return request.hasSystem() && request.system().stream().anyMatch(block -> block.cachePoint() != null)
        || request.messages().stream().anyMatch(m -> m.content().stream().anyMatch(b -> b.cachePoint() != null));
  }
}
