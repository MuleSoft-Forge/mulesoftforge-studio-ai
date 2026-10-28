package com.mulesoftforge.studio.ai.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.edit.LineDiff;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;

import dev.langchain4j.model.chat.ChatModel;

// The MuleSoftForge AI view: runs each message, records the chat for saving, and tracks its cost.
// Layout pieces live in Composer, ChangesPanel, ChatHistoryMenu and the Transcript implementations.
public class ChatView extends ViewPart {
  public static final String ID = "com.mulesoftforge.studio.ai.chat";

  private record Running(Job job, AtomicBoolean cancelled) {}

  private static final String BUILD_PLAN = "build-plan";

  private final ChatSession session = new ChatSession();
  private final CostMeter chatCost = new CostMeter();
  private boolean chatCounted;
  private Running running;

  // The chat being shown; id is null until the first message is sent.
  private String chatId;
  private String chatTitle;
  private String chatCreated;
  private String chatProject;
  private final List<ChatRecord.Entry> entries = new ArrayList<>();
  private boolean replaying;

  private Label header;
  private Label costLabel;
  private Transcript transcript;
  private ChangesPanel changes;
  private Composer composer;
  private Runnable stopWatchingSettings;
  private final AtomicBoolean settingsRefreshPending = new AtomicBoolean();

  @Override
  public void createPartControl(Composite parent) {
    parent.setLayout(new GridLayout(1, false));
    IWorkbenchPage page = getSite().getPage();

    Composite top = new Composite(parent, SWT.NONE);
    top.setLayout(new GridLayout(4, false));
    top.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    header = new Label(top, SWT.NONE);
    header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new VersionLink(top, page);
    Link history = new Link(top, SWT.NONE);
    history.setText("<a>History ▾</a>");
    history.setToolTipText("Saved chats in this workspace");
    ChatHistoryMenu historyMenu = new ChatHistoryMenu(parent.getShell(), new HistoryHost());
    history.addListener(SWT.Selection, e -> historyMenu.show(history));
    Link settings = new Link(top, SWT.NONE);
    settings.setText("<a>Settings</a>");
    settings.addListener(SWT.Selection, e -> openSettings());
    costLabel = new Label(top, SWT.NONE);
    costLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
    costLabel.setFont(TranscriptStyles.bold());

    transcript = HtmlTranscript.create(parent, new TranscriptActions(), AiSettings.richTranscript());
    transcript.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    changes = new ChangesPanel(parent, page, new ChangesPanel.Listener() {
      @Override
      public void note(String speaker, String text) {
        append(speaker, text);
      }

      @Override
      public void reviewed(String note) {
        session.noteReview(note);
      }

      @Override
      public void resolved() {
        saveChat();
      }
    });

    composer = new Composer(parent, page, new Composer.Listener() {
      @Override
      public void send() {
        ChatView.this.send();
      }

      @Override
      public void cancel() {
        ChatView.this.cancel();
      }

      @Override
      public void newChat() {
        resetChat();
      }

      @Override
      public void note(String speaker, String text) {
        append(speaker, text);
      }
    });

    IActionBars bars = getViewSite().getActionBars();
    bars.setGlobalActionHandler(ActionFactory.PASTE.getId(), new Action() {
      @Override
      public void run() {
        composer.paste();
      }
    });
    bars.updateActionBars();

    Display display = parent.getDisplay();
    stopWatchingSettings = AiSettings.onChange(key -> {
      if (AiSettings.affectsModels(key) && settingsRefreshPending.compareAndSet(false, true)) {
        display.asyncExec(this::settingsChanged);
      }
    });

    refreshHeader();
    refreshCost();
    pruneOldChats();
  }

  // Preferences can be opened from Studio's menu as well as from this view, and saves several keys at once.
  private void settingsChanged() {
    settingsRefreshPending.set(false);
    if (header.isDisposed()) {
      return;
    }
    refreshHeader();
    composer.refreshModels();
  }

  @Override
  public void dispose() {
    if (stopWatchingSettings != null) {
      stopWatchingSettings.run();
    }
    super.dispose();
  }

  @Override
  public void setFocus() {
    composer.focus();
  }

  // Used by the Ask AI command.
  void attach(Attachments.Result result) {
    composer.attach(result);
  }

