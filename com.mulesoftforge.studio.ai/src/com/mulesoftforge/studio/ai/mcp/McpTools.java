package com.mulesoftforge.studio.ai.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.SecretMask;
import com.mulesoftforge.studio.ai.mcp.McpClient.McpException;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// The user's MCP servers as two fixed tools, mcp_find_tools and mcp_call, instead of one tool per server tool: the
// MuleSoft Platform server alone has 126 tools, about 67k tokens of definitions. Codex likewise defers every
// MCP tool behind tool_search and Roo-Code routes calls through one use_mcp_tool. The tool list stays the same
// whatever the servers offer, so the prompt cache holds; the schemas the model looked up stay in the chat.
public final class McpTools {
  public static final String FIND = "mcp_find_tools";
  public static final String CALL = "mcp_call";
  // Counted in the turn's activity when the user said no.
  public static final String DECLINED = "mcp_declined";
  static final int SHOWN = 8;
  static final int MAX_RESULT_CHARS = 40_000;
  static final int MAX_FIND_CHARS = 30_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> STOP = Set.of("a", "an", "the", "of", "for", "to", "in", "on", "and", "or",
      "my", "me", "all", "with", "by", "from", "is", "are", "what", "which", "show", "get", "list", "find", "tool",
      "tools");

  private final McpHub hub;
  private final SecretMask secrets;
  private final ChatMode mode;
  private final Progress progress;

  public McpTools(McpHub hub, SecretMask secrets, ChatMode mode, Progress progress) {
    this.hub = hub;
    this.secrets = secrets;
    this.mode = mode;
    this.progress = progress;
  }

  public static boolean handles(String tool) {
    return FIND.equals(tool) || CALL.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    JsonNode args = args(request);
    if (FIND.equals(request.name())) {
      String query = args.path("query").asText("").strip();
      return query.isEmpty() ? "Listing MCP tools" : "Finding MCP tools for \"" + query + "\"";
    }
    return "Calling " + args.path("tool").asText("?") + " on " + args.path("server").asText("?");
  }

  // Built from the configured servers only, never from what they answer, so it does not change between turns.
  public List<ToolSpecification> specifications() {
    StringBuilder servers = new StringBuilder();
    for (McpServers.Server server : hub.config().servers()) {
      servers.append("\n- ").append(server.name())
          .append(server.description().isBlank() ? "" : ": " + server.description());
    }
    ToolSpecification find = ToolSpecification.builder()
        .name(FIND)
        .description("Find tools on the user's MCP servers. Returns up to " + SHOWN + " tools that match the query,"
            + " with their parameters and whether they change anything. Call it before mcp_call, and again when"
            + " you need a tool you have not looked up in this chat. An empty query lists every tool's name."
            + " Servers:" + servers)
        .parameters(JsonObjectSchema.builder()
            .addStringProperty("query", "What you want to do, in a few words, e.g. \"api policies\", or a tool name")
            .addStringProperty("server", "Only this server's tools (optional)")
            .required("query")
            .build())
        .build();
    ToolSpecification call = ToolSpecification.builder()
        .name(CALL)
        .description("Call one tool on an MCP server, with arguments that match the parameters mcp_find_tools"
            + " returned. Tools that only read run at once. Tools that change something run only in Agent mode,"
            + " and only after the user approves that call; if the user declines, do not call it again.")
        .parameters(JsonObjectSchema.builder()
            .addStringProperty("server", "The server name")
            .addStringProperty("tool", "The tool name, exactly as mcp_find_tools returned it")
            .addProperty("arguments", JsonObjectSchema.builder()
                .description("The tool's arguments as an object; {} when it has none")
                .additionalProperties(true)
                .build())
            .required("server", "tool", "arguments")
            .build())
        .build();
    return List.of(find, call);
  }

