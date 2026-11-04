import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.ContextWindow;
import com.mulesoftforge.studio.ai.llm.Progress;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

// check-context: what earlier turns send again. Tool calls and results stay in history and come back unchanged,
// an unchanged attachment is not sent twice, old tool output is cut within a budget, the mode is named and a
// switch noted, requests without tools get the text-only history, and long chats are compacted.
final class ContextChecks {
  private static final String OPEN = "<mule>\n  <flow name=\"orders\"/>\n</mule>\n";

  private ContextChecks() {}

  static void run() throws Exception {
    Path root = Files.createTempDirectory("studio-ai-context");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.writeString(root.resolve("src/main/mule/api.xml"), OPEN);
    Files.writeString(root.resolve("src/main/mule/big.xml"), "<mule>\n" + "<!-- padding -->\n".repeat(3_750)
        + "</mule>\n");
    Harness.DiskProject project = new Harness.DiskProject(root);
    int failed = 0;
    failed += toolHistory(project);
    failed += budget(project);
    failed += compaction(project);
    System.exit(failed == 0 ? 0 : 1);
  }

  // A model that reads the file it is told to, then answers; every request is kept.
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

    ChatRequest last() {
      return requests.get(requests.size() - 1);
    }
  }

  private static ChatResponse readThenAnswer(ChatRequest request, String path, int inputTokens) {
    List<ChatMessage> messages = request.messages();
    ChatMessage last = messages.get(messages.size() - 1);
    AiMessage reply = last instanceof ToolExecutionResultMessage ? AiMessage.from("Read it.")
        : AiMessage.from(List.of(ToolExecutionRequest.builder().id("call_" + messages.size()).name("read_file")
            .arguments("{\"path\":\"" + path + "\"}").build()));
    return ChatResponse.builder().aiMessage(reply).tokenUsage(new TokenUsage(inputTokens, 50)).build();
  }

  private static int toolHistory(Harness.DiskProject project) {
    int failed = 0;
    Scripted model = new Scripted(r -> readThenAnswer(r, "src/main/mule/api.xml", 2_000));
    ChatSession session = new ChatSession();
    Attachment open = Attachment.openFile("p", "src/main/mule/api.xml", OPEN);
    session.send(model, ChatMode.AGENT, "what does it do", List.of(open), project, Progress.NONE);
    List<ChatMessage> firstFinal = model.last().messages();
    int before = model.requests.size();
    session.send(model, ChatMode.AGENT, "and the next part", List.of(open), project, Progress.NONE);
    List<ChatMessage> second = model.requests.get(before).messages();

    failed += check("the next turn gets the earlier tool call and its result", second.stream()
        .anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
        && second.stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
    List<ChatMessage> expected = new ArrayList<>(firstFinal);
    expected.add(AiMessage.from("Read it."));
    failed += check("the earlier turn comes back exactly as it was sent, so it is read from the cache",
        second.size() > expected.size() && second.subList(0, expected.size()).equals(expected));
    String latest = text(second.get(second.size() - 1));
    failed += check("an unchanged open file is not sent again", latest.contains("ATTACHED AGAIN, UNCHANGED: open file")
        && !latest.contains("<flow name=\"orders\"/>") && count(second, "<flow name=\"orders\"/>") == 1);
    failed += check("the message names its mode", latest.contains("Current mode: Agent."));

    Attachment edited = Attachment.openFile("p", "src/main/mule/api.xml", OPEN.replace("orders", "invoices"));
    before = model.requests.size();
    session.send(model, ChatMode.AGENT, "now?", List.of(edited), project, Progress.NONE);
    failed += check("a changed open file is sent in full", text(last(model.requests.get(before)))
        .contains("<flow name=\"invoices\"/>"));

    before = model.requests.size();
    session.send(model, ChatMode.ASK, "explain it", List.of(), project, Progress.NONE);
    String asked = text(last(model.requests.get(before)));
    failed += check("switching mode is noted once", asked.contains("Current mode: Ask.")
        && asked.contains("Mode change: the user switched from Agent mode to Ask mode"));
    before = model.requests.size();
    session.send(model, ChatMode.ASK, "and again", List.of(), project, Progress.NONE);
    failed += check("staying in the mode adds no note", !text(last(model.requests.get(before))).contains("Mode change"));

    before = model.requests.size();
    session.send(model, ChatMode.ASK, "without the project", List.of(open), null, Progress.NONE);
    List<ChatMessage> plain = model.requests.get(before).messages();
    failed += check("a request without tools gets the text-only history", plain.stream()
        .noneMatch(m -> m instanceof ToolExecutionResultMessage || m instanceof AiMessage ai
            && ai.hasToolExecutionRequests())
        && plain.stream().anyMatch(m -> m instanceof AiMessage ai && ai.text().contains("[What you did this turn:")));
    failed += check("and gets the open file in full, since that history has no attachments",
        text(plain.get(plain.size() - 1)).contains("<flow name=\"orders\"/>"));

    List<ChatRecord.Message> saved = session.savedHistory();
    failed += check("the saved chat keeps labels and notes, not file content or tool output", saved.size() == 12
        && saved.get(0).text().startsWith("[Attached at the time: open file src/main/mule/api.xml")
        && saved.stream().noneMatch(m -> m.text().contains("<flow name=")));

    Scripted blank = new Scripted(r -> ChatResponse.builder().aiMessage(AiMessage.from("")).build());
    ChatSession empty = new ChatSession();
    empty.send(blank, ChatMode.AGENT, "one", List.of(), project, Progress.NONE);
    empty.send(blank, ChatMode.AGENT, "two", List.of(), project, Progress.NONE);
    failed += check("an empty reply goes into history as the text shown", blank.last().messages().stream()
        .noneMatch(m -> m instanceof AiMessage ai && !ai.hasToolExecutionRequests()
            && (ai.text() == null || ai.text().isBlank())));
    return failed;
  }

  // Each turn reads a 60,000-character file, about 15,000 tokens.
  private static int budget(Harness.DiskProject project) {
    int failed = 0;
    Scripted model = new Scripted(r -> readThenAnswer(r, "src/main/mule/big.xml", 2_000));
    ChatSession session = new ChatSession();
    List<List<ChatMessage>> firsts = new ArrayList<>();
    for (int turn = 1; turn <= 6; turn++) {
      int before = model.requests.size();
      session.send(model, ChatMode.AGENT, "read it again, turn " + turn, List.of(), project, Progress.NONE);
      firsts.add(model.requests.get(before).messages());
    }
    List<Boolean> fifth = cut(firsts.get(4));
    List<Boolean> sixth = cut(firsts.get(5));
    failed += check("old tool output is kept until enough of it can go", cut(firsts.get(3)).stream().noneMatch(c -> c));
    failed += check("then output beyond the newest 30,000 tokens is cut to a note", fifth.equals(List.of(true, true,
        false, false)));
    failed += check("cut output stays cut, and the newest is kept", sixth.size() == 5 && sixth.get(0) && sixth.get(1)
        && !sixth.get(4));
    return failed;
  }

  private static int compaction(Harness.DiskProject project) {
    int failed = 0;
    String compactAsk = "Do not call any tools. Write the summary";
    int window = ContextWindow.DEFAULT;
    int nearlyFull = (int) (window * ChatSession.COMPACT_AT) + 1_000;
    Scripted model = new Scripted(r -> text(last(r)).contains(compactAsk)
        ? ChatResponse.builder().aiMessage(AiMessage.from("- The user is working on the orders flow."))
            .tokenUsage(new TokenUsage(nearlyFull, 300)).build()
        : readThenAnswer(r, "src/main/mule/api.xml", nearlyFull));
    ChatSession session = new ChatSession();
    List<String> steps = new CopyOnWriteArrayList<>();
    Progress progress = new Progress() {
      @Override
      public void step(String message) {
        steps.add(message);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
    Attachment open = Attachment.openFile("p", "src/main/mule/api.xml", OPEN);
    session.send(model, ChatMode.AGENT, "kickoff-xq", List.of(open), project, progress);
    List<ChatMessage> firstFinal = model.last().messages();
    failed += check("the context size is the provider's count of the last call",
        session.contextTokens() == nearlyFull + 50);
    int before = model.requests.size();
    ChatSession.Turn turn = session.send(model, ChatMode.AGENT, "carry on", List.of(open), project, progress);
    ChatRequest summary = model.requests.get(before);
    List<ChatMessage> expected = new ArrayList<>(firstFinal);
    expected.add(AiMessage.from("Read it."));
    failed += check("a chat near its window is compacted before the next message",
        text(last(summary)).contains(compactAsk) && steps.stream().anyMatch(s -> s.startsWith("Compacting the chat")));
    failed += check("the summary request continues the cached history with the same tools",
        summary.messages().subList(0, expected.size()).equals(expected)
            && summary.toolSpecifications().equals(firstFinalTools(model)));
    List<ChatMessage> after = model.requests.get(before + 1).messages();
    failed += check("the summary replaces the summarised turns", text(after.get(1)).startsWith("SUMMARY OF THE EARLIER")
        && text(after.get(1)).contains("orders flow") && after.stream().noneMatch(m -> text(m).contains("kickoff-xq")));
    failed += check("attachments are sent in full again after compaction",
        text(last(model.requests.get(before + 1))).contains("<flow name=\"orders\"/>"));
    failed += check("the summary call counts in the turn's cost", turn.calls().size() == 3);
    failed += check("the saved chat starts with the summary", session.savedHistory().get(0).text()
        .startsWith("SUMMARY OF THE EARLIER"));

    Scripted small = new Scripted(r -> text(last(r)).contains(compactAsk)
        ? ChatResponse.builder().aiMessage(AiMessage.from("- Summary.")).build()
        : readThenAnswer(r, "src/main/mule/api.xml", 1_000));
    ChatSession manual = new ChatSession();
    manual.send(small, ChatMode.AGENT, "turn-xq-one", List.of(), project, Progress.NONE);
    manual.send(small, ChatMode.AGENT, "two", List.of(), project, Progress.NONE);
    int sent = small.requests.size();
    manual.send(small, ChatMode.AGENT, "turn-xq-three", List.of(), project, Progress.NONE);
    failed += check("a small chat is not compacted", small.requests.stream().noneMatch(r -> text(last(r))
        .contains(compactAsk)));
    manual.compactBeforeNext();
    sent = small.requests.size();
    manual.send(small, ChatMode.AGENT, "four", List.of(), project, Progress.NONE);
    List<ChatMessage> compacted = small.requests.get(sent + 1).messages();
    failed += check("Compact summarises the older turns and keeps the recent ones before the next message",
        text(last(small.requests.get(sent))).contains(compactAsk) && text(compacted.get(1)).contains("Summary.")
            && compacted.stream().anyMatch(m -> text(m).contains("turn-xq-three"))
            && compacted.stream().noneMatch(m -> text(m).endsWith("turn-xq-one")));

    Scripted failing = new Scripted(r -> {
      if (text(last(r)).contains(compactAsk)) {
        throw new IllegalStateException("summary failed");
      }
      return readThenAnswer(r, "src/main/mule/api.xml", nearlyFull);
    });
    ChatSession kept = new ChatSession();
    steps.clear();
    kept.send(failing, ChatMode.AGENT, "turn-xq-first", List.of(), project, progress);
    ChatSession.Turn survived = kept.send(failing, ChatMode.AGENT, "second", List.of(), project, progress);
    failed += check("a failed summary leaves the chat as it was and the message still goes",
        survived.text().equals("Read it.") && steps.stream().anyMatch(s -> s.startsWith("Could not compact"))
            && failing.last().messages().stream().anyMatch(m -> text(m).endsWith("turn-xq-first")));
    return failed;
  }

  private static Object firstFinalTools(Scripted model) {
    return model.requests.get(0).toolSpecifications();
  }

  // For each tool result in the request, whether it was cut to a note.
  private static List<Boolean> cut(List<ChatMessage> messages) {
    List<Boolean> out = new ArrayList<>();
    for (ChatMessage message : messages) {
      if (message instanceof ToolExecutionResultMessage result) {
        out.add(result.text().startsWith("[Output removed"));
      }
    }
    return out;
  }

  private static ChatMessage last(ChatRequest request) {
    return request.messages().get(request.messages().size() - 1);
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

  // User messages that carry the text: attachments, not tool results.
  private static long count(List<ChatMessage> messages, String needle) {
    return messages.stream().filter(m -> m instanceof UserMessage && text(m).contains(needle)).count();
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
