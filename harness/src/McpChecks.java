import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.exchange.ExchangeSearch;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Memory;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.mcp.McpClient;
import com.mulesoftforge.studio.ai.mcp.McpHub;
import com.mulesoftforge.studio.ai.mcp.McpServers;
import com.mulesoftforge.studio.ai.mcp.McpTools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

// check-mcp: the MCP client against a fake Streamable HTTP server that serves 12 of the hosted MuleSoft Platform
// server's tools (fixtures/mcp, recorded 2026-11-08 from tools/list; it lists 126). It redirects /mcp to /mcp/ and
// answers with an event stream, as the live server does. Config parsing, the region address, sessions, paging,
// which tools ask first, finding tools, and the approval and mode rules in a turn.
final class McpChecks {
  private static final Path FIXTURE = Path.of("fixtures", "mcp", "platform-tools.json");
  private static final String TOKEN = "anypoint-token-7d1e44b2";
  private static final ObjectMapper JSON = new ObjectMapper();

  private McpChecks() {}

  static void run() throws Exception {
    Path home = Files.createTempDirectory("studio-ai-mcp-home");
    Extensions.overrideHome(home);
    Memory.overrideUserFile(home.resolve("memory.md"));
    int failed = 0;
    failed += config();
    failed += client();
    failed += tools();
    failed += inTurn();
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int config() throws Exception {
    int failed = 0;
    List<McpServers.Server> servers = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    Path file = Files.createTempDirectory("studio-ai-mcp").resolve("mcp.json");
    Files.writeString(file, """
        {"mcpServers": {
          "jira": {"type": "streamable-http", "url": "https://jira.example.com/mcp", "description": "Issues",
                   "headers": {"Authorization": "Bearer ${env:JIRA_TOKEN}"}},
          "local": {"url": "http://localhost:3000/mcp"},
          "npx": {"command": "npx", "args": ["-y", "mulesoft-mcp-server"]},
          "plain": {"url": "http://example.com/mcp"},
          "off": {"url": "https://off.example.com/mcp", "disabled": true},
          "mulesoft-platform": {"url": "https://evil.example.com/mcp"}
        }}
        """);
    McpServers.Loaded loaded = McpServers.load(file, true);
    servers.addAll(loaded.servers());
    problems.addAll(loaded.problems());
    failed += check("the Platform server comes first, then the remote servers in file order",
        servers.stream().map(McpServers.Server::name).toList().equals(List.of("mulesoft-platform", "jira", "local")));
    failed += check("a local stdio server is refused, so nothing is installed or started",
        problems.stream().anyMatch(p -> p.startsWith("\"npx\"") && p.contains("stdio")));
    failed += check("plain http is refused except on localhost", problems.stream().anyMatch(p -> p.startsWith(
        "\"plain\"")) && loaded.find("local") != null);
    failed += check("a disabled server is skipped and the Platform name cannot be taken", loaded.find("off") == null
        && problems.stream().anyMatch(p -> p.startsWith("\"mulesoft-platform\"")));
    failed += check("${env:NAME} in a header is read from the environment", McpServers.expand(
        loaded.find("jira").headers(), n -> n.equals("JIRA_TOKEN") ? "t-1" : null).get("Authorization")
        .equals("Bearer t-1"));
    failed += check("without the file and the setting, there are no servers", McpServers.load(
        file.resolveSibling("missing.json"), false).servers().isEmpty());
    failed += check("the Platform address follows Studio's region", "https://omni.mulesoft.com/mcp".equals(
        McpServers.platformUrl("https://anypoint.mulesoft.com")) && "https://eu1.omni.mulesoft.com/mcp".equals(
        McpServers.platformUrl("eu1.anypoint.mulesoft.com")) && McpServers.platformUrl("https://anypoint.acme.local")
        == null);
    return failed;
  }

  private static int client() throws Exception {
    int failed = 0;
    FakeServer server = new FakeServer(fixtureTools());
    McpClient client = new McpClient(URI.create("https://omni.mulesoft.com/mcp"),
        () -> Map.of("Authorization", "Bearer " + TOKEN), server, Duration.ofSeconds(5));
    List<JsonNode> tools = client.listTools();
    failed += check("307 to /mcp/ is followed with the same method, body and sign-in", server.posts.stream()
        .anyMatch(p -> p.startsWith("/mcp/ initialize bearer")) && server.posts.get(0).startsWith("/mcp initialize"));
    failed += check("tools/list pages are joined", tools.size() == 12 && server.methods().stream()
        .filter("tools/list"::equals).count() == 2);
    failed += check("the session id and protocol version are sent after initialize", server.sessions.stream()
        .skip(1).allMatch("s-1 2025-06-18"::equals));
    server.expireSession = true;
    JsonNode result = client.callTool("list_apis", JSON.createObjectNode());
    failed += check("an expired session (404) starts a new one and the call still answers", result.path("content")
        .get(0).path("text").asText().contains("list_apis") && server.methods().stream()
        .filter("initialize"::equals).count() == 2);
    McpClient elsewhere = new McpClient(URI.create("https://omni.mulesoft.com/mcp"), Map::of,
        (uri, headers, body, timeout) -> new McpClient.Reply(307, "", null, "https://evil.example.com/mcp", ""),
        Duration.ofSeconds(5));
    failed += check("a redirect to another host is not followed", message(() -> elsewhere.listTools())
        .contains("another host (evil.example.com)"));
    McpClient refused = new McpClient(URI.create("https://omni.mulesoft.com/mcp"),
        () -> Map.of("Authorization", "Bearer " + TOKEN),
        (uri, headers, body, timeout) -> new McpClient.Reply(401, "application/json", null, null,
            "{\"error\":\"unauthorized\"}"), Duration.ofSeconds(5));
    String why = message(() -> refused.listTools());
    failed += check("a refused sign-in says so without the token", why.contains("refused the sign-in (HTTP 401)")
        && !why.contains(TOKEN));
    return failed;
  }

  private static int tools() throws Exception {
    int failed = 0;
    McpHub hub = new McpHub(new McpServers.Loaded(List.of(McpServers.platform()), List.of()), McpChecks::login,
        new FakeServer(fixtureTools()), n -> null);
    List<McpHub.Tool> tools = hub.tools(null).tools();
    failed += check("read-only by the server's hint and by name runs without asking", !find(tools, "list_apis")
        .changes() && !find(tools, "view_api_instance_policies").changes());
    failed += check("create_omni_gateway asks first though the server marks it read-only",
        find(tools, "create_omni_gateway").changes());
    failed += check("a tool without hints asks first", find(tools, "apply_policy_to_instance").changes()
        && find(tools, "check_policy_conformance").changes());
    failed += check("a destructive tool asks first", find(tools, "remove_applied_policy").changes());
    List<McpHub.Tool> ranked = McpTools.rank(tools, "api instance policies");
    failed += check("\"api instance policies\" finds view_api_instance_policies first",
        ranked.get(0).name().equals("view_api_instance_policies"));
    failed += check("an exact tool name comes first", McpTools.rank(tools, "list_environments").get(0).name()
        .equals("list_environments"));
    String compact = McpTools.compact(find(tools, "list_apis").inputSchema());
    failed += check("schemas lose titles and anyOf-with-null", !compact.contains("\"title\"")
        && !compact.contains("anyOf") && compact.contains("\"organization_id\":{\"type\":\"string\"}"));
    failed += check("list_apis's schema is at least a third smaller", compact.length() * 3 < find(tools, "list_apis")
        .inputSchema().toString().length() * 2);
    return failed;
  }

  private static int inTurn() throws Exception {
    int failed = 0;
    Path dir = Files.createTempDirectory("studio-ai-mcp-turn");
    Files.createDirectories(dir.resolve("src/main/mule"));
    Files.writeString(dir.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");

    FakeServer server = new FakeServer(fixtureTools());
    ChatSession session = new ChatSession();
    session.mcp(new McpHub(new McpServers.Loaded(List.of(McpServers.platform()), List.of()), McpChecks::login,
        server, n -> null));
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(ExtendChecks.tool(McpTools.FIND, "{\"query\":\"list apis\"}")),
        ExtendChecks.calls(ExtendChecks.tool(McpTools.CALL,
            "{\"server\":\"mulesoft-platform\",\"tool\":\"list_apis\",\"arguments\":{}}")),
        ExtendChecks.calls(ExtendChecks.tool(McpTools.CALL,
            "{\"server\":\"mulesoft-platform\",\"tool\":\"create_environment\",\"arguments\":{\"name\":\"qa\"}}")),
        ExtendChecks.answer("You have 2 APIs.")));
    ChatSession.Turn turn = session.send(model, ChatMode.ASK, "which APIs do I have?", List.of(),
        new Harness.DiskProject(dir), Progress.NONE);
    List<ToolSpecification> offered = model.requests.get(0).toolSpecifications();
    failed += check("two MCP tools are offered, whatever the server has", offered.stream()
        .filter(s -> s.name().startsWith("mcp_")).count() == 2 && offered.stream()
        .noneMatch(s -> s.name().equals("list_apis")));
    failed += check("mcp_find_tools returns the parameters to call with", ExtendChecks.results(model.requests.get(1))
        .get(0).text().contains("mulesoft-platform / list_apis (read-only)"));
    failed += check("a read-only tool runs in Ask mode and its result goes back", ExtendChecks.results(
        model.requests.get(2)).get(1).text().startsWith("RESULT of list_apis on mulesoft-platform"));
    failed += check("a changing tool is refused in Ask mode and never reaches the server", ExtendChecks.results(
        model.requests.get(3)).get(2).text().startsWith(ChatMode.REFUSED) && !server.called("create_environment"));
    failed += check("the activity counts lookups and calls", turn.activity().contains("looked up MCP tools once")
        && turn.activity().contains("called MCP tools once"));
    boolean leaked = model.requests.stream().flatMap(r -> r.messages().stream()).map(ChatMessage::toString)
        .anyMatch(m -> m.contains(TOKEN));
    failed += check("the Anypoint token never reaches the model", !leaked);

    List<String> asked = new CopyOnWriteArrayList<>();
    failed += agentTurn(dir, false, asked, "Agent mode asks first; declined, the server is not called",
        (s, results) -> results.get(1).text().equals(McpTools.DECLINED_RESULT) && !s.called("create_environment")
            && asked.size() == 1 && asked.get(0).contains("\"name\" : \"qa\""));
    asked.clear();
    failed += agentTurn(dir, true, asked, "Agent mode, allowed: the call runs with the arguments",
        (s, results) -> results.get(1).text().startsWith("RESULT of create_environment")
            && s.called("create_environment {\"name\":\"qa\"}"));

    ChatSession none = new ChatSession();
    none.mcp(new McpHub(McpServers.Loaded.NONE, () -> null, server, n -> null));
    ExtendChecks.Scripted plain = new ExtendChecks.Scripted(List.of(ExtendChecks.answer("Hi.")));
    none.send(plain, ChatMode.ASK, "hi", List.of(), new Harness.DiskProject(dir), Progress.NONE);
    failed += check("without servers no MCP tool is offered", plain.requests.get(0).toolSpecifications().stream()
        .noneMatch(s -> s.name().startsWith("mcp_")));

    ChatSession signedOut = new ChatSession();
    signedOut.mcp(new McpHub(new McpServers.Loaded(List.of(McpServers.platform()), List.of()), () -> null, server,
        n -> null));
    ExtendChecks.Scripted lookup = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(ExtendChecks.tool(McpTools.FIND, "{\"query\":\"apis\"}")), ExtendChecks.answer("ok")));
    signedOut.send(lookup, ChatMode.ASK, "apis?", List.of(), new Harness.DiskProject(dir), Progress.NONE);
    failed += check("signed out of Studio, the model is told to tell the user to sign in", ExtendChecks.results(
        lookup.requests.get(1)).get(0).text().contains("Preferences > Anypoint Studio > Authentication"));
    return failed;
  }

  private interface TurnCheck {
    boolean ok(FakeServer server, List<dev.langchain4j.data.message.ToolExecutionResultMessage> results);
  }

  private static int agentTurn(Path dir, boolean allow, List<String> asked, String name, TurnCheck check)
      throws Exception {
    FakeServer server = new FakeServer(fixtureTools());
    ChatSession session = new ChatSession();
    session.mcp(new McpHub(new McpServers.Loaded(List.of(McpServers.platform()), List.of()), McpChecks::login,
        server, n -> null));
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(ExtendChecks.tool(McpTools.FIND, "{\"query\":\"create_environment\"}")),
        ExtendChecks.calls(ExtendChecks.tool(McpTools.CALL,
            "{\"server\":\"mulesoft-platform\",\"tool\":\"create_environment\",\"arguments\":{\"name\":\"qa\"}}")),
        ExtendChecks.answer("Done.")));
    session.send(model, ChatMode.AGENT, "create a qa environment", List.of(), new Harness.DiskProject(dir),
        new Progress() {
          @Override
          public void step(String message) {}

          @Override
          public boolean isCancelled() {
            return false;
          }

          @Override
          public boolean approve(String question, String detail) {
            asked.add(question + "\n" + detail);
            return allow;
          }
        });
    return check(name, check.ok(server, ExtendChecks.results(model.requests.get(2))));
  }

  // Serves initialize, tools/list in two pages and tools/call, like the hosted server: /mcp redirects to /mcp/,
  // and answers come as an event stream.
  static final class FakeServer implements McpClient.Transport {
    final List<String> posts = new CopyOnWriteArrayList<>();
    final List<String> sessions = new CopyOnWriteArrayList<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    private final ArrayNode tools;
    private int session;
    boolean expireSession;

    FakeServer(ArrayNode tools) {
      this.tools = tools;
    }

    List<String> methods() {
      return posts.stream().filter(p -> p.startsWith("/mcp/ ")).map(p -> p.split(" ")[1]).toList();
    }

    boolean called(String what) {
      return calls.stream().anyMatch(c -> c.startsWith(what));
    }

    @Override
    public McpClient.Reply post(URI uri, Map<String, String> headers, String body, Duration timeout) {
      try {
        JsonNode message = JSON.readTree(body);
        String method = message.path("method").asText();
        String auth = headers.getOrDefault("Authorization", "");
        posts.add(uri.getPath() + " " + method + (auth.equals("Bearer " + TOKEN) ? " bearer" : " anonymous"));
        if (uri.getPath().equals("/mcp")) {
          return new McpClient.Reply(307, "", null, "https://omni.mulesoft.com/mcp/", "");
        }
        if (!auth.equals("Bearer " + TOKEN)) {
          return new McpClient.Reply(401, "application/json", null, null, "{\"error\":\"unauthorized\"}");
        }
        sessions.add(headers.getOrDefault("Mcp-Session-Id", "-") + " " + headers.getOrDefault("MCP-Protocol-Version",
            "-"));
        if (method.equals("initialize")) {
          session++;
          ObjectNode result = JSON.createObjectNode();
          result.put("protocolVersion", McpClient.PROTOCOL);
          result.putObject("serverInfo").put("name", "MuleSoft Platform MCP Server");
          return stream(message, result, "s-" + session);
        }
        if (method.startsWith("notifications/")) {
          return new McpClient.Reply(202, "", null, null, "");
        }
        if (expireSession && !"-".equals(headers.getOrDefault("Mcp-Session-Id", "-"))) {
          expireSession = false;
          return new McpClient.Reply(404, "application/json", null, null, "{}");
        }
        if (method.equals("tools/list")) {
          boolean second = message.path("params").has("cursor");
          ObjectNode result = JSON.createObjectNode();
          ArrayNode page = result.putArray("tools");
          for (int i = second ? 8 : 0; i < (second ? tools.size() : 8); i++) {
            page.add(tools.get(i));
          }
          if (!second) {
            result.put("nextCursor", "page-2");
          }
          return stream(message, result, null);
        }
        if (method.equals("tools/call")) {
          String name = message.path("params").path("name").asText();
          calls.add(name + " " + message.path("params").path("arguments"));
          ObjectNode result = JSON.createObjectNode();
          result.putArray("content").addObject().put("type", "text").put("text", "{\"tool\":\"" + name
              + "\",\"apis\":[\"orders-api\",\"customers-api\"]}");
          return stream(message, result, null);
        }
        return new McpClient.Reply(400, "application/json", null, null, "{}");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    private static McpClient.Reply stream(JsonNode request, JsonNode result, String sessionId) {
      ObjectNode response = JSON.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", request.path("id"));
      response.set("result", result);
      return new McpClient.Reply(200, "text/event-stream", sessionId, null,
          "event: message\ndata: " + response + "\n\n");
    }
  }

  private static ExchangeSearch.Login login() {
    return new ExchangeSearch.Login("https://anypoint.mulesoft.com", TOKEN, "org-1", "Acme");
  }

  private static ArrayNode fixtureTools() throws Exception {
    return (ArrayNode) JSON.readTree(Files.readString(FIXTURE));
  }

  private static McpHub.Tool find(List<McpHub.Tool> tools, String name) {
    return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private interface Failing {
    void run() throws Exception;
  }

  private static String message(Failing action) {
    try {
      action.run();
      return "";
    } catch (Exception e) {
      return String.valueOf(e.getMessage());
    }
  }

  private static int check(String name, boolean ok) {
    return ExtendChecks.check(name, ok);
  }
}
