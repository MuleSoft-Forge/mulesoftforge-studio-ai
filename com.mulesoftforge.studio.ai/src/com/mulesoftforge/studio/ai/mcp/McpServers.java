package com.mulesoftforge.studio.ai.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// The MCP servers the AI may use: the hosted MuleSoft Platform server (off until turned on in Preferences; it signs
// in with Studio's Anypoint login) and the user's own remote servers from ~/.mulesoftforge/studio-ai/mcp.json, in
// the "mcpServers" format Roo-Code, Cline and gemini-cli read. Only Streamable HTTP servers are supported: nothing
// is installed or started on this machine.
public final class McpServers {
  public static final String FILE = "mcp.json";
  public static final String PLATFORM = "mulesoft-platform";
  public static final String PLATFORM_DESCRIPTION = "MuleSoft Anypoint Platform, as the user signed in to Studio:"
      + " business groups, environments, APIs and their instances, policies, governance, monitoring, Exchange"
      + " assets, agents, MCP servers and LLMs.";
  static final int DEFAULT_TIMEOUT_SECONDS = 60;
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");
  private static final Pattern ENV = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)\\}");
  private static final ObjectMapper JSON = new ObjectMapper();

  // url is null for the Platform server, whose address follows the region Studio is signed in to.
  public record Server(String name, String description, String url, Map<String, String> headers,
      boolean anypointLogin, int timeoutSeconds) {}

  public record Loaded(List<Server> servers, List<String> problems) {
    public static final Loaded NONE = new Loaded(List.of(), List.of());

    public Server find(String name) {
      for (Server server : servers) {
        if (server.name().equalsIgnoreCase(name == null ? "" : name.strip())) {
          return server;
        }
      }
      return null;
    }
  }

  private McpServers() {}

  public static Server platform() {
    return new Server(PLATFORM, PLATFORM_DESCRIPTION, null, Map.of(), true, DEFAULT_TIMEOUT_SECONDS);
  }

  // file may be missing; then only the Platform server, when on, is listed.
  public static Loaded load(Path file, boolean platformOn) {
    List<Server> servers = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    if (platformOn) {
      servers.add(platform());
    }
    if (file != null && Files.isRegularFile(file)) {
      try {
        parse(Files.readString(file, StandardCharsets.UTF_8), servers, problems);
      } catch (Exception e) {
        problems.add(file.getFileName() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName()
            : e.getMessage().lines().findFirst().orElse("")));
      }
    }
    return new Loaded(List.copyOf(servers), List.copyOf(problems));
  }

  static void parse(String text, List<Server> servers, List<String> problems) throws Exception {
    JsonNode root = JSON.readTree(text);
    JsonNode entries = root == null ? null : root.path("mcpServers");
    if (entries == null || !entries.isObject()) {
      problems.add(FILE + " has no \"mcpServers\" object");
      return;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = entries.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> entry = it.next();
      String name = entry.getKey();
      JsonNode node = entry.getValue();
      if (node.path("disabled").asBoolean(false)) {
        continue;
      }
      if (!NAME.matcher(name).matches()) {
        problems.add("\"" + name + "\": use letters, digits, - and _ for the name (up to 40)");
        continue;
      }
      if (name.equalsIgnoreCase(PLATFORM) || servers.stream().anyMatch(s -> s.name().equalsIgnoreCase(name))) {
        problems.add("\"" + name + "\": the name is already used");
        continue;
      }
      String type = node.path("type").asText("streamable-http").toLowerCase(Locale.ROOT);
      if (node.has("command") || type.equals("stdio")) {
        problems.add("\"" + name + "\": local (stdio) servers are not supported; use a remote server's https URL");
        continue;
      }
      if (!type.equals("streamable-http") && !type.equals("streamablehttp") && !type.equals("http")) {
        problems.add("\"" + name + "\": type \"" + type + "\" is not supported; use \"streamable-http\"");
        continue;
      }
      String url = node.path("url").asText("").strip();
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        uri = null;
      }
      boolean local = uri != null && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
      if (uri == null || uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
          || local && "http".equalsIgnoreCase(uri.getScheme()))) {
        problems.add("\"" + name + "\": url must be https:// (or http://localhost)");
        continue;
      }
      Map<String, String> headers = new LinkedHashMap<>();
      node.path("headers").fields().forEachRemaining(h -> headers.put(h.getKey(), h.getValue().asText("")));
      int timeout = node.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS);
      servers.add(new Server(name, node.path("description").asText("").strip(), url, Map.copyOf(headers), false,
          Math.max(1, Math.min(timeout, 600))));
    }
  }

  // ${env:NAME} in a header value is read from the environment, so a token need not be written in the file.
  public static Map<String, String> expand(Map<String, String> headers, Function<String, String> env) {
    Map<String, String> out = new LinkedHashMap<>();
    headers.forEach((key, value) -> {
      Matcher m = ENV.matcher(value);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        String found = env.apply(m.group(1));
        m.appendReplacement(sb, Matcher.quoteReplacement(found == null ? "" : found));
      }
      m.appendTail(sb);
      out.put(key, sb.toString());
    });
    return out;
  }

  // https://omni.mulesoft.com/mcp for anypoint.mulesoft.com, https://eu1.omni.mulesoft.com/mcp for
  // eu1.anypoint.mulesoft.com; null for a control plane MuleSoft does not host the server for.
  public static String platformUrl(String anypointBase) {
    if (anypointBase == null || anypointBase.isBlank()) {
      anypointBase = "https://anypoint.mulesoft.com";
    }
    String base = anypointBase.strip();
    String host;
    try {
      host = URI.create(base.contains("://") ? base : "https://" + base).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (host == null) {
      return null;
    }
    host = host.toLowerCase(Locale.ROOT);
    if (host.equals("anypoint.mulesoft.com")) {
      return "https://omni.mulesoft.com/mcp";
    }
    if (host.endsWith(".anypoint.mulesoft.com")) {
      return "https://" + host.substring(0, host.length() - ".anypoint.mulesoft.com".length())
          + ".omni.mulesoft.com/mcp";
    }
    return null;
  }

  public static String template() {
    return """
        {
          "mcpServers": {
            "example": {
              "type": "streamable-http",
              "url": "https://example.com/mcp",
              "description": "What this server is for, so the AI knows when to use it",
              "headers": { "Authorization": "Bearer ${env:EXAMPLE_TOKEN}" },
              "timeout": 60,
              "disabled": true
            }
          }
        }
        """;
  }
}
