import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

// check-stream: replies streamed as server-sent events from a local server. Text and usage, tool calls split
// across chunks, text and tool calls in separate choices, errors before and inside the stream, a silent model,
// Cancel, and the raw log.
final class StreamChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  private StreamChecks() {}

  static void run() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      try {
        answer(exchange, body);
      } catch (InterruptedException | IOException e) {
        // The client went away: the cancel and silence checks.
      } finally {
        exchange.close();
      }
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    int failed = 0;
    try {
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1", "stream-model", "");
      failed += text(connection, bodies);
      failed += tools(connection);
      failed += errors(connection);
      failed += silence(connection);
      failed += cancel(connection);
    } finally {
      server.stop(0);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int text(Connection connection, List<String> bodies) throws Exception {
    int failed = 0;
    List<String> receiving = new CopyOnWriteArrayList<>();
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
      public void receiving(String what) {
        receiving.add(what);
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
    // Through AutoRouter, as the chat view sends every turn, so its progress wrapper is covered too.
    ChatSession.Turn turn = AutoRouter.send(new ChatSession(),
        List.of(new AutoRouter.Option("stream-model", ChatModels.create(connection, capture))), ChatMode.ASK,
        "say hello", List.of(), null, progress);
    failed += check("a streamed answer is put together from its pieces", turn.text().equals("Hello there."));
    failed += check("the request asks for a stream with usage", bodies.size() == 1
        && JSON.readTree(bodies.get(0)).path("stream").asBoolean()
        && JSON.readTree(bodies.get(0)).path("stream_options").path("include_usage").asBoolean());
    CallUsage call = usage.get();
    failed += check("usage, cache reads and the billed cost come from the final chunk", call != null
        && call.output() == 3 && call.cacheRead() == 4 && call.uncachedInput() == 6
        && Double.valueOf(0.0021).equals(call.reportedCost()));
    failed += check("progress shows the answer arriving, through Auto as well", receiving.contains("Writing the answer…"));
    failed += check("progress says it is waiting from the start of the call", !receiving.isEmpty()
        && receiving.get(0).equals(ChatSession.WAITING_FOR_DATA));
    failed += check("the answer text reaches the view as it streams", pieces.toString().equals("Hello there."));
    RawCapture.Call raw = capture.calls().isEmpty() ? null : capture.calls().get(0);
    failed += check("the raw log keeps the stream as received", raw != null && raw.http() != null
        && raw.http().response().contains("data: ") && raw.http().response().contains("\"Hello\"")
        && raw.http().status() == 200);
    return failed;
  }

  private static int tools(Connection connection) {
    int failed = 0;
    ChatModel model = ChatModels.create(connection);
    ToolSpecification edit = ToolSpecification.builder().name("edit_file")
        .parameters(JsonObjectSchema.builder().addStringProperty("path").build()).build();
    ChatResponse split = model.chat(ChatRequest.builder().messages(UserMessage.from("use a tool"))
        .toolSpecifications(edit).build());
    List<ToolExecutionRequest> calls = split.aiMessage().toolExecutionRequests();
    failed += check("a tool call split across chunks arrives whole", calls.size() == 1
        && calls.get(0).name().equals("edit_file") && calls.get(0).arguments().equals("{\"path\":\"api.xml\"}"));

    ChatResponse choices = model.chat(ChatRequest.builder().messages(UserMessage.from("split choices"))
        .toolSpecifications(edit).build());
    failed += check("text and a tool call sent as separate choices are both kept",
        "Let me look.".equals(choices.aiMessage().text()) && choices.aiMessage().toolExecutionRequests().size() == 1
            && choices.aiMessage().toolExecutionRequests().get(0).arguments().equals("{\"path\":\"b.xml\"}"));
    return failed;
  }

  private static int errors(Connection connection) {
    int failed = 0;
    ChatModel model = ChatModels.create(connection);
    RuntimeException busy = attempt(model, "busy");
    failed += check("a 429 before the stream keeps its status and Retry-After", status(busy) == 429
        && Duration.ofSeconds(7).equals(Errors.classify(busy, Provider.CUSTOM).retryAfter()));
    RuntimeException midway = attempt(model, "fail midway");
    failed += check("an error inside the stream fails the call and can move to the other model",
        status(midway) == 503 && AutoRouter.retryable(midway)
            && Errors.classify(midway, Provider.CUSTOM).details().contains("Upstream overloaded"));
    return failed;
  }

  // The HTTP status of the first HttpException in the chain; LangChain4j wraps ours in its own.
  private static int status(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof HttpException http) {
        return http.statusCode();
      }
    }
    return -1;
  }

  private static int silence(Connection connection) {
    ChatModels.streamTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(1));
    try {
      long started = System.nanoTime();
      RuntimeException quiet = attempt(ChatModels.create(connection), "go quiet");
      double seconds = (System.nanoTime() - started) / 1e9;
      return check("a model silent for the idle limit is stopped, not waited on", quiet != null && seconds < 2.5
          && Errors.describe(quiet).contains("sent nothing for 1 s") && AutoRouter.retryable(quiet)
          && !Thread.currentThread().isInterrupted());
    } finally {
      ChatModels.streamTimeouts(Duration.ofSeconds(120), Duration.ofSeconds(120));
    }
  }

  private static int cancel(Connection connection) throws Exception {
    AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
    ChatModel model = ChatModels.create(connection, null, inFlight);
    CompletableFuture<RuntimeException> outcome = CompletableFuture.supplyAsync(() -> attempt(model, "slow answer"));
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (inFlight.get() == null && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    Thread.sleep(300);
    long started = System.nanoTime();
    CompletableFuture<?> call = inFlight.get();
    if (call != null) {
      call.cancel(true);
    }
    RuntimeException error = outcome.get();
    double seconds = (System.nanoTime() - started) / 1e9;
    return check("Cancel stops a stream that is still arriving", call != null
        && error instanceof CancellationException && seconds < 1.5);
  }

  private static RuntimeException attempt(ChatModel model, String prompt) {
    try {
      model.chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build());
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }

  private static void answer(HttpExchange exchange, String body) throws IOException, InterruptedException {
    if (body.contains("busy")) {
      byte[] error = "{\"error\":{\"message\":\"Too many requests\"}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.getResponseHeaders().add("Retry-After", "7");
      exchange.sendResponseHeaders(429, error.length);
      exchange.getResponseBody().write(error);
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    if (body.contains("say hello")) {
      send(out, chunk("{\"role\":\"assistant\",\"content\":\"\"}", null));
      send(out, chunk("{\"content\":\"Hello\"}", null));
      send(out, chunk("{\"content\":\" there.\"}", null));
      send(out, chunk("{}", "stop"));
      send(out, "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stream-model\","
          + "\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13,"
          + "\"cost\":0.0021,\"prompt_tokens_details\":{\"cached_tokens\":4}}}");
      send(out, "[DONE]");
    } else if (body.contains("use a tool")) {
      send(out, chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
          + "\"function\":{\"name\":\"edit_file\",\"arguments\":\"\"}}]}", null));
      send(out, chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"api\"}}]}", null));
      send(out, chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\".xml\\\"}\"}}]}", null));
      send(out, chunk("{}", "tool_calls"));
      send(out, "[DONE]");
    } else if (body.contains("split choices")) {
      send(out, "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stream-model\","
          + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Let me look.\"}},"
          + "{\"index\":1,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_2\","
          + "\"type\":\"function\",\"function\":{\"name\":\"edit_file\",\"arguments\":\"{\\\"path\\\":\\\"b.xml\\\"}\"}}]}}]}");
      send(out, "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stream-model\","
          + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"},"
          + "{\"index\":1,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
      send(out, "[DONE]");
    } else if (body.contains("fail midway")) {
      send(out, chunk("{\"role\":\"assistant\",\"content\":\"Starting\"}", null));
      send(out, "{\"error\":{\"message\":\"Upstream overloaded\",\"code\":503}}");
    } else if (body.contains("go quiet") || body.contains("slow answer")) {
      send(out, chunk("{\"role\":\"assistant\",\"content\":\"Hi\"}", null));
      Thread.sleep(4000);
      send(out, chunk("{}", "stop"));
      send(out, "[DONE]");
    } else {
      send(out, chunk("{\"role\":\"assistant\",\"content\":\"ok\"}", "stop"));
      send(out, "[DONE]");
    }
  }

  private static String chunk(String delta, String finish) {
    return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stream-model\",\"choices\":"
        + "[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"")
        + "}]}";
  }

  private static void send(OutputStream out, String data) throws IOException {
    out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
