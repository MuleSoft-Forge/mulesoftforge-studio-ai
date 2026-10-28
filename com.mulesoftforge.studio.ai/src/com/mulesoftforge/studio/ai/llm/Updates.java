package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// The plugin's update site on the MuleSoftForge docs site, and the small latest.json published with each release.
public final class Updates {
  public static final String SITE = "https://docs.mulesoftforge.com/studio-ai/updates/";
  public static final String LATEST_URL = SITE + "latest.json";
  public static final String PAGE = "https://docs.mulesoftforge.com/studio-ai/";
  private static final ObjectMapper JSON = new ObjectMapper();

  public record Latest(String version, String notes) {}

  private Updates() {}

  public static Latest fetch(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .GET()
        .build();
    HttpResponse<String> response = Network.builder().build().send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("GET " + url + " returned HTTP " + response.statusCode() + "\n"
          + response.headers().map() + "\n" + response.body());
    }
    return parse(response.body());
  }

  public static Latest parse(String json) throws IOException {
    JsonNode root = JSON.readTree(json);
    String version = root.path("version").asText("").trim();
    if (version.isEmpty()) {
      throw new IOException("latest.json has no version: " + json);
    }
    return new Latest(version, root.path("notes").asText(""));
  }

  // "0.1.0.202610281104" (an OSGi version with a build qualifier) becomes "0.1.0".
  public static String shortVersion(String version) {
    String[] parts = version.split("\\.");
    return parts.length <= 3 ? version : parts[0] + "." + parts[1] + "." + parts[2];
  }

  // Compares major.minor.patch numerically; qualifiers are ignored, missing parts count as 0.
  public static int compare(String a, String b) {
    String[] left = shortVersion(a).split("\\.");
    String[] right = shortVersion(b).split("\\.");
    for (int i = 0; i < 3; i++) {
      int difference = Integer.compare(number(left, i), number(right, i));
      if (difference != 0) {
        return difference;
      }
    }
    return 0;
  }

  public static boolean isNewer(String candidate, String installed) {
    return compare(candidate, installed) > 0;
  }

  private static int number(String[] parts, int index) {
    if (index >= parts.length) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
