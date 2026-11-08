package com.mulesoftforge.studio.ai.exchange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// search_connectors: ranked Mule 4 connectors on Exchange for a system, with the pom.xml dependency to add.
// Read-only, so every mode offers it. Results are kept for a few minutes, since one design asks for several
// systems and the same term often comes up again.
public final class ExchangeTools {
  public static final String SEARCH = "search_connectors";
  static final int SHOWN = 5;
  private static final Duration KEEP = Duration.ofMinutes(10);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

  private record Cached(ExchangeSearch.Result result, Instant at) {}

  public static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
      .name(SEARCH)
      .description("Search Anypoint Exchange for Mule 4 connectors for one external system, for example"
          + " \"salesforce\", \"servicenow\" or \"sftp\". Returns the best matches with their latest release and the"
          + " Maven dependency to add to pom.xml, and says which ones the project already uses. Call it once per"
          + " system before you design a flow that calls a system the project has no connector for, and before"
          + " you use http:request for a named system. Use connector_reference afterwards for the operations of"
          + " a connector the project already depends on.")
      .parameters(JsonObjectSchema.builder()
          .addStringProperty("term", "The system or product name, one or two words, e.g. \"netsuite\"")
          .required("term")
          .build())
      .build();

  private final ExchangeSearch search;
  private final ProjectFiles project;

  // project may be null; then nothing is said about what the project already uses.
  public ExchangeTools(ExchangeSearch search, ProjectFiles project) {
    this.search = search;
    this.project = project;
  }

  public static boolean handles(String tool) {
    return SEARCH.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    return "Searching Exchange for " + term(request) + " connectors";
  }

  // Tests start from an empty cache.
  public static void clearCache() {
    CACHE.clear();
  }

  public String execute(ToolExecutionRequest request) {
    String term = term(request);
    if (term.isEmpty()) {
      return "ERROR: term is required, for example {\"term\":\"salesforce\"}.";
    }
    String key = term.toLowerCase(Locale.ROOT);
    Cached cached = CACHE.get(key);
    ExchangeSearch.Result result;
    if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
      result = cached.result();
    } else {
      result = search.search(term);
      if (result.problem() == null) {
        CACHE.put(key, new Cached(result, Instant.now()));
      }
    }
    return format(term, result, pom());
  }

  static String format(String term, ExchangeSearch.Result result, String pom) {
    if (result.problem() != null) {
      return "ERROR: could not search Exchange for \"" + term + "\": " + result.problem() + ". Do not guess a"
          + " connector's coordinates; tell the user, or ask them which connector and version to use.";
    }
    List<ExchangeSearch.Asset> ranked = result.ranked();
    StringBuilder out = new StringBuilder("EXCHANGE CONNECTORS for \"").append(term).append("\" (searched ")
        .append(result.scope()).append("):\n");
    if (ranked.isEmpty()) {
      return out.append("No Mule 4 connector matches. There is no dedicated connector for this on Exchange, so use"
          + " http:request (or another generic connector such as sftp or db) and say so in your answer.\n")
          .toString();
    }
    boolean weak = ranked.get(0).score() <= 0;
    if (weak) {
      out.append("No connector's name matches \"").append(term).append("\" well. If none of these is for that"
          + " system, it has no dedicated connector: use http:request and say so.\n");
    }
    List<ExchangeSearch.Asset> shown = ranked.subList(0, Math.min(SHOWN, ranked.size()));
    for (int i = 0; i < shown.size(); i++) {
      ExchangeSearch.Asset asset = shown.get(i);
      out.append(i + 1).append(". ").append(asset.name()).append(" — ").append(asset.coordinates());
      if (asset.deprecated()) {
        out.append(" (deprecated: do not use for new work)");
      }
      String used = usedVersion(pom, asset.assetId());
      if (used != null) {
        out.append(" (this project already depends on it").append(used.isEmpty() ? "" : ", version " + used)
            .append(")");
      }
      out.append('\n');
      if (!asset.description().isBlank()) {
        out.append("   ").append(oneLine(asset.description())).append('\n');
      }
    }
    ExchangeSearch.Asset top = shown.get(0);
    if (!weak && usedVersion(pom, top.assetId()) == null) {
      out.append("\nTo use ").append(top.assetId()).append(", propose this in pom.xml <dependencies>:\n")
          .append("<dependency>\n  <groupId>").append(top.groupId()).append("</groupId>\n  <artifactId>")
          .append(top.assetId()).append("</artifactId>\n  <version>").append(top.version())
          .append("</version>\n  <classifier>mule-plugin</classifier>\n</dependency>\n")
          .append("Studio downloads it when the user accepts; connector_reference knows its operations after"
              + " that. Until then, take element and parameter names only from the connector's documentation"
              + " (fetch_docs), not from memory, and tell the user which ones you could not check.\n");
    }
    return out.toString();
  }

  // The version the project's pom.xml uses for this artifact, "" when it is a property, null when absent.
  static String usedVersion(String pom, String assetId) {
    if (pom == null) {
      return null;
    }
    int at = pom.indexOf("<artifactId>" + assetId + "</artifactId>");
    if (at < 0) {
      return null;
    }
    int end = pom.indexOf("</dependency>", at);
    String block = pom.substring(at, end < 0 ? pom.length() : end);
    int open = block.indexOf("<version>");
    int close = block.indexOf("</version>");
    if (open < 0 || close < open) {
      return "";
    }
    String version = block.substring(open + "<version>".length(), close).strip();
    return version.startsWith("${") ? "" : version;
  }

  private String pom() {
    if (project == null) {
      return null;
    }
    try {
      return project.read("pom.xml");
    } catch (Exception e) {
      return null;
    }
  }

  private static String term(ToolExecutionRequest request) {
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank() ? "{}"
          : request.arguments());
      return args.path("term").asText("").strip();
    } catch (Exception e) {
      return "";
    }
  }

  private static String oneLine(String text) {
    String flat = text.replaceAll("\\s+", " ").strip();
    return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
  }
}
