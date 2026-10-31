package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.RawCapture;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;

// One chat turn. Cancelled from the Progress view or the Cancel button; canceling() aborts the in-flight HTTP call.
public final class ChatJob extends Job {
  public static final Object FAMILY = ChatJob.class;

  interface Listener {
    void step(String message);

    void answered(CallUsage usage, Pricing.CallCost cost, long seconds, boolean showModel);

    void finished(ChatSession.Turn turn, Errors.UserError failure, boolean cancelled, long seconds,
        CostMeter answerCost, List<RawCapture.Call> rawCalls);
  }

  private final ChatSession session;
  private final ChatMode mode;
  private final String prompt;
  private final List<Attachment> attachments;
  private final ProjectFiles project;
  private final String chatId;
  private final int turn;
  private final boolean showModel;
  private final List<AutoRouter.Option> prebuilt;
  private final AutoRouter.Choice auto;
  private final Display display;
  private final Listener listener;
  private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
  private final CostMeter answerCost = new CostMeter();

  ChatJob(ChatSession session, ChatMode mode, String prompt, List<Attachment> attachments, ProjectFiles project,
      String chatId, int turn, boolean showModel, List<AutoRouter.Option> prebuilt, AutoRouter.Choice auto,
      Display display, Listener listener) {
    super("MuleSoftForge AI");
    this.session = session;
    this.mode = mode;
    this.prompt = prompt;
    this.attachments = List.copyOf(attachments);
    this.project = project;
    this.chatId = chatId;
    this.turn = turn;
    this.showModel = showModel;
    this.prebuilt = prebuilt;
    this.auto = auto;
    this.display = display;
    this.listener = listener;
    setUser(false);
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    SubMonitor sub = SubMonitor.convert(monitor, IProgressMonitor.UNKNOWN);
    long started = System.nanoTime();
    Provider provider = AiSettings.provider();
    RawCapture capture = null;
    ChatSession.Turn turnResult = null;
    Errors.UserError failure = null;
    boolean cancelled = false;
    try {
      Connection connection = AiSettings.current();
      provider = connection.provider();
      String keyProblem = provider.credentialsProblem(connection.apiKey(), connection.clientId(),
          connection.clientSecret());
      capture = AiSettings.rawLog() ? new RawCapture(turn, provider.label, call -> {
        try {
          Stores.raw().append(chatId, call);
        } catch (Exception e) {
          Activator.logFailure("Could not write the MuleSoftForge AI raw log", e);
        }
      }) : null;
      List<AutoRouter.Option> models = models(connection, capture);
      if (auto != null) {
        Ui.async(display, null, () -> listener.step("Auto picked " + auto.model() + ": " + auto.reason()));
      }
      Progress progress = jobProgress(sub, provider);
      session.maskSecrets(AiSettings.maskSecrets());
      session.instructions(AiSettings.instructions());
      turnResult = AutoRouter.send(session, models, mode, prompt, attachments, project, progress);
    } catch (CancellationException e) {
      cancelled = true;
    } catch (Exception e) {
      if (sub.isCanceled() || Thread.currentThread().isInterrupted()) {
        cancelled = true;
      } else {
        Activator.logFailure("MuleSoftForge AI request failed", e);
        Errors.UserError classified = Errors.classify(e, provider);
        String message = classified.message();
        String keyProblem = null;
        try {
          Connection connection = AiSettings.current();
          keyProblem = connection.provider().credentialsProblem(connection.apiKey(), connection.clientId(),
              connection.clientSecret());
        } catch (Exception ignored) {
          // Keep the classified message.
        }
        if (keyProblem != null) {
          message = keyProblem + " Check Settings.\n\n" + message;
        }
        if (attachments.stream().anyMatch(a -> a.kind() == Attachment.Kind.IMAGE)) {
          message += "\n\nThis message included an image. If the model cannot read images, pick one that can"
              + " (for example a Claude, GPT-4o/GPT-5 or Gemini model, or a vision model such as llava in Ollama),"
              + " or remove the image and send again.";
        }
        failure = new Errors.UserError(message, classified.details(), classified.retryAfter());
      }
    }
    long seconds = (System.nanoTime() - started) / 1_000_000_000;
    ChatSession.Turn result = turnResult;
    Errors.UserError error = failure;
    boolean wasCancelled = cancelled || sub.isCanceled();
    List<RawCapture.Call> rawCalls = capture == null ? List.of() : capture.calls();
    Ui.async(display, null, () -> listener.finished(result, error, wasCancelled, seconds, answerCost, rawCalls));
    return wasCancelled ? Status.CANCEL_STATUS : Status.OK_STATUS;
  }

  private List<AutoRouter.Option> models(Connection connection, RawCapture capture) {
    if (prebuilt != null && !prebuilt.isEmpty()) {
      return prebuilt;
    }
    List<AutoRouter.Option> models = new ArrayList<>();
    if (auto != null) {
      models.add(new AutoRouter.Option(auto.model(),
          ChatModels.create(connection.withModel(auto.model()), capture, inFlight)));
      if (auto.fallback() != null) {
        models.add(new AutoRouter.Option(auto.fallback(),
            ChatModels.create(connection.withModel(auto.fallback()), capture, inFlight)));
      }
      return models;
    }
    models.add(new AutoRouter.Option(connection.model(), ChatModels.create(connection, capture, inFlight)));
    return models;
  }

  private Progress jobProgress(SubMonitor sub, Provider provider) {
    return new Progress() {
      @Override
      public void step(String message) {
        if (!isCancelled()) {
          Ui.async(display, null, () -> listener.step(message));
        }
      }

      @Override
      public void answered(CallUsage usage, long seconds) {
        // Price only from the already-loaded list so a stalled openrouter.ai fetch cannot block the turn.
        Pricing.CallCost cost = Pricing.costCached(provider, usage);
        answerCost.add(cost);
        Ui.async(display, null, () -> listener.answered(usage, cost, seconds, showModel));
      }

      @Override
      public boolean isCancelled() {
        return sub.isCanceled() || Thread.currentThread().isInterrupted();
      }
    };
  }

  @Override
  protected void canceling() {
    CompletableFuture<?> call = inFlight.get();
    if (call != null) {
      call.cancel(true);
    }
  }

  @Override
  public boolean belongsTo(Object family) {
    return family == FAMILY;
  }
}
