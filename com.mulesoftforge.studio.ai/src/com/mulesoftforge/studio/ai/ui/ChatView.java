package com.mulesoftforge.studio.ai.ui;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.compare.CompareUI;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.service.event.EventHandler;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.debug.DebugStop;
import com.mulesoftforge.studio.ai.edit.ChangeOffer;
import com.mulesoftforge.studio.ai.edit.FollowUpAsk;
import com.mulesoftforge.studio.ai.edit.LineDiff;
import com.mulesoftforge.studio.ai.edit.TodoItem;
import com.mulesoftforge.studio.ai.feedback.LinkedInPost;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.http.HttpTools;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.CodeBlocks;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.ContextWindow;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.DataWeaveFix;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.RawCapture;
import com.mulesoftforge.studio.ai.render.Alerts;
import com.mulesoftforge.studio.ai.render.HtmlPage;
import com.mulesoftforge.studio.ai.render.LoadingPhrases;
import com.mulesoftforge.studio.ai.render.Palette;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.Provider;

// The MuleSoftForge Studio AI view: runs each message, records the chat for saving, and tracks its cost.
// Layout pieces live in Composer, ChangesPanel, ChatHistoryMenu and the Transcript implementations.
public class ChatView extends ViewPart {
  public static final String ID = "com.mulesoftforge.studio.ai.chat";

  private record DataWeaveProposal(WeakReference<DataWeaveScript> scriptRef, String fixed, String where,
      String originalText) {
    DataWeaveScript script() {
      return scriptRef == null ? null : scriptRef.get();
    }
  }

  private static final String BUILD_PLAN = "build-plan";
  private static final String MAKE_CHANGES = "make-changes";
  private static final String SUGGEST = "suggest";
  private static final String OFFER = "offer:";
  private static final String ASK = "ask:";
  private static final String DATAWEAVE_DIFF = "dw-diff:";
  private static final String DATAWEAVE_APPLY = "dw-apply:";
  private static final String OPEN_RAW = "raw-log";
  private static final String DIAGNOSE_INSTEAD = "diagnose-instead";
  private static final String VERIFY_FIX = "verify-fix";
  private static final String CONTINUE = "continue";
  private static final String RETRY = "retry";
  private static final String EDIT_LAST = "edit-last";
  private static final String FEEDBACK_UP = "feedback:up";
  private static final String FEEDBACK_DOWN = "feedback:down";
  private static final String APPROVE_PLAN = "approve-plan";

  // Fixes offered under answers in this chat, by the index in their Review diff / Apply buttons.
  private final List<DataWeaveProposal> dataWeaveFixes = new ArrayList<>();
  // Checklists of changes the AI offered to make, by the index in their Draft selected changes button.
  private final List<List<ChangeOffer>> offers = new ArrayList<>();
  // Follow-up questions from ask_followup_question this chat, by ask id.
  private final List<FollowUpAsk> asks = new ArrayList<>();
  // Checklists that came from Diagnose mode; drafting one offers to check the fix afterwards.
  private final Set<Integer> diagnosedOffers = new HashSet<>();
  // The next message builds a fix from a diagnosis.
  private boolean buildingDiagnosis;
  // Diagnose mode is suggested once per chat, for this message.
  private String diagnosePrompt;
  private boolean diagnoseSuggested;
  // The app console Diagnose mode last attached by itself, so the same output is not sent again.
  private String autoConsole;

  private final ChatSession session = new ChatSession();
  private final CostMeter chatCost = new CostMeter();
  private boolean chatCounted;
  private ChatJob running;
  private Job pendingSave;

  // The chat being shown; id is null until the first message is sent.
  private String chatId;
  private String chatTitle;
  private String chatCreated;
  private String chatProject;
  // The model Auto used for this chat's last message.
  private String chatAuto;
  // The model call in progress, shown in the transcript's live line and ticked every second.
  private String liveState;
  private String livePreview = "";
  private long liveSince;
  // Where this turn's loading phrases start, so each turn does not open with the same one.
  private int liveSeed;
  private final Runnable liveTicker = this::tickLive;
  private Attention attention;
  // The mode of the turn that paused at the step limit, which Continue resumes in.
  private ChatMode continueMode;
  // The model of the last call, whose window the context size is shown against.
  private String contextModel;
  private final List<ChatRecord.Entry> entries = new ArrayList<>();
  private boolean replaying;
  // Messages sent in this chat; raw log calls carry the number of the turn they belong to.
  private int turns;

  private Composite banner;
  private Label header;
  private Button rawToggle;
  private StatusPanel status;
  private Transcript transcript;
  private ChangesPanel changes;
  private Composer composer;
  private DebugBar debugBar;
  private Runnable debuggerListener;
  private Composite root;
  private Runnable stopWatchingSettings;
  private EventHandler themeHandler;
  private IEventBroker eventBroker;
  private final AtomicBoolean settingsRefreshPending = new AtomicBoolean();

