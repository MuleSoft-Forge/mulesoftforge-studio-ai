/*
 * The ranking in rank() is adapted from lib/exchange-rank.mjs in the build-mule-integration skill of
 * @salesforce/mulesoft-vibes-skills (https://github.com/mulesoft/mulesoft-dx).
 * Copyright (c) 2026 Salesforce, Inc. Licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 * Changed: ported to Java; "mule4" is dropped like "mule", so mule4-x-connector ranks with mule-x-connector;
 * search terms split on spaces too; deprecated assets sort last.
 */
package com.mulesoftforge.studio.ai.exchange;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.Network;

// Searches Anypoint Exchange for Mule 4 connectors (assets of type extension), as anypoint-cli-v4 exchange asset
// list does. Public connectors need no login; with Studio's Anypoint login the organization's own assets are
// searched too. The token goes only into the Authorization header of this request.
public final class ExchangeSearch {
  public static final String PUBLIC_BASE = "https://anypoint.mulesoft.com";
  static final int LIMIT = 40;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern PRERELEASE = Pattern.compile("-(SNAPSHOT|RC|alpha|beta|M[0-9])");
  private static final Set<String> FILLER = Set.of("mule", "mule4", "connector");

  // Studio's Anypoint login; baseUrl is the control plane, e.g. https://anypoint.mulesoft.com.
  public record Login(String baseUrl, String token, String organizationId, String organizationName) {}

  public record Response(int status, String body) {}

  public interface Fetch {
    // bearer is null for an anonymous request.
    Response get(URI uri, String bearer) throws Exception;
  }

  public record Asset(String groupId, String assetId, String version, String name, String description,
      boolean deprecated, int score) {
    public String coordinates() {
      return groupId + ":" + assetId + ":" + version;
    }
  }

  // scope says what was searched; problem is set, and ranked empty, when Exchange could not be searched.
  public record Result(List<Asset> ranked, String scope, String problem) {}

  private final Fetch fetch;
  private final Supplier<Login> login;

  public ExchangeSearch(Fetch fetch, Supplier<Login> login) {
    this.fetch = fetch;
    this.login = login == null ? () -> null : login;
  }

  public static ExchangeSearch live(Supplier<Login> login) {
    return new ExchangeSearch((uri, bearer) -> {
      HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json");
      if (bearer != null) {
        request.header("Authorization", "Bearer " + bearer);
      }
      HttpResponse<String> response = Network.client().send(request.GET().build(), BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    }, login);
  }

  public Result search(String term) {
    String query = term == null ? "" : term.strip();
    Login user = null;
    try {
      user = login.get();
    } catch (RuntimeException e) {
      user = null;
    }
    String fallbackWhy = null;
    if (user != null && user.token() != null && !user.token().isBlank()) {
      String base = user.baseUrl() != null && user.baseUrl().startsWith("https://")
          ? user.baseUrl().replaceAll("/+$", "") : PUBLIC_BASE;
      try {
        Response response = fetch.get(uri(base, query), user.token());
        if (response.status() == 200) {
          String org = user.organizationName() == null || user.organizationName().isBlank() ? "your organization"
              : user.organizationName();
          return new Result(rank(parse(response.body()), query), "Exchange as " + org + " (Studio's Anypoint login),"
              + " with public assets", null);
        }
        fallbackWhy = "Studio's Anypoint login got HTTP " + response.status();
      } catch (Exception e) {
        fallbackWhy = "Studio's Anypoint login could not be used";
      }
    }
    try {
      Response response = fetch.get(uri(PUBLIC_BASE, query), null);
      if (response.status() != 200) {
        return new Result(List.of(), "", "Exchange answered HTTP " + response.status());
      }
      String scope = "public Exchange" + (fallbackWhy == null ? " (not signed in to Anypoint Platform in Studio, so"
          + " your organization's private assets are not included)" : " (" + fallbackWhy + ")");
      return new Result(rank(parse(response.body()), query), scope, null);
    } catch (Exception e) {
      return new Result(List.of(), "", "Exchange could not be reached: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  static URI uri(String base, String term) {
    return URI.create(base + "/exchange/api/v2/assets/search?search=" + URLEncoder.encode(term, StandardCharsets.UTF_8)
        + "&types=extension&limit=" + LIMIT);
  }

  static List<Asset> parse(String body) throws Exception {
    JsonNode root = JSON.readTree(body);
    List<Asset> out = new ArrayList<>();
    if (!root.isArray()) {
      return out;
    }
    for (JsonNode row : root) {
      if (!"extension".equals(row.path("type").asText())) {
        continue;
      }
      String name = row.path("name").asText("");
      out.add(new Asset(row.path("groupId").asText(""), row.path("assetId").asText(""), row.path("version").asText(""),
          name, row.path("description").asText(""), name.toLowerCase(Locale.ROOT).contains("deprecated"), 0));
    }
    return out;
  }

  // Best first: releases only, the highest version of each asset, scored by how its assetId's words match the
  // term (2 per exact word, 1 per partial, -1 per other word), then MuleSoft's own groups, then the shorter id.
  public static List<Asset> rank(List<Asset> assets, String term) {
    List<String> searchTokens = tokens(term);
    Map<String, Asset> best = new LinkedHashMap<>();
    for (Asset asset : assets) {
      if (asset.version().isEmpty() || PRERELEASE.matcher(asset.version()).find()) {
        continue;
      }
      String key = asset.groupId() + " " + asset.assetId();
      Asset kept = best.get(key);
      if (kept == null || compareVersions(asset.version(), kept.version()) > 0) {
        best.put(key, asset);
      }
    }
    List<Asset> scored = new ArrayList<>();
    for (Asset asset : best.values()) {
      int score = 0;
      for (String token : tokens(asset.assetId())) {
        score += searchTokens.contains(token) ? 2 : partial(token, searchTokens) ? 1 : -1;
      }
      scored.add(new Asset(asset.groupId(), asset.assetId(), asset.version(), asset.name(), asset.description(),
          asset.deprecated(), score));
    }
    scored.sort(Comparator.comparing(Asset::deprecated)
        .thenComparing(Comparator.comparingInt(Asset::score).reversed())
        .thenComparingInt(a -> groupPreference(a.groupId()))
        .thenComparingInt(a -> a.assetId().length()));
    return scored;
  }

  private static List<String> tokens(String text) {
    List<String> out = new ArrayList<>();
    for (String token : String.valueOf(text).toLowerCase(Locale.ROOT).split("[-\\s_]+")) {
      if (!token.isEmpty() && !FILLER.contains(token)) {
        out.add(token);
      }
    }
    return out;
  }

  private static boolean partial(String token, List<String> searchTokens) {
    if (token.length() < 2) {
      return false;
    }
    return searchTokens.stream().anyMatch(s -> s.length() >= 2 && (s.contains(token) || token.contains(s)));
  }

  private static int groupPreference(String groupId) {
    return switch (groupId) {
      case "com.mulesoft.connectors" -> 0;
      case "org.mule.connectors" -> 1;
      default -> 2;
    };
  }

  static int compareVersions(String a, String b) {
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      int x = i < left.length ? number(left[i]) : 0;
      int y = i < right.length ? number(right[i]) : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return 0;
  }

  private static int number(String segment) {
    try {
      return Integer.parseInt(segment);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