  public String execute(ToolExecutionRequest request) {
    JsonNode args = args(request);
    String text = FIND.equals(request.name()) ? find(args) : call(args);
    return secrets.mask(null, text).text();
  }

  private String find(JsonNode args) {
    String query = args.path("query").asText("").strip();
    String server = args.path("server").asText("").strip();
    if (!server.isEmpty() && hub.config().find(server) == null) {
      return "ERROR: there is no MCP server \"" + server + "\". Servers: " + names() + ".";
    }
    McpHub.Listing listing = hub.tools(server.isEmpty() ? null : server);
    StringBuilder out = new StringBuilder();
    for (String problem : listing.problems()) {
      out.append("Could not reach ").append(problem).append(".\n");
    }
    if (listing.tools().isEmpty()) {
      return out.length() == 0 ? "No MCP tools are available." : "ERROR: " + out.toString().strip()
          + " Tell the user; do not guess what the server would have returned.";
    }
    List<McpHub.Tool> ranked = rank(listing.tools(), query);
    if (query.isEmpty() || ranked.isEmpty()) {
      out.append(query.isEmpty() ? "" : "No tool matches \"" + query + "\". ").append("Every tool, by server:\n");
      appendNames(out, listing.tools());
      return out.append("\nCall mcp_find_tools with a tool's name to see its parameters.").toString();
    }
    out.append("MCP TOOLS for \"").append(query).append("\" (").append(ranked.size() < SHOWN ? ranked.size() : SHOWN)
        .append(" of ").append(listing.tools().size()).append("):\n");
    List<McpHub.Tool> more = new ArrayList<>();
    for (McpHub.Tool tool : ranked.subList(0, Math.min(SHOWN, ranked.size()))) {
      String entry = entry(tool);
      if (out.length() + entry.length() > MAX_FIND_CHARS) {
        more.add(tool);
        continue;
      }
      out.append('\n').append(entry);
    }
    if (!more.isEmpty()) {
      out.append("\nAlso matching, look up by name for parameters: ")
          .append(String.join(", ", more.stream().map(McpHub.Tool::name).toList())).append('\n');
    }
    return out.toString();
  }

  private String call(JsonNode args) {
    String server = args.path("server").asText("").strip();
    String name = args.path("tool").asText("").strip();
    if (server.isEmpty() || name.isEmpty()) {
      return "ERROR: server and tool are required, e.g. {\"server\":\"" + firstServer()
          + "\",\"tool\":\"...\",\"arguments\":{}}.";
    }
    McpHub.Tool tool;
    try {
      tool = hub.tool(server, name);
    } catch (McpException e) {
      return "ERROR: " + e.getMessage() + ". Servers: " + names() + ".";
    }
    if (tool == null) {
      return "ERROR: " + server + " has no tool \"" + name + "\". Call mcp_find_tools to find the right one.";
    }
    JsonNode arguments = args.path("arguments");
    if (arguments.isTextual()) {
      try {
        arguments = JSON.readTree(arguments.asText());
      } catch (Exception e) {
        return "ERROR: arguments must be a JSON object.";
      }
    }
    if (arguments.isMissingNode() || arguments.isNull()) {
      arguments = JSON.createObjectNode();
    }
    if (!arguments.isObject()) {
      return "ERROR: arguments must be a JSON object.";
    }
    JsonNode sent;
    try {
      sent = JSON.readTree(secrets.unmask(arguments.toString()));
    } catch (Exception e) {
      return "ERROR: arguments must be a JSON object.";
    }
    if (tool.changes()) {
      if (!mode.canEdit) {
        return ChatMode.refused(mode.label + " mode cannot use MCP tools that change something (" + tool.name()
            + " on " + tool.server() + "). Agent mode can, after the user approves the call.");
      }
      if (!progress.approve("Allow " + tool.name() + " on " + tool.server() + "?", approval(tool, sent))) {
        return DECLINED_RESULT;
      }
    }
    JsonNode result;
    try {
      result = hub.call(tool, sent);
    } catch (McpException e) {
      return "ERROR: " + tool.name() + " on " + tool.server() + " failed: " + e.getMessage() + ".";
    }
    return capped(text(tool, result));
  }

