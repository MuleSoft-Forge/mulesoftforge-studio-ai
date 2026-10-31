package com.mulesoftforge.studio.ai.llm;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.settings.Provider;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;

// "Auto" in the model picker: a fast model for plain questions, a strong one for changes, reviews, images and
// large context, chosen per message from rules. If the chosen model fails before answering anything (rate limit,
// server error, timeout), the turn runs once more on the other model. A turn never switches model mid-way.
public final class AutoRouter {
  public static final String AUTO = "Auto";

  public record Models(String fast, String strong) {}

  // model is what to call; fallback is the other model, or null when both are the same.
  public record Choice(String model, String fallback, String reason) {}

  public record Option(String name, ChatModel model) {}

  private static final int LARGE_CONTEXT_CHARS = 24_000;
  private static final int LONG_PROMPT_CHARS = 1_500;
  private static final Pattern NEEDS_STRONG = Pattern.compile("\\b(review|fix\\w*|bugs?|wrong|broken|fail\\w*|"
      + "errors?|exceptions?|debug\\w*|refactor\\w*|improve\\w*|optimi[sz]\\w*|migrat\\w*|secur\\w*|vulnerab\\w*|"
      + "write|generate|create|implement\\w*|design\\w*|convert\\w*|rewrite|test(s|ing)?|munit)\\b");
  private static final Pattern DATAWEAVE = Pattern.compile("dataweave|%dw|\\bdwl\\b|\\.dwl\\b|transform message");

  private AutoRouter() {}

  public static boolean isAuto(String model) {
    return model != null && AUTO.equalsIgnoreCase(model.trim());
  }

  // Starting points per provider; both can be changed in Preferences.
  public static Models defaults(Provider provider) {
    return switch (provider) {
      case OPENROUTER, MULESOFT_AI_GATEWAY -> new Models("openai/gpt-5-mini", "anthropic/claude-sonnet-4.6");
      case OPENAI -> new Models("gpt-5-mini", "gpt-5");
      case ANTHROPIC -> new Models("claude-haiku-4-5", "claude-sonnet-4-6");
      case GEMINI -> new Models("gemini-2.5-flash", "gemini-2.5-pro");
      case AZURE_OPENAI -> new Models("gpt-4o-mini", "gpt-4o");
      case BEDROCK -> new Models("anthropic.claude-haiku-4-5-20251001-v1:0", "anthropic.claude-sonnet-4-20250514-v1:0");
      case OLLAMA -> new Models("qwen2.5-coder", "qwen2.5-coder");
      case CUSTOM -> new Models("", "");
    };
  }

  // fix: the message asks to correct something specific, such as Fix with MuleSoftForge AI in a DataWeave editor.
  public static Choice choose(ChatMode mode, String prompt, List<Attachment> attachments, boolean fix,
      Models models) {
    String fast = models.fast() == null ? "" : models.fast().trim();
    String strong = models.strong() == null ? "" : models.strong().trim();
    if (fast.isEmpty() || strong.isEmpty() || isAuto(fast) || isAuto(strong)) {
      throw new IllegalStateException("Auto needs a fast and a strong model. Set them in Preferences >"
          + " MuleSoftForge AI, next to Model.");
    }
    String reason = strongReason(mode, prompt == null ? "" : prompt, attachments, fix);
    String model = reason == null ? fast : strong;
    String other = reason == null ? strong : fast;
    return new Choice(model, other.equals(model) ? null : other,
        reason == null ? "a question with little context" : reason);
  }

  private static String strongReason(ChatMode mode, String prompt, List<Attachment> attachments, boolean fix) {
    switch (mode) {
      case AGENT -> {
        return "Agent mode can change files";
      }
      case PLAN -> {
        return "Plan mode";
      }
      case ASK -> {
        // Questions are routed below.
      }
    }
    if (fix) {
      return "it asks for a fix";
    }
    String lower = prompt.toLowerCase(Locale.ROOT);
    if (attachments.stream().anyMatch(a -> a.kind() == Attachment.Kind.DEBUG_EVENT)) {
      return "a live debug event is attached";
    }
    if (attachments.stream().anyMatch(AutoRouter::isDataWeave) || DATAWEAVE.matcher(lower).find()) {
      return "it is about DataWeave";
    }
    if (attachments.stream().anyMatch(a -> a.kind() == Attachment.Kind.IMAGE)) {
      return "an image is attached";
    }
    long chars = attachments.stream().mapToLong(a -> a.content() == null ? 0 : a.content().length()).sum();
    if (chars > LARGE_CONTEXT_CHARS) {
      return "a lot of context is attached";
    }
    if (prompt.length() > LONG_PROMPT_CHARS) {
      return "a long message";
    }
    if (NEEDS_STRONG.matcher(lower).find()) {
      return "it asks for a review, a fix or new code";
    }
    return null;
  }

  private static boolean isDataWeave(Attachment attachment) {
    return attachment.kind() == Attachment.Kind.DATAWEAVE
        || attachment.path() != null && attachment.path().toLowerCase(Locale.ROOT).endsWith(".dwl");
  }

  // Worth trying another model: rate limits, server errors, timeouts and dropped connections. Not bad keys,
  // bad requests or unknown models, which the other model would hit too or which the user has to fix.
  public static boolean retryable(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof CancellationException || t instanceof InterruptedException) {
        return false;
      }
      if (t instanceof HttpException http) {
        int status = http.statusCode();
        return status == 408 || status == 429 || status >= 500;
      }
      if (t instanceof RetriableException || t instanceof HttpTimeoutException || t instanceof SocketException
          || t instanceof SocketTimeoutException || t instanceof TransportException) {
        return true;
      }
    }
    return false;
  }

  // First line of the error, for the step that says the turn moved to the other model.
  public static String shortReason(Throwable error) {
    String text = Errors.describe(error).strip();
    int line = text.indexOf('\n');
    String first = line < 0 ? text : text.substring(0, line);
    return first.length() > 120 ? first.substring(0, 120) + "…" : first;
  }

  // Runs the turn on the first option; on a retryable failure before any call answered, once on the next.
  public static ChatSession.Turn send(ChatSession session, List<Option> options, ChatMode mode, String prompt,
      List<Attachment> attachments, ProjectFiles project, Progress progress) {
    AtomicInteger answered = new AtomicInteger();
    Progress counting = new Progress() {
      @Override
      public void step(String message) {
        progress.step(message);
      }

      @Override
      public void answered(CallUsage usage, long seconds) {
        answered.incrementAndGet();
        progress.answered(usage, seconds);
      }

      @Override
      public boolean isCancelled() {
        return progress.isCancelled();
      }
    };
    for (int i = 0; ; i++) {
      Option option = options.get(i);
      try {
        return session.send(option.model(), mode, prompt, attachments, project, counting);
      } catch (RuntimeException e) {
        boolean another = i + 1 < options.size() && answered.get() == 0 && !progress.isCancelled()
            && retryable(e);
        if (!another) {
          throw e;
        }
        progress.step(option.name() + " failed (" + shortReason(e) + "); trying " + options.get(i + 1).name());
      }
    }
  }
}
