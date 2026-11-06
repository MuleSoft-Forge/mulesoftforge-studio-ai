package com.mulesoftforge.studio.ai.llm;

import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveTools;
import com.mulesoftforge.studio.ai.debug.DebugSession;
import com.mulesoftforge.studio.ai.debug.DebugTools;
import com.mulesoftforge.studio.ai.debug.PersonalData;
import com.mulesoftforge.studio.ai.edit.ChangeOffer;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.FollowUpAsk;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.edit.TodoItem;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.hooks.Hooks;
import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.http.HttpTools;
import com.mulesoftforge.studio.ai.launch.AppLauncher;
import com.mulesoftforge.studio.ai.launch.LaunchTools;
import com.mulesoftforge.studio.ai.launch.MunitRunner;
import com.mulesoftforge.studio.ai.launch.MunitTools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import dev.langchain4j.model.output.FinishReason;

public class ChatSession {
  public static final int MAX_TOOL_ROUNDS = 12;
  // Share of the model's context window at which the chat is compacted before the next message (Cline and Codex
  // use 90%; a little earlier leaves room for that message's attachments and tool output).
  public static final double COMPACT_AT = 0.85;
  public static final String EMPTY_REPLY = "(empty reply)";
  // Asked once in Agent mode when the answer to a change request pastes code but proposes nothing.
  static final String DRAFT_NUDGE = "You put the changes in your reply as code instead of proposing them. Propose"
      + " them now with edit_file or write_file so the user can review the diffs. Do not repeat the explanation;"
      + " finish with one short line saying what you proposed.";
  // Asked once when the answer says it proposed changes but no edit tool was called.
  static final String CLAIM_NUDGE = "You said you proposed changes, but you called no edit tool, so nothing was"
      + " proposed and the user has nothing to accept. Propose them now with edit_file or write_file, then finish"
      + " with a short answer that matches what you proposed.";
  // A reply that reached the output limit was cut off, so its tool calls lost their arguments: they are not run,
  // and the model is told why instead of getting "missing argument", which it answers by resending the same call.
  static final String CUT_OFF_CALL = "ERROR: not run. Your response reached the output limit and was cut off, so"
      + " the arguments of this call are incomplete. Do not send it again. Change existing files with edit_file,"
      + " one call per file with a list of small edits; keep write_file for new"
      + " files short enough to fit.";
  static final String CUT_OFF_EMPTY = "Your last response reached the output limit before you wrote anything the"
      + " user can see. Answer now, briefly. Make file changes with edit_file and a list of small edits rather than"
      + " one large write_file.";
  // After this many cut-off replies in one turn the turn stops: each one is a full output budget spent for nothing.
  static final int MAX_CUT_OFFS = 2;
  // Counted in toolCounts so the activity line can say the model hit the limit.
  public static final String CUT_OFF = "(cut off)";
  // Counted in toolCounts in place of the tool: calls the mode did not allow, and identical calls refused.
  public static final String MODE_REFUSED = "(not in this mode)";
  public static final String REPEATED = "(repeated)";
  public static final String HOOK_BLOCKED = "(blocked by a hook)";
  static final String HOOK_BLOCKED_CALL = "ERROR: not run. The user's PreToolUse hook blocked this call: ";
  static final String HOOK_NOTE = "[From the user's %s hook]\n";
  // In toolCounts when the turn stopped at MAX_TOOL_ROUNDS, so the view can offer Continue.
  public static final String STEP_LIMIT = "(step limit)";
  // The third identical call in a turn is refused, as Roo-Code's ToolRepetitionDetector does; its result will not
  // change, and a model that repeats a call usually keeps repeating it.
  static final int MAX_IDENTICAL_CALLS = 3;
  private static final String REPEATED_START = "ERROR: not run. This is call number";
  static final String REPEATED_CALL = REPEATED_START + " %d to %s with exactly these arguments this turn, and the"
      + " result would be the same. Use the result you already have, or try a different approach.";
  public static final String WAITING_FOR_DATA = "Thinking…";
  static final int MAX_RETRIES = 3;
  // A provider asking to wait longer than this is reported to the user rather than waited out.
  static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
  private static volatile Duration firstRetryWait = Duration.ofSeconds(2);
  private static volatile Duration maxRetryWait = Duration.ofSeconds(15);
  private static final ObjectMapper CANONICAL = new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  private static final Pattern CLAIMS_PROPOSED = Pattern.compile(
      "\\bI(?:'ve| have)? (?:now |also )?(?:proposed|re-proposed|drafted)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern ASKS_FOR_CHANGE = Pattern.compile("\\b(add|create|fix|change|update|replace|remove|"
      + "delete|rename|refactor|implement|write|make|convert|move|migrate|generate|set ?up|configure|apply)\\b");
  // "What did we learn … to create the tests right first time" mentions a change but asks for an explanation;
  // "can you add …" is still a request.
  private static final Pattern ASKS_A_QUESTION = Pattern.compile(
      "^\\W*(what|why|how|which|who|when|where|did|does|do|is|are|was|were|explain|tell me)\\b");
  // History notes the model sometimes copies into its own answer, where they would show twice.
  private static final Pattern ECHOED_NOTE = Pattern.compile(
      "(?m)^[ \\t]*\\[(?:Proposed for review|Offered to make|What you did this turn): [^\\]\\n]*][ \\t]*(?:\\n|$)");
  // Include the metadata guidance for type warnings, which models otherwise answer with an invented file format.
  private static final Pattern ASKS_ABOUT_METADATA = Pattern.compile("\\b(metadata|meta data|metadate|"
      + "application-types|datasense|unable to resolve reference|auto-coercing|warnings?)\\b",
      Pattern.CASE_INSENSITIVE);
  // Include the MUnit guidance when the user asks for tests, or when a suite under src/test/munit is attached.
  private static final Pattern ASKS_FOR_MUNIT = Pattern.compile(
      "\\b(munit|unit tests?|tests? for|(re)?run (the |my |all |these |those )?(failing )?tests?|test suites?)\\b",
      Pattern.CASE_INSENSITIVE);

  // problems: what the static checks still find in the proposals when the model stopped.
  // offers: changes the model recommended but did not make, for the user to pick from.
  // ask: clarifying question with clickable answers, or null.
  // todos: latest task list from update_todo_list this turn (may be empty).
  // masked: secret values hidden from the model this turn.
  public record Turn(String text, ChangeSet changes, int toolCalls, List<CallUsage> calls,
      Map<String, Integer> toolCounts, List<Problem> problems, List<ChangeOffer> offers, FollowUpAsk ask,
      List<TodoItem> todos, int masked) {

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
      int searched = toolCounts.getOrDefault("search_files", 0);
      if (searched > 0) {
        parts.add("searched the project " + (searched == 1 ? "once" : searched + " times"));
      }
      if (toolCounts.getOrDefault("connector_reference", 0) > 0) {
        parts.add("looked up connector details");
      }
      if (toolCounts.getOrDefault("check_project", 0) > 0) {
        parts.add("ran the project checks");
      }
      int evaluated = toolCounts.getOrDefault(DebugTools.EVALUATE, 0);
      if (evaluated > 0) {
        parts.add("evaluated " + plural(evaluated, "expression") + " at the breakpoint");
      }
      int expanded = toolCounts.getOrDefault(DebugTools.EXPAND, 0);
      if (expanded > 0) {
        parts.add("loaded " + plural(expanded, "field") + " from the debugger");
      }
      int runs = toolCounts.getOrDefault(DataWeaveTools.RUN, 0);
      if (runs > 0) {
        parts.add("ran DataWeave " + (runs == 1 ? "once" : runs + " times"));
      }
      int validated = toolCounts.getOrDefault(DataWeaveTools.VALIDATE, 0);
      if (validated > 0) {
        parts.add("checked DataWeave " + (validated == 1 ? "once" : validated + " times"));
      }
      if (toolCounts.getOrDefault(HttpTools.LIST, 0) > 0) {
        parts.add("found the app's endpoints");
      }
      int called = toolCounts.getOrDefault(HttpTools.CALL, 0);
      if (called > 0) {
        parts.add("called the app " + (called == 1 ? "once" : called + " times"));
      }
      if (toolCounts.getOrDefault(HttpTools.CONSOLE, 0) > 0) {
        parts.add("read the app's console");
      }
      int started = toolCounts.getOrDefault(LaunchTools.RUN, 0);
      if (started > 0) {
        parts.add("started the app" + (started == 1 ? "" : " " + started + " times"));
      }
      if (toolCounts.getOrDefault(LaunchTools.STOP, 0) > 0) {
        parts.add("stopped the app");
      }
      int tested = toolCounts.getOrDefault(MunitTools.RUN, 0);
      if (tested > 0) {
        parts.add("ran the MUnit tests" + (tested == 1 ? "" : " " + tested + " times"));
      }
      int pages = toolCounts.getOrDefault(HttpTools.DOCS, 0);
      if (pages > 0) {
        parts.add("read " + plural(pages, "docs page"));
      }
      if (proposed > 0) {
        parts.add("proposed changes to " + plural(proposed, "file"));
      } else if (edits > 0) {
        parts.add("tried " + plural(edits, "edit") + " that did not apply");
      }
      if (!offers.isEmpty()) {
        parts.add("offered " + plural(offers.size(), "change"));
      }
      if (ask != null) {
        parts.add("asked a follow-up");
      }
      if (!todos.isEmpty()) {
        long done = todos.stream().filter(t -> t.status() == TodoItem.Status.COMPLETED).count();
        parts.add("tracked " + todos.size() + " tasks (" + done + " done)");
      }
      int refused = toolCounts.getOrDefault(MODE_REFUSED, 0);
      if (refused > 0) {
        parts.add("skipped " + plural(refused, "call") + " this mode does not allow");
      }
      int repeated = toolCounts.getOrDefault(REPEATED, 0);
      if (repeated > 0) {
        parts.add("refused " + plural(repeated, "repeated call"));
      }
      int hooked = toolCounts.getOrDefault(HOOK_BLOCKED, 0);
      if (hooked > 0) {
        parts.add("your hooks blocked " + plural(hooked, "call"));
      }
      int cut = toolCounts.getOrDefault(CUT_OFF, 0);
      if (cut > 0) {
        parts.add("hit the output limit " + (cut == 1 ? "once" : cut + " times"));
      }
      if (stoppedAtLimit()) {
        parts.add("paused at the step limit");
      }
      return parts.isEmpty() ? "answered directly" : String.join(", ", parts);
    }

    // The model was still working when the turn reached MAX_TOOL_ROUNDS; the user can let it continue.
    public boolean stoppedAtLimit() {
      return toolCounts.containsKey(STEP_LIMIT);
    }

    private static String plural(int n, String noun) {
      return n + " " + noun + (n == 1 ? "" : "s");
    }
  }