  private final class TranscriptActions implements Transcript.Actions {
    @Override
    public void copy(String code) {
      Clipboard clipboard = new Clipboard(transcript.control().getDisplay());
      try {
        clipboard.setContents(new Object[] {code}, new Transfer[] {TextTransfer.getInstance()});
      } finally {
        clipboard.dispose();
      }
      appendStep("Copied " + LineDiff.lineCount(code) + " line(s) to the clipboard");
    }

    @Override
    public void insert(String code) {
      insertAtCursor(code);
    }

    @Override
    public void open(String url) {
      Program.launch(url);
    }

    @Override
    public void command(String id) {
      if (BUILD_PLAN.equals(id) && running == null) {
        composer.setMode(ChatMode.AGENT);
        composer.setPrompt(ChatMode.BUILD_PLAN_PROMPT);
        send();
      }
    }
  }

  private final class HistoryHost implements ChatHistoryMenu.Host {
    @Override
    public String currentChatId() {
      return chatId;
    }

    @Override
    public boolean busy() {
      return running != null;
    }

    @Override
    public void newChat() {
      resetChat();
    }

    @Override
    public void open(String id) {
      openChat(id);
    }

    @Override
    public void renamed(String id, String title) {
      if (id.equals(chatId)) {
        chatTitle = title;
      }
    }

    @Override
    public void deleted(String id) {
      if (id.equals(chatId)) {
        resetChat();
      }
    }

    @Override
    public void note(String speaker, String text) {
      append(speaker, text);
    }
  }

  // Clears the view and starts a new, not-yet-saved chat. The previous one is already saved.
  private void resetChat() {
    session.clear();
    changes.clear();
    chatCost.reset();
    chatCounted = false;
    entries.clear();
    chatId = null;
    chatTitle = null;
    chatCreated = null;
    chatProject = null;
    transcript.clear();
    refreshCost();
  }

