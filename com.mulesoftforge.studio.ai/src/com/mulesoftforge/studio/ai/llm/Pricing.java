package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.settings.Provider;

// Turns token counts into dollars. OpenRouter's reported cost wins; otherwise prices come from
// OpenRouter's public model list (same class of estimate as Roo-Code's curated $/MTok and Aider's
// LiteLLM table). Bedrock ids are normalised to OpenRouter ids before lookup.
public final class Pricing {
  public static final String PRICE_LIST_URL = "https://openrouter.ai/api/v1/models";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long RETRY_AFTER_FAILURE_MS = 10 * 60 * 1000;
  // Longest first. Matches Roo-Code AWS_INFERENCE_PROFILE_MAPPING + global. (bedrock.ts parseBaseModelId).
  private static final String[] BEDROCK_PROFILE_PREFIXES = {
      "global.", "apac.", "us.", "eu.", "au.", "jp.", "ca.", "ug.", "sa."
  };

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

  // Whether OpenRouter's price list has loaded. fetchedAt is null until it has; failure is the last fetch error.
  public record ListStatus(Instant fetchedAt, String failure) {}

  private static Map<String, Price> prices;
  private static long failedAt;
  private static Instant fetchedAt;
  private static String failure;

  private Pricing() {}

  public static synchronized ListStatus status() {
    return new ListStatus(fetchedAt, failure);
  }

  // Drops the cached list, so the next lookup fetches it again even straight after a failure.
  public static synchronized void refresh() {
    prices = null;
    failedAt = 0;
    fetchedAt = null;
    failure = null;
  }

  // Providers that set their own prices, so OpenRouter's list price is only a guide to the bill.
  public static boolean listPriceIsGuide(Provider provider) {
    return switch (provider) {
      case AZURE_OPENAI, BEDROCK, MULESOFT_AI_GATEWAY, CUSTOM -> true;
      case OPENAI, ANTHROPIC, GEMINI, OPENROUTER, OLLAMA -> false;
      default -> throw new IllegalStateException("Unhandled provider " + provider);
    };
  }

  public static CallCost cost(Provider provider, CallUsage usage) {
    return cost(provider, usage, true);
  }

  // Same as cost(), but never fetches the price list. Safe to call between agent tool rounds.
  public static CallCost costCached(Provider provider, CallUsage usage) {
    return cost(provider, usage, false);
  }

  private static CallCost cost(Provider provider, CallUsage usage, boolean mayFetch) {
    if (usage.reportedCost() != null) {
      return new CallCost(usage, usage.reportedCost(), Basis.BILLED);
    }
    if (provider == Provider.OLLAMA) {
      return new CallCost(usage, 0.0, Basis.FREE);
    }
    Price price = mayFetch ? lookup(provider, usage.model()) : lookupCached(provider, usage.model());
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

  // For a list headed "per 1M tokens". Null when the model is not in the price list. May fetch the list.
  public static String shortPriceText(Provider provider, String model) {
    if (model != null && model.trim().equals("openrouter/auto")) {
      return "whichever model it picks";
    }
    ListPrice price = listPrice(provider, model);
    if (price == null) {
      return null;
    }
    if (price.isFree()) {
      return "free";
    }
    return perMillion(price.inputPerMillion()) + " in · " + perMillion(price.outputPerMillion()) + " out";
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
    return lookupIn(prices(), provider, model);
  }

  private static Price lookupCached(Provider provider, String model) {
    Map<String, Price> list;
    synchronized (Pricing.class) {
      list = prices == null ? Map.of() : prices;
    }
    return lookupIn(list, provider, model);
  }

  private static Price lookupIn(Map<String, Price> list, Provider provider, String model) {
    if (model == null || model.isBlank() || list.isEmpty()) {
      return null;
    }
    for (String id : candidates(provider, model.trim())) {
      Price price = list.get(id);
      if (price != null) {
        return price;
      }
    }
    return null;
  }

  // Provider model ids differ from OpenRouter's: dated snapshots, and Anthropic uses 4-6 for 4.6.
  // Public for offline harness checks of Bedrock → OpenRouter id mapping.
  public static List<String> candidates(Provider provider, String model) {
    String undated = stripDateSuffix(model);
    Set<String> ids = new LinkedHashSet<>();
    switch (provider) {
      case OPENAI -> {
        ids.add("openai/" + model);
        ids.add("openai/" + undated);
      }
      case ANTHROPIC -> {
        ids.add("anthropic/" + model);
        ids.add("anthropic/" + anthropicOpenRouterName(undated));
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
      case BEDROCK -> addBedrockCandidates(ids, model);
      case OPENROUTER, MULESOFT_AI_GATEWAY, CUSTOM, OLLAMA -> {
        ids.add(model);
        ids.add(undated);
        if (!model.contains("/")) {
          ids.add("openai/" + undated);
          ids.add("anthropic/" + anthropicOpenRouterName(undated));
          ids.add("google/" + undated);
        }
      }
      default -> throw new IllegalStateException("Unhandled provider " + provider);
    }
    return new ArrayList<>(ids);
  }

  private static void addBedrockCandidates(Set<String> ids, String model) {
    ids.add(model);
    ids.add(stripDateSuffix(model));
    // us.anthropic.… / global.amazon.… → anthropic.… / amazon.… (Roo-Code parseBaseModelId).
    String bare = stripBedrockProfilePrefix(model);
    if (!bare.equals(model)) {
      ids.add(bare);
      ids.add(stripDateSuffix(bare));
    }
    String noVersion = stripBedrockVersionSuffix(bare);
    if (!noVersion.equals(bare)) {
      ids.add(noVersion);
      ids.add(stripDateSuffix(noVersion));
    }
    for (String form : List.of(bare, noVersion, stripDateSuffix(bare), stripDateSuffix(noVersion))) {
      addBedrockOpenRouterIds(ids, form);
    }
  }

  // Inference-profile prefixes from Roo-Code AWS_INFERENCE_PROFILE_MAPPING, plus global.
  static String stripBedrockProfilePrefix(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return modelId;
    }
    for (String prefix : BEDROCK_PROFILE_PREFIXES) {
      if (modelId.startsWith(prefix)) {
        return modelId.substring(prefix.length());
      }
    }
    return modelId;
  }

  static String stripBedrockVersionSuffix(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return modelId;
    }
    return modelId.replaceFirst(":\\d+$", "");
  }

