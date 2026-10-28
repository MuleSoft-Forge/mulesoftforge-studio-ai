package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.history.ChatRecord;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
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
      """;

  private static final int MAX_TOOL_ROUNDS = 12;

  public record Turn(String text, ChangeSet changes, int toolCalls, List<CallUsage> calls,
      Map<String, Integer> toolCounts) {

    // Plain-language summary for the transcript, e.g. "read 2 files, proposed changes to 1 file".
    public String activity() {
      List<String> parts = new ArrayList<>();
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
      if (proposed > 0) {
        parts.add("proposed changes to " + plural(proposed, "file"));
      } else if (edits > 0) {
        parts.add("tried " + plural(edits, "edit") + " that did not apply");
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

  public synchronized Turn send(ChatModel model, String prompt, List<Attachment> attachments, ProjectFiles project) {
    return send(model, ChatMode.AGENT, prompt, attachments, project, Progress.NONE);
  }

  public synchronized Turn send(ChatModel model, ChatMode mode, String prompt, List<Attachment> attachments,
      ProjectFiles project, Progress progress) {
    StringBuilder context = new StringBuilder();
    List<String> sent = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    for (Attachment attachment : attachments) {
      String block = attachment.toPrompt();
      if (context.length() + block.length() > Attachment.MAX_TOTAL_CHARS) {
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
    UserMessage message = UserMessage.from(text);
    String system = project == null ? SYSTEM_PROMPT : SYSTEM_PROMPT + mode.projectPrompt(project.name());

    ChangeSet changes = new ChangeSet();
    ProjectTools tools = project == null ? null : new ProjectTools(project, changes, mode.canEdit);
    List<ChatMessage> turn = new ArrayList<>();
    turn.add(message);
    int toolCalls = 0;
    List<CallUsage> calls = new ArrayList<>();
    Map<String, Integer> toolCounts = new LinkedHashMap<>();

    String answer = null;
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
            ? (changes.isEmpty() ? "(empty reply)" : "Proposed changes are ready for review.")
            : reply.text();
        break;
      }
      for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
        toolCalls++;
        toolCounts.merge(call.name(), 1, Integer::sum);
        progress.step(ProjectTools.describe(call));
        turn.add(ToolExecutionResultMessage.from(call, tools.execute(call)));
      }
    }
    if (answer == null) {
      answer = "Stopped after " + MAX_TOOL_ROUNDS + " steps before the model finished."
          + (changes.isEmpty() ? "" : " What it proposed so far is ready to review; ask it to continue if needed.");
    }

    // Tool traffic stays out of history: some providers reject tool messages in a request without tools.
    String remembered = changes.isEmpty() ? answer : answer + "\n\n[Proposed for review: "
        + String.join(", ", changes.all().stream().map(c -> c.path()).toList()) + "]";
    history.add(message);
    history.add(AiMessage.from(remembered));
    saved.add(new ChatRecord.Message("user", savedText));
    saved.add(new ChatRecord.Message("ai", remembered));
    reviewNotes.clear();
    cancelNotes.removeAll(cancelled);
    return new Turn(answer, changes, toolCalls, calls, toolCounts);
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
    String shown = prompt.length() > 200 ? prompt.substring(0, 200) + "…" : prompt;
    synchronized (cancelNotes) {
      cancelNotes.add("NOTE: The user cancelled an earlier request before you answered (\"" + shown
          + "\"). Nothing from it was kept; do not assume it was done.");
    }
  }

  public synchronized void clear() {
    history.clear();
    saved.clear();
    reviewNotes.clear();
    cancelNotes.clear();
  }
}