  private final History history = new History();
  // The mode of the last answered turn, so the next message can say the user switched.
  private volatile ChatMode lastMode;
  // Tokens the last model call read and wrote, as the provider counted them: the size of the context now.
  private volatile long contextTokens;
  private volatile boolean compactNext;
  // Lock-free so Accept/Reject/Apply on the UI thread never block on an in-flight model call.
  private final Queue<String> reviewNotes = new ConcurrentLinkedQueue<>();
  private final Queue<String> cancelNotes = new ConcurrentLinkedQueue<>();
  private final Queue<String> lateNotes = new ConcurrentLinkedQueue<>();
  // Bumped by clear(), so a request from a cleared chat does not report into the next one.
  private final AtomicInteger generation = new AtomicInteger();
  private volatile Consumer<HttpTools.Late> lateListener;
  private final SecretMask secrets = new SecretMask();
  private final PersonalData personal = new PersonalData();
  private volatile boolean maskPersonal = true;
  private volatile DebugSession debugger;
  private volatile DataWeaveCli dataWeave;
  private volatile AppConsole appConsole;
  private volatile AppLauncher appLauncher;
  private volatile MunitRunner munitRunner;
  private volatile Hooks hooks = Hooks.NONE;
  private final String sessionId = UUID.randomUUID().toString();
  private volatile String instructions = "";
  private volatile String httpInstructions = "";
  private volatile String switchedFrom;
  private volatile List<String> docsHosts = List.of();
  // Only one send at a time per session; serialises turn starts without holding the monitor across I/O.
  private final Object sendGate = new Object();

  // Off sends values as they are; files that only hold secrets are withheld either way.
  public void maskSecrets(boolean on) {
    secrets.setEnabled(on);
  }

  // Email addresses and phone numbers in live debug events; on by default since those hold real data.
  public void maskPersonalData(boolean on) {
    maskPersonal = on;
  }

  // The user's hooks for the next turns; Hooks.NONE runs nothing.
  public void hooks(Hooks userHooks) {
    hooks = userHooks == null ? Hooks.NONE : userHooks;
  }