  @Override
  public void createPartControl(Composite parent) {
    root = parent;
    GridLayout rootLayout = new GridLayout(1, false);
    rootLayout.marginWidth = 0;
    rootLayout.marginHeight = 0;
    rootLayout.verticalSpacing = 0;
    parent.setLayout(rootLayout);
    IWorkbenchPage page = getSite().getPage();
    ThemeStyles.applyClass(parent, "MsfRoot");

    // A dark-blue banner: provider and Settings on the left; version, Raw, History and Feedback on the right.
    Composite top = new Composite(parent, SWT.NONE);
    banner = top;
    GridLayout topLayout = new GridLayout(7, false);
    topLayout.marginHeight = 6;
    topLayout.marginWidth = 10;
    top.setLayout(topLayout);
    top.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    header = new Label(top, SWT.NONE);
    header.setFont(TranscriptStyles.bold());
    Link settings = new Link(top, SWT.NONE);
    settings.setText("<a>Settings</a>");
    settings.setToolTipText("Provider, model, API key, network and chat history");
    settings.addListener(SWT.Selection, e -> openSettings());
    new Label(top, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new VersionLink(top, page);
    rawToggle = new Button(top, SWT.CHECK);
    rawToggle.setText("Raw");
    rawToggle.setToolTipText("Record every request and response from now on, and show them under each answer."
        + " Exact HTTP bodies, URL and status; never headers, so API keys stay out.");
    rawToggle.setSelection(AiSettings.rawLog());
    rawToggle.addListener(SWT.Selection, e -> toggleRaw(rawToggle.getSelection()));
    Link history = new Link(top, SWT.NONE);
    history.setText("<a>History ▾</a>");
    history.setToolTipText("Saved chats in this workspace");
    ChatHistoryMenu historyMenu = new ChatHistoryMenu(parent.getShell(), new HistoryHost());
    history.addListener(SWT.Selection, e -> historyMenu.show(history));
    Link feedback = new Link(top, SWT.NONE);
    feedback.setText("<a>Feedback</a>");
    feedback.setToolTipText("Report a bug or send feedback to the MuleSoftForge team, as a public GitHub issue");
    feedback.addListener(SWT.Selection, e -> new FeedbackDialog(getSite().getShell()).open());
    styleBanner();

    // The product site's blue-to-purple line under the banner.
    Canvas accent = new Canvas(parent, SWT.NO_BACKGROUND);
    GridData accentData = new GridData(SWT.FILL, SWT.TOP, true, false);
    accentData.heightHint = 2;
    accent.setLayoutData(accentData);
    Color accentFrom = new Color(0, 163, 224);
    Color accentTo = new Color(109, 93, 252);
    accent.addPaintListener(e -> {
      Rectangle area = accent.getClientArea();
      e.gc.setForeground(accentFrom);
      e.gc.setBackground(accentTo);
      e.gc.fillGradientRectangle(0, 0, area.width, area.height, false);
    });

    // The transcript and, beside it, the model and cost. Drag the divider to resize.
    SashForm middle = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
    middle.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    ThemeStyles.applyClass(middle, "MsfDivider");
    transcript = HtmlTranscript.create(middle, new TranscriptActions(), AiSettings.richTranscript());
    transcript.showRaw(AiSettings.rawLog());
    status = new StatusPanel(middle, () -> composer.refreshPrices(), this::compactChat);
    middle.setWeights(78, 22);
    middle.setSashWidth(1);

    changes = new ChangesPanel(parent, page, AiSettings.richTranscript(), new ChangesPanel.Listener() {
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

    debugBar = new DebugBar(parent, this::askAboutDebugEvent);
    composer = new Composer(parent, page, AiSettings.richTranscript(), new Composer.Listener() {
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

      @Override
      public void modelShown(String model, String price, String priceTable, StatusPanel.PriceList prices) {
        if (!status.isDisposed()) {
          status.showModel(model, price, priceTable);
          status.showPrices(prices);
        }
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
    StudioDebugger debugger = StudioDebugger.get();
    debugger.start();
    debuggerListener = () -> Ui.async(display, root, () -> debugBar.show(debugger.current()));
    debugger.addListener(debuggerListener);
    debugBar.show(debugger.current());
    session.onLateResult(late -> Ui.async(display, root, () -> append("Note", late.request() + " answered after"
        + " the AI stopped waiting. Result: " + late.summary() + ". The AI sees the full response with your next"
        + " message.")));
    stopWatchingSettings = AiSettings.onChange(key -> {
      if (AiSettings.affectsModels(key) && settingsRefreshPending.compareAndSet(false, true)) {
        Ui.async(display, header, this::settingsChanged);
      }
      if (AiSettings.affectsRawLog(key)) {
        Ui.async(display, rawToggle, this::rawSettingChanged);
      }
    });

    eventBroker = PlatformUI.getWorkbench().getService(IEventBroker.class);
    if (eventBroker != null) {
      themeHandler = event -> applyTheme();
      eventBroker.subscribe(IThemeEngine.Events.THEME_CHANGED, themeHandler);
    }
    // CSS may style widgets after createPartControl; re-apply once the skin event has run.
    display.asyncExec(this::applyTheme);

    attention = new Attention(this);
    refreshHeader();
    refreshCost();
    pruneOldChats();
  }

  private void applyTheme() {
    if (root == null || root.isDisposed()) {
      return;
    }
    Palette palette = ThemeStyles.palette();
    if (transcript != null) {
      transcript.applyTheme(palette, ThemeStyles.background(), ThemeStyles.foreground());
    }
    if (status != null && !status.isDisposed()) {
      status.applyTheme();
    }
    if (composer != null) {
      composer.applyTheme();
    }
    if (changes != null) {
      changes.applyTheme();
    }
    styleBanner();
  }

  // The theme's CSS gives the banner and its labels their colours; link colours are not CSS, so white is set here.
  private void styleBanner() {
    if (banner == null || banner.isDisposed()) {
      return;
    }
    ThemeStyles.applyClass(banner, "MsfBanner");
    Color white = banner.getDisplay().getSystemColor(SWT.COLOR_WHITE);
    for (Control child : banner.getChildren()) {
      ThemeStyles.applyClass(child, "MsfBanner");
      if (child instanceof Link link) {
        link.setLinkForeground(white);
      }
    }
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

  private void rawSettingChanged() {
    if (rawToggle.isDisposed()) {
      return;
    }
    boolean on = AiSettings.rawLog();
    rawToggle.setSelection(on);
    transcript.showRaw(on);
  }

  private void toggleRaw(boolean on) {
    try {
      AiSettings.saveRawLog(on);
    } catch (Exception e) {
      append("Error", "Could not save the Raw setting: " + Errors.describe(e));
    }
    transcript.showRaw(on);
    if (on) {
      transcript.step("Raw is on: each request and response is recorded from the next message, without headers");
    }
  }

  // The calls of one turn, collapsed under its answer.
  private void showRawTurn(List<RawCapture.Call> calls) {
    if (calls == null || calls.isEmpty()) {
      return;
    }
    List<Transcript.RawCall> shown = calls.stream()
        .map(c -> new Transcript.RawCall(c.heading(), c.requestText(), c.responseText()))
        .toList();
    transcript.raw("Raw · " + calls.size() + (calls.size() == 1 ? " call" : " calls"), shown,
        chatId == null ? null : OPEN_RAW);
  }

  @Override
  public void dispose() {
    if (running != null) {
      running.cancel();
      running = null;
    }
    if (pendingSave != null) {
      pendingSave.cancel();
      pendingSave = null;
    }
    if (stopWatchingSettings != null) {
      stopWatchingSettings.run();
    }
    if (debuggerListener != null) {
      StudioDebugger.get().removeListener(debuggerListener);
    }
    if (eventBroker != null && themeHandler != null) {
      eventBroker.unsubscribe(themeHandler);
    }
    if (attention != null) {
      attention.dispose();
    }
    super.dispose();
  }

  @Override
  public void setFocus() {
    composer.focus();
  }

  // Used by the Send to MuleSoftForge Studio AI command and menu items.
  void attach(Attachments.Result result) {
    composer.attach(result);
  }

  void attach(Attachments.Result result, String promptIfEmpty) {
    composer.attach(result, promptIfEmpty);
  }

  // Attaches the event Studio's debugger is stopped at. Finding the processor's XML reads files, so it runs in a job.
  void askAboutDebugEvent() {
    StudioDebugger debugger = StudioDebugger.get();
    DebugStop stop = debugger.current();
    if (stop == null) {
      append("Note", debugger.available()
          ? "Studio's debugger is not stopped at a processor now. Stop at a breakpoint, then ask again."
          : "MuleSoftForge Studio AI cannot follow Studio's Mule debugger in this Studio.");
      return;
    }
    Display display = root.getDisplay();
    Job.create("Reading the event the debugger stopped at", monitor -> {
      Attachment event = DebugEvents.attachment(stop);
      Ui.async(display, root, () -> composer.attach(new Attachments.Result(List.of(event), List.of())));
      return Status.OK_STATUS;
    }).schedule();
  }

  // Fix with MuleSoftForge Studio AI in a DataWeave editor: sends the script and the errors the editor shows, in Ask mode
  // because the fix goes back into the editor rather than into a file. The answer gets Review diff and Apply.
  void fixDataWeave(DataWeaveScript script) throws Exception {
    if (running != null) {
      append("Note", "Wait for the current answer, or cancel it, then choose Fix with MuleSoftForge Studio AI again.");
      return;
    }
    List<Attachment.Problem> problems = script.problems();
    List<Attachment> attached = new ArrayList<>();
    attached.add(script.attachment(false));
    if (!problems.isEmpty()) {
      attached.add(Attachment.problems(script.project(), DataWeaveScript.PROBLEMS_SCOPE, problems));
    }
    composer.setPrompt(DataWeaveFix.prompt(script.selectedLines(), !problems.isEmpty()));
    composer.attach(new Attachments.Result(attached, List.of()));
    send(ChatMode.ASK, script);
  }

  private final class TranscriptActions implements Transcript.Actions {
    @Override
    public void copy(String shown) {
      String code = session.unmask(shown);
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
      insertAtCursor(session.unmask(code));
    }

    @Override
    public void open(String url) {
      try {
        IWorkbenchBrowserSupport support = PlatformUI.getWorkbench().getBrowserSupport();
        support.getExternalBrowser().openURL(java.net.URI.create(url).toURL());
      } catch (Exception e) {
        append("Error", "Could not open the link: " + Errors.describe(e));
      }
    }

    @Override
    public void fillComposer(String text) {
      if (text != null && !text.isBlank()) {
        composer.setPrompt(text);
        composer.focus();
      }
    }

    @Override
    public void quote(String text) {
      if (text == null || text.isBlank()) {
        return;
      }
      String quoted = text.lines().map(line -> "> " + line).reduce((a, b) -> a + "\n" + b).orElse("> " + text);
      String current = composer.prompt();
      composer.setPrompt(current.isEmpty() ? quoted + "\n\n" : quoted + "\n\n" + current);
      composer.focus();
    }

    @Override
    public void command(String id) {
      if (BUILD_PLAN.equals(id) || APPROVE_PLAN.equals(id)) {
        sendInAgentMode(ChatMode.BUILD_PLAN_PROMPT);
      } else if (MAKE_CHANGES.equals(id)) {
        sendInAgentMode(ChatMode.MAKE_CHANGES_PROMPT);
      } else if (SUGGEST.equals(id)) {
        sendInCurrentMode(ChatMode.SUGGEST_PROMPT);
      } else if (DIAGNOSE_INSTEAD.equals(id) && diagnosePrompt != null) {
        sendInMode(ChatMode.DIAGNOSE, diagnosePrompt);
      } else if (CONTINUE.equals(id) && continueMode != null) {
        sendInMode(continueMode, ChatMode.CONTINUE_PROMPT);
      } else if (VERIFY_FIX.equals(id)) {
        sendInMode(ChatMode.DIAGNOSE, ChatMode.VERIFY_PROMPT);
      } else if (RETRY.equals(id)) {
        retryLast();
      } else if (EDIT_LAST.equals(id)) {
        editLast();
      } else if (FEEDBACK_UP.equals(id)) {
        appendStep("Thanks! Opening a LinkedIn post you can edit, or close without posting.");
        open(LinkedInPost.url());
      } else if (FEEDBACK_DOWN.equals(id)) {
        appendStep("Sorry it missed. Opening feedback.");
        new FeedbackDialog(getSite().getShell(), "An answer missed").open();
      } else if (id.startsWith(ASK)) {
        answerAsk(id.substring(ASK.length()));
      } else if (id.startsWith(OFFER)) {
        String value = id.substring(OFFER.length());
        List<ChangeOffer> picked = pickedOffers(value);
        if (picked != null && running == null) {
          buildingDiagnosis = diagnosedOffers.contains(checklist(value));
          sendInAgentMode(ChangeOffer.prompt(picked));
        }
      } else if (id.startsWith(DATAWEAVE_DIFF)) {
        DataWeaveProposal proposal = dataWeaveFix(id.substring(DATAWEAVE_DIFF.length()));
        if (proposal != null) {
          DataWeaveScript script = proposal.script();
          String before = script == null ? proposal.originalText() : script.text();
          CompareUI.openCompareDialog(ChangeCompareInput.dataWeave(proposal.where(), before, proposal.fixed()));
        }
      } else if (OPEN_RAW.equals(id) && chatId != null) {
        try {
          ChatHistoryMenu.openRawLog(chatId);
        } catch (Exception e) {
          append("Error", "Could not open the raw log: " + Errors.describe(e));
        }
      } else if (id.startsWith(DATAWEAVE_APPLY)) {
        DataWeaveProposal proposal = dataWeaveFix(id.substring(DATAWEAVE_APPLY.length()));
        if (proposal != null) {
          applyDataWeaveFix(proposal);
        }
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
    dataWeaveFixes.clear();
    offers.clear();
    asks.clear();
    composer.clearQueue();
    diagnosedOffers.clear();
    buildingDiagnosis = false;
    diagnosePrompt = null;
    diagnoseSuggested = false;
    autoConsole = null;
    chatCost.reset();
    chatCounted = false;
    entries.clear();
    turns = 0;
    chatId = null;
    chatTitle = null;
    chatCreated = null;
    chatProject = null;
    chatAuto = null;
    contextModel = null;
    continueMode = null;
    transcript.clear();
    refreshCost();
    refreshContext();
  }

  private void compactChat() {
    if (!session.canCompact()) {
      return;
    }
    session.compactBeforeNext();
    appendStep("The older turns will be summarised before your next message, where the summary reads the chat"
        + " from the prompt cache.");
  }

  private void refreshContext() {
    if (status.isDisposed()) {
      return;
    }
    String model = contextModel;
    if (model == null) {
      try {
        model = chatAuto != null ? chatAuto : AiSettings.model(AiSettings.provider());
      } catch (Exception e) {
        model = null;
      }
    }
    status.showContext(session.contextTokens(), ContextWindow.of(model), session.canCompact());
  }

  private void pruneOldChats() {
    int days = AiSettings.retentionDays();
    Job job = Job.create("Prune MuleSoftForge Studio AI chats", monitor -> {
      try {
        Stores.chats().prune(days, Instant.now());
        Stores.raw().prune(days, Instant.now());
      } catch (Exception e) {
        Activator.logFailure("Could not prune saved chats", e);
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
        changes.size(), chatAuto);
    if (pendingSave != null) {
      pendingSave.cancel();
    }
    Job job = Job.create("Save MuleSoftForge Studio AI chat", monitor -> {
      if (monitor.isCanceled()) {
        return Status.CANCEL_STATUS;
      }
      try {
        Stores.chats().save(record);
      } catch (Exception e) {
        Activator.logFailure("Could not save chat " + record.id(), e);
      }
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    pendingSave = job;
    job.schedule(200);
  }

  private void openChat(String id) {
    Display display = transcript.control().getDisplay();
    Job job = Job.create("Open MuleSoftForge Studio AI chat", monitor -> {
      ChatRecord record;
      Map<Integer, List<RawCapture.Call>> raw;
      try {
        record = Stores.chats().load(id);
        raw = rawCalls(record.id());
      } catch (Exception e) {
        Ui.async(display, transcript.control(),
            () -> append("Error", "Could not open chat: " + Errors.describe(e)));
        return Status.OK_STATUS;
      }
      ChatRecord loaded = record;
      Map<Integer, List<RawCapture.Call>> rawByTurn = raw;
      Ui.async(display, transcript.control(), () -> showLoadedChat(loaded, rawByTurn));
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  private void showLoadedChat(ChatRecord record, Map<Integer, List<RawCapture.Call>> raw) {
    resetChat();
    chatId = record.id();
    chatTitle = record.title();
    chatCreated = record.created();
    chatProject = record.project();
    chatAuto = record.auto();
    replaying = true;
    try {
      for (ChatRecord.Entry entry : record.entries()) {
        switch (entry.speaker()) {
          case "AI" -> appendMarkdown("AI", entry.text());
          case "You" -> {
            if (turns > 0) {
              showRawTurn(raw.get(turns));
            }
            turns++;
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
      showRawTurn(raw.get(turns));
    } finally {
      replaying = false;
    }
    entries.addAll(record.entries());
    session.restore(record.history());
    refreshContext();
    chatCost.restore(record.cost());
    if (record.pending() > 0) {
      append("Note", record.pending() + " proposed change(s) were not reviewed before this chat was closed and"
          + " have expired. Ask again to get a fresh proposal.");
    }
    refreshCost();
  }

  private Map<Integer, List<RawCapture.Call>> rawCalls(String id) {
    Map<Integer, List<RawCapture.Call>> byTurn = new TreeMap<>();
    try {
      for (RawCapture.Call call : Stores.raw().load(id)) {
        byTurn.computeIfAbsent(call.turn(), t -> new ArrayList<>()).add(call);
      }
    } catch (Exception e) {
      Activator.logFailure("Could not read the raw log of chat " + id, e);
    }
    return byTurn;
  }

  private void send() {
    send(composer.mode(), null);
  }

  // With fix, the answer is checked for a corrected script to offer back to that DataWeave editor.
  private void send(ChatMode mode, DataWeaveScript fix) {
    String prompt = composer.prompt();
    if (prompt.isEmpty() || running != null) {
      return;
    }
    refreshHeader();
    String id = chatId != null ? chatId : ChatStore.newId();
    List<Attachment> attachments = new ArrayList<>();
    EclipseProject project;
    AutoRouter.Choice auto = null;
    boolean showModel;
    try {
      Provider provider = AiSettings.provider();
      String model = AiSettings.model(provider);
      List<Attachment> attached = composer.attached();
      Attachment open = composer.includeOpenFile() ? ActiveFile.capture(getSite().getPage()) : null;
      if (open != null && attached.stream().noneMatch(a -> a.kind() == Attachment.Kind.FILE
          && a.path().equals(open.path()) && Objects.equals(a.project(), open.project()))) {
        attachments.add(open);
      }
      attachments.addAll(attached);
      EclipseProject shown = EclipseProject.find(getSite().getPage());
      // With no editor open and nothing selected (after a restart, say), keep working on the chat's project.
      project = shown != null ? shown : EclipseProject.named(chatProject);
      if (mode == ChatMode.DIAGNOSE && project != null) {
        attachments.addAll(diagnoseContext(project, attachments));
      }
      if (AutoRouter.isAuto(model)) {
        auto = AutoRouter.choose(mode, prompt, attachments, fix != null, AiSettings.autoModels(provider), chatAuto);
        if (chatAuto != null && !chatAuto.equals(auto.model())) {
          session.modelChanged(chatAuto);
        }
        chatAuto = auto.model();
      }
      showModel = auto != null || model.endsWith("/auto");
    } catch (Exception e) {
      append("Error", Errors.classify(e, AiSettings.provider()).transcript());
      return;
    }

    boolean checkFixAfter = buildingDiagnosis && mode == ChatMode.AGENT;
    buildingDiagnosis = false;
    turns++;
    if (chatId == null) {
      chatId = id;
      chatTitle = ChatStore.titleFrom(prompt);
      chatCreated = Instant.now().toString();
      chatProject = project == null ? null : project.name();
    }
    String usageChat = chatId;
    String usageProject = project == null ? chatProject : project.name();
    Provider provider = AiSettings.provider();

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
    DataWeaveScript fixTarget = fix;
    AutoRouter.Choice autoChoice = auto;
    ChatJob job = new ChatJob(session, mode, prompt, attachments, project, id, turns, showModel, null, autoChoice,
        display, new ChatJob.Listener() {
          @Override
          public void step(String message) {
            if (!transcript.control().isDisposed()) {
              appendStep(message);
            }
          }

          @Override
          public void answered(CallUsage usage, Pricing.CallCost cost, long seconds, boolean showModel) {
            chatCost.add(cost);
            CostMeter.SESSION.add(cost);
            try {
              Stores.usage().append(usageChat, usageProject, provider.name(), cost);
            } catch (Exception e) {
              Activator.logFailure("Could not write the MuleSoftForge Studio AI cost ledger", e);
            }
            if (transcript.control().isDisposed()) {
              return;
            }
            if (!chatCounted) {
              chatCounted = true;
              CostMeter.SESSION.countChat();
            }
            appendStep("Model answered in " + seconds + " s" + (showModel ? " · " + usage.model() : "") + " · "
                + CostMeter.describe(cost));
            refreshCost();
          }

          @Override
          public void live(String state, String preview, long since) {
            if (transcript.control().isDisposed() || running == null) {
              return;
            }
            boolean starting = liveState == null && state != null;
            liveState = state;
            livePreview = preview;
            liveSince = since;
            showLive();
            if (starting) {
              display.timerExec(1000, liveTicker);
            }
          }

          @Override
          public void finished(ChatSession.Turn result, Errors.UserError failure, boolean cancelled, long seconds,
              CostMeter answerCost, List<RawCapture.Call> rawCalls) {
            if (transcript.control().isDisposed()) {
              return;
            }
            liveState = null;
            transcript.live(null, null);
            String spent = answerCost.isEmpty() ? "" : " · this answer " + answerCost.summary();
            if (cancelled) {
              session.noteCancelled(prompt);
              append("Note", "Cancelled after " + seconds + " s" + spent
                  + ". The AI will be told this request was dropped.");
            } else if (result == null) {
              append("Error", failure == null ? "Request failed." : failure.transcript());
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
              int drafted = result.changes().all().size();
              if (drafted > 0) {
                appendStep("Drafted changes to " + drafted + (drafted == 1 ? " file" : " files")
                    + ". Review the diffs below; nothing is written until you accept.");
              }
              if (!result.todos().isEmpty()) {
                transcript.todos(result.todos());
              }
              if (result.ask() != null) {
                transcript.ask(String.valueOf(asks.size()), result.ask().question(), result.ask().choices());
                asks.add(result.ask());
              }
              if (result.stoppedAtLimit()) {
                continueMode = mode;
                transcript.action("Continue after " + ChatSession.MAX_TOOL_ROUNDS
                    + " steps — let the AI carry on from where it paused", CONTINUE);
              }
              if (checkFixAfter && drafted > 0) {
                transcript.action("After you accept and Studio redeploys, check the fix in Diagnose mode",
                    VERIFY_FIX);
              }
              if (project != null && !result.offers().isEmpty()) {
                if (mode == ChatMode.DIAGNOSE) {
                  diagnosedOffers.add(offers.size());
                }
                transcript.offer(String.valueOf(offers.size()),
                    result.offers().stream().map(ChangeOffer::label).toList(), mode == ChatMode.ASK);
                offers.add(result.offers());
                if (mode == ChatMode.PLAN) {
                  transcript.action("Approve plan and draft in Agent mode", APPROVE_PLAN);
                }
              } else if (mode == ChatMode.PLAN && project != null) {
                transcript.action("Draft this plan for review in Agent mode", BUILD_PLAN);
              } else if (project != null && fixTarget == null && result.changes().isEmpty()) {
                if (CodeBlocks.hasProjectCode(result.text())) {
                  transcript.action("Draft these changes as diffs", MAKE_CHANGES);
                } else if (result.text().contains("```") || result.toolCounts().containsKey(HttpTools.CALL)) {
                  transcript.action("See possible improvements", SUGGEST);
                }
              }
              if (!diagnoseSuggested && project != null && fixTarget == null && result.changes().isEmpty()
                  && (mode == ChatMode.ASK || mode == ChatMode.AGENT) && ChatMode.reportsProblem(prompt)) {
                diagnoseSuggested = true;
                diagnosePrompt = prompt;
                transcript.action("Find the cause in Diagnose mode (reproduces it and reads the console)",
                    DIAGNOSE_INSTEAD);
              }
              if (fixTarget != null) {
                offerDataWeaveFix(fixTarget, result.text());
              }
            }
            showRawTurn(rawCalls);
            if (result != null && !result.calls().isEmpty()) {
              contextModel = result.calls().get(result.calls().size() - 1).model();
            }
            refreshContext();
            running = null;
            composer.setBusy(false);
            saveChat();
            if (!cancelled && attention != null) {
              boolean waits = result != null && (result.ask() != null
                  || (mode == ChatMode.PLAN && project != null) || result.stoppedAtLimit());
              attention.ended(result == null ? Alerts.Kind.ERROR : waits ? Alerts.Kind.QUESTION : Alerts.Kind.ANSWER,
                  session.sessionId());
            }
            flushQueue();
          }

          @Override
          public void todos(List<TodoItem> items) {
            if (!transcript.control().isDisposed()) {
              transcript.todos(items);
            }
          }

          // Saved chats keep the summary line only, like the other steps.
          @Override
          public void thought(String text, long millis) {
            if (!transcript.control().isDisposed()) {
              String summary = Progress.thoughtFor(millis);
              record("Step", summary);
              transcript.thought(summary, text);
            }
          }
        });
    running = job;
    liveSeed = ThreadLocalRandom.current().nextInt(1000);
    // Through the site's progress service, so the view's tab shows it is busy while the turn runs.
    IWorkbenchSiteProgressService progress = getSite().getService(IWorkbenchSiteProgressService.class);
    if (progress != null) {
      progress.schedule(job);
    } else {
      job.schedule();
    }
  }

  private void flushQueue() {
    if (running != null || !composer.hasQueued()) {
      return;
    }
    String next = composer.pollQueued();
    if (next != null && !next.isBlank()) {
      composer.setPrompt(next);
      send();
    }
  }

  private void answerAsk(String value) {
    int colon = value.indexOf(':');
    if (colon < 0 || running != null) {
      return;
    }
    try {
      FollowUpAsk ask = asks.get(Integer.parseInt(value.substring(0, colon)));
      int index = Integer.parseInt(value.substring(colon + 1));
      if (index < 0 || index >= ask.choices().size()) {
        return;
      }
      sendInCurrentMode(ask.choices().get(index));
    } catch (RuntimeException e) {
      append("Note", "That question was from an earlier chat. Ask again if you still need to choose.");
    }
  }

  private void retryLast() {
    if (running != null) {
      append("Note", "Wait for the current answer, or cancel it, then retry.");
      return;
    }
    String prompt = lastYouPrompt();
    if (prompt == null || session.dropLastExchange() == null) {
      append("Note", "Nothing to retry yet.");
      return;
    }
    trimLastYouEntries();
    appendStep("Retrying the last message (model history rolled back one turn).");
    composer.setPrompt(prompt);
    send();
  }

  private void editLast() {
    if (running != null) {
      append("Note", "Wait for the current answer, or cancel it, then edit.");
      return;
    }
    String prompt = lastYouPrompt();
    if (prompt == null || session.dropLastExchange() == null) {
      append("Note", "Nothing to edit yet.");
      return;
    }
    trimLastYouEntries();
    appendStep("Last turn removed from the model history. Edit and send again."
        + " Accepted files are not rolled back automatically — use Local History if needed.");
    composer.setPrompt(prompt);
    composer.focus();
  }

  // Prompt text of the newest You entry, without the trailing [context] ChatView appends.
  private String lastYouPrompt() {
    for (int i = entries.size() - 1; i >= 0; i--) {
      ChatRecord.Entry entry = entries.get(i);
      if (!"You".equals(entry.speaker())) {
        continue;
      }
      String text = entry.text();
      int marker = text.lastIndexOf("\n[");
      return marker > 0 ? text.substring(0, marker) : text;
    }
    return null;
  }

  // Drops transcript entries from the last You through the end so a retry does not leave a ghost turn.
  private void trimLastYouEntries() {
    int start = -1;
    for (int i = entries.size() - 1; i >= 0; i--) {
      if ("You".equals(entries.get(i).speaker())) {
        start = i;
        break;
      }
    }
    if (start >= 0) {
      entries.subList(start, entries.size()).clear();
    }
  }

  // What Diagnose mode reads with every message without being asked: Studio's problems in the project, and the
  // app's console when it shows a failure and has changed since it was last sent. Nothing the user attached is
  // added twice.
  private List<Attachment> diagnoseContext(EclipseProject project, List<Attachment> attached) {
    List<Attachment> out = new ArrayList<>();
    if (attached.stream().noneMatch(a -> a.kind() == Attachment.Kind.PROBLEMS)
        && Problems.count(project.resource()) > 0) {
      out.addAll(Problems.attach(project.resource()).attached());
    }
    if (attached.stream().noneMatch(a -> a.kind() == Attachment.Kind.CONSOLE)) {
      AppConsole.Text console = Consoles.muleApp(project.name());
      if (console != null && !console.text().equals(autoConsole)) {
        Attachment output = Attachment.console(console.name(), console.text(), false);
        if (output.reportsFailure()) {
          autoConsole = console.text();
          out.add(output);
        }
      }
    }
    return out;
  }

  private void offerDataWeaveFix(DataWeaveScript script, String answer) {
    String fixed = session.unmask(DataWeaveFix.script(answer));
    if (fixed == null) {
      append("Note", "The answer has no DataWeave code block, so there is nothing to apply to the editor.");
      return;
    }
    if (fixed.strip().equals(script.text().strip())) {
      append("Note", "The proposed script is the same as the one in the editor.");
      return;
    }
    int index = dataWeaveFixes.size();
    dataWeaveFixes.add(new DataWeaveProposal(new WeakReference<>(script), fixed, script.where(), script.text()));
    transcript.action("Review diff", DATAWEAVE_DIFF + index);
    transcript.action("Apply to the DataWeave editor", DATAWEAVE_APPLY + index);
  }

  private void sendInAgentMode(String prompt) {
    sendInMode(ChatMode.AGENT, prompt);
  }

  private void sendInMode(ChatMode mode, String prompt) {
    if (running != null) {
      append("Note", "Wait for the current answer, or cancel it, then click again.");
      return;
    }
    composer.setMode(mode);
    composer.setPrompt(prompt);
    send();
  }

  // The checklist index in "<checklist index>:<ticked indices>", or -1.
  private static int checklist(String value) {
    int colon = value.indexOf(':');
    try {
      return Integer.parseInt(colon < 0 ? value : value.substring(0, colon));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // offer_changes works in every mode, so asking for suggestions does not switch to Agent.
  private void sendInCurrentMode(String prompt) {
    if (running != null) {
      append("Note", "Wait for the current answer, or cancel it, then click again.");
      return;
    }
    composer.setPrompt(prompt);
    send();
  }

  // "<checklist index>:<ticked indices>" from a Draft selected changes click.
  private List<ChangeOffer> pickedOffers(String value) {
    int colon = value.indexOf(':');
    List<ChangeOffer> offered;
    try {
      offered = offers.get(Integer.parseInt(value.substring(0, colon)));
    } catch (RuntimeException e) {
      append("Note", "These changes were offered in an earlier chat. Ask again to get a fresh list.");
      return null;
    }
    List<ChangeOffer> picked = new ArrayList<>();
    for (String index : value.substring(colon + 1).split(",")) {
      if (index.matches("\\d+") && Integer.parseInt(index) < offered.size()) {
        picked.add(offered.get(Integer.parseInt(index)));
      }
    }
    if (picked.isEmpty()) {
      append("Note", "Tick at least one change, then click " + HtmlPage.OFFER_BUTTON + ".");
      return null;
    }
    return picked;
  }

  private DataWeaveProposal dataWeaveFix(String index) {
    try {
      return dataWeaveFixes.get(Integer.parseInt(index));
    } catch (RuntimeException e) {
      append("Note", "This fix belongs to an earlier chat. Choose Fix with MuleSoftForge Studio AI again.");
      return null;
    }
  }

  private void applyDataWeaveFix(DataWeaveProposal proposal) {
    try {
      DataWeaveScript script = proposal.script();
      if (script == null) {
        append("Note", "The DataWeave editor this fix was for has been closed. Choose Fix with MuleSoftForge Studio AI"
            + " again.");
        return;
      }
      String problem = script.apply(proposal.fixed());
      if (problem != null) {
        append("Note", problem);
        return;
      }
      appendStep("Applied the fix to the DataWeave editor for " + proposal.where()
          + " (Cmd/Ctrl+Z in that editor undoes it; save the Mule file to keep it)");
      session.noteReview("The user applied your corrected DataWeave script to the editor.");
    } catch (Exception e) {
      append("Error", "Could not apply the fix: " + Errors.describe(e));
    }
  }

  private void cancel() {
    ChatJob current = running;
    if (current == null) {
      return;
    }
    current.cancel();
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

  // The live line is not recorded: it is replaced by the steps and the answer.
  private void showLive() {
    if (transcript.control().isDisposed()) {
      return;
    }
    long seconds = (System.nanoTime() - liveSince) / 1_000_000_000;
    String phrase = livePreview == null || livePreview.isEmpty()
        ? LoadingPhrases.at(AiSettings.loadingPhrases(), seconds, liveSeed) : null;
    transcript.live(liveState == null ? null
        : liveState + " · " + seconds + " s" + (phrase == null ? "" : " · " + phrase), livePreview);
  }

  private void tickLive() {
    if (liveState != null && running != null && !transcript.control().isDisposed()) {
      showLive();
      transcript.control().getDisplay().timerExec(1000, liveTicker);
    }
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
    CostMeter sessionCost = CostMeter.SESSION;
    int chats = sessionCost.chats();
    UsageLog ledger = Stores.usage();
    String chatModels = chatCost.breakdown();
    String sessionModels = sessionCost.breakdown();
    String project = chatProject == null ? "" : "\nProject " + chatProject + ", all time: "
        + CostMeter.describe(ledger.project(chatProject));
    status.showCost(chatCost.summary(), CostMeter.describe(ledger.today()), CostMeter.describe(ledger.thisMonth()),
        "This chat\n" + (chatModels.isEmpty() ? "nothing yet" : chatModels)
        + "\n\nStudio session (since Studio started), " + chats + (chats == 1 ? " chat" : " chats") + "\n"
        + (sessionModels.isEmpty() ? "nothing yet" : sessionModels)
        + "\n\nAll time in this workspace: " + CostMeter.describe(ledger.allTime()) + project
        + "\n\nOpenRouter amounts are what OpenRouter billed. Amounts marked ~ are estimated from list prices at "
        + Pricing.PRICE_LIST_URL + ". Ollama is free.");
  }

  private void refreshHeader() {
    header.setText(AiSettings.provider().label);
    header.getParent().layout();
  }

  private void openSettings() {
    PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiPreferencePage.ID, null, null).open();
  }
}
