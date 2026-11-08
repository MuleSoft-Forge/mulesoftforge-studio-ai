package com.mulesoftforge.studio.ai.mcp;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoftforge.studio.ai.exchange.ExchangeSearch;
import com.mulesoftforge.studio.ai.mcp.McpClient.McpException;

// The connections to the configured MCP servers, opened on first use and kept across turns, with each server's
// tool list kept for KEEP. Nothing connects until the model asks for a tool, so a chat that never uses MCP costs
// no requests.
public final class McpHub {
  static final Duration KEEP = Duration.ofMinutes(10);
  // Tool names that start with a verb that changes something. A server's readOnlyHint is not trusted alone: the
  // annotations are hints (MCP spec), and some servers mark a create_ tool read-only.
  private static final Pattern CHANGES = Pattern.compile("^(create|delete|update|edit|apply|remove|toggle|rename"
      + "|add|provision|trigger|onboard|batch|set|deploy|undeploy|redeploy|publish|unpublish|revoke|grant|start"
      + "|stop|restart|send|post|put|patch|write|upload|import|run|execute|invoke|cancel|approve|reject|assign"
      + "|unassign|move|merge|close|reopen|enable|disable|promote|rollback|scale|reset|rotate|register"
      + "|unregister|subscribe|unsubscribe|install|uninstall|archive|restore|purge)(_|$)");

  public record Tool(String server, String name, String description, JsonNode inputSchema, Boolean readOnlyHint,
      Boolean destructiveHint) {
    // Asks the user first and only in Agent mode. Read-only needs both the server's hint and a name that does
    // not start with a changing verb; a tool without hints counts as changing, as in Codex.
    public boolean changes() {
      return !Boolean.TRUE.equals(readOnlyHint) || Boolean.TRUE.equals(destructiveHint)
          || CHANGES.matcher(name.toLowerCase(Locale.ROOT)).find();
    }
  }

  public record Listing(List<Tool> tools, List<String> problems) {}

  private record Cached(List<Tool> tools, Instant at) {}

  private final McpServers.Loaded config;
  private final Supplier<ExchangeSearch.Login> login;
  private final McpClient.Transport transport;
  private final Function<String, String> env;
  private final Map<String, McpClient> clients = new HashMap<>();
  private final Map<String, Cached> lists = new HashMap<>();

  public McpHub(McpServers.Loaded config, Supplier<ExchangeSearch.Login> login, McpClient.Transport transport,
      Function<String, String> env) {
    this.config = config;
    this.login = login;
    this.transport = transport;
    this.env = env;
  }

  public static McpHub live(McpServers.Loaded config, Supplier<ExchangeSearch.Login> login) {
    return new McpHub(config, login, McpClient.LIVE, System::getenv);
  }

  public McpServers.Loaded config() {
    return config;
  }

  public boolean isEmpty() {
    return config.servers().isEmpty();
  }

  // Every tool of one server, or of all of them when server is null; a server that cannot be reached is a problem,
  // not an error, so the others still answer.
  public synchronized Listing tools(String server) {
    List<Tool> tools = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (McpServers.Server each : config.servers()) {
      if (server != null && !each.name().equalsIgnoreCase(server)) {
        continue;
      }
      try {
        tools.addAll(toolsOf(each));
      } catch (McpException e) {
        problems.add(each.name() + ": " + e.getMessage());
      }
    }
    return new Listing(tools, problems);
  }

  public synchronized Tool tool(String server, String name) throws McpException {
    McpServers.Server found = config.find(server);
    if (found == null) {
      throw new McpException("there is no MCP server \"" + server + "\"");
    }
    for (Tool tool : toolsOf(found)) {
      if (tool.name().equals(name)) {
        return tool;
      }
    }
    return null;
  }

  public synchronized JsonNode call(Tool tool, JsonNode arguments) throws McpException {
    McpServers.Server server = config.find(tool.server());
    if (server == null) {
      throw new McpException("there is no MCP server \"" + tool.server() + "\"");
    }
    return client(server).callTool(tool.name(), arguments);
  }

  private List<Tool> toolsOf(McpServers.Server server) throws McpException {
    Cached cached = lists.get(server.name());
    if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
      return cached.tools();
    }
    List<Tool> tools = new ArrayList<>();
    for (JsonNode node : client(server).listTools()) {
      String name = node.path("name").asText("");
      if (name.isEmpty()) {
        continue;
      }
      JsonNode annotations = node.path("annotations");
      tools.add(new Tool(server.name(), name, node.path("description").asText("").strip(),
          node.path("inputSchema"), hint(annotations, "readOnlyHint"), hint(annotations, "destructiveHint")));
    }
    List<Tool> kept = List.copyOf(tools);
    lists.put(server.name(), new Cached(kept, Instant.now()));
    return kept;
  }

  private McpClient client(McpServers.Server server) throws McpException {
    McpClient existing = clients.get(server.name());
    if (existing != null) {
      return existing;
    }
    McpClient created;
    Duration timeout = Duration.ofSeconds(server.timeoutSeconds());
    if (server.anypointLogin()) {
      ExchangeSearch.Login now = login == null ? null : login.get();
      if (now == null || now.token() == null || now.token().isBlank()) {
        throw new McpException("Studio is not signed in to Anypoint Platform. Sign in under Preferences >"
            + " Anypoint Studio > Authentication");
      }
      String url = McpServers.platformUrl(now.baseUrl());
      if (url == null) {
        throw new McpException("MuleSoft does not host the Platform MCP server for " + now.baseUrl());
      }
      created = new McpClient(URI.create(url), () -> {
        ExchangeSearch.Login current = login.get();
        return current == null || current.token() == null ? Map.of()
            : Map.of("Authorization", "Bearer " + current.token());
      }, transport, timeout);
    } else {
      Map<String, String> headers = server.headers();
      created = new McpClient(URI.create(server.url()), () -> McpServers.expand(headers, env), transport, timeout);
    }
    clients.put(server.name(), created);
    return created;
  }

  private static Boolean hint(JsonNode annotations, String key) {
    JsonNode value = annotations.get(key);
    return value == null || !value.isBoolean() ? null : value.asBoolean();
  }
}