  // Passed to hooks as session_id, so a hook can tell chats apart.
  public String sessionId() {
    return sessionId;
  }

  // Studio's debugger, so the model gets the debug tools whenever it is stopped; null when unavailable.
  public void debugger(DebugSession session) {
    debugger = session;
  }

  // The DataWeave CLI, so proposals' DataWeave is compiled and the DataWeave tools are offered; null without it.
  public void dataWeave(DataWeaveCli cli) {
    dataWeave = cli;
  }

  // The running app's console, so calls to the app report what it printed and read_console is offered; null without.
  public void appConsole(AppConsole console) {
    appConsole = console;
  }

  // Studio's Run As / Debug As > Mule Application, so Agent and Diagnose can start and stop the app; null without.
  public void appLauncher(AppLauncher launcher) {
    appLauncher = launcher;
  }

  // Studio's MUnit runner, so Agent and Diagnose can run the project's suites; null without.
  public void munitRunner(MunitRunner runner) {
    munitRunner = runner;
  }

  // Told, off the UI thread, when a request call_endpoint stopped waiting for gets its answer. The model is told
  // with the next prompt either way.
  public void onLateResult(Consumer<HttpTools.Late> listener) {
    lateListener = listener;
  }

  private Consumer<HttpTools.Late> lateSink() {
    int chat = generation.get();
    return late -> {
      if (generation.get() != chat) {
        return;
      }
      lateNotes.add("LATE RESULT: " + late.request() + " was sent in an earlier turn and answered after that turn"
          + " ended:\n" + late.result());
      Consumer<HttpTools.Late> listener = lateListener;
      if (listener != null) {
        listener.accept(late);
      }
    };
  }

  // Auto moved this chat from that model to another; the next message says so, once.
  public void modelChanged(String from) {
    switchedFrom = from;
  }

  // The user's own instructions from Preferences, sent with every message.
  public void instructions(String text) {
    instructions = text == null ? "" : text;
  }

  // Extra guidance for the HTTP tools, from Preferences; appended after the built-in http.md when those tools are on.
  public void httpInstructions(String text) {
    httpInstructions = text == null ? "" : text;
  }

  // Hosts fetch_docs may read; empty means the built-in defaults.
  public void docsHosts(List<String> hosts) {
    docsHosts = hosts == null ? List.of() : List.copyOf(hosts);
  }

  private static String agentsFile(ProjectFiles project) {
    if (project == null) {
      return "";
    }
    try {
      String text = project.read(Prompts.AGENTS_FILE);
      return text == null ? "" : text;
    } catch (Exception e) {
      return "";
    }
  }

  // The step shown on the first message, or null when there are no instructions to follow.
  static String following(Prompts.Capped fromProject, Prompts.Capped fromUser, Prompts.Capped fromHttp, int hidden) {
    List<String> parts = new ArrayList<>();
    if (!fromProject.isEmpty()) {
      parts.add(Prompts.AGENTS_FILE + " (" + size(fromProject) + ")");
    }
    if (!fromUser.isEmpty()) {
      parts.add("your instructions (" + size(fromUser) + ")");
    }
    if (fromHttp != null && !fromHttp.isEmpty()) {
      parts.add("HTTP and docs instructions (" + size(fromHttp) + ")");
    }
    if (parts.isEmpty()) {
      return null;
    }
    String step = "Following " + String.join(" and ", parts);
    return hidden == 0 ? step : step + "; hid " + hidden + (hidden == 1 ? " secret value" : " secret values")
        + " in them";
  }

  static String following(Prompts.Capped fromProject, Prompts.Capped fromUser, int hidden) {
    return following(fromProject, fromUser, null, hidden);
  }

  private static String size(Prompts.Capped text) {
    String chars = String.format(Locale.ROOT, "%,d characters", text.chars());
    return text.cut() ? chars + ", cut to " + String.format(Locale.ROOT, "%,d", Prompts.MAX_INSTRUCTIONS) : chars;
  }

  // The real values for placeholders in text the model wrote, for copying or applying it in Studio.
  public String unmask(String text) {
    return secrets.unmask(text);
  }

  public Turn send(ChatModel model, String prompt, List<Attachment> attachments, ProjectFiles project) {
    return send(model, ChatMode.AGENT, prompt, attachments, project, Progress.NONE);
  }

  public Turn send(ChatModel model, ChatMode mode, String prompt, List<Attachment> attachments,
      ProjectFiles project, Progress progress) {
    synchronized (sendGate) {
      return sendLocked(model, mode, prompt, attachments, project, progress);
    }
  }

