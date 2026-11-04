package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mulesoftforge.studio.ai.history.ChatRecord;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

// The chat's earlier turns. Each keeps the messages exactly as they were sent, tool calls and results included,
// so the next turn resends them unchanged and reads them from the prompt cache. Each also keeps a text-only form
// (the message with attachment labels, the answer with notes of what was done), for requests without tools, which
// some providers reject when the history holds tool messages, and for saving the chat to disk.
final class History {
  // The newest tool output, in tokens, that always stays in full.
  static final int PROTECTED_TOOL_TOKENS = 30_000;
  // Older output is cut only once this much can go, so the cached history changes rarely, not on every turn.
  static final int MIN_PRUNABLE_TOKENS = 20_000;
  // Compaction keeps the most recent turns up to this size and summarises the rest.
  static final int KEEP_RECENT_TOKENS = 20_000;
  static final String CUT_OUTPUT = "[Output removed to keep the chat within its context window: %s returned"
      + " %,d characters here. Call the tool again if you need it.]";
  static final String SUMMARY_HEADING = "SUMMARY OF THE EARLIER CONVERSATION (you wrote it when the chat grew"
      + " long; the turns after it are as they happened):\n";
  static final String SUMMARY_ACK = "Understood. I will continue from this summary.";

  record Exchange(List<ChatMessage> sent, String user, String answer) {}

  private final List<Exchange> exchanges = new ArrayList<>();
  // The last full copy of each attachment still in the history, by label, so an unchanged one is not sent again.
  private final Map<String, String> attached = new HashMap<>();

  synchronized boolean isEmpty() {
    return exchanges.isEmpty();
  }

  synchronized int size() {
    return exchanges.size();
  }

  // withTools: the request offers tools, so tool calls and results may be sent; otherwise the text-only form.
  synchronized List<ChatMessage> messages(boolean withTools) {
    List<ChatMessage> out = new ArrayList<>();
    for (Exchange exchange : exchanges) {
      if (withTools) {
        out.addAll(exchange.sent());
      } else {
        out.add(UserMessage.from(exchange.user()));
        out.add(AiMessage.from(exchange.answer()));
      }
    }
    return out;
  }

  // Whether this exact attachment block is already in the history, the last time it was sent.
  synchronized boolean hasAttached(String label, String block) {
    return block.equals(attached.get(label));
  }

  // blocks: the attachments sent in full with this turn, by label.
  synchronized void add(Exchange exchange, Map<String, String> blocks) {
    exchanges.add(new Exchange(new ArrayList<>(exchange.sent()), exchange.user(), exchange.answer()));
    attached.putAll(blocks);
    cutOldToolOutput();
  }

  synchronized List<ChatRecord.Message> saved() {
    List<ChatRecord.Message> out = new ArrayList<>();
    for (Exchange exchange : exchanges) {
      out.add(new ChatRecord.Message("user", exchange.user()));
      out.add(new ChatRecord.Message("ai", exchange.answer()));
    }
    return List.copyOf(out);
  }

  // A saved chat comes back in its text-only form.
  synchronized void restore(List<ChatRecord.Message> messages) {
    clear();
    String user = null;
    for (ChatRecord.Message m : messages) {
      if (m.text() == null) {
        continue;
      }
      if ("user".equals(m.role())) {
        user = user == null ? m.text() : user + "\n\n" + m.text();
      } else if (user != null) {
        exchanges.add(new Exchange(List.of(UserMessage.from(user), AiMessage.from(m.text())), user, m.text()));
        user = null;
      }
    }
  }

  synchronized void clear() {
    exchanges.clear();
    attached.clear();
  }

  // How many of the oldest turns compaction summarises: those before the recent ones that fit KEEP_RECENT_TOKENS,
  // and at least one. 0 when there is nothing to compact.
  synchronized int toSummarise() {
    int kept = 0;
    long tokens = 0;
    for (int i = exchanges.size() - 1; i > 0; i--) {
      tokens += tokens(exchanges.get(i).sent());
      if (tokens > KEEP_RECENT_TOKENS) {
        break;
      }
      kept++;
    }
    return exchanges.size() - kept;
  }

  // Replaces the oldest turns with the summary. Attachments are sent in full again afterwards.
  synchronized void compact(int summarised, String summary) {
    List<Exchange> kept = new ArrayList<>(exchanges.subList(Math.min(summarised, exchanges.size()), exchanges.size()));
    exchanges.clear();
    String text = SUMMARY_HEADING + summary.strip();
    exchanges.add(new Exchange(List.of(UserMessage.from(text), AiMessage.from(SUMMARY_ACK)), text, SUMMARY_ACK));
    exchanges.addAll(kept);
    attached.clear();
  }

  synchronized long tokens() {
    long total = 0;
    for (Exchange exchange : exchanges) {
      total += tokens(exchange.sent());
    }
    return total;
  }

  // About four characters a token, which is close for English and XML; an image counts as about 1,000.
  static long tokens(List<ChatMessage> messages) {
    long chars = 0;
    long images = 0;
    for (ChatMessage message : messages) {
      if (message instanceof UserMessage user) {
        for (Content content : user.contents()) {
          if (content instanceof TextContent text) {
            chars += text.text().length();
          } else if (content instanceof ImageContent) {
            images++;
          }
        }
      } else if (message instanceof AiMessage ai) {
        chars += ai.text() == null ? 0 : ai.text().length();
        if (ai.hasToolExecutionRequests()) {
          for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
            chars += call.name().length() + (call.arguments() == null ? 0 : call.arguments().length());
          }
        }
      } else if (message instanceof ToolExecutionResultMessage result) {
        chars += result.text() == null ? 0 : result.text().length();
      }
    }
    return chars / 4 + images * 1_000;
  }

  // Tool output older than the newest PROTECTED_TOOL_TOKENS is cut to a note, the latest turn's never, as
  // gemini-cli's toolOutputMaskingService does. Once cut it stays cut, so the cached history stays the same.
  private void cutOldToolOutput() {
    record Prunable(List<ChatMessage> messages, int index, ToolExecutionResultMessage result) {}
    List<Prunable> prunable = new ArrayList<>();
    long seen = 0;
    long cuttable = 0;
    for (int e = exchanges.size() - 2; e >= 0; e--) {
      List<ChatMessage> messages = exchanges.get(e).sent();
      for (int m = messages.size() - 1; m >= 0; m--) {
        if (!(messages.get(m) instanceof ToolExecutionResultMessage result) || result.text() == null
            || isCut(result.text())) {
          continue;
        }
        long tokens = result.text().length() / 4;
        seen += tokens;
        if (seen > PROTECTED_TOOL_TOKENS) {
          prunable.add(new Prunable(messages, m, result));
          cuttable += tokens;
        }
      }
    }
    if (cuttable < MIN_PRUNABLE_TOKENS) {
      return;
    }
    for (Prunable p : prunable) {
      String note = String.format(Locale.ROOT, CUT_OUTPUT, p.result().toolName(), p.result().text().length());
      p.messages().set(p.index(), ToolExecutionResultMessage.from(p.result().id(), p.result().toolName(), note));
    }
  }

  private static boolean isCut(String text) {
    return text.startsWith("[Output removed to keep the chat");
  }
}
