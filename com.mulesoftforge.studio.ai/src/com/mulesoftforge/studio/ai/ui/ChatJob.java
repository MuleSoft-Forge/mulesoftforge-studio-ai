package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.check.MunitSchemas;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.exchange.ExchangeSearch;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.hooks.Hooks;
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

    // What the current model call is doing, the answer text so far, and when the call started (System.nanoTime);
    // state is null when no call is running.
    void live(String state, String preview, long since);

    default void todos(List<com.mulesoftforge.studio.ai.edit.TodoItem> items) {}

    // A model call's reasoning, once it ends.
    default void thought(String text, long millis) {}

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
  // A stream sends many small pieces; the view is updated at most every LIVE_MS with the latest of them.
  private static final int LIVE_MS = 150;
  private static final int PREVIEW_CHARS = 20_000;
  private final Object liveLock = new Object();
  private final AtomicBoolean liveQueued = new AtomicBoolean();
  private final StringBuilder livePreview = new StringBuilder();
  private String liveState;
  private boolean liveThinking;
  private long liveSince;

  ChatJob(ChatSession session, ChatMode mode, String prompt, List<Attachment> attachments, ProjectFiles project,
      String chatId, int turn, boolean showModel, List<AutoRouter.Option> prebuilt, AutoRouter.Choice auto,
      Display display, Listener listener) {
    super("MuleSoftForge Studio AI");
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
          Activator.logFailure("Could not write the MuleSoftForge Studio AI raw log", e);
        }
      }) : null;
      List<AutoRouter.Option> models = models(connection, capture);
      if (auto != null) {
        Ui.async(display, null, () -> listener.step("Auto picked " + auto.model() + ": " + auto.reason()));
      }
      Progress progress = jobProgress(sub, provider);
      session.maskSecrets(AiSettings.maskSecrets());
      session.maskPersonalData(AiSettings.maskPersonalData());
      session.instructions(AiSettings.instructions());
      session.httpInstructions(AiSettings.httpInstructions());
      session.docsHosts(AiSettings.docsHosts());
      StudioDebugger debugger = StudioDebugger.get();
      session.debugger(debugger.available() ? debugger : null);
      session.appConsole(Consoles.MULE_APP);
      session.appLauncher(new StudioLauncher(display));
      session.munitRunner(new StudioMunitRunner(display));
      MunitSchemas.searchIn(MunitSchemas.studioRepositories(
          ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()));
      session.dataWeave(DataWeaveCli.lookup(AiSettings.dataWeaveCli(), false).cli());
      session.hooks(hooks(progress));
      session.extensions(Extensions.load(project == null ? null : project.location(), AiSettings.disabledBuiltIns()));
      session.fixChecksBeforeFinishing(AiSettings.fixChecksBeforeFinishing());
      session.exchange(ExchangeSearch.live(StudioAnypoint::login));
      turnResult = AutoRouter.send(session, models, mode, prompt, attachments, project, progress);
    } catch (CancellationException e) {
      cancelled = true;
    } catch (Hooks.Blocked e) {
      failure = new Errors.UserError(e.getMessage(), null, null);
    } catch (Exception e) {
      if (sub.isCanceled() || Thread.currentThread().isInterrupted()) {
        cancelled = true;
      } else {
        Activator.logFailure("MuleSoftForge Studio AI request failed", e);
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
    clearLive();
    Ui.async(display, null, () -> listener.finished(result, error, wasCancelled, seconds, answerCost, rawCalls));
    return wasCancelled ? Status.CANCEL_STATUS : Status.OK_STATUS;
  }

  // Read each turn, so edits to the file apply to the next message.
  private static Hooks hooks(Progress progress) {
    if (!AiSettings.hooksEnabled()) {
      return Hooks.NONE;
    }
    Hooks hooks = Hooks.load(Hooks.userFile());
    if (hooks.problem() != null) {
      progress.step("Hooks: " + hooks.problem());
    }
    return hooks;
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
        clearLive();
        if (!isCancelled()) {
          Ui.async(display, null, () -> listener.step(message));
        }
      }

      @Override
      public void answered(CallUsage usage, long seconds) {
        clearLive();
        // Price only from the already-loaded list so a stalled openrouter.ai fetch cannot block the turn.
        Pricing.CallCost cost = Pricing.costCached(provider, usage);
        answerCost.add(cost);
        Ui.async(display, null, () -> listener.answered(usage, cost, seconds, showModel));
      }

      @Override
      public void todos(List<com.mulesoftforge.studio.ai.edit.TodoItem> items) {
        if (!isCancelled()) {
          List<com.mulesoftforge.studio.ai.edit.TodoItem> copy = List.copyOf(items);
          Ui.async(display, null, () -> listener.todos(copy));
        }
      }

      @Override
      public void receiving(String what) {
        sub.subTask(what);
        synchronized (liveLock) {
          if (liveState == null) {
            liveSince = System.nanoTime();
          }
          // The reasoning preview gives way to the answer; the reasoning itself goes to thought().
          if (liveThinking && !ChatSession.WAITING_FOR_DATA.equals(what)) {
            livePreview.setLength(0);
            liveThinking = false;
          }
          liveState = what;
        }
        queueLive();
      }

      @Override
      public void partial(String text) {
        preview(text);
      }

      @Override
      public void thinking(String text) {
        synchronized (liveLock) {
          liveThinking = true;
        }
        preview(text);
      }

      @Override
      public void thought(String text, long millis) {
        if (!isCancelled()) {
          Ui.async(display, null, () -> listener.thought(text, millis));
        }
      }

      @Override
      public boolean isCancelled() {
        return sub.isCanceled() || Thread.currentThread().isInterrupted();
      }
    };
  }

  private void preview(String text) {
    synchronized (liveLock) {
      livePreview.append(text);
      if (livePreview.length() > PREVIEW_CHARS) {
        livePreview.delete(0, livePreview.length() - PREVIEW_CHARS);
      }
    }
    queueLive();
  }

  private void clearLive() {
    synchronized (liveLock) {
      liveState = null;
      liveThinking = false;
      livePreview.setLength(0);
    }
    queueLive();
  }

  private void queueLive() {
    if (!liveQueued.compareAndSet(false, true)) {
      return;
    }
    Ui.async(display, null, () -> display.timerExec(LIVE_MS, () -> {
      liveQueued.set(false);
      String state;
      String preview;
      long since;
      synchronized (liveLock) {
        state = liveState;
        preview = livePreview.toString();
        since = liveSince;
      }
      listener.live(state, preview, since);
    }));
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
