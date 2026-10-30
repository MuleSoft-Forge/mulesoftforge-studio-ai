package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.edit.ChangeOffer;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.history.ChatRecord;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

public class ChatSession {
  private static final String SYSTEM_PROMPT = """
      You are an expert MuleSoft developer working inside Anypoint Studio.
      You help with Mule 4 flows (XML), DataWeave 2.0, RAML and OAS API specs, MUnit tests,
      connector configuration, and Mule project structure (pom.xml, mule-artifact.json, properties).
      Be concise and practical. Put Mule XML, DataWeave and specs in fenced code blocks.
      Never invent connector operations, attributes or namespaces; say so when you are unsure.
      Passwords, keys, tokens and client ids are hidden from you as «masked-N». That is deliberate: never ask
      for the real values. When an edit touches a masked value, copy the placeholder exactly; the real value is
      restored when the change is applied. A masked literal in a Mule file is a hardcoded secret, so recommend
      moving it to secure properties.
      """;

  private static final int MAX_TOOL_ROUNDS = 12;
  private static final String EMPTY_REPLY = "(empty reply)";

  // problems: what the static checks still find in the proposals when the model stopped.
  // offers: changes the model recommended but did not make, for the user to pick from.
  // masked: secret values hidden from the model this turn.
  public record Turn(String text, ChangeSet changes, int toolCalls, List<CallUsage> calls,
      Map<String, Integer> toolCounts, List<Problem> problems, List<ChangeOffer> offers, int masked) {

    // Plain-language summary for the transcript, e.g. "read 2 files, proposed changes to 1 file".
    public String activity() {
      List<String> parts = new ArrayList<>();
      if (masked > 0) {
        parts.add("hid " + plural(masked, "secret value") + " from the model");
      }
      int listed = toolCounts.getOrDefault("list_files", 0);
      int read = toolCounts.getOrDefault("read_file", 0);
      int edits = toolCounts.getOrDefault("edit_file", 0) + toolCounts.getOrDefault("write_file", 0);
      int proposed = changes.all().size();
      if (listed > 0) {
        parts.add("listed " + plural(listed, "folder"));
      }
      if (read > 0) {
        parts.add("read " + plural(read, "file"));
      }
      if (toolCounts.getOrDefault("connector_reference", 0) > 0) {
        parts.add("looked up connector details");
      }
      if (toolCounts.getOrDefault("check_project", 0) > 0) {
        parts.add("ran the project checks");
      }
      if (proposed > 0) {
        parts.add("proposed changes to " + plural(proposed, "file"));
      } else if (edits > 0) {
        parts.add("tried " + plural(edits, "edit") + " that did not apply");
      }
      if (!offers.isEmpty()) {
        parts.add("offered " + plural(offers.size(), "change"));
      }
      return parts.isEmpty() ? "answered directly" : String.join(", ", parts);
    }

    private static String plural(int n, String noun) {
      return n + " " + noun + (n == 1 ? "" : "s");
    }
  }

  private final List<ChatMessage> history = new ArrayList<>();
  private final List<String> reviewNotes = new ArrayList<>();
  private final List<String> cancelNotes = Collections.synchronizedList(new ArrayList<>());
  private final List<ChatRecord.Message> saved = new ArrayList<>();
  private final SecretMask secrets = new SecretMask();

  // Off sends values as they are; files that only hold secrets are withheld either way.
  public void maskSecrets(boolean on) {
    secrets.setEnabled(on);
  }

  // The real values for placeholders in text the model wrote, for copying or applying it in Studio.
  public String unmask(String text) {
    return secrets.unmask(text);
  }

  public synchronized Turn send(ChatModel model, String prompt, List<Attachment> attachments, ProjectFiles project) {
    return send(model, ChatMode.AGENT, prompt, attachments, project, Progress.NONE);
  }