  private Turn sendLocked(ChatModel model, ChatMode mode, String prompt, List<Attachment> attachments,
      ProjectFiles project, Progress progress) {
    String currentInstructions = instructions;
    String currentHttpInstructions = httpInstructions;
    List<String> currentDocsHosts = docsHosts;
    SecretMask.Masked projectText = secrets.mask(Prompts.AGENTS_FILE, agentsFile(project));
    SecretMask.Masked userText = secrets.mask(null, currentInstructions);
    SecretMask.Masked httpText = secrets.mask(null, currentHttpInstructions);
    int maskedBefore = secrets.total();
    int personalBefore = personal.total();
    PersonalData personalMask = maskPersonal ? personal : null;
    prompt = secrets.mask(null, prompt).text();
    Hooks userHooks = hooks;
    Path hookDir = project == null ? null : project.location();
    if (userHooks.has(Hooks.Event.USER_PROMPT_SUBMIT)) {
      ObjectNode input = Hooks.input(Hooks.Event.USER_PROMPT_SUBMIT, sessionId, hookDir);
      input.put("prompt", prompt);
      input.put("mode", mode.name().toLowerCase(Locale.ROOT));
      Hooks.Outcome outcome = userHooks.run(Hooks.Event.USER_PROMPT_SUBMIT, null, input, hookDir,
          progress::isCancelled);
      hookProblems(progress, Hooks.Event.USER_PROMPT_SUBMIT, outcome);
      if (outcome.blocked()) {
        throw new Hooks.Blocked("Your UserPromptSubmit hook stopped this message before it was sent: "
            + outcome.reason());
      }
      if (!outcome.context().isEmpty()) {
        progress.step("Your UserPromptSubmit hook added context to the message");
        prompt = prompt + "\n\n" + HOOK_NOTE.formatted(Hooks.Event.USER_PROMPT_SUBMIT.wire)
            + secrets.mask(null, outcome.contextText()).text();
      }
    }
    List<Block> blocks = new ArrayList<>();
    int attachedChars = 0;
    List<String> sent = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    List<ImageContent> images = new ArrayList<>();
    for (Attachment original : attachments) {
      Attachment attachment = secrets.protect(original);
      if (personalMask != null && attachment.kind() == Attachment.Kind.DEBUG_EVENT) {
        attachment = new Attachment(attachment.kind(), attachment.project(), attachment.path(), attachment.lines(),
            personalMask.mask(attachment.content()), attachment.truncated());
      }
      String block = attachment.toPrompt();
      if (attachment.kind() == Attachment.Kind.IMAGE) {
        images.add(attachment.toImage());
        blocks.add(new Block(attachment.label(), block, true));
        attachedChars += block.length();
        sent.add(attachment.label());
      } else if (attachedChars + block.length() > Attachment.MAX_TOTAL_CHARS) {
        dropped.add(attachment.label());
      } else {
        blocks.add(new Block(attachment.label(), block, false));
        attachedChars += block.length();
        sent.add(attachment.label());
      }
    }
    String savedText = sent.isEmpty() ? prompt : "[Attached at the time: " + String.join(", ", sent) + "]\n" + prompt;
    String notes = "";
    List<String> cancelled = drain(cancelNotes);
    List<String> reviews = drain(reviewNotes);
    List<String> lateResults = drain(lateNotes);
    if (!cancelled.isEmpty()) {
      notes += String.join("\n", cancelled) + "\n\n";
    }
    if (!lateResults.isEmpty()) {
      notes += String.join("\n\n", lateResults) + "\n\n";
    }
    if (!reviews.isEmpty()) {
      notes += "REVIEW OF YOUR EARLIER PROPOSALS:\n" + String.join("\n", reviews) + "\n\n";
    }
    boolean munit = wantsMunit(prompt, attachments);
    boolean metadata = wantsMetadata(prompt, attachments) && project != null;
    savedText = notes + savedText;
    int maskedInMessage = secrets.total() - maskedBefore;
    if (maskedInMessage > 0) {
      progress.step("Hid " + maskedInMessage + (maskedInMessage == 1 ? " secret value" : " secret values")
          + " in your message and attachments before sending");
    }
    notePersonal(progress, personal.total() - personalBefore);
    DebugSession debug = debugger;
    DebugTools debugTools = debug != null && debug.current() != null
        ? new DebugTools(debug, secrets, personalMask) : null;
    DataWeaveCli cli = dataWeave;
    DataWeaveTools dataWeaveTools = cli == null ? null : new DataWeaveTools(cli, project, secrets);
    HttpTools httpTools = project == null ? null
        : new HttpTools(project, secrets, personalMask, mode.canChangeApp, currentDocsHosts, HttpTools.TIMEOUT,
            appConsole).debugger(debug).lateResults(lateSink()).cancelledWhen(progress::isCancelled);
    AppLauncher launcher = appLauncher;
    LaunchTools launchTools = project == null || launcher == null || !mode.canChangeApp ? null
        : new LaunchTools(project, launcher, appConsole, secrets, progress::isCancelled, LaunchTools.DEPLOY_TIMEOUT);
    Prompts.Capped fromProject = Prompts.capped(projectText.text());
    Prompts.Capped fromUser = Prompts.capped(userText.text());
    Prompts.Capped fromHttp = httpTools == null ? new Prompts.Capped("", 0, false) : Prompts.capped(httpText.text());
    Prompts.SystemParts built = Prompts.systemParts(mode, project == null ? null : project.name(), fromProject.text(),
        fromUser.text(), debugTools != null, dataWeaveTools != null, httpTools != null, false, false,
        fromHttp.text());
    String from = switchedFrom;
    switchedFrom = null;
    ChatMode previousMode = lastMode;
    String changed = (previousMode == null || previousMode == mode ? "" : "\n\n" + Prompts.modeSwitch(previousMode, mode))
        + (from == null ? "" : "\n\n" + Prompts.modelSwitch(from));
    Prompts.SystemParts system = withNotes(built, changed);

    if (history.isEmpty()) {
      String following = following(fromProject, fromUser, fromHttp,
          projectText.count() + userText.count() + httpText.count());
      if (following != null) {
        progress.step(following);
      }
    }

    ChangeSet changes = new ChangeSet();
    ProjectTools tools = project == null ? null : new ProjectTools(project, changes, mode.canEdit, secrets);
    if (tools != null) {
      tools.useDataWeave(cli);
      tools.onTodosChanged(() -> progress.todos(tools.todos()));
    }
    MunitRunner tester = munitRunner;
    MunitTools munitTools = project == null || tester == null || !mode.canChangeApp ? null
        : new MunitTools(project, tester, changes, secrets, progress::isCancelled, MunitTools.RUN_TIMEOUT);
    List<ToolSpecification> specifications = new ArrayList<>();
    if (tools != null) {
      specifications.addAll(tools.specifications());
    }
    if (debugTools != null) {
      specifications.addAll(DebugTools.SPECIFICATIONS);
    }
    if (dataWeaveTools != null) {
      specifications.addAll(DataWeaveTools.SPECIFICATIONS);
    }
    if (httpTools != null) {
      specifications.addAll(httpTools.specifications());
    }
    // Offered in every mode, like the edit tools, so switching modes keeps the prompt cache; Ask and Plan refuse them.
    if (project != null && launcher != null) {
      specifications.addAll(LaunchTools.SPECIFICATIONS);
    }
    if (project != null && tester != null) {
      specifications.addAll(MunitTools.SPECIFICATIONS);
    }
    // Tool calls and results go back only to a request that offers tools; without, history is text only.
    boolean withTools = !specifications.isEmpty();
    List<CallUsage> calls = new ArrayList<>();
    if (compactNext || needsCompaction(model)) {
      compactNext = false;
      compact(model, system, specifications, withTools, calls, progress);
    }
    List<ChatMessage> past = history.messages(withTools);
    String lead = Prompts.extras(munit, metadata) + notes;
    Attached attached = attached(blocks, dropped, withTools);
    String text = lead + attached.text() + prompt;
    UserMessage message = current(system, text, images);
    List<ChatMessage> turn = new ArrayList<>();
    turn.add(message);
    int toolCalls = 0;
    Map<String, Integer> toolCounts = new LinkedHashMap<>();

    String answer = null;
    // The first answer when the model was asked to draft its pasted code; the follow-up's summary is added to it.
    String pastedAnswer = null;
    // Models often write the whole answer in the message that calls offer_changes, then only a closing line.
    String offeredWith = null;
    boolean claimNudged = false;
    int cutOffs = 0;
    boolean overflowCompacted = false;
    // Edits that failed this turn, by tool and exact arguments, so an identical retry is refused, not re-run.
    Map<String, String> failedEdits = new HashMap<>();
    // Every call this turn by tool and arguments, so a third identical one is refused.
    Map<String, Integer> seenCalls = new HashMap<>();
    for (int round = 0; round < MAX_TOOL_ROUNDS && answer == null; round++) {
      List<ChatMessage> messages = new ArrayList<>();
      messages.add(SystemMessage.from(system.shared()));
      messages.addAll(past);
      messages.addAll(turn);
      ChatRequest.Builder request = ChatRequest.builder().messages(messages);
      if (!specifications.isEmpty()) {
        request.toolSpecifications(specifications);
      }

      progress.checkCancelled();
      progress.step(round == 0 ? "Waiting for the model…" : "Waiting for the model (step " + (round + 1) + ")…");
      long started = System.nanoTime();
      ChatResponse response;
      try {
        response = call(model, request.build(), progress);
      } catch (RuntimeException e) {
        // Too large for the model: summarise the earlier turns from their text-only form, which is far smaller,
        // and send this turn again on top of the summary.
        if (overflowCompacted || !Errors.contextOverflow(e) || history.isEmpty()) {
          throw e;
        }
        overflowCompacted = true;
        progress.step("The chat is too large for the model's context window");
        if (!compact(model, system, List.of(), false, calls, progress)) {
          throw e;
        }
        past = history.messages(withTools);
        attached = attached(blocks, dropped, withTools);
        text = lead + attached.text() + prompt;
        turn.set(0, current(system, text, images));
        round--;
        continue;
      }
      CallUsage usage = CallUsage.from(response, model.defaultRequestParameters().modelName(), ReportedCost.take());
      calls.add(usage);
      progress.answered(usage, (System.nanoTime() - started) / 1_000_000_000);
      progress.checkCancelled();
      AiMessage reply = response.aiMessage();
      long counted = usage.input() + usage.output();
      contextTokens = counted > 0 ? counted : History.tokens(messages) + History.tokens(List.of(reply));
      turn.add(reply);
      boolean cutOff = response.finishReason() == FinishReason.LENGTH;
      boolean blank = reply.text() == null || reply.text().isBlank();
      // A model writes its calls in order, so only the last call of a cut-off reply can be incomplete; the
      // gateway may still close its JSON, so it is refused whatever it looks like. The earlier calls are run.
      ToolExecutionRequest truncated = null;
      if (cutOff && (reply.hasToolExecutionRequests() || blank)) {
        cutOffs++;
        toolCounts.merge(CUT_OFF, 1, Integer::sum);
        if (!reply.hasToolExecutionRequests()) {
          // Providers reject an assistant message with no content.
          turn.remove(turn.size() - 1);
          turn.add(UserMessage.from(CUT_OFF_EMPTY));
          if (cutOffs >= MAX_CUT_OFFS) {
            answer = cutOffStop(!changes.isEmpty());
            break;
          }
          progress.step("The model's reply was cut off at the output limit; asking for smaller steps…");
          continue;
        }
        List<ToolExecutionRequest> requests = reply.toolExecutionRequests();
        truncated = requests.get(requests.size() - 1);
        progress.step("The model's reply was cut off at the output limit; running the complete calls and asking"
            + " for the rest in smaller steps…");
      }
      if (specifications.isEmpty() || !reply.hasToolExecutionRequests()) {
        answer = blank
            ? (changes.isEmpty() ? EMPTY_REPLY : "Proposed changes are ready for review.")
            : reply.text();
        if (cutOff) {
          answer += "\n\n(This answer was cut off at the output limit.)";
        }
        if (pastedAnswer == null && pastedInsteadOfProposed(mode, tools, changes, answer, prompt)) {
          pastedAnswer = answer;
          answer = null;
          progress.step("Drafting the code from the answer as changes to review…");
          turn.add(UserMessage.from(DRAFT_NUDGE));
          continue;
        }
        if (!claimNudged && claimedProposal(mode, tools, changes, answer)) {
          claimNudged = true;
          answer = null;
          progress.step("The answer said it proposed changes but none were made; asking for them…");
          turn.add(UserMessage.from(CLAIM_NUDGE));
          continue;
        }
        break;
      }
      if (reply.text() != null && !reply.text().isBlank() && reply.toolExecutionRequests().stream()
          .anyMatch(call -> call.name().equals("offer_changes"))) {
        offeredWith = reply.text().strip();
      }
      for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
        if (call == truncated) {
          turn.add(ToolExecutionResultMessage.from(call, CUT_OFF_CALL));
          continue;
        }
        toolCalls++;
        boolean debugCall = DebugTools.handles(call.name());
        boolean dataWeaveCall = DataWeaveTools.handles(call.name());
        boolean httpCall = HttpTools.handles(call.name());
        boolean launchCall = LaunchTools.handles(call.name());
        boolean munitCall = MunitTools.handles(call.name());
        progress.step(debugCall ? DebugTools.describe(call) : dataWeaveCall ? DataWeaveTools.describe(call)
            : httpCall ? HttpTools.describe(call) : launchCall ? LaunchTools.describe(call)
            : munitCall ? MunitTools.describe(call) : ProjectTools.describe(call));
        int before = secrets.total();
        int personalBeforeCall = personal.total();
        boolean editCall = ProjectTools.isEdit(call.name());
        String signature = signature(call);
        String repeated = editCall ? failedEdits.get(signature) : null;
        int seen = seenCalls.merge(signature, 1, Integer::sum);
        boolean runs = repeated == null && seen < MAX_IDENTICAL_CALLS;
        String blocked = runs ? preToolUse(userHooks, call, hookDir, progress) : null;
        String result = repeated != null ? "ERROR: not run. You already sent this exact call this turn and it"
                + " failed: " + repeated + "\nChange the call or the approach instead of repeating it."
            : seen >= MAX_IDENTICAL_CALLS ? String.format(Locale.ROOT, REPEATED_CALL, seen, call.name())
            : blocked != null ? blocked
            : debugCall && debugTools != null ? debugTools.execute(call)
            : dataWeaveCall && dataWeaveTools != null ? dataWeaveTools.execute(call)
            : httpCall && httpTools != null ? httpTools.execute(call)
            : launchCall && launchTools != null ? launchTools.execute(call)
            : munitCall && munitTools != null ? munitTools.execute(call)
            : (launchCall || munitCall) && !mode.canChangeApp ? ChatMode.refused(mode.label + " mode cannot run or"
                + " stop the app or run tests. Agent or Diagnose mode can.")
            : tools != null && !debugCall && !dataWeaveCall && !httpCall && !launchCall && !munitCall
                ? tools.execute(call)
            : "ERROR: " + call.name() + " is not available now.";
        if (runs && blocked == null) {
          result = postToolUse(userHooks, call, result, hookDir, progress);
        }
        if (editCall && repeated == null && blocked == null && result.startsWith("ERROR")) {
          failedEdits.put(signature, result);
        }
        // A refused call did nothing, so it counts as what stopped it rather than as a call that failed.
        String countedAs = blocked != null ? HOOK_BLOCKED
            : result.startsWith(ChatMode.REFUSED) ? MODE_REFUSED
            : result.startsWith(REPEATED_START) ? REPEATED : call.name();
        toolCounts.merge(countedAs, 1, Integer::sum);
        int hidden = secrets.total() - before;
        if (hidden > 0) {
          progress.step("Hid " + hidden + (hidden == 1 ? " secret value" : " secret values") + " before sending");
        }
        notePersonal(progress, personal.total() - personalBeforeCall);
        turn.add(ToolExecutionResultMessage.from(call, result));
      }
      // A call that stopped at a breakpoint leaves the debugger stopped mid-turn: offer its tools from now on.
      if (debugTools == null && debug != null && debug.current() != null && reply.toolExecutionRequests().stream()
          .anyMatch(call -> HttpTools.CALL.equals(call.name()))) {
        debugTools = new DebugTools(debug, secrets, personalMask);
        specifications.addAll(DebugTools.SPECIFICATIONS);
        system = withNotes(Prompts.systemParts(mode, project == null ? null : project.name(), fromProject.text(),
            fromUser.text(), true, dataWeaveTools != null, httpTools != null, false, false, fromHttp.text()), changed);
        turn.set(0, current(system, text, images));
        progress.step("Studio's debugger stopped during the call; the AI can now look at the event");
      }
      if (truncated != null && cutOffs >= MAX_CUT_OFFS) {
        answer = cutOffStop(!changes.isEmpty());
      }
    }
    if (answer == null) {
      toolCounts.put(STEP_LIMIT, 1);
      answer = "Paused after " + MAX_TOOL_ROUNDS + " steps before the model finished."
          + (changes.isEmpty() ? "" : " What it proposed so far is ready to review.")
          + " Continue to let it carry on from where it stopped.";
    }
    if (pastedAnswer != null) {
      answer = changes.isEmpty() ? pastedAnswer : pastedAnswer + "\n\n" + answer;
    }
    answer = withoutEchoedNotes(withOfferText(answer, offeredWith));

