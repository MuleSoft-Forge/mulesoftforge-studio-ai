package com.mulesoftforge.studio.ai.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Requests ask for ChatModels.MAX_OUTPUT_TOKENS, which thinking models need, since thinking counts against the
// output cap. A model with a smaller limit answers 400 naming the token field; the request is then sent again with
// the next lower step, down to FALLBACK_TOKENS, and every later request to that URL and model asks for the step that
// was accepted from the start.
final class OutputCap {
  static final int FALLBACK_TOKENS = 8192;
  private static final int[] STEPS = {16384, FALLBACK_TOKENS};
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, Integer> LOWERED = new ConcurrentHashMap<>();
  private static final Pattern REJECTS = Pattern.compile(
      "(?i)max_completion_tokens|max_tokens|maxOutputTokens|max(imum)?[ _-]?(output[ _-]?)?tokens|num_predict");

  private OutputCap() {}

  static boolean rejects(int status, String body) {
    return status == 400 && body != null && REJECTS.matcher(body).find();
  }

  // The body with its output cap lowered to the next step below it; the same string when there is no lower step.
  static String lowered(String body) {
    int cap = cap(body);
    for (int step : STEPS) {
      if (step < cap) {
        return capped(body, step);
      }
    }
    return body;
  }

  // Records the cap in this body, which was lowered after the one before it was refused.
  static void remember(String url, String body) {
    int cap = cap(body);
    if (cap != Integer.MAX_VALUE) {
      LOWERED.merge(key(url, body), cap, Math::min);
    }
  }

  // The body as it should go to this URL and model: capped at the last step that pair was lowered to.
  static String forTarget(String url, String body) {
    Integer cap = LOWERED.get(key(url, body));
    return cap == null ? body : capped(body, cap);
  }

  // The largest output cap in the body; MAX_VALUE when it has none.
  private static int cap(String body) {
    ObjectNode request = parse(body);
    if (request == null) {
      return Integer.MAX_VALUE;
    }
    int cap = Math.max(value(request, "max_completion_tokens"), value(request, "max_tokens"));
    if (request.get("generationConfig") instanceof ObjectNode gemini) {
      cap = Math.max(cap, value(gemini, "maxOutputTokens"));
    }
    return cap < 0 ? Integer.MAX_VALUE : cap;
  }

  // The body with every output cap above the limit lowered to it; the same string when there was none.
  private static String capped(String body, int limit) {
    ObjectNode request = parse(body);
    if (request == null) {
      return body;
    }
    boolean changed = lower(request, "max_completion_tokens", limit) | lower(request, "max_tokens", limit);
    if (request.get("generationConfig") instanceof ObjectNode gemini) {
      changed |= lower(gemini, "maxOutputTokens", limit);
    }
    try {
      return changed ? JSON.writeValueAsString(request) : body;
    } catch (Exception e) {
      return body;
    }
  }

  private static ObjectNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return JSON.readTree(body) instanceof ObjectNode request ? request : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static int value(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.canConvertToInt() ? value.asInt() : -1;
  }

  private static boolean lower(ObjectNode node, String field, int limit) {
    if (value(node, field) > limit) {
      node.put(field, limit);
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