  public synchronized Turn send(ChatModel model, ChatMode mode, String prompt, List<Attachment> attachments,
      ProjectFiles project, Progress progress) {
    int maskedBefore = secrets.total();
    prompt = secrets.mask(null, prompt).text();
    StringBuilder context = new StringBuilder();
    List<String> sent = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    List<ImageContent> images = new ArrayList<>();
    for (Attachment original : attachments) {
      Attachment attachment = secrets.protect(original);
      String block = attachment.toPrompt();
      if (attachment.kind() == Attachment.Kind.IMAGE) {
        images.add(attachment.toImage());
        context.append(block);
        sent.add(attachment.label());
      } else if (context.length() + block.length() > Attachment.MAX_TOTAL_CHARS) {
        dropped.add(attachment.label());
      } else {
        context.append(block);
        sent.add(attachment.label());
      }
    }
    if (!dropped.isEmpty()) {
      context.append("NOT ATTACHED (too large to send together; read them with tools if needed): ")
          .append(String.join(", ", dropped)).append("\n\n");
    }
    String text = context + prompt;
    String savedText = sent.isEmpty() ? prompt : "[Attached at the time: " + String.join(", ", sent) + "]\n" + prompt;
    String notes = "";
    List<String> cancelled;
    synchronized (cancelNotes) {
      cancelled = List.copyOf(cancelNotes);
    }
    if (!cancelled.isEmpty()) {
      notes += String.join("\n", cancelled) + "\n\n";
    }
    if (!reviewNotes.isEmpty()) {
      notes += "REVIEW OF YOUR EARLIER PROPOSALS:\n" + String.join("\n", reviewNotes) + "\n\n";
    }
    text = notes + text;
    savedText = notes + savedText;
    UserMessage textOnly = UserMessage.from(text);
    UserMessage message = textOnly;
    if (!images.isEmpty()) {
      List<Content> parts = new ArrayList<>(images);
      parts.add(TextContent.from(text));
      message = UserMessage.from(parts);
    }
    String system = project == null ? SYSTEM_PROMPT : SYSTEM_PROMPT + mode.projectPrompt(project.name());
    int maskedInMessage = secrets.total() - maskedBefore;
    if (maskedInMessage > 0) {
      progress.step("Hid " + maskedInMessage + (maskedInMessage == 1 ? " secret value" : " secret values")
          + " in your message and attachments before sending");
    }

    ChangeSet changes = new ChangeSet();
    ProjectTools tools = project == null ? null : new ProjectTools(project, changes, mode.canEdit, secrets);
    List<ChatMessage> turn = new ArrayList<>();
    turn.add(message);
    int toolCalls = 0;
    List<CallUsage> calls = new ArrayList<>();
    Map<String, Integer> toolCounts = new LinkedHashMap<>();

    String answer = null;
    // Models often write the whole answer in the message that calls offer_changes, then only a closing line.
    String offeredWith = null;
    for (int round = 0; round < MAX_TOOL_ROUNDS && answer == null; round++) {
      List<ChatMessage> messages = new ArrayList<>();
      messages.add(SystemMessage.from(system));
      messages.addAll(history);
      messages.addAll(turn);
      ChatRequest.Builder request = ChatRequest.builder().messages(messages);
      if (tools != null) {
        request.toolSpecifications(tools.specifications());
      }

      progress.checkCancelled();
      progress.step(round == 0 ? "Waiting for the model…" : "Waiting for the model (step " + (round + 1) + ")…");
      long started = System.nanoTime();
      ReportedCost.take();
      ChatResponse response = model.chat(request.build());
      CallUsage usage = CallUsage.from(response, model.defaultRequestParameters().modelName(), ReportedCost.take());
      calls.add(usage);
      progress.answered(usage, (System.nanoTime() - started) / 1_000_000_000);
      progress.checkCancelled();
      AiMessage reply = response.aiMessage();
      turn.add(reply);
      if (tools == null || !reply.hasToolExecutionRequests()) {
        answer = reply.text() == null || reply.text().isBlank()
            ? (changes.isEmpty() ? EMPTY_REPLY : "Proposed changes are ready for review.")
            : reply.text();
        break;
      }
      if (reply.text() != null && !reply.text().isBlank() && reply.toolExecutionRequests().stream()
          .anyMatch(call -> call.name().equals("offer_changes"))) {
        offeredWith = reply.text().strip();
      }
      for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
        toolCalls++;
        toolCounts.merge(call.name(), 1, Integer::sum);
        progress.step(ProjectTools.describe(call));
        int before = secrets.total();
        String result = tools.execute(call);
        int hidden = secrets.total() - before;
        if (hidden > 0) {
          progress.step("Hid " + hidden + (hidden == 1 ? " secret value" : " secret values") + " before sending");
        }
        turn.add(ToolExecutionResultMessage.from(call, result));
      }
    }
    if (answer == null) {
      answer = "Stopped after " + MAX_TOOL_ROUNDS + " steps before the model finished."
          + (changes.isEmpty() ? "" : " What it proposed so far is ready to review; ask it to continue if needed.");
    }
    if (offeredWith != null && !answer.contains(offeredWith)) {
      answer = answer.equals(EMPTY_REPLY) ? offeredWith : offeredWith + "\n\n" + answer;
    }

    // Tool traffic stays out of history: some providers reject tool messages in a request without tools.
    String remembered = changes.isEmpty() ? answer : answer + "\n\n[Proposed for review: "
        + String.join(", ", changes.all().stream().map(c -> c.path()).toList()) + "]";
    List<ChangeOffer> offers = tools == null ? List.of() : tools.offers();
    if (!offers.isEmpty()) {
      remembered += "\n\n" + ChangeOffer.remembered(offers);
    }
    // Images are large and billed on every request that carries them, so later turns keep only the text.
    history.add(textOnly);
    history.add(AiMessage.from(remembered));
    saved.add(new ChatRecord.Message("user", savedText));
    saved.add(new ChatRecord.Message("ai", remembered));
    reviewNotes.clear();
    cancelNotes.removeAll(cancelled);
    List<Problem> problems = tools == null || changes.isEmpty() ? List.of() : tools.problemsIntroduced();
    return new Turn(answer, changes, toolCalls, calls, toolCounts, problems, offers, secrets.total() - maskedBefore);
  }

  // History safe to write to disk: same turns, without the open-file contents.
  public synchronized List<ChatRecord.Message> savedHistory() {
    return new ArrayList<>(saved);
  }

  public synchronized void restore(List<ChatRecord.Message> messages) {
    clear();
    if (messages == null) {
      return;
    }
    for (ChatRecord.Message m : messages) {
      if (m.text() == null) {
        continue;
      }
      history.add("user".equals(m.role()) ? UserMessage.from(m.text()) : AiMessage.from(m.text()));
      saved.add(m);
    }
  }

  // Told to the model with the next prompt, so it knows which proposals actually landed.
  public synchronized void noteReview(String note) {
    reviewNotes.add(note);
  }

  // Cancelled turns leave no history, so the model is told the request was dropped rather than answered.
  public void noteCancelled(String prompt) {
    String masked = secrets.mask(null, prompt).text();
    String shown = masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
    synchronized (cancelNotes) {
      cancelNotes.add("NOTE: The user cancelled an earlier request before you answered (\"" + shown
          + "\"). Nothing from it was kept; do not assume it was done.");
    }
  }

  public synchronized void clear() {
    history.clear();
    saved.clear();
    secrets.clear();
    reviewNotes.clear();
    cancelNotes.clear();
  }
}
