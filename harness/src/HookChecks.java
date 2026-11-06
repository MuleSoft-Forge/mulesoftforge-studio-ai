import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.hooks.Hooks;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Progress;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

// check-hooks: the user's hooks.json, run as real shell commands. Matchers, exit codes, the JSON answers Codex,
// Copilot and Cline hooks give, timeouts, and the hooks inside a turn: a blocked tool call, a comment on a result,
// context and a block on the prompt, and Stop with the answer.
final class HookChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  private HookChecks() {}

  static void run() throws Exception {
    Path dir = Files.createTempDirectory("studio-ai-hooks");
    Files.createDirectories(dir.resolve("src/main/mule"));
    Files.writeString(dir.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");
    Harness.DiskProject project = new Harness.DiskProject(dir);
    int failed = 0;
    failed += parsing();
    failed += outcomes(dir);
    failed += inTurn(project, dir);
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int parsing() {
    int failed = 0;
    failed += check("no file means no hooks", Hooks.load(Path.of("/nonexistent/hooks.json")).isEmpty()
        && Hooks.load(Path.of("/nonexistent/hooks.json")).problem() == null);
    Hooks broken = Hooks.parse("{ not json");
    failed += check("a file that is not JSON gives no hooks and says why", broken.isEmpty()
        && broken.problem().contains("not valid JSON"));
    failed += check("a file without a hooks object says why", Hooks.parse("{\"PreToolUse\":[]}").problem()
        .contains("no \"hooks\" object"));
    Hooks badMatcher = Hooks.parse(file(Map.of("PreToolUse", List.of(group("([", "true")))));
    failed += check("a bad matcher is skipped and reported", badMatcher.isEmpty()
        && badMatcher.problem().contains("not a valid pattern"));
    Hooks template = Hooks.parse(Hooks.template());
    failed += check("the template is valid and runs nothing", template.isEmpty() && template.problem() == null);
    Hooks typed = Hooks.parse("{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"prompt\",\"command\":\"x\"},"
        + "{\"type\":\"command\",\"command\":\"true\",\"timeout\":9999}]}]}}");
    failed += check("only command hooks are kept", typed.count() == 1 && typed.has(Hooks.Event.STOP));
    return failed;
  }

  private static int outcomes(Path dir) throws Exception {
    int failed = 0;
    Hooks exact = Hooks.parse(file(Map.of("PreToolUse", List.of(group("read_file|edit_file", "exit 2")))));
    failed += check("names separated by | match exactly", blocked(exact, "read_file", dir)
        && blocked(exact, "edit_file", dir) && !blocked(exact, "search_files", dir) && !blocked(exact, "read", dir));
    Hooks regex = Hooks.parse(file(Map.of("PreToolUse", List.of(group("call_.*", "exit 2")))));
    failed += check("other matchers are regular expressions", blocked(regex, "call_endpoint", dir)
        && !blocked(regex, "read_file", dir));
    Hooks all = Hooks.parse(file(Map.of("PreToolUse", List.of(group("*", "exit 2")))));
    failed += check("* matches every tool", blocked(all, "anything", dir));

    Hooks.Outcome exit2 = pre(Hooks.parse(file(Map.of("PreToolUse",
        List.of(group("", "echo 'not on Fridays' >&2; exit 2"))))), "read_file", dir);
    failed += check("exit 2 blocks with stderr as the reason", exit2.blocked() && exit2.reason().equals("not on Fridays"));
    Hooks.Outcome deny = pre(Hooks.parse(file(Map.of("PreToolUse", List.of(group("", "echo '{\"hookSpecificOutput\":"
        + "{\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"read-only today\","
        + "\"additionalContext\":\"ask the user\"}}'"))))), "edit_file", dir);
    failed += check("a deny decision blocks with its reason and keeps the context", deny.blocked()
        && deny.reason().equals("read-only today") && deny.context().equals(List.of("ask the user")));
    Hooks.Outcome ask = pre(Hooks.parse(file(Map.of("PreToolUse", List.of(group("",
        "echo '{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}'"))))), "edit_file", dir);
    failed += check("an ask decision is refused, since there is no approval prompt", ask.blocked()
        && ask.reason().contains("approval"));
    Hooks.Outcome cline = pre(Hooks.parse(file(Map.of("PreToolUse", List.of(group("",
        "echo '{\"cancel\":true,\"errorMessage\":\"Cline says no\"}'"))))), "edit_file", dir);
    failed += check("Cline's cancel answer blocks too", cline.blocked() && cline.reason().equals("Cline says no"));
    Hooks.Outcome failing = pre(Hooks.parse(file(Map.of("PreToolUse", List.of(group("",
        "echo broken >&2; exit 1"))))), "read_file", dir);
    failed += check("another exit code is reported and blocks nothing", !failing.blocked()
        && failing.problems().size() == 1 && failing.problems().get(0).contains("exited with 1: broken"));
    Hooks.Outcome garbled = pre(Hooks.parse(file(Map.of("PreToolUse", List.of(group("", "echo '{oops'"))))),
        "read_file", dir);
    failed += check("unreadable JSON is reported and blocks nothing", !garbled.blocked()
        && garbled.problems().get(0).contains("could not be read"));

    Hooks slow = Hooks.parse("{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":"
        + "\"sleep 5; exit 2\",\"timeout\":1}]}]}}");
    long started = System.nanoTime();
    Hooks.Outcome timedOut = pre(slow, "read_file", dir);
    double seconds = (System.nanoTime() - started) / 1e9;
    failed += check("a hook over its timeout is stopped, reported and blocks nothing", !timedOut.blocked()
        && timedOut.problems().get(0).contains("timed out after 1 s") && seconds < 3);
    Hooks.Outcome stopped = slow.run(Hooks.Event.PRE_TOOL_USE, "read_file",
        Hooks.input(Hooks.Event.PRE_TOOL_USE, "s", dir), dir, () -> true);
    failed += check("Cancel skips the hooks", !stopped.blocked());

    Path seen = dir.resolve("seen.json");
    Hooks recording = Hooks.parse(file(Map.of("PreToolUse", List.of(group("", "cat > '" + seen + "'")))));
    ObjectNode input = Hooks.input(Hooks.Event.PRE_TOOL_USE, "session-1", dir);
    input.put("tool_name", "read_file");
    input.set("tool_input", Hooks.toolInput("{\"path\":\"src/main/mule/api.xml\"}"));
    recording.run(Hooks.Event.PRE_TOOL_USE, "read_file", input, dir, () -> false);
    JsonNode got = JSON.readTree(Files.readString(seen));
    failed += check("the hook gets the event as JSON on stdin", got.path("hook_event_name").asText().equals("PreToolUse")
        && got.path("tool_name").asText().equals("read_file") && got.path("session_id").asText().equals("session-1")
        && got.path("tool_input").path("path").asText().equals("src/main/mule/api.xml")
        && got.path("cwd").asText().equals(dir.toString()));
    Path where = dir.resolve("where.txt");
    Hooks pwd = Hooks.parse(file(Map.of("Stop", List.of(group("", "pwd > '" + where + "'; echo \"$MSF_PROJECT_DIR\" >> '"
        + where + "'; exit 2")))));
    Hooks.Outcome stop = pwd.run(Hooks.Event.STOP, null, Hooks.input(Hooks.Event.STOP, "s", dir), dir, () -> false);
    List<String> lines = Files.readAllLines(where);
    failed += check("hooks run in the project folder and get MSF_PROJECT_DIR", lines.size() == 2
        && Path.of(lines.get(0)).toRealPath().equals(dir.toRealPath()) && lines.get(1).equals(dir.toString()));
    failed += check("exit 2 from a Stop hook blocks nothing", !stop.blocked());
    return failed;
  }

  private static int inTurn(Harness.DiskProject project, Path dir) throws Exception {
    int failed = 0;
    String read = "{\"path\":\"src/main/mule/api.xml\"}";

    ChatSession session = new ChatSession();
    session.hooks(Hooks.parse(file(Map.of("PreToolUse", List.of(group("read_file",
        "echo 'that file is off limits' >&2; exit 2"))))));
    Scripted model = new Scripted(r -> results(r).isEmpty() ? calls(tool("read_file", read)) : answer("Could not read."));
    List<String> steps = new CopyOnWriteArrayList<>();
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "read the api", List.of(), project, recorder(steps));
    List<ToolExecutionResultMessage> results = results(model.requests.get(model.requests.size() - 1));
    failed += check("a blocked call is not run and the model is told the hook's reason", results.size() == 1
        && results.get(0).text().startsWith("ERROR: not run. The user's PreToolUse hook blocked this call:")
        && results.get(0).text().contains("that file is off limits") && !results.get(0).text().contains("orders"));
    failed += check("the user sees the block and the activity counts it", steps.stream()
        .anyMatch(s -> s.equals("Your PreToolUse hook blocked read_file: that file is off limits"))
        && turn.activity().contains("your hooks blocked 1 call"));

    session = new ChatSession();
    session.hooks(Hooks.parse(file(Map.of("PostToolUse", List.of(group("read_file",
        "echo '{\"hookSpecificOutput\":{\"additionalContext\":\"This flow is owned by the payments team\"}}'"))))));
    model = new Scripted(r -> results(r).isEmpty() ? calls(tool("read_file", read)) : answer("Read it."));
    session.send(model, ChatMode.AGENT, "read the api", List.of(), project, Progress.NONE);
    results = results(model.requests.get(model.requests.size() - 1));
    failed += check("a PostToolUse comment follows the tool's result", results.size() == 1
        && results.get(0).text().contains("orders")
        && results.get(0).text().contains("[From the user's PostToolUse hook]\nThis flow is owned by the payments team"));

    session = new ChatSession();
    session.hooks(Hooks.parse(file(Map.of("UserPromptSubmit", List.of(group("", "echo 'Team rule: use Mule 4.9'"))))));
    model = new Scripted(r -> answer("Noted."));
    session.send(model, ChatMode.ASK, "which runtime?", List.of(), project, Progress.NONE);
    String sent = lastUser(model.requests.get(0));
    failed += check("plain text from a prompt hook is added to the message", sent.contains("which runtime?")
        && sent.contains("[From the user's UserPromptSubmit hook]\nTeam rule: use Mule 4.9"));

    session = new ChatSession();
    session.hooks(Hooks.parse(file(Map.of("UserPromptSubmit", List.of(group("",
        "echo '{\"decision\":\"block\",\"reason\":\"no secrets in prompts\"}'"))))));
    model = new Scripted(r -> answer("Should not run."));
    String blockedBy = null;
    try {
      session.send(model, ChatMode.ASK, "my password is hunter2", List.of(), project, Progress.NONE);
    } catch (Hooks.Blocked e) {
      blockedBy = e.getMessage();
    }
    failed += check("a blocking prompt hook stops the message before the model is called", blockedBy != null
        && blockedBy.contains("no secrets in prompts") && model.requests.isEmpty());

    Path last = dir.resolve("stop.json");
    session = new ChatSession();
    session.hooks(Hooks.parse(file(Map.of("Stop", List.of(group("", "cat > '" + last + "'"))))));
    session.send(new Scripted(r -> answer("All done.")), ChatMode.ASK, "hi", List.of(), project, Progress.NONE);
    JsonNode stop = JSON.readTree(Files.readString(last));
    failed += check("a Stop hook gets the answer when the turn ends", stop.path("hook_event_name").asText().equals("Stop")
        && stop.path("last_assistant_message").asText().equals("All done.")
        && stop.path("session_id").asText().equals(session.sessionId()));

    session = new ChatSession();
    model = new Scripted(r -> results(r).isEmpty() ? calls(tool("read_file", read)) : answer("Read it."));
    session.send(model, ChatMode.AGENT, "read the api", List.of(), project, Progress.NONE);
    failed += check("without hooks the call runs as before", results(model.requests.get(1)).get(0).text()
        .contains("orders"));
    return failed;
  }

  private static boolean blocked(Hooks hooks, String tool, Path dir) {
    return pre(hooks, tool, dir).blocked();
  }

  private static Hooks.Outcome pre(Hooks hooks, String tool, Path dir) {
    ObjectNode input = Hooks.input(Hooks.Event.PRE_TOOL_USE, "s", dir);
    input.put("tool_name", tool);
    return hooks.run(Hooks.Event.PRE_TOOL_USE, tool, input, dir, () -> false);
  }

  private static Map<String, Object> group(String matcher, String command) {
    Map<String, Object> hook = new LinkedHashMap<>();
    hook.put("type", "command");
    hook.put("command", command);
    hook.put("timeout", 10);
    Map<String, Object> group = new LinkedHashMap<>();
    group.put("matcher", matcher);
    group.put("hooks", List.of(hook));
    return group;
  }

  private static String file(Map<String, List<Map<String, Object>>> events) {
    try {
      return JSON.writeValueAsString(Map.of("hooks", events));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Progress recorder(List<String> steps) {
    return new Progress() {
      @Override
      public void step(String message) {
        steps.add(message);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
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

  private static ToolExecutionRequest tool(String name, String arguments) {
    return ToolExecutionRequest.builder().id("call_" + System.nanoTime()).name(name).arguments(arguments).build();
  }

  private static ChatResponse calls(ToolExecutionRequest call) {
    return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).tokenUsage(new TokenUsage(100, 10)).build();
  }

  private static ChatResponse answer(String text) {
    return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(100, 10)).build();
  }

  private static List<ToolExecutionResultMessage> results(ChatRequest request) {
    return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).toList();
  }

  private static String lastUser(ChatRequest request) {
    List<String> texts = new ArrayList<>();
    request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .forEach(u -> texts.add(u.hasSingleText() ? u.singleText() : u.contents().toString()));
    return texts.isEmpty() ? "" : texts.get(texts.size() - 1);
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
