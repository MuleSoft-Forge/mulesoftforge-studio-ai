package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Streamed /chat/completions chunks, as LangChain4j's OpenAI client reads them: only the first choice of each
// chunk. A gateway that sends text and tool calls as separate choices (see ChatCompletions.collapseChoices) has
// each choice passed on as choice 0, keeping text from the first choice that sent text and tool calls from the
// first that sent tool calls, so alternatives are not merged. One instance per response.
final class ChatStream {
  private static final ObjectMapper JSON = new ObjectMapper();
  static final String DONE = "[DONE]";

  private Integer textChoice;
  private Integer toolChoice;

  // The chunks to pass on for one received chunk; the data unchanged when it is not a JSON chunk.
  List<String> rewrite(String data) {
    if (data == null || data.isBlank() || DONE.equals(data.strip()) || !data.contains("\"choices\"")) {
      return List.of(data);
    }
    try {
      if (!(JSON.readTree(data) instanceof ObjectNode chunk) || !(chunk.get("choices") instanceof ArrayNode choices)
          || choices.size() == 0) {
        return List.of(data);
      }
      boolean plain = choices.size() == 1 && choices.get(0).path("index").asInt(0) == 0;
      List<String> out = new ArrayList<>();
      for (JsonNode choice : choices) {
        int index = choice.path("index").asInt(0);
        if (!(choice.deepCopy() instanceof ObjectNode kept)) {
          continue;
        }
        if (kept.get("delta") instanceof ObjectNode delta) {
          if (delta.hasNonNull("content") && !delta.path("content").asText().isEmpty()) {
            textChoice = textChoice == null ? index : textChoice;
            if (textChoice != index) {
              delta.remove("content");
            }
          }
          if (delta.has("tool_calls")) {
            toolChoice = toolChoice == null ? index : toolChoice;
            if (toolChoice != index) {
              delta.remove("tool_calls");
            }
          }
        }
        if (plain && kept.equals(choice)) {
          return List.of(data);
        }
        kept.put("index", 0);
        ObjectNode one = chunk.deepCopy();
        one.putArray("choices").add(kept);
        out.add(JSON.writeValueAsString(one));
      }
      return out;
    } catch (Exception e) {
      return List.of(data);
    }
  }

  // A complete chat.completion body, from a server that did not stream, as one chunk and [DONE].
  static List<String> fromCompletion(String body) {
    try {
      JsonNode root = JSON.readTree(ChatCompletions.collapseChoices(body));
      JsonNode choice = root.path("choices").path(0);
      ObjectNode chunk = JSON.createObjectNode();
      for (String field : List.of("id", "created", "model", "system_fingerprint")) {
        if (root.has(field)) {
          chunk.set(field, root.get(field));
        }
      }
      chunk.put("object", "chat.completion.chunk");
      ArrayNode choices = chunk.putArray("choices");
      if (!choice.isMissingNode()) {
        ObjectNode delta = choice.path("message").isObject() ? ((ObjectNode) choice.get("message")).deepCopy()
            : JSON.createObjectNode();
        if (delta.get("tool_calls") instanceof ArrayNode calls) {
          for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i) instanceof ObjectNode call && !call.has("index")) {
              call.put("index", i);
            }
          }
        }
        ObjectNode streamed = choices.addObject();
        streamed.put("index", 0);
        streamed.set("delta", delta);
        streamed.set("finish_reason", choice.get("finish_reason"));
      }
      if (root.has("usage")) {
        chunk.set("usage", root.get("usage"));
      }
      return List.of(JSON.writeValueAsString(chunk), DONE);
    } catch (Exception e) {
      return List.of(body);
    }
  }

  // Asks an OpenAI-format server for the usage block in the final chunk, which a stream otherwise leaves out.
  static String withUsage(String body) {
    if (body == null || !body.contains("\"stream\"") || body.contains("include_usage")) {
      return body;
    }
    try {
      if (JSON.readTree(body) instanceof ObjectNode request && request.path("stream").asBoolean(false)) {
        request.putObject("stream_options").put("include_usage", true);
        return JSON.writeValueAsString(request);
      }
    } catch (Exception ignored) {
      // Not JSON: sent as it is.
    }
    return body;
  }
}
