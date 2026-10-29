package com.mulesoftforge.studio.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.settings.Provider;

// Turns token counts into dollars. OpenRouter's reported cost wins; otherwise prices come from
// OpenRouter's public model list, which also carries OpenAI and Anthropic list prices.
public final class Pricing {
  public static final String PRICE_LIST_URL = "https://openrouter.ai/api/v1/models";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long RETRY_AFTER_FAILURE_MS = 10 * 60 * 1000;

  // USD per token.
  record Price(double input, double output, double cacheRead, double cacheWrite) {}

  public enum Basis {
    BILLED("billed by provider"),
    ESTIMATED("estimated from list price"),
    FREE("local, no charge"),
    UNKNOWN("no price available");

    public final String label;

    Basis(String label) {
      this.label = label;
    }
  }

  public record CallCost(CallUsage usage, Double usd, Basis basis) {}

  private static Map<String, Price> prices;
  private static long failedAt;

  private Pricing() {}

  public static CallCost cost(Provider provider, CallUsage usage) {
    if (usage.reportedCost() != null) {
      return new CallCost(usage, usage.reportedCost(), Basis.BILLED);
    }
    if (provider == Provider.OLLAMA) {
      return new CallCost(usage, 0.0, Basis.FREE);
    }
    Price price = lookup(provider, usage.model());
    if (price == null) {
      return new CallCost(usage, null, Basis.UNKNOWN);
    }
    double usd = usage.uncachedInput() * price.input()
        + usage.cacheRead() * (price.cacheRead() > 0 ? price.cacheRead() : price.input())
        + usage.cacheWrite() * (price.cacheWrite() > 0 ? price.cacheWrite() : price.input())
        + usage.output() * price.output();
    return new CallCost(usage, usd, Basis.ESTIMATED);
  }

  // USD per million tokens, for showing a model's price before it is used.
  public record ListPrice(double inputPerMillion, double outputPerMillion) {
    public boolean isFree() {
      return inputPerMillion == 0 && outputPerMillion == 0;
    }
  }

  // May fetch the price list, so call it off the UI thread. Null when the model is not listed.
  public static ListPrice listPrice(Provider provider, String model) {
    if (provider == Provider.OLLAMA) {
      return new ListPrice(0, 0);
    }
    Price price = lookup(provider, model);
    return price == null ? null : new ListPrice(price.input() * 1_000_000, price.output() * 1_000_000);
  }

  // May fetch the price list, so call it off the UI thread.
  public static String priceText(Provider provider, String model) {
    if (model != null && model.trim().equals("openrouter/auto")) {
      return "price of whichever model it picks";
    }
    return describe(listPrice(provider, model));
  }

  public static String describe(ListPrice price) {
    if (price == null) {
      return "price unknown";
    }
    if (price.isFree()) {
      return "free";
    }
    return perMillion(price.inputPerMillion()) + " in · " + perMillion(price.outputPerMillion()) + " out per 1M";
  }

  private static String perMillion(double usd) {
    return String.format(Locale.ROOT, usd >= 10 ? "$%.0f" : usd >= 0.01 ? "$%.2f" : "$%.4f", usd);
  }

  private static Price lookup(Provider provider, String model) {
    if (model == null || model.isBlank()) {
      return null;
    }
    Map<String, Price> list = prices();
    for (String id : candidates(provider, model.trim())) {
      Price price = list.get(id);
      if (price != null) {
        return price;
      }
    }
    return null;
  }

  // Provider model ids differ from OpenRouter's: dated snapshots, and Anthropic uses 4-6 for 4.6.
  static List<String> candidates(Provider provider, String model) {
    String undated = model.replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "").replaceFirst("-\\d{8}$", "")
        .replaceFirst("-\\d{4}$", "");
    List<String> ids = new ArrayList<>();
    switch (provider) {
      case OPENAI -> {
        ids.add("openai/" + model);
        ids.add("openai/" + undated);
      }
      case ANTHROPIC -> {
        ids.add("anthropic/" + model);
        ids.add("anthropic/" + undated.replaceAll("(\\d)-(\\d)", "$1.$2"));
      }
      case GEMINI -> {
        ids.add("google/" + model);
        ids.add("google/" + undated);
      }
      case AZURE_OPENAI -> {
        ids.add("openai/" + model);
        ids.add("openai/" + undated);
        ids.add(model);
      }
      case BEDROCK -> {
        ids.add(model);
        ids.add(undated);
        // OpenRouter-style ids when the Bedrock id embeds the vendor.
        if (model.startsWith("anthropic.")) {
          ids.add("anthropic/" + undated.replace("anthropic.", "").replaceAll("(\\d)-(\\d)", "$1.$2"));
        }
        if (model.startsWith("amazon.")) {
          ids.add("amazon/" + undated.replace("amazon.", ""));
        }
      }
      case OPENROUTER, MULESOFT_AI_GATEWAY, CUSTOM, OLLAMA -> {
        ids.add(model);
        ids.add(undated);
        if (!model.contains("/")) {
          ids.add("openai/" + undated);
          ids.add("anthropic/" + undated.replaceAll("(\\d)-(\\d)", "$1.$2"));
          ids.add("google/" + undated);
        }
      }
      default -> throw new IllegalStateException("Unhandled provider " + provider);
    }
    return ids;
  }

  private static synchronized Map<String, Price> prices() {
    if (prices != null) {
      return prices;
    }
    if (failedAt > 0 && System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
      return Map.of();
    }
    try {
      HttpClient http = Network.builder().build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(PRICE_LIST_URL))
          .timeout(Duration.ofSeconds(20))
          .GET()
          .build();
      String body = http.send(request, BodyHandlers.ofString()).body();
      Map<String, Price> parsed = new HashMap<>();
      for (JsonNode model : JSON.readTree(body).path("data")) {
        JsonNode p = model.path("pricing");
        double input = number(p, "prompt");
        double output = number(p, "completion");
        if (input >= 0 && output >= 0) {
          parsed.put(model.path("id").asText(), new Price(input, output,
              Math.max(0, number(p, "input_cache_read")), Math.max(0, number(p, "input_cache_write"))));
        }
      }
      prices = parsed;
      return prices;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failedAt = System.currentTimeMillis();
      return Map.of();
    }
  }

  private static double number(JsonNode pricing, String field) {
    JsonNode value = pricing.get(field);
    if (value == null || value.isNull()) {
      return 0;
    }
    try {
      return Double.parseDouble(value.asText());
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