  public static final String DECLINED_RESULT = "DECLINED BY THE USER (not a failure of your call): the user did not"
      + " allow this change. Do not call it again unless the user asks; say what it would have done instead.";

  // What the approval dialog shows: the tool, what the server says it does, and the exact arguments.
  static String approval(McpHub.Tool tool, JsonNode arguments) {
    String pretty;
    try {
      pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(arguments);
    } catch (Exception e) {
      pretty = arguments.toString();
    }
    String about = tool.description().lines().findFirst().orElse("");
    return "The AI wants to call " + tool.name() + " on the MCP server " + tool.server() + "."
        + (about.isBlank() ? "" : "\n\n" + (about.length() > 300 ? about.substring(0, 300) + "…" : about))
        + (Boolean.TRUE.equals(tool.destructiveHint()) ? "\n\nThe server marks this tool as destructive." : "")
        + "\n\nArguments:\n" + (pretty.length() > 2000 ? pretty.substring(0, 2000) + "\n…" : pretty)
        + "\n\nThis can change your account and cannot be undone from Studio.";
  }

  // Text and resources as text; images and audio are named, not sent. structuredContent is used when the server
  // sent no text, as Codex does.
  static String text(McpHub.Tool tool, JsonNode result) {
    StringBuilder out = new StringBuilder();
    boolean error = result.path("isError").asBoolean(false);
    for (JsonNode part : result.path("content")) {
      String type = part.path("type").asText("");
      String piece = switch (type) {
        case "text" -> part.path("text").asText("");
        case "resource" -> part.path("resource").has("text") ? part.path("resource").path("text").asText("")
            : "[resource " + part.path("resource").path("uri").asText("") + " not shown]";
        case "resource_link" -> "Resource link: " + part.path("name").asText("") + " "
            + part.path("uri").asText("");
        case "image", "audio" -> "[" + type + " (" + part.path("mimeType").asText("?") + ") not shown]";
        default -> "";
      };
      if (!piece.isBlank()) {
        out.append(out.length() == 0 ? "" : "\n\n").append(piece);
      }
    }
    JsonNode structured = result.get("structuredContent");
    if (out.length() == 0 && structured != null && !structured.isNull()) {
      out.append(structured.toString());
    }
    String body = out.length() == 0 ? "(no content)" : out.toString();
    return (error ? "ERROR from " : "RESULT of ") + tool.name() + " on " + tool.server() + ":\n" + body;
  }

  static String capped(String text) {
    if (text.length() <= MAX_RESULT_CHARS) {
      return text;
    }
    return text.substring(0, MAX_RESULT_CHARS) + String.format(Locale.ROOT, "\n\n[Cut: the result held %,d"
        + " characters; only the first %,d are shown. Ask for less, e.g. with a filter or a smaller page.]",
        text.length(), MAX_RESULT_CHARS);
  }

  // Score per query word: 3 in the name, 2 in a parameter name, 1 in the description. Plurals match singulars.
  public static List<McpHub.Tool> rank(List<McpHub.Tool> tools, String query) {
    Set<String> words = words(query);
    String exact = query.strip().toLowerCase(Locale.ROOT);
    if (words.isEmpty()) {
      return List.of();
    }
    Map<McpHub.Tool, Integer> scores = new LinkedHashMap<>();
    for (McpHub.Tool tool : tools) {
      if (tool.name().equalsIgnoreCase(exact)) {
        scores.put(tool, 1000);
        continue;
      }
      Set<String> name = words(tool.name());
      Set<String> params = words(String.join(" ", parameterNames(tool.inputSchema())));
      Set<String> description = words(tool.description());
      int score = 0;
      for (String word : words) {
        score += (name.contains(word) ? 3 : 0) + (params.contains(word) ? 2 : 0) + (description.contains(word) ? 1 : 0);
      }
      if (score > 0) {
        scores.put(tool, score);
      }
    }
    return scores.entrySet().stream()
        .sorted(Map.Entry.<McpHub.Tool, Integer>comparingByValue(Comparator.reverseOrder())
            .thenComparing(e -> e.getKey().name().length()))
        .map(Map.Entry::getKey)
        .toList();
  }

