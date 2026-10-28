package com.mulesoftforge.studio.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// OpenRouter returns the billed amount as usage.cost; LangChain4j drops it, so the HTTP client keeps it
// for the thread that made the (synchronous) call.
final class ReportedCost {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ThreadLocal<Double> LAST = new ThreadLocal<>();

  private ReportedCost() {}

  static void capture(String body) {
    if (body == null || !body.contains("\"cost\"")) {
      return;
    }
    try {
      JsonNode cost = JSON.readTree(body).path("usage").path("cost");
      if (cost.isNumber()) {
        LAST.set(cost.asDouble());
      }
    } catch (Exception ignored) {
      // Not JSON or no usage block: fall back to the price list.
    }
  }

  static Double take() {
    Double value = LAST.get();
    LAST.remove();
    return value;
  }
}
