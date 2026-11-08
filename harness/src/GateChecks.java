import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Memory;
import com.mulesoftforge.studio.ai.llm.Progress;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.ChatRequest;

// check-gate: a turn that proposed changes does not finish while the project checks fail on them. The problems go
// back to the model up to twice; then the turn ends with them, so the view can say "Checks still fail". Off in
// Preferences, or in a mode that cannot edit, nothing is sent back.
final class GateChecks {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NUDGE = "Before you finish: the project checks still fail";

  private GateChecks() {}

  static void run() throws Exception {
    Path home = Files.createTempDirectory("studio-ai-gate-home");
    Extensions.overrideHome(home);
    Memory.overrideUserFile(home.resolve("memory.md"));
    int failed = 0;
    failed += fixed();
    failed += neverFixed();
    failed += off();
    failed += clean();
    System.exit(failed == 0 ? 0 : 1);
  }

  // The model proposes a flow-ref to a flow that does not exist, finishes, is sent the problem, fixes it.
  private static int fixed() throws Exception {
    int failed = 0;
    Harness.DiskProject project = project();
    ChatSession session = new ChatSession();
    List<String> steps = new CopyOnWriteArrayList<>();
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(write("missing-flow")), ExtendChecks.answer("Added the extra flow."),
        ExtendChecks.calls(write("orders")), ExtendChecks.answer("Fixed the flow-ref.")));
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "add an extra flow", List.of(), project,
        recorder(steps));
    List<String> nudges = nudges(model.requests.get(model.requests.size() - 1));
    failed += check("a failing proposal is sent back with the problem before the turn ends", nudges.size() == 1
        && nudges.get(0).contains("missing-flow") && nudges.get(0).contains("src/main/mule/extra.xml"));
    failed += check("after the fix the turn ends with the model's answer and no problems", turn.text()
        .equals("Fixed the flow-ref.") && turn.problems().isEmpty() && model.requests.size() == 4);
    failed += check("the user sees why the turn went on, and the activity counts it", steps.stream()
        .anyMatch(s -> s.startsWith("The project checks still fail on the proposals (1 problem)"))
        && turn.activity().contains("sent failing checks back to the AI once"));
    return failed;
  }

  private static int neverFixed() throws Exception {
    int failed = 0;
    ChatSession session = new ChatSession();
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(write("missing-flow")), ExtendChecks.answer("Done."), ExtendChecks.answer("Still done."),
        ExtendChecks.answer("Really done."), ExtendChecks.answer("Should not be asked.")));
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "add an extra flow", List.of(), project(),
        Progress.NONE);
    failed += check("problems are sent back at most twice", model.requests.size() == 4
        && nudges(model.requests.get(3)).size() == 2);
    failed += check("then the turn ends with the problems for the view to show", turn.text().equals("Really done.")
        && turn.problems().size() == 1 && turn.problems().get(0).message().contains("missing-flow"));
    return failed;
  }

  private static int off() throws Exception {
    int failed = 0;
    ChatSession session = new ChatSession();
    session.fixChecksBeforeFinishing(false);
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(write("missing-flow")), ExtendChecks.answer("Done.")));
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "add an extra flow", List.of(), project(),
        Progress.NONE);
    failed += check("turned off in Preferences, nothing is sent back and the problems stay on the turn",
        model.requests.size() == 2 && turn.problems().size() == 1);
    return failed;
  }

  private static int clean() throws Exception {
    int failed = 0;
    ChatSession session = new ChatSession();
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(write("orders")), ExtendChecks.answer("Added.")));
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "add an extra flow", List.of(), project(),
        Progress.NONE);
    failed += check("a proposal the checks pass ends the turn at once", model.requests.size() == 2
        && turn.problems().isEmpty() && !turn.activity().contains("sent failing checks back"));

    session = new ChatSession();
    model = new ExtendChecks.Scripted(List.of(ExtendChecks.answer("The flow calls orders.")));
    session.send(model, ChatMode.ASK, "what does api.xml do?", List.of(), project(), Progress.NONE);
    failed += check("a turn without proposals is never held back", model.requests.size() == 1);
    return failed;
  }

  private static Harness.DiskProject project() throws Exception {
    Path dir = Files.createTempDirectory("studio-ai-gate");
    Files.createDirectories(dir.resolve("src/main/mule"));
    Files.writeString(dir.resolve("src/main/mule/api.xml"), mule("<flow name=\"orders\"/>"));
    return new Harness.DiskProject(dir);
  }

  private static ToolExecutionRequest write(String target) throws Exception {
    return ExtendChecks.tool("write_file", JSON.writeValueAsString(Map.of("path", "src/main/mule/extra.xml",
        "content", mule("<flow name=\"extra\">\n    <flow-ref name=\"" + target + "\"/>\n  </flow>"))));
  }

  private static String mule(String body) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <mule xmlns="http://www.mulesoft.org/schema/mule/core"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd">
          %s
        </mule>
        """.formatted(body);
  }

  private static List<String> nudges(ChatRequest request) {
    return ExtendChecks.users(request).stream().filter(u -> u.startsWith(NUDGE)).toList();
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

  private static int check(String name, boolean ok) {
    return ExtendChecks.check(name, ok);
  }
}