  static Set<String> words(String text) {
    Set<String> out = new HashSet<>();
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (raw.length() < 2 || STOP.contains(raw)) {
        continue;
      }
      out.add(stem(raw));
    }
    return out;
  }

  private static String stem(String word) {
    if (word.length() > 4 && word.endsWith("ies")) {
      return word.substring(0, word.length() - 3) + "y";
    }
    if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
      return word.substring(0, word.length() - 1);
    }
    return word;
  }

  private static List<String> parameterNames(JsonNode schema) {
    List<String> names = new ArrayList<>();
    schema.path("properties").fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String entry(McpHub.Tool tool) {
    StringBuilder out = new StringBuilder();
    out.append(tool.server()).append(" / ").append(tool.name())
        .append(tool.changes() ? " (changes something: Agent mode, the user approves each call)" : " (read-only)")
        .append('\n');
    if (!tool.description().isBlank()) {
      out.append("  ").append(tool.description().replaceAll("\\s+", " ").strip()).append('\n');
    }
    out.append("  parameters: ").append(compact(tool.inputSchema())).append('\n');
    return out.toString();
  }

  // The input schema without titles, and with "anyOf: [X, null]" folded to X: about 28% smaller for the Platform
  // server's schemas, which are generated from Python type hints.
  public static String compact(JsonNode schema) {
    if (schema == null || schema.isMissingNode() || schema.isNull()) {
      return "{}";
    }
    return simplify(schema.deepCopy()).toString();
  }

  private static JsonNode simplify(JsonNode node) {
    if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, simplify(array.get(i)));
      }
      return array;
    }
    if (!node.isObject()) {
      return node;
    }
    ObjectNode object = (ObjectNode) node;
    object.remove("title");
    JsonNode anyOf = object.get("anyOf");
    if (anyOf != null && anyOf.isArray()) {
      List<JsonNode> kept = new ArrayList<>();
      anyOf.forEach(option -> {
        if (!"null".equals(option.path("type").asText())) {
          kept.add(option);
        }
      });
      if (kept.size() == 1 && kept.get(0).isObject()) {
        object.remove("anyOf");
        kept.get(0).fields().forEachRemaining(f -> object.set(f.getKey(), f.getValue()));
      }
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> field = it.next();
      field.setValue(simplify(field.getValue()));
    }
    if (object.has("default") && object.get("default").isNull()) {
      object.remove("default");
    }
    return object;
  }

  private static void appendNames(StringBuilder out, List<McpHub.Tool> tools) {
    String server = null;
    List<String> line = new ArrayList<>();
    for (McpHub.Tool tool : tools) {
      if (!tool.server().equals(server)) {
        if (server != null) {
          out.append(server).append(": ").append(String.join(", ", line)).append('\n');
        }
        server = tool.server();
        line.clear();
      }
      line.add(tool.name());
    }
    if (server != null) {
      out.append(server).append(": ").append(String.join(", ", line)).append('\n');
    }
  }

  private String names() {
    return String.join(", ", hub.config().servers().stream().map(McpServers.Server::name).toList());
  }

  private String firstServer() {
    return hub.config().servers().isEmpty() ? "name" : hub.config().servers().get(0).name();
  }

  private static JsonNode args(ToolExecutionRequest request) {
    try {
      String raw = request.arguments();
      return JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    } catch (Exception e) {
      return JSON.createObjectNode();
    }
  }
}