  private void pruneOldChats() {
    int days = AiSettings.retentionDays();
    Job job = Job.create("Prune MuleSoftForge AI chats", monitor -> {
      try {
        Stores.chats().prune(days, Instant.now());
      } catch (Exception e) {
        Activator.logError("Could not prune saved chats", e);
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  private void saveChat() {
    if (chatId == null || entries.isEmpty() || !AiSettings.saveChats()) {
      return;
    }
    ChatRecord record = new ChatRecord(ChatRecord.SCHEMA, chatId, chatTitle, chatProject, chatCreated,
        Instant.now().toString(), new ArrayList<>(entries), session.savedHistory(), chatCost.snapshot(),
        changes.size());
    Job job = Job.create("Save MuleSoftForge AI chat", monitor -> {
      try {
        Stores.chats().save(record);
      } catch (Exception e) {
        Activator.logError("Could not save chat " + record.id(), e);
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  private void openChat(String id) {
    ChatRecord record;
    try {
      record = Stores.chats().load(id);
    } catch (Exception e) {
      append("Error", "Could not open chat: " + Errors.describe(e));
      return;
    }
    resetChat();
    chatId = record.id();
    chatTitle = record.title();
    chatCreated = record.created();
    chatProject = record.project();
    replaying = true;
    try {
      for (ChatRecord.Entry entry : record.entries()) {
        switch (entry.speaker()) {
          case "AI" -> appendMarkdown("AI", entry.text());
          case "You" -> {
            String text = entry.text();
            int split = text.lastIndexOf("\n[");
            if (split >= 0 && text.endsWith("]")) {
              transcript.message("You", text.substring(0, split), text.substring(split + 2, text.length() - 1));
            } else {
              transcript.message("You", text, null);
            }
          }
          case "Step" -> appendStep(entry.text());
          default -> append(entry.speaker(), entry.text());
        }
      }
    } finally {
      replaying = false;
    }
    entries.addAll(record.entries());
    session.restore(record.history());
    chatCost.restore(record.cost());
    if (record.pending() > 0) {
      append("Note", record.pending() + " proposed change(s) were not reviewed before this chat was closed and"
          + " have expired. Ask again to get a fresh proposal.");
    }
    refreshCost();
  }

  private void send() {
    String prompt = composer.prompt();
    if (prompt.isEmpty() || running != null) {
      return;
    }
    refreshHeader();
    ChatModel model;
    List<Attachment> attachments = new ArrayList<>();
    EclipseProject project;
    Provider provider;
    String keyProblem;
    try {
      Connection connection = AiSettings.current();
      provider = connection.provider();
      keyProblem = provider.keyProblem(connection.apiKey());
      model = ChatModels.create(connection);
      List<Attachment> attached = composer.attached();
      Attachment open = composer.includeOpenFile() ? ActiveFile.capture(getSite().getPage()) : null;
      if (open != null && attached.stream().noneMatch(a -> a.kind() == Attachment.Kind.FILE
          && a.path().equals(open.path()) && Objects.equals(a.project(), open.project()))) {
        attachments.add(open);
      }
      attachments.addAll(attached);
      project = EclipseProject.find(getSite().getPage());
    } catch (Exception e) {
      append("Error", Errors.describe(e));
      return;
    }

    if (chatId == null) {
      chatId = ChatStore.newId();
      chatTitle = ChatStore.titleFrom(prompt);
      chatCreated = Instant.now().toString();
      chatProject = project == null ? null : project.name();
    }
    String usageChat = chatId;
    String usageProject = project == null ? chatProject : project.name();

    ChatMode mode = composer.mode();
    List<String> context = new ArrayList<>();
    context.add(mode.label);
    for (Attachment attachment : attachments) {
      context.add(attachment.label());
    }
    context.add(project == null ? "no project open, so no file access" : "project " + project.name());
    appendYou(prompt, String.join(", ", context));
    composer.setPrompt("");
    composer.clearAttached();
    composer.setBusy(true);

    Display display = transcript.control().getDisplay();
    AtomicBoolean cancelled = new AtomicBoolean();
    long started = System.nanoTime();
    CostMeter answerCost = new CostMeter();
    Progress progress = new Progress() {
      @Override
      public void step(String message) {
        display.asyncExec(() -> {
          if (!transcript.control().isDisposed() && !cancelled.get()) {
            appendStep(message);
          }
        });
      }

      // Counted as each call returns, so cancelled and failed answers still show what they cost.
      @Override
      public void answered(CallUsage usage, long seconds) {
        Pricing.CallCost cost = Pricing.cost(provider, usage);
        answerCost.add(cost);
        chatCost.add(cost);
        CostMeter.SESSION.add(cost);
        try {
          Stores.usage().append(usageChat, usageProject, provider.name(), cost);
        } catch (Exception e) {
          Activator.logError("Could not write the MuleSoftForge AI cost ledger", e);
        }
        display.asyncExec(() -> {
          if (transcript.control().isDisposed()) {
            return;
          }
          if (!chatCounted) {
            chatCounted = true;
            CostMeter.SESSION.countChat();
          }
          if (!cancelled.get()) {
            appendStep("Model answered in " + seconds + " s · " + CostMeter.describe(cost));
          }
          refreshCost();
        });
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }
    };
    Job job = Job.create("MuleSoftForge AI", (IProgressMonitor monitor) -> {
      ChatSession.Turn turn = null;
      String error = null;
      try {
        turn = session.send(model, mode, prompt, attachments, project, progress);
      } catch (Exception e) {
        if (!cancelled.get()) {
          Activator.logError("MuleSoftForge AI request failed", e);
          error = (keyProblem == null ? "" : keyProblem + " Check the key in Settings.\n\n") + Errors.describe(e);
          if (attachments.stream().anyMatch(a -> a.kind() == Attachment.Kind.IMAGE)) {
            error += "\n\nThis message included an image. If the model " + model.defaultRequestParameters().modelName()
                + " cannot read images, pick one that can (for example a Claude, GPT-4o/GPT-5 or Gemini model,"
                + " or a vision model such as llava in Ollama), or remove the image and send again.";
          }
        }
      }
      ChatSession.Turn result = turn;
      String failure = error;
      long seconds = (System.nanoTime() - started) / 1_000_000_000;
      display.asyncExec(() -> {
        if (transcript.control().isDisposed()) {
          return;
        }
        String spent = answerCost.isEmpty() ? "" : " · this answer " + answerCost.summary();
        if (cancelled.get()) {
          session.noteCancelled(prompt);
          append("Note", "Cancelled after " + seconds + " s" + spent + ". The AI will be told this request was dropped.");
        } else if (result == null) {
          append("Error", failure);
          if (!spent.isEmpty()) {
            appendStep("Spent before the error" + spent);
          }
        } else {
          appendMarkdown("AI", result.text());
          appendStep("Done in " + seconds + " s · " + result.activity() + spent);
          if (!result.problems().isEmpty()) {
            append("Note", "The project checks still find problems in these proposals. Look at them before you"
                + " accept, or ask the AI to fix them:\n" + MuleChecks.describe(result.problems()));
          }
          changes.add(project, result.changes().all());
          if (mode == ChatMode.PLAN && project != null) {
            transcript.action("Build this plan in Agent mode", BUILD_PLAN);
          }
        }
        running = null;
        composer.setBusy(false);
        saveChat();
      });
      return Status.OK_STATUS;
    });
    job.setUser(false);
    running = new Running(job, cancelled);
    job.schedule();
  }

  private void cancel() {
    Running current = running;
    if (current == null) {
      return;
    }
    current.cancelled().set(true);
    Thread thread = current.job().getThread();
    if (thread != null) {
      thread.interrupt();
    }
    composer.setCancelling();
  }

  private void record(String speaker, String text) {
    if (!replaying) {
      entries.add(new ChatRecord.Entry(speaker, text));
    }
  }

  private void append(String speaker, String text) {
    record(speaker, text);
    transcript.message(speaker, text, null);
  }

  // Saved as one entry ("prompt\n[context]") so older saved chats replay the same way.
  private void appendYou(String prompt, String context) {
    record("You", prompt + "\n[" + context + "]");
    transcript.message("You", prompt, context);
  }

  private void appendMarkdown(String speaker, String markdown) {
    record(speaker, markdown);
    transcript.markdown(speaker, markdown);
  }

  private void appendStep(String text) {
    record("Step", text);
    transcript.step(text);
  }

  private void insertAtCursor(String code) {
    IWorkbenchPage page = getSite().getPage();
    IEditorPart editor = page.getActiveEditor();
    ITextEditor textEditor = ActiveFile.textEditor(editor);
    if (textEditor == null) {
      append("Note", "Open a text editor, or the Configuration XML tab of the Mule editor, place the cursor, then click Insert.");
      return;
    }
    try {
      IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
      ITextSelection selection = (ITextSelection) textEditor.getSelectionProvider().getSelection();
      document.replace(selection.getOffset(), selection.getLength(), code);
      page.activate(editor);
      textEditor.selectAndReveal(selection.getOffset(), code.length());
      appendStep("Inserted into " + editor.getTitle() + " (unsaved; Cmd/Ctrl+Z undoes it)");
    } catch (Exception e) {
      append("Error", "Could not insert: " + Errors.describe(e));
    }
  }

  private void refreshCost() {
    CostMeter session = CostMeter.SESSION;
    int chats = session.chats();
    UsageLog ledger = Stores.usage();
    costLabel.setText("This chat: " + chatCost.summary() + "     Today: " + CostMeter.describe(ledger.today())
        + "     This month: " + CostMeter.describe(ledger.thisMonth()));
    String chatModels = chatCost.breakdown();
    String sessionModels = session.breakdown();
    String project = chatProject == null ? "" : "\nProject " + chatProject + ", all time: "
        + CostMeter.describe(ledger.project(chatProject));
    costLabel.setToolTipText("This chat\n" + (chatModels.isEmpty() ? "nothing yet" : chatModels)
        + "\n\nStudio session (since Studio started), " + chats + (chats == 1 ? " chat" : " chats") + "\n"
        + (sessionModels.isEmpty() ? "nothing yet" : sessionModels)
        + "\n\nAll time in this workspace: " + CostMeter.describe(ledger.allTime()) + project
        + "\n\nOpenRouter amounts are what OpenRouter billed. Amounts marked ~ are estimated from list prices at "
        + Pricing.PRICE_LIST_URL + ". Ollama is free.");
    costLabel.getParent().layout();
  }

  private void refreshHeader() {
    try {
      Connection connection = AiSettings.current();
      header.setText(connection.provider().label);
    } catch (Exception e) {
      header.setText("Secure storage unavailable: " + Errors.describe(e));
    }
    header.getParent().layout();
  }

  private void openSettings() {
    PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiPreferencePage.ID, null, null).open();
  }
}
