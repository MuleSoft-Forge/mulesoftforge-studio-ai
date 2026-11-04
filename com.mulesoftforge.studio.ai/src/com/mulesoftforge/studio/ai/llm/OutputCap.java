package com.mulesoftforge.studio.ai.llm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Requests ask for ChatModels.MAX_OUTPUT_TOKENS, which thinking models need, since thinking counts against the
// output cap. A model with a smaller limit answers 400 naming the token field; the request is then sent once more
// with FALLBACK_TOKENS, and every later request to that URL and model asks for that from the start.
final class OutputCap {
  static final int FALLBACK_TOKENS = 8192;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> LOWERED = ConcurrentHashMap.newKeySet();
  private static final Pattern REJECTS = Pattern.compile(
      "(?i)max_completion_tokens|max_tokens|maxOutputTokens|max(imum)?[ _-]?(output[ _-]?)?tokens|num_predict");

  private OutputCap() {}

  static boolean rejects(int status, String body) {
    return status == 400 && body != null && REJECTS.matcher(body).find();
  }

  // The body with every output cap above FALLBACK_TOKENS lowered to it; the same string when there was none.
  static String lowered(String body) {
    if (body == null || body.isBlank()) {
      return body;
    }
    try {
      if (!(JSON.readTree(body) instanceof ObjectNode request)) {
        return body;
      }
      boolean changed = lower(request, "max_completion_tokens") | lower(request, "max_tokens");
      if (request.get("generationConfig") instanceof ObjectNode gemini) {
        changed |= lower(gemini, "maxOutputTokens");
      }
      return changed ? JSON.writeValueAsString(request) : body;
    } catch (Exception e) {
      return body;
    }
  }

  static void remember(String url, String body) {
    LOWERED.add(key(url, body));
  }

  // The body as it should go to this URL and model: lowered once that pair has refused the full cap.
  static String forTarget(String url, String body) {
    return LOWERED.contains(key(url, body)) ? lowered(body) : body;
  }

  private static boolean lower(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value != null && value.canConvertToInt() && value.asInt() > FALLBACK_TOKENS) {
      node.put(field, FALLBACK_TOKENS);
      return true;
    }
    return false;
  }

  private static String key(String url, String body) {
    String model = "";
    try {
      model = JSON.readTree(body == null ? "{}" : body).path("model").asText("");
    } catch (Exception e) {
      // Not JSON: the URL alone identifies the target.
    }
    return url + "\n" + model;
  }
}
