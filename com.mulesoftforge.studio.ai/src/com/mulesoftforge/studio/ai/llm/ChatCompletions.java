package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Some OpenAI-compatible gateways (notably Grok behind Salesforce's model gateway) return two choices for one
// turn: a text "thinking" choice and a separate tool_calls choice. LangChain4j's OpenAI client refuses that
// ("expected exactly one choice"). Collapse to one choice before the client parses the body.
public final class ChatCompletions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ChatCompletions() {}

  // OpenAI-format gateways in front of Claude pass cache_control through; the marks are CacheMarks'. Anything else is
  // returned as it came.
  public static String withCacheBreakpoints(String body) {
    if (body == null || !body.contains("\"messages\"") || body.contains("cache_control")) {
      return body;
    }
    try {
      if (!(JSON.readTree(body) instanceof ObjectNode request)
          || !request.path("model").asText().toLowerCase(Locale.ROOT).contains("claude")
          || !(request.get("messages") instanceof ArrayNode messages) || messages.isEmpty()) {
        return body;
      }
      List<CacheMarks.Kind> kinds = new ArrayList<>();
      for (JsonNode message : messages) {
        String role = message.path("role").asText();
        kinds.add("system".equals(role) ? CacheMarks.Kind.SYSTEM
            : "user".equals(role) && message.path("content").isTextual() ? CacheMarks.Kind.USER
            : "tool".equals(role) ? CacheMarks.Kind.TOOL : CacheMarks.Kind.OTHER);
      }
      int marked = 0;
      for (int i : CacheMarks.chosen(kinds)) {
        if (messages.get(i) instanceof ObjectNode message && message.path("content").isTextual()
            && !message.path("content").asText().isEmpty()) {
          ArrayNode parts = JSON.createArrayNode();
          ObjectNode part = parts.addObject();
          part.put("type", "text");
          part.put("text", message.path("content").asText());
          part.putObject("cache_control").put("type", "ephemeral");
          message.set("content", parts);
          marked++;
        }
      }
      return marked == 0 ? body : JSON.writeValueAsString(request);
    } catch (Exception e) {
      return body;
    }
  }

  // Leaves the body alone when it is not a chat completion, has 0 or 1 choices, or is not JSON.
  public static String collapseChoices(String body) {
    if (body == null || body.isBlank() || !body.contains("\"choices\"")) {
      return body;
    }
    try {
      JsonNode root = JSON.readTree(body);
      JsonNode choices = root.get("choices");
      if (choices == null || !choices.isArray() || choices.size() <= 1) {
        return body;
      }
      JsonNode kept = pick(choices);
      if (kept == null) {
        return body;
      }
      ObjectNode message = messageOf(kept);
      if (message != null && !hasToolCalls(kept) && !hasContent(kept)) {
        return body;
      }
      if (message != null && hasToolCalls(kept) && !hasContent(kept)) {
        String text = firstContent(choices);
        if (text != null) {
          message.put("content", text);
        }
      }
      ObjectNode out = (ObjectNode) root.deepCopy();
      ArrayNode one = out.putArray("choices");
      ObjectNode choice = kept.deepCopy();
      choice.put("index", 0);
      one.add(choice);
      return JSON.writeValueAsString(out);
    } catch (Exception e) {
      return body;
    }
  }

  // Prefer a choice that has tool calls; otherwise the first with text; otherwise the first choice.
  private static JsonNode pick(JsonNode choices) {
    for (JsonNode choice : choices) {
      if (hasToolCalls(choice)) {
        return choice;
      }
    }
    for (JsonNode choice : choices) {
      if (hasContent(choice)) {
        return choice;
      }
    }
    return choices.get(0);
  }

  private static String firstContent(JsonNode choices) {
    for (JsonNode choice : choices) {
      if (hasContent(choice)) {
        return messageOf(choice).path("content").asText();
      }
    }
    return null;
  }

  private static boolean hasToolCalls(JsonNode choice) {
    ObjectNode message = messageOf(choice);
    JsonNode tools = message == null ? null : message.get("tool_calls");
    return tools != null && tools.isArray() && !tools.isEmpty();
  }

  private static boolean hasContent(JsonNode choice) {
    ObjectNode message = messageOf(choice);
    if (message == null) {
      return false;
    }
    JsonNode content = message.get("content");
    return content != null && content.isTextual() && !content.asText().isBlank();
  }

  private static ObjectNode messageOf(JsonNode choice) {
    JsonNode message = choice == null ? null : choice.get("message");
    return message instanceof ObjectNode object ? object : null;
  }
}
