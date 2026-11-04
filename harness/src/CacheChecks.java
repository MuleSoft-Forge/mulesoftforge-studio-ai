import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.CacheMarks;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

// check-cache: where prompt-cache marks go on native Anthropic and Bedrock. Both get the same four as the OpenAI-format
// path (system prompt, the history before the latest user message, the latest user message, the latest tool result),
// never more than four, only on the copy that is sent, and the cache tokens they report are counted.
final class CacheChecks {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String READ = "Read the flow file please";

  private CacheChecks() {}

  static void run() throws Exception {
    Path root = Files.createTempDirectory("studio-ai-cache");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.writeString(root.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");
    Harness.DiskProject project = new Harness.DiskProject(root);
    int failed = 0;
    failed += rule();
    failed += anthropic(project);
    failed += bedrock(project);
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int rule() {
    CacheMarks.Kind s = CacheMarks.Kind.SYSTEM;
    CacheMarks.Kind u = CacheMarks.Kind.USER;
    CacheMarks.Kind t = CacheMarks.Kind.TOOL;
    CacheMarks.Kind o = CacheMarks.Kind.OTHER;
    int failed = 0;
    failed += check("rule: first message marks the system prompt and the message",
        CacheMarks.chosen(List.of(s, u)).equals(Set.of(0, 1)));
    failed += check("rule: a later turn also marks the history before it",
        CacheMarks.chosen(List.of(s, u, o, u)).equals(Set.of(0, 2, 3)));
    failed += check("rule: a tool round marks its latest result, four marks at most",
        CacheMarks.chosen(List.of(s, u, o, u, o, t, o, t)).equals(Set.of(0, 2, 3, 7)));
    return failed;
  }

  private static int anthropic(Harness.DiskProject project) throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      boolean answered = body.contains("\"tool_result\"");
      anthropicStream(exchange, !answered && body.contains(READ));
    });
    server.start();
    int failed = 0;
    try {
      ChatModel model = ChatModels.create(new Connection(Provider.ANTHROPIC,
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "claude-sonnet-4-6", "sk-ant-test-key"));
      ChatSession session = new ChatSession();
      ChatSession.Turn first = session.send(model, ChatMode.ASK, READ, List.of(), project, Progress.NONE);
      session.send(model, ChatMode.ASK, "and the next part?", List.of(), project, Progress.NONE);
      JsonNode opening = JSON.readTree(bodies.get(0));
      JsonNode round = JSON.readTree(bodies.get(1));
      JsonNode next = JSON.readTree(bodies.get(2));

      failed += check("anthropic: the system prompt is marked, the tools are not (the system mark covers them)",
          last(opening.path("system")).has("cache_control") && opening.path("tools").size() > 0
              && !opening.path("tools").toString().contains("cache_control"));
      failed += check("anthropic: the first request marks the system prompt and the message",
          marks(bodies.get(0)) == 2 && last(last(opening.path("messages")).path("content")).has("cache_control"));
      JsonNode result = last(last(round.path("messages")).path("content"));
      failed += check("anthropic: a tool round marks the latest tool result",
          "tool_result".equals(result.path("type").asText()) && result.has("cache_control") && marks(bodies.get(1)) == 3);
      JsonNode messages = next.path("messages");
      JsonNode before = messages.get(messages.size() - 2);
      failed += check("anthropic: the next turn marks the earlier answer and the new message",
          "assistant".equals(before.path("role").asText()) && last(before.path("content")).has("cache_control")
              && marks(bodies.get(2)) == 3);
      failed += check("anthropic: earlier marks are not kept in the history",
          !messages.get(0).toString().contains("cache_control"));
      failed += check("anthropic: never more than four marks", bodies.stream().allMatch(b -> marks(b) <= 4));
      failed += check("anthropic: cache reads and writes are counted", first.calls().get(0).cacheRead() == 900
          && first.calls().get(0).cacheWrite() == 100);
    } finally {
      server.stop(0);
    }
    return failed;
  }

  private static int bedrock(Harness.DiskProject project) throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    List<String> authorizations = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/model/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      boolean answered = body.contains("\"toolResult\"");
      String content = !answered && body.contains(READ)
          ? "[{\"toolUse\":{\"toolUseId\":\"t1\",\"name\":\"read_file\",\"input\":{\"path\":\"src/main/mule/api.xml\"}}}]"
          : "[{\"text\":\"Read it.\"}]";
      String reply = "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":" + content + "}},"
          + "\"stopReason\":\"" + (content.contains("toolUse") ? "tool_use" : "end_turn") + "\","
          + "\"usage\":{\"inputTokens\":40,\"outputTokens\":5,\"totalTokens\":1045,\"cacheReadInputTokens\":700,"
          + "\"cacheWriteInputTokens\":300},\"metrics\":{\"latencyMs\":1}}";
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    int failed = 0;
    BedrockRuntimeClient client = ChatModels.bedrockClientBuilder("us-east-1", "test-bedrock-key")
        .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
    try {
      ChatModel claude = BedrockChatModel.builder().client(client).modelId("us.anthropic.claude-sonnet-4-6-v1:0")
          .build();
      ChatSession session = new ChatSession();
      ChatSession.Turn first = session.send(claude, ChatMode.ASK, READ, List.of(), project, Progress.NONE);
      session.send(claude, ChatMode.ASK, "and the next part?", List.of(), project, Progress.NONE);
      JsonNode opening = JSON.readTree(bodies.get(0));
      JsonNode round = JSON.readTree(bodies.get(1));

      failed += check("bedrock: an API key is sent as a bearer token, not signed with AWS credentials",
          authorizations.stream().allMatch(a -> a.equals("Bearer test-bedrock-key")));
      failed += check("bedrock: the first request marks the system prompt and the message",
          last(opening.path("system")).has("cachePoint") && last(last(opening.path("messages")).path("content"))
              .has("cachePoint") && points(bodies.get(0)) == 2);
      JsonNode result = round.path("messages").get(round.path("messages").size() - 1).path("content");
      failed += check("bedrock: a tool round marks the latest tool result",
          result.get(0).has("toolResult") && last(result).has("cachePoint") && points(bodies.get(1)) == 3);
      failed += check("bedrock: the next turn marks the earlier answer and the new message, not the old ones",
          points(bodies.get(2)) == 3
              && !JSON.readTree(bodies.get(2)).path("messages").get(0).toString().contains("cachePoint"));
      failed += check("bedrock: cache reads and writes are counted", first.calls().get(0).cacheRead() == 700
          && first.calls().get(0).cacheWrite() == 300 && first.calls().get(0).uncachedInput() == 40);

      bodies.clear();
      ChatModel llama = BedrockChatModel.builder().client(client).modelId("meta.llama3-70b-instruct-v1:0").build();
      new ChatSession().send(llama, ChatMode.ASK, "hello", List.of(), project, Progress.NONE);
      failed += check("bedrock: models AWS does not cache for get no cache points", points(bodies.get(0)) == 0);
    } finally {
      client.close();
      server.stop(0);
    }
    return failed;
  }

  private static void anthropicStream(HttpExchange exchange, boolean callTool) throws IOException {
    StringBuilder events = new StringBuilder();
    event(events, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
        + "\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\",\"content\":[],\"stop_reason\":null,"
        + "\"usage\":{\"input_tokens\":40,\"output_tokens\":1,\"cache_read_input_tokens\":900,"
        + "\"cache_creation_input_tokens\":100}}}");
    if (callTool) {
      event(events, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
          + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}");
      event(events, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
          + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"src/main/mule/api.xml\\\"}\"}}");
    } else {
      event(events, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
          + "{\"type\":\"text\",\"text\":\"\"}}");
      event(events, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
          + "{\"type\":\"text_delta\",\"text\":\"Read it.\"}}");
    }
    event(events, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    event(events, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
        + (callTool ? "tool_use" : "end_turn") + "\"},\"usage\":{\"output_tokens\":5}}");
    event(events, "message_stop", "{\"type\":\"message_stop\"}");
    byte[] bytes = events.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void event(StringBuilder events, String name, String data) {
    events.append("event: ").append(name).append("\ndata: ").append(data).append("\n\n");
  }

  private static JsonNode last(JsonNode array) {
    return array.isArray() && !array.isEmpty() ? array.get(array.size() - 1) : JSON.createObjectNode();
  }

  private static int marks(String body) {
    return count(body, "\"cache_control\"");
  }

  private static int points(String body) {
    return count(body, "\"cachePoint\"");
  }

  private static int count(String text, String needle) {
    int n = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