    // The text-only form, for requests without tools and for disk: what was proposed, offered and done, since a
    // later turn without the tool traffic cannot tell a real call from a described one and may disown either.
    String remembered = changes.isEmpty() ? answer : answer + "\n\n[Proposed for review: "
        + String.join(", ", changes.all().stream().map(c -> c.path()).toList()) + "]";
    List<ChangeOffer> offers = tools == null ? List.of() : tools.offers();
    if (!offers.isEmpty()) {
      remembered += "\n\n" + ChangeOffer.remembered(offers);
    }
    remembered += "\n\n[What you did this turn: "
        + new Turn(answer, changes, toolCalls, calls, toolCounts, List.of(), offers, null, List.of(), 0)
            .activity() + "]";
    history.add(new History.Exchange(asSent(turn, answer), savedText, remembered), attached.inFull());
    lastMode = mode;
    if (userHooks.has(Hooks.Event.STOP) && !progress.isCancelled()) {
      ObjectNode input = Hooks.input(Hooks.Event.STOP, sessionId, hookDir);
      input.put("last_assistant_message", answer);
      input.put("stop_hook_active", false);
      hookProblems(progress, Hooks.Event.STOP,
          userHooks.run(Hooks.Event.STOP, null, input, hookDir, progress::isCancelled));
    }
    List<Problem> problems = tools == null || changes.isEmpty() ? List.of() : tools.problemsIntroduced();
    FollowUpAsk ask = tools == null ? null : tools.ask();
    List<TodoItem> todos = tools == null ? List.of() : tools.todos();
    return new Turn(answer, changes, toolCalls, calls, toolCounts, problems, offers, ask, todos,
        secrets.total() - maskedBefore);
  }

  // The result to send instead of running the call when a PreToolUse hook blocks it, else null. Hooks see the
  // arguments as the model wrote them, with secrets still masked.
  private String preToolUse(Hooks userHooks, ToolExecutionRequest call, Path dir, Progress progress) {
    if (!userHooks.has(Hooks.Event.PRE_TOOL_USE)) {
      return null;
    }
    ObjectNode input = Hooks.input(Hooks.Event.PRE_TOOL_USE, sessionId, dir);
    input.put("tool_name", call.name());
    input.set("tool_input", Hooks.toolInput(call.arguments()));
    input.put("tool_use_id", call.id() == null ? "" : call.id());
    Hooks.Outcome outcome = userHooks.run(Hooks.Event.PRE_TOOL_USE, call.name(), input, dir, progress::isCancelled);
    hookProblems(progress, Hooks.Event.PRE_TOOL_USE, outcome);
    if (!outcome.blocked()) {
      return null;
    }
    progress.step("Your PreToolUse hook blocked " + call.name() + ": " + outcome.reason());
    return HOOK_BLOCKED_CALL + secrets.mask(null, outcome.reason()).text();
  }

  // The result with what PostToolUse hooks said about it added; a hook that "blocks" here can only add its reason,
  // since the call already ran.
  private String postToolUse(Hooks userHooks, ToolExecutionRequest call, String result, Path dir,
      Progress progress) {
    if (!userHooks.has(Hooks.Event.POST_TOOL_USE)) {
      return result;
    }
    ObjectNode input = Hooks.input(Hooks.Event.POST_TOOL_USE, sessionId, dir);
    input.put("tool_name", call.name());
    input.set("tool_input", Hooks.toolInput(call.arguments()));
    input.put("tool_response", result);
    input.put("tool_use_id", call.id() == null ? "" : call.id());
    Hooks.Outcome outcome = userHooks.run(Hooks.Event.POST_TOOL_USE, call.name(), input, dir, progress::isCancelled);
    hookProblems(progress, Hooks.Event.POST_TOOL_USE, outcome);
    List<String> said = new ArrayList<>(outcome.context());
    if (outcome.blocked()) {
      said.add(outcome.reason());
    }
    if (said.isEmpty()) {
      return result;
    }
    progress.step("Your PostToolUse hook commented on " + call.name());
    return result + "\n\n" + HOOK_NOTE.formatted(Hooks.Event.POST_TOOL_USE.wire)
        + secrets.mask(null, String.join("\n\n", said)).text();
  }

  private static void hookProblems(Progress progress, Hooks.Event event, Hooks.Outcome outcome) {
    for (String problem : outcome.problems()) {
      progress.step("A " + event.wire + " hook did not work (" + problem + "); carrying on without it");
    }
  }

  private static UserMessage current(Prompts.SystemParts system, String text, List<ImageContent> images) {
    String full = Prompts.forRequest(system.perRequest()) + text;
    if (images.isEmpty()) {
      return UserMessage.from(full);
    }
    List<Content> parts = new ArrayList<>(images);
    parts.add(TextContent.from(full));
    return UserMessage.from(parts);
  }

  private record Block(String label, String text, boolean image) {}

  // inFull: the attachments sent in full, by label, which the history then holds.
  private record Attached(String text, Map<String, String> inFull) {}

  // An attachment already in the history unchanged is named, not sent again; that history has attachments only
  // when it goes with tools.
  private Attached attached(List<Block> blocks, List<String> dropped, boolean withTools) {
    StringBuilder context = new StringBuilder();
    Map<String, String> inFull = new HashMap<>();
    for (Block block : blocks) {
      if (!block.image() && withTools && history.hasAttached(block.label(), block.text())) {
        context.append(Prompts.unchangedAttachment(block.label()));
      } else {
        context.append(block.text());
        if (!block.image()) {
          inFull.put(block.label(), block.text());
        }
      }
    }
    if (!dropped.isEmpty()) {
      context.append("NOT ATTACHED (too large to send together; read them with tools if needed): ")
          .append(String.join(", ", dropped)).append("\n\n");
    }
    return new Attached(context.toString(), inFull);
  }

  private static Prompts.SystemParts withNotes(Prompts.SystemParts parts, String notes) {
    return notes.isEmpty() ? parts : new Prompts.SystemParts(parts.shared(), parts.perRequest().strip() + notes);
  }

  // The turn as the provider saw it, ending with an answer: a turn stopped after tool results or a cut-off gets
  // the stop message, and an empty reply the text shown, since providers reject an empty assistant message.
  private static List<ChatMessage> asSent(List<ChatMessage> turn, String answer) {
    List<ChatMessage> out = new ArrayList<>(turn);
    if (out.get(out.size() - 1) instanceof AiMessage last && !last.hasToolExecutionRequests()) {
      if (last.text() == null || last.text().isBlank()) {
        out.set(out.size() - 1, AiMessage.from(answer));
      }
    } else {
      out.add(AiMessage.from(answer));
    }
    return out;
  }

  private boolean needsCompaction(ChatModel model) {
    return !history.isEmpty()
        && contextTokens >= COMPACT_AT * ContextWindow.of(model.defaultRequestParameters().modelName());
  }

  // Asks the chat's own model for a summary at the end of the history it already has cached, as Codex does, then
  // keeps that summary and the most recent turns. A failed summary leaves the history as it was. True when compacted.
  private boolean compact(ChatModel model, Prompts.SystemParts system, List<ToolSpecification> specifications,
      boolean withTools, List<CallUsage> calls, Progress progress) {
    int summarised = history.toSummarise();
    if (summarised == 0) {
      return false;
    }
    long before = Math.max(contextTokens, history.tokens());
    progress.step("Compacting the chat: summarising " + (summarised == 1 ? "the earliest turn" : "the earliest "
        + summarised + " turns") + " to stay within the model's context window…");
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(SystemMessage.from(system.shared()));
    messages.addAll(history.messages(withTools));
    messages.add(UserMessage.from(Prompts.compact()));
    ChatRequest.Builder request = ChatRequest.builder().messages(messages);
    if (!specifications.isEmpty()) {
      request.toolSpecifications(specifications);
    }
    String summary;
    try {
      long started = System.nanoTime();
      ChatResponse response = call(model, request.build(), progress);
      CallUsage usage = CallUsage.from(response, model.defaultRequestParameters().modelName(), ReportedCost.take());
      calls.add(usage);
      progress.answered(usage, (System.nanoTime() - started) / 1_000_000_000);
      summary = response.aiMessage().text();
    } catch (RuntimeException e) {
      progress.checkCancelled();
      progress.step("Could not compact the chat (" + Errors.describe(e) + "); sending it as it is");
      return false;
    }
    progress.checkCancelled();
    if (summary == null || summary.isBlank()) {
      progress.step("The model wrote no summary, so the chat was not compacted");
      return false;
    }
    history.compact(summarised, summary);
    contextTokens = history.tokens();
    progress.step("Compacted the chat from about " + ContextWindow.tokens(before) + " to about "
        + ContextWindow.tokens(contextTokens) + " tokens");
    return true;
  }

  // One model call, sent again after a busy or failed provider (408, 429, 5xx, a dropped connection) with growing
  // waits, as Cline (3 tries, 1 to 15 s) and gemini-cli (5 to 30 s, honouring the provider's delay) do. Nothing
  // of a failed call was used, so sending it again is safe even when part of it had streamed. Not retried: a
  // model that went silent, which already waited out the idle limit, and a wait the provider asks for that is
  // longer than MAX_RETRY_AFTER, which the user is told about instead.
  private static ChatResponse call(ChatModel model, ChatRequest request, Progress progress) {
    for (int attempt = 0; ; attempt++) {
      ReportedCost.take();
      if (model instanceof StreamingChat streamed) {
        streamed.progress(progress);
      }
      progress.receiving(model instanceof StreamingChat ? WAITING_FOR_DATA : "Waiting for the answer…");
      try {
        return model.chat(request);
      } catch (RuntimeException e) {
        Duration wait = attempt < MAX_RETRIES && !progress.isCancelled() ? retryWait(e, attempt) : null;
        if (wait == null) {
          throw e;
        }
        progress.step(AutoRouter.shortReason(e) + "; trying again in " + Math.max(1, wait.toSeconds()) + " s"
            + " (" + (attempt + 2) + " of " + (MAX_RETRIES + 1) + ")…");
        pause(wait, progress);
      }
    }
  }

  // null when the error is not worth another try.
  static Duration retryWait(Throwable error, int attempt) {
    if (!AutoRouter.retryable(error) || Errors.find(error, HttpTimeoutException.class) != null) {
      return null;
    }
    Duration backoff = firstRetryWait.multipliedBy(1L << attempt);
    if (backoff.compareTo(maxRetryWait) > 0) {
      backoff = maxRetryWait;
    }
    Duration asked = Errors.classify(error, null).retryAfter();
    if (asked == null) {
      return backoff;
    }
    return asked.compareTo(MAX_RETRY_AFTER) > 0 ? null : asked.compareTo(backoff) > 0 ? asked : backoff;
  }

  private static void pause(Duration wait, Progress progress) {
    long until = System.nanoTime() + wait.toNanos();
    while (System.nanoTime() < until) {
      progress.checkCancelled();
      try {
        Thread.sleep(Math.min(250, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Cancelled.");
      }
    }
  }

  // For the harness: shorter waits between retries.
  public static void retryWaits(Duration first, Duration max) {
    firstRetryWait = first;
    maxRetryWait = max;
  }

  // Tool name and arguments with keys sorted, so the same call written in another key order still matches.
  static String signature(ToolExecutionRequest call) {
    String arguments = call.arguments() == null ? "" : call.arguments();
    try {
      // Read as Maps, not a JsonNode tree: only Maps are written with their keys sorted.
      arguments = CANONICAL.writeValueAsString(CANONICAL.readValue(arguments, Object.class));
    } catch (Exception e) {
      // Not JSON: compared as written.
    }
    return call.name() + "\n" + arguments;
  }

  // The size of the context after the last model call, in tokens; 0 before the first.
  public long contextTokens() {
    return contextTokens;
  }

  // Compacts the chat before the next message, where the summary reads the history from the prompt cache.
  public void compactBeforeNext() {
    compactNext = true;
  }

  public boolean canCompact() {
    return history.size() > 1 || history.tokens() > History.KEEP_RECENT_TOKENS;
  }

  static String cutOffStop(boolean proposed) {
    return "Stopped: the model's replies were cut off at the output limit " + MAX_CUT_OFFS + " times, so it could not finish." + (proposed ? " What it proposed so far is ready to review." : "")
        + " Ask for fewer changes at a time.";
  }

  private static void notePersonal(Progress progress, int hidden) {
    if (hidden > 0) {
      progress.step("Hid " + hidden + (hidden == 1 ? " email address or phone number" : " email addresses or phone"
          + " numbers") + " from the live event before sending");
    }
  }

  private static List<String> drain(Queue<String> queue) {
    List<String> out = new ArrayList<>();
    String next;
    while ((next = queue.poll()) != null) {
      out.add(next);
    }
    return out;
  }

  public static boolean wantsMunit(String prompt, List<Attachment> attachments) {
    if (prompt != null && ASKS_FOR_MUNIT.matcher(prompt).find()) {
      return true;
    }
    if (attachments == null) {
      return false;
    }
    for (Attachment attachment : attachments) {
      String path = attachment.path();
      if (path != null && path.replace('\\', '/').contains("src/test/munit/")) {
        return true;
      }
    }
    return false;
  }

  public static boolean wantsMetadata(String prompt, List<Attachment> attachments) {
    if (prompt != null && ASKS_ABOUT_METADATA.matcher(prompt).find()) {
      return true;
    }
    for (Attachment attachment : attachments == null ? List.<Attachment>of() : attachments) {
      if (attachment.kind() == Attachment.Kind.PROBLEMS && attachment.content() != null
          && ASKS_ABOUT_METADATA.matcher(attachment.content()).find()
          || attachment.path() != null && attachment.path().endsWith("application-types.xml")) {
        return true;
      }
    }
    return false;
  }

  static boolean pastedInsteadOfProposed(ChatMode mode, ProjectTools tools, ChangeSet changes, String answer,
      String prompt) {
    String asked = prompt.toLowerCase(Locale.ROOT);
    return mode.canEdit && tools != null && changes.isEmpty() && tools.offers().isEmpty()
        && CodeBlocks.hasProjectCode(answer) && ASKS_FOR_CHANGE.matcher(asked).find()
        && !ASKS_A_QUESTION.matcher(asked).find();
  }

  static boolean claimedProposal(ChatMode mode, ProjectTools tools, ChangeSet changes, String answer) {
    return mode.canEdit && tools != null && changes.isEmpty() && CLAIMS_PROPOSED.matcher(answer).find();
  }

  public static String withoutEchoedNotes(String answer) {
    String cleaned = ECHOED_NOTE.matcher(answer).replaceAll("").strip();
    return cleaned.isEmpty() ? answer : cleaned;
  }

  // The text sent with offer_changes is kept only when the final reply is just a closing line; a final reply that
  // restates it would otherwise show the answer twice.
  public static String withOfferText(String answer, String offeredWith) {
    if (offeredWith == null || answer.contains(offeredWith)) {
      return answer;
    }
    if (answer.equals(EMPTY_REPLY)) {
      return offeredWith;
    }
    return answer.strip().length() < Math.min(200, offeredWith.length() / 2) ? offeredWith + "\n\n" + answer : answer;
  }

  // History safe to write to disk: same turns, without the open-file contents.
  public List<ChatRecord.Message> savedHistory() {
    return history.saved();
  }

  public synchronized void restore(List<ChatRecord.Message> messages) {
    clear();
    if (messages != null) {
      history.restore(messages);
      contextTokens = history.tokens();
    }
  }

  // Drops the newest exchange. Returns the user text of that exchange, or null when there is none.
  public synchronized String dropLastExchange() {
    String user = history.dropLast();
    contextTokens = history.tokens();
    return user;
  }

  public synchronized String lastUserPrompt() {
    return history.lastUser();
  }

  // Told to the model with the next prompt, so it knows which proposals actually landed.
  public void noteReview(String note) {
    reviewNotes.add(note);
  }

  // Cancelled turns leave no history, so the model is told the request was dropped rather than answered.
  public void noteCancelled(String prompt) {
    String masked = secrets.mask(null, prompt).text();
    String shown = masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
    cancelNotes.add("NOTE: The user cancelled an earlier request before you answered (\"" + shown
        + "\"). Nothing from it was kept; do not assume it was done.");
  }

  public synchronized void clear() {
    generation.incrementAndGet();
    lateNotes.clear();
    history.clear();
    lastMode = null;
    contextTokens = 0;
    compactNext = false;
    secrets.clear();
    personal.clear();
    reviewNotes.clear();
    cancelNotes.clear();
  }
}
