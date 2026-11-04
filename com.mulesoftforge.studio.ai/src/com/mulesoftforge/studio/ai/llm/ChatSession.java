package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveTools;
import com.mulesoftforge.studio.ai.debug.DebugSession;
import com.mulesoftforge.studio.ai.debug.DebugTools;
import com.mulesoftforge.studio.ai.debug.PersonalData;
import com.mulesoftforge.studio.ai.edit.ChangeOffer;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.history.ChatRecord;
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
  private static final int MAX_TOOL_ROUNDS = 12;
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
      int cut = toolCounts.getOrDefault(CUT_OFF, 0);
      if (cut > 0) {
        parts.add("hit the output limit " + (cut == 1 ? "once" : cut + " times"));
      }
      return parts.isEmpty() ? "answered directly" : String.join(", ", parts);
    }

    private static String plural(int n, String noun) {
      return n + " " + noun + (n == 1 ? "" : "s");
    }
  }

  private final List<ChatMessage> history = new ArrayList<>();
  // Lock-free so Accept/Reject/Apply on the UI thread never block on an in-flight model call.
  private final Queue<String> reviewNotes = new ConcurrentLinkedQueue<>();
  private final Queue<String> cancelNotes = new ConcurrentLinkedQueue<>();
  private final Queue<String> lateNotes = new ConcurrentLinkedQueue<>();
  // Bumped by clear(), so a request from a cleared chat does not report into the next one.
  private final AtomicInteger generation = new AtomicInteger();
  private volatile Consumer<HttpTools.Late> lateListener;
  private final List<ChatRecord.Message> saved = new ArrayList<>();
  private volatile List<ChatRecord.Message> savedSnapshot = List.of();
  private final SecretMask secrets = new SecretMask();
  private final PersonalData personal = new PersonalData();
  private volatile boolean maskPersonal = true;
  private volatile DebugSession debugger;
  private volatile DataWeaveCli dataWeave;
  private volatile AppConsole appConsole;
  private volatile AppLauncher appLauncher;
  private volatile MunitRunner munitRunner;
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
    StringBuilder context = new StringBuilder();
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
    text = Prompts.extras(munit, metadata) + notes + text;
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
    Prompts.SystemParts system = from == null ? built
        : new Prompts.SystemParts(built.shared(), built.perRequest().strip() + "\n\n" + Prompts.modelSwitch(from));
    UserMessage message = current(system, text, images);

    List<ChatMessage> past;
    boolean firstTurn;
    synchronized (this) {
      past = List.copyOf(history);
      firstTurn = history.isEmpty();
    }
    if (firstTurn) {
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
    List<ChatMessage> turn = new ArrayList<>();
    turn.add(message);
    int toolCalls = 0;
    List<CallUsage> calls = new ArrayList<>();
    Map<String, Integer> toolCounts = new LinkedHashMap<>();

    String answer = null;
    // The first answer when the model was asked to draft its pasted code; the follow-up's summary is added to it.
    String pastedAnswer = null;
    // Models often write the whole answer in the message that calls offer_changes, then only a closing line.
    String offeredWith = null;
    boolean claimNudged = false;
    int cutOffs = 0;
    // Edits that failed this turn, by tool and exact arguments, so an identical retry is refused, not re-run.
    Map<String, String> failedEdits = new HashMap<>();
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
      ReportedCost.take();
      if (model instanceof StreamingChat streamed) {
        streamed.progress(progress);
      }
      ChatResponse response = model.chat(request.build());
      CallUsage usage = CallUsage.from(response, model.defaultRequestParameters().modelName(), ReportedCost.take());
      calls.add(usage);
      progress.answered(usage, (System.nanoTime() - started) / 1_000_000_000);
      progress.checkCancelled();
      AiMessage reply = response.aiMessage();
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
        toolCounts.merge(call.name(), 1, Integer::sum);
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
        String signature = call.name() + "\n" + call.arguments();
        String repeated = editCall ? failedEdits.get(signature) : null;
        String result = repeated != null ? "ERROR: not run. You already sent this exact call this turn and it"
                + " failed: " + repeated + "\nChange the call or the approach instead of repeating it."
            : debugCall && debugTools != null ? debugTools.execute(call)
            : dataWeaveCall && dataWeaveTools != null ? dataWeaveTools.execute(call)
            : httpCall && httpTools != null ? httpTools.execute(call)
            : launchCall && launchTools != null ? launchTools.execute(call)
            : munitCall && munitTools != null ? munitTools.execute(call)
            : (launchCall || munitCall) && !mode.canChangeApp ? "ERROR: " + mode.label + " mode cannot run or stop"
                + " the app or run tests. Answer without it; the user can switch to Agent or Diagnose mode for that."
            : tools != null && !debugCall && !dataWeaveCall && !httpCall && !launchCall && !munitCall
                ? tools.execute(call)
            : "ERROR: " + call.name() + " is not available now.";
        if (editCall && repeated == null && result.startsWith("ERROR")) {
          failedEdits.put(signature, result);
        }
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
        system = Prompts.systemParts(mode, project == null ? null : project.name(), fromProject.text(), fromUser.text(),
            true, dataWeaveTools != null, httpTools != null, false, false, fromHttp.text());
        turn.set(0, current(system, text, images));
        progress.step("Studio's debugger stopped during the call; the AI can now look at the event");
      }
      if (truncated != null && cutOffs >= MAX_CUT_OFFS) {
        answer = cutOffStop(!changes.isEmpty());
      }
    }
    if (answer == null) {
      answer = "Stopped after " + MAX_TOOL_ROUNDS + " steps before the model finished."
          + (changes.isEmpty() ? "" : " What it proposed so far is ready to review; ask it to continue if needed.");
    }
    if (pastedAnswer != null) {
      answer = changes.isEmpty() ? pastedAnswer : pastedAnswer + "\n\n" + answer;
    }
    answer = withoutEchoedNotes(withOfferText(answer, offeredWith));

    // Tool traffic stays out of history: some providers reject tool messages in a request without tools.
    String remembered = changes.isEmpty() ? answer : answer + "\n\n[Proposed for review: "
        + String.join(", ", changes.all().stream().map(c -> c.path()).toList()) + "]";
    List<ChangeOffer> offers = tools == null ? List.of() : tools.offers();
    if (!offers.isEmpty()) {
      remembered += "\n\n" + ChangeOffer.remembered(offers);
    }
    // Without the tool traffic, a later turn cannot tell a real call from a described one and may disown either.
    remembered += "\n\n[What you did this turn: "
        + new Turn(answer, changes, toolCalls, calls, toolCounts, List.of(), offers, 0).activity() + "]";
    // Every request resends the whole history, and attachments (the open file, console) come again with each new
    // turn, so history keeps only what was attached; the tools can read a file again. Unchanged history is what
    // lets the next turn read all of it from Claude's prompt cache.
    synchronized (this) {
      history.add(UserMessage.from(savedText));
      history.add(AiMessage.from(remembered));
      saved.add(new ChatRecord.Message("user", savedText));
      saved.add(new ChatRecord.Message("ai", remembered));
      savedSnapshot = List.copyOf(saved);
    }
    List<Problem> problems = tools == null || changes.isEmpty() ? List.of() : tools.problemsIntroduced();
    return new Turn(answer, changes, toolCalls, calls, toolCounts, problems, offers, secrets.total() - maskedBefore);
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
    return savedSnapshot;
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
    savedSnapshot = List.copyOf(saved);
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
    saved.clear();
    savedSnapshot = List.of();
    secrets.clear();
    personal.clear();
    reviewNotes.clear();
    cancelNotes.clear();
  }
}