  private static void addBedrockOpenRouterIds(Set<String> ids, String bare) {
    if (bare == null || bare.isBlank() || !bare.contains(".")) {
      return;
    }
    int dot = bare.indexOf('.');
    String vendor = bare.substring(0, dot);
    String rest = bare.substring(dot + 1);
    if (rest.isBlank()) {
      return;
    }
    switch (vendor) {
      case "anthropic" -> ids.add("anthropic/" + anthropicOpenRouterName(rest));
      case "amazon" -> ids.add("amazon/" + rest);
      case "openai" -> ids.add("openai/" + rest);
      case "meta" -> ids.add("meta-llama/" + rest);
      case "mistral" -> ids.add("mistralai/" + rest);
      case "google" -> ids.add("google/" + rest);
      case "cohere" -> ids.add("cohere/" + rest);
      case "deepseek" -> ids.add("deepseek/" + rest);
      case "xai" -> {
        ids.add("x-ai/" + rest);
        ids.add("xai/" + rest);
      }
      case "qwen" -> ids.add("qwen/" + rest);
      case "moonshotai", "moonshot" -> {
        ids.add("moonshotai/" + rest);
        ids.add("moonshot/" + rest);
      }
      default -> {
        // Unknown Bedrock vendor: still try vendor/rest in case OpenRouter uses the same slug.
        ids.add(vendor + "/" + rest);
      }
    }
  }

  private static String stripDateSuffix(String model) {
    return model.replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "").replaceFirst("-\\d{8}$", "")
        .replaceFirst("-\\d{4}$", "");
  }

  // Anthropic OpenRouter ids use dots in the version (claude-sonnet-4.6), Bedrock often uses hyphens.
  private static String anthropicOpenRouterName(String name) {
    String value = name.startsWith("anthropic.") ? name.substring("anthropic.".length()) : name;
    return value.replaceAll("(\\d)-(\\d)", "$1.$2");
  }

  private static synchronized Map<String, Price> prices() {
    if (prices != null) {
      return prices;
    }
    if (failedAt > 0 && System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
      return Map.of();
    }
    try {
      HttpClient http = Network.client();
      HttpRequest request = HttpRequest.newBuilder(URI.create(PRICE_LIST_URL))
          .timeout(Duration.ofSeconds(20))
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("OpenRouter answered HTTP " + response.statusCode());
      }
      Map<String, Price> parsed = new HashMap<>();
      for (JsonNode model : JSON.readTree(response.body()).path("data")) {
        JsonNode p = model.path("pricing");
        double input = number(p, "prompt");
        double output = number(p, "completion");
        if (input >= 0 && output >= 0) {
          parsed.put(model.path("id").asText(), new Price(input, output,
              Math.max(0, number(p, "input_cache_read")), Math.max(0, number(p, "input_cache_write"))));
        }
      }
      if (parsed.isEmpty()) {
        throw new IOException("OpenRouter's model list had no prices");
      }
      prices = parsed;
      fetchedAt = Instant.now();
      failure = null;
      return prices;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failedAt = System.currentTimeMillis();
      failure = Errors.describe(e);
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
