package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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

  // Claude caches a request prefix only up to a block marked cache_control, and OpenAI-format gateways in front of
  // Claude pass the marker through. Marked (Claude allows four): the first system message; the history up to the
  // latest user message, which the next turn sends unchanged (that message goes into history without its
  // attachments and request instructions); the latest user message, which every tool round of this turn sends
  // again; and the latest tool result, so the next round reads this round's calls and results from the cache too.
  // Anything else is returned as it came.
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
      List<Integer> users = new ArrayList<>();
      for (int i = 0; i < messages.size(); i++) {
        JsonNode message = messages.get(i);
        if ("user".equals(message.path("role").asText()) && message.path("content").isTextual()) {
          users.add(i);
        }
      }
      Set<Integer> marks = new TreeSet<>();
      if ("system".equals(messages.get(0).path("role").asText())) {
        marks.add(0);
      }
      if (!users.isEmpty()) {
        int settled = users.get(users.size() - 1) - 1;
        if (settled > 0 && !"system".equals(messages.get(settled).path("role").asText())) {
          marks.add(settled);
        }
      }
      if (!users.isEmpty()) {
        marks.add(users.get(users.size() - 1));
      }
      int last = messages.size() - 1;
      if ("tool".equals(messages.get(last).path("role").asText())
          && (users.isEmpty() || last > users.get(users.size() - 1))) {
        marks.add(last);
      }
      int marked = 0;
      for (int i : marks) {
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
