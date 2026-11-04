import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.ProviderHttpException;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

// check-loop: the tool loop's guards. A third identical call is refused, a call the mode does not allow is not a
// failure, busy or failing providers are tried again with growing waits, a turn that runs out of steps can be
// continued, and a chat too large for the model is compacted and the turn sent again.
final class LoopChecks {
  private LoopChecks() {}

  static void run() throws Exception {
    Path root = Files.createTempDirectory("studio-ai-loop");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.writeString(root.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");
    Harness.DiskProject project = new Harness.DiskProject(root);
    ChatSession.retryWaits(Duration.ofMillis(40), Duration.ofMillis(100));
    int failed = 0;
    failed += repeats(project);
    failed += modeRefusal(project);
    failed += retries(project);
    failed += stepLimit(project);
    failed += overflow(project);
    System.exit(failed == 0 ? 0 : 1);
  }

  private static final class Scripted implements ChatModel {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final Function<ChatRequest, ChatResponse> reply;

    Scripted(Function<ChatRequest, ChatResponse> reply) {
      this.reply = reply;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
      requests.add(request);
      return reply.apply(request);
    }
  }

  private static int repeats(Harness.DiskProject project) {
    int failed = 0;
    String[] orders = {"{\"path\":\"src/main/mule/api.xml\",\"why\":\"look\"}",
        "{\"why\":\"look\",\"path\":\"src/main/mule/api.xml\"}", "{\"path\":\"src/main/mule/api.xml\",\"why\":\"look\"}"};
    Scripted model = new Scripted(r -> {
      int results = (int) r.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count();
      return results < orders.length ? calls(tool("read_file", orders[results])) : answer("Read it.");
    });
    ChatSession.Turn turn = new ChatSession().send(model, ChatMode.AGENT, "read it three times", List.of(), project,
        Progress.NONE);
    List<ToolExecutionResultMessage> results = results(model.requests.get(model.requests.size() - 1));
    failed += check("the same call in another key order counts as the same call", results.size() == 3
        && results.get(1).text().contains("orders"));
    failed += check("the third identical call is refused and the model told why", results.get(2).text()
        .startsWith("ERROR: not run. This is call number 3 to read_file") && !results.get(2).text().contains("orders"));
    failed += check("the activity says a repeated call was refused", turn.activity().contains("refused 1 repeated call")
        && turn.text().equals("Read it."));
    return failed;
  }

  private static int modeRefusal(Harness.DiskProject project) {
    int failed = 0;
    Scripted model = new Scripted(r -> results(r).isEmpty()
        ? calls(tool("edit_file", "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"orders\",\"new_text\":\"x\"}"))
        : answer("Switch to Agent mode to make that change."));
    ChatSession.Turn turn = new ChatSession().send(model, ChatMode.ASK, "rename the flow", List.of(), project,
        Progress.NONE);
    List<ToolExecutionResultMessage> results = results(model.requests.get(model.requests.size() - 1));
    failed += check("the model is told the mode refused the call, not that it failed", results.size() == 1
        && results.get(0).text().startsWith(ChatMode.REFUSED) && results.get(0).text().contains("Agent mode"));
    failed += check("a refused edit is not reported as an edit that did not apply",
        turn.activity().contains("skipped 1 call this mode does not allow") && !turn.activity().contains("did not apply")
            && turn.changes().isEmpty());
    return failed;
  }

  private static int retries(Harness.DiskProject project) {
    int failed = 0;
    int[] attempts = {0};
    Scripted busy = new Scripted(r -> ++attempts[0] <= 2 ? fail(429, Map.of()) : answer("Fine."));
    List<String> steps = new CopyOnWriteArrayList<>();
    Progress recorded = new Progress() {
      @Override
      public void step(String message) {
        steps.add(message);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
    ChatSession.Turn turn = new ChatSession().send(busy, ChatMode.ASK, "hello", List.of(), project, recorded);
    failed += check("a rate-limited call is tried again until it answers", busy.requests.size() == 3
        && turn.text().equals("Fine."));
    failed += check("the user is told it is trying again", steps.stream()
        .anyMatch(s -> s.contains("trying again in") && s.contains("(2 of 4)")));

    attempts[0] = 0;
    Scripted asksToWait = new Scripted(r -> ++attempts[0] == 1 ? fail(503, Map.of("Retry-After", List.of("1")))
        : answer("Fine."));
    long started = System.nanoTime();
    new ChatSession().send(asksToWait, ChatMode.ASK, "hello", List.of(), project, Progress.NONE);
    failed += check("Retry-After is waited out when it is longer than the backoff",
        asksToWait.requests.size() == 2 && System.nanoTime() - started >= 1_000_000_000L);

    failed += check("a Retry-After over a minute is reported, not waited out",
        fails(new Scripted(r -> fail(429, Map.of("Retry-After", List.of("120")))), project, 1));
    failed += check("a provider that keeps failing is tried four times, then reported",
        fails(new Scripted(r -> fail(503, Map.of())), project, 4));
    failed += check("a bad request is not tried again", fails(new Scripted(r -> fail(400, Map.of())), project, 1));
    failed += check("a model that stopped answering is not tried again", fails(new Scripted(r -> {
      throw new RuntimeException(new HttpTimeoutException("no data for 120 s"));
    }), project, 1));

    ChatSession.retryWaits(Duration.ofSeconds(10), Duration.ofSeconds(10));
    long cancelAt = System.nanoTime() + 200_000_000L;
    Progress cancelling = new Progress() {
      @Override
      public void step(String message) {}

      @Override
      public boolean isCancelled() {
        return System.nanoTime() > cancelAt;
      }
    };
    started = System.nanoTime();
    boolean cancelled = false;
    try {
      new ChatSession().send(new Scripted(r -> fail(503, Map.of())), ChatMode.ASK, "hello", List.of(), project,
          cancelling);
    } catch (CancellationException e) {
      cancelled = true;
    }
    failed += check("cancel stops the wait between tries", cancelled
        && System.nanoTime() - started < 2_000_000_000L);
    ChatSession.retryWaits(Duration.ofMillis(40), Duration.ofMillis(100));
    return failed;
  }

  private static boolean fails(Scripted model, Harness.DiskProject project, int expectedRequests) {
    try {
      new ChatSession().send(model, ChatMode.ASK, "hello", List.of(), project, Progress.NONE);
      return false;
    } catch (RuntimeException e) {
      return model.requests.size() == expectedRequests;
    }
  }

  private static int stepLimit(Harness.DiskProject project) {
    int failed = 0;
    Scripted model = new Scripted(r -> {
      String latest = text(r.messages().get(r.messages().size() - 1));
      if (latest.contains(ChatMode.CONTINUE_PROMPT)) {
        return answer("Finished.");
      }
      int results = (int) r.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count();
      return calls(tool("read_file", "{\"path\":\"src/main/mule/api.xml\",\"step\":" + results + "}"));
    });
    ChatSession session = new ChatSession();
    ChatSession.Turn paused = session.send(model, ChatMode.AGENT, "keep reading", List.of(), project, Progress.NONE);
    failed += check("a turn that runs out of steps pauses and offers Continue", paused.stoppedAtLimit()
        && paused.text().startsWith("Paused after 12 steps") && paused.activity().contains("paused at the step limit"));
    int before = model.requests.size();
    ChatSession.Turn resumed = session.send(model, ChatMode.AGENT, ChatMode.CONTINUE_PROMPT, List.of(), project,
        Progress.NONE);
    List<ChatMessage> sent = model.requests.get(before).messages();
    failed += check("Continue resumes with the earlier steps still in the chat", !resumed.stoppedAtLimit()
        && resumed.text().equals("Finished.")
        && sent.stream().filter(ToolExecutionResultMessage.class::isInstance).count() == 12);
    return failed;
  }

  private static int overflow(Harness.DiskProject project) {
    int failed = 0;
    Scripted model = new Scripted(r -> {
      if (r.toolSpecifications() == null || r.toolSpecifications().isEmpty()) {
        return answer("SUMMARY-OF-EARLIER-TURNS");
      }
      boolean summarised = r.messages().stream().anyMatch(m -> text(m).contains("SUMMARY-OF-EARLIER-TURNS"));
      String latest = text(r.messages().get(r.messages().size() - 1));
      if (latest.contains("too big") && !summarised || latest.contains("always overflows")) {
        return fail(400, Map.of(), "{\"error\":{\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}");
      }
      return answer(summarised ? "Answered on the summary." : "ok");
    });
    ChatSession session = new ChatSession();
    session.send(model, ChatMode.ASK, "first question", List.of(), project, Progress.NONE);
    int before = model.requests.size();
    ChatSession.Turn turn = session.send(model, ChatMode.ASK, "too big", List.of(), project, Progress.NONE);
    failed += check("a chat too large for the model is compacted and the turn sent again",
        model.requests.size() - before == 3 && turn.text().equals("Answered on the summary."));

    before = model.requests.size();
    boolean reported = false;
    try {
      session.send(model, ChatMode.ASK, "always overflows", List.of(), project, Progress.NONE);
    } catch (RuntimeException e) {
      reported = true;
    }
    failed += check("compaction is tried once per turn, then the error is reported", reported
        && model.requests.size() - before == 3);

    before = model.requests.size();
    reported = false;
    try {
      new ChatSession().send(model, ChatMode.ASK, "always overflows", List.of(), project, Progress.NONE);
    } catch (RuntimeException e) {
      reported = true;
    }
    failed += check("a first turn that is too large is reported; there is nothing to compact", reported
        && model.requests.size() - before == 1);
    return failed;
  }

  private static ToolExecutionRequest tool(String name, String arguments) {
    return ToolExecutionRequest.builder().id("call_" + System.nanoTime()).name(name).arguments(arguments).build();
  }

  private static ChatResponse calls(ToolExecutionRequest call) {
    return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).tokenUsage(new TokenUsage(100, 10)).build();
  }

  private static ChatResponse answer(String text) {
    return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(100, 10)).build();
  }

  private static ChatResponse fail(int status, Map<String, List<String>> headers) {
    return fail(status, headers, "{\"error\":{\"message\":\"failed\"}}");
  }

  private static ChatResponse fail(int status, Map<String, List<String>> headers, String body) {
    throw new ProviderHttpException("POST", "http://127.0.0.1/v1/chat/completions", status, headers, body);
  }

  private static List<ToolExecutionResultMessage> results(ChatRequest request) {
    return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).toList();
  }

  private static String text(ChatMessage message) {
    if (message instanceof UserMessage user) {
      return user.hasSingleText() ? user.singleText() : user.contents().toString();
    }
    if (message instanceof AiMessage ai) {
      return ai.text() == null ? "" : ai.text();
    }
    if (message instanceof ToolExecutionResultMessage result) {
      return result.text();
    }
    return "";
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
