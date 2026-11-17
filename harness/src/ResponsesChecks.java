import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.RawCapture;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.mulesoftforge.studio.ai.settings.WireApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

// check-responses: an OpenAI-compatible connection set to the Responses API, against a local /v1/responses server.
// The request shape (store off, encrypted reasoning asked for, output cap), streamed text and usage, a tool call,
// the encrypted reasoning sent back with the tool result, a Foundry-style 400, the output-cap retry, and Chat
// Completions staying the default.
final class ResponsesChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ResponsesChecks() {}

  static void run() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      try {
        answer(exchange, body);
      } finally {
        exchange.close();
      }
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    int failed = 0;
    try {
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1", "resp-model", "").withWireApi(WireApi.RESPONSES);
      failed += defaults();
      failed += text(connection, bodies);
      failed += tools(connection, bodies);
      failed += errors(connection);
      failed += outputCap(connection.withModel("small-model"), bodies);
    } finally {
      server.stop(0);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int defaults() {
    Connection plain = new Connection(Provider.CUSTOM, "http://127.0.0.1/v1", "m", "");
    return check("connections stay on Chat Completions unless Responses is chosen",
        plain.wireApi() == WireApi.CHAT_COMPLETIONS && plain.withModel("other").wireApi() == WireApi.CHAT_COMPLETIONS
            && WireApi.fromName(null) == WireApi.CHAT_COMPLETIONS && WireApi.fromName("RESPONSES") == WireApi.RESPONSES
            && plain.withWireApi(WireApi.RESPONSES).withModel("other").wireApi() == WireApi.RESPONSES);
  }

  private static int text(Connection connection, List<String> bodies) throws Exception {
    int failed = 0;
    StringBuffer pieces = new StringBuffer();
    AtomicReference<CallUsage> usage = new AtomicReference<>();
    Progress progress = new Progress() {
      @Override
      public void step(String message) {}

      @Override
      public void answered(CallUsage call, long seconds) {
        usage.set(call);
      }

      @Override
      public void partial(String text) {
        pieces.append(text);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
    RawCapture capture = new RawCapture(1, "Custom", call -> {});
    bodies.clear();
    ChatSession.Turn turn = AutoRouter.send(new ChatSession(),
        List.of(new AutoRouter.Option("resp-model", ChatModels.create(connection, capture))), ChatMode.ASK,
        "say hello", List.of(), null, progress);
    failed += check("a Responses answer is put together from its stream", "Hello there.".equals(turn.text()));
    failed += check("the answer text reaches the view as it streams", pieces.toString().equals("Hello there."));
    JsonNode sent = bodies.isEmpty() ? JSON.createObjectNode() : JSON.readTree(bodies.get(0));
    failed += check("the request is Responses-shaped: input, not messages, and streamed", sent.has("input")
        && !sent.has("messages") && sent.path("stream").asBoolean() && "resp-model".equals(sent.path("model").asText()));
    failed += check("the chat is not stored by the provider", sent.has("store") && !sent.path("store").asBoolean(true));
    failed += check("encrypted reasoning is asked for, so it can be sent back",
        sent.path("include").toString().contains("reasoning.encrypted_content"));
    failed += check("the output cap is sent as max_output_tokens", sent.path("max_output_tokens").asInt() == 32000
        && !sent.has("max_completion_tokens"));
    failed += check("no reasoning effort is chosen for the model", !sent.has("reasoning"));
    CallUsage call = usage.get();
    failed += check("usage, cache reads and reasoning tokens come from response.completed", call != null
        && call.output() == 3 && call.cacheRead() == 4 && call.uncachedInput() == 6 && call.reasoning() == 2);
    RawCapture.Call raw = capture.calls().isEmpty() ? null : capture.calls().get(0);
    failed += check("the raw log keeps the /responses exchange", raw != null && raw.http() != null
        && raw.http().url().endsWith("/v1/responses") && raw.http().response().contains("response.completed"));
    return failed;
  }

  private static int tools(Connection connection, List<String> bodies) throws Exception {
    int failed = 0;
    ChatModel model = ChatModels.create(connection);
    ToolSpecification edit = ToolSpecification.builder().name("edit_file").description("Edit a file")
        .parameters(JsonObjectSchema.builder().addStringProperty("path").build()).build();
    UserMessage user = UserMessage.from("use a tool");
    bodies.clear();
    ChatResponse first = model.chat(ChatRequest.builder().messages(user).toolSpecifications(edit).build());
    List<ToolExecutionRequest> calls = first.aiMessage().toolExecutionRequests();
    failed += check("a tool call streamed in pieces arrives whole", calls.size() == 1
        && "edit_file".equals(calls.get(0).name()) && "call_1".equals(calls.get(0).id())
        && "{\"path\":\"api.xml\"}".equals(calls.get(0).arguments()));
    JsonNode tool = bodies.isEmpty() ? JSON.createObjectNode() : JSON.readTree(bodies.get(0)).path("tools").path(0);
    failed += check("tools are sent in the Responses format", "function".equals(tool.path("type").asText())
        && "edit_file".equals(tool.path("name").asText()) && tool.has("parameters"));

    AiMessage reply = first.aiMessage();
    ToolExecutionResultMessage result = ToolExecutionResultMessage.from(calls.get(0), "edited");
    bodies.clear();
    ChatResponse second = model.chat(ChatRequest.builder().messages(user, reply, result).toolSpecifications(edit)
        .build());
    failed += check("the model answers after the tool result", "Done.".equals(second.aiMessage().text()));
    JsonNode input = bodies.isEmpty() ? JSON.createArrayNode() : JSON.readTree(bodies.get(0)).path("input");
    boolean reasoning = false;
    boolean output = false;
    for (JsonNode item : input) {
      reasoning |= "reasoning".equals(item.path("type").asText())
          && "ENC-reasoning-1".equals(item.path("encrypted_content").asText());
      output |= "function_call_output".equals(item.path("type").asText())
          && "call_1".equals(item.path("call_id").asText()) && "edited".equals(item.path("output").asText());
    }
    failed += check("the encrypted reasoning is sent back with the tool result", reasoning);
    failed += check("the tool result is sent as function_call_output", output);
    return failed;
  }

  private static int errors(Connection connection) {
    RuntimeException error = attempt(ChatModels.create(connection), "foundry refuses");
    return check("a 400 from the provider keeps its status and message", status(error) == 400
        && Errors.describe(error).contains("To use function tools, use /v1/responses"));
  }

  private static int outputCap(Connection connection, List<String> bodies) throws Exception {
    bodies.clear();
    RuntimeException error = attempt(ChatModels.create(connection), "small cap");
    boolean lowered = bodies.size() == 2 && JSON.readTree(bodies.get(0)).path("max_output_tokens").asInt() == 32000
        && JSON.readTree(bodies.get(1)).path("max_output_tokens").asInt() == 16384;
    return check("a model with a smaller output limit gets max_output_tokens lowered and the call sent again",
        error == null && lowered);
  }

  private static int status(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof HttpException http) {
        return http.statusCode();
      }
    }
    return -1;
  }

  private static RuntimeException attempt(ChatModel model, String prompt) {
    try {
      model.chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build());
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }

  private static void answer(HttpExchange exchange, String body) throws IOException {
    if (body.contains("foundry refuses")) {
      error(exchange, "{\"error\":{\"code\":\"reasoning_mandatory\",\"message\":\"Function tools with reasoning_effort"
          + " are not supported for gpt-6-luna in /v1/chat/completions. To use function tools, use /v1/responses or"
          + " set reasoning_effort to 'none'.\"}}");
      return;
    }
    if (body.contains("small cap") && JSON.readTree(body).path("max_output_tokens").asInt() > 16384) {
      error(exchange, "{\"error\":{\"message\":\"max_output_tokens is too large: 32000. This model supports at most"
          + " 16384 output tokens.\"}}");
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    if (body.contains("function_call_output")) {
      send(out, "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"Done.\"}");
      completed(out, message("Done."));
    } else if (body.contains("use a tool")) {
      String added = "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"edit_file\","
          + "\"arguments\":\"\"}";
      String done = "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"edit_file\","
          + "\"arguments\":\"{\\\"path\\\":\\\"api.xml\\\"}\"}";
      send(out, "response.output_item.added", "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":"
          + added + "}");
      send(out, "response.function_call_arguments.delta", "{\"type\":\"response.function_call_arguments.delta\","
          + "\"item_id\":\"fc_1\",\"output_index\":1,\"delta\":\"{\\\"path\\\":\\\"api\"}");
      send(out, "response.function_call_arguments.delta", "{\"type\":\"response.function_call_arguments.delta\","
          + "\"item_id\":\"fc_1\",\"output_index\":1,\"delta\":\".xml\\\"}\"}");
      send(out, "response.output_item.done", "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":"
          + done + "}");
      completed(out, "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"ENC-reasoning-1\"},"
          + done);
    } else {
      send(out, "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}");
      send(out, "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\" there.\"}");
      completed(out, message("Hello there."));
    }
  }

  private static String message(String text) {
    return "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":"
        + "[{\"type\":\"output_text\",\"text\":\"" + text + "\",\"annotations\":[]}]}";
  }

  private static void completed(OutputStream out, String output) throws IOException {
    send(out, "response.completed", "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
        + "\"object\":\"response\",\"model\":\"resp-model\",\"status\":\"completed\",\"output\":[" + output + "],"
        + "\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":4},\"output_tokens\":3,"
        + "\"output_tokens_details\":{\"reasoning_tokens\":2},\"total_tokens\":13}}}");
  }

  private static void error(HttpExchange exchange, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(400, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void send(OutputStream out, String event, String data) throws IOException {
    out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
