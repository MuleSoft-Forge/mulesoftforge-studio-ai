import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.mulesoftforge.studio.ai.edit.AttachmentReader;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.edit.LineDiff;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectPaths;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.ModelLists;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.Updates;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.render.Highlighter;
import com.mulesoftforge.studio.ai.render.HtmlPage;
import com.mulesoftforge.studio.ai.render.HtmlRenderer;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer;
import com.mulesoftforge.studio.ai.render.MarkdownRenderer.Rendered;
import com.mulesoftforge.studio.ai.render.Palette;
import com.mulesoftforge.studio.ai.render.Span;
import com.mulesoftforge.studio.ai.settings.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * CLI smoke tests for the Studio AI LLM layer, outside Eclipse.
 *
 *   java Harness test-connection [provider...]
 *   java Harness list-models [provider...]
 *   java Harness chat [--provider name] [--model id] [--file path] [--mode ask|agent|plan] [--attach path] [--lines path:a-b] [--project dir [--apply]] "prompt"
 *   java Harness check-tools
 *
 * Reads keys from ../.env (KEY=value lines). Never prints secret values.
 */
public class Harness {
  private static final Path ENV = Path.of("..").resolve(".env").toAbsolutePath().normalize();
  private static String modelOverride;
  private static ChatMode mode = ChatMode.AGENT;
  private static final Path DEFAULT_STATE = Path.of(System.getProperty("user.home"),
      "AnypointStudio", "studio-workspace-728", ".metadata", ".plugins", "com.mulesoftforge.studio.ai");

  public static void main(String[] args) throws Exception {
    Map<String, String> env = loadEnv(ENV);
    Network.configure(new Network.Trust(!"false".equalsIgnoreCase(env.getOrDefault("USE_SYSTEM_CA", "true")),
        env.getOrDefault("CA_PEM", "")));
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    switch (args[0]) {
      case "test-connection" -> {
        String[] names = args.length == 1 ? new String[] {"openrouter", "openai"} : tail(args);
        int failed = 0;
        for (String name : names) {
          if (!testConnection(env, name)) {
            failed++;
          }
        }
        System.exit(failed == 0 ? 0 : 1);
      }
      case "list-models" -> {
        String[] names = args.length == 1 ? new String[] {"openrouter", "openai"} : tail(args);
        int failed = 0;
        for (String name : names) {
          if (!listModels(env, name)) {
            failed++;
          }
        }
        System.exit(failed == 0 ? 0 : 1);
      }
      case "chat" -> {
        String provider = "openrouter";
        Path file = null;
        Path project = null;
        boolean apply = false;
        List<String> attach = new ArrayList<>();
        int i = 1;
        while (i < args.length && args[i].startsWith("--")) {
          switch (args[i]) {
            case "--provider" -> provider = args[++i];
            case "--file" -> file = Path.of(args[++i]);
            case "--attach" -> attach.add(args[++i]);
            case "--lines" -> attach.add("lines:" + args[++i]);
            case "--project" -> project = Path.of(args[++i]);
            case "--apply" -> apply = true;
            case "--model" -> modelOverride = args[++i];
            case "--mode" -> mode = ChatMode.valueOf(args[++i].toUpperCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
          }
          i++;
        }
        if (i >= args.length) {
          usage();
          System.exit(2);
        }
        String prompt = String.join(" ", Arrays.copyOfRange(args, i, args.length));
        chat(env, provider, file, project, attach, apply, prompt);
      }
      case "check-tools" -> checkTools();
      case "check-attach" -> checkAttach();
      case "check-html" -> checkHtml(args.length > 1 ? Path.of(args[1]) : null);
      case "check-render" -> checkRender();
      case "check-history" -> checkHistory();
      case "check-tls" -> checkTls();
      case "check-updates" -> checkUpdates(args.length > 1 ? args[1] : null);
      case "prices" -> prices(Arrays.copyOfRange(args, 1, args.length));
      case "list-chats" -> listChats(args.length > 1 ? Path.of(args[1]) : DEFAULT_STATE);
      case "show-chat" -> System.out.println(ChatStore.toMarkdown(
          new ChatStore((args.length > 2 ? Path.of(args[2]) : DEFAULT_STATE).resolve("chats")).load(args[1])));
      case "render" -> System.out.println(ansi(MarkdownRenderer.render(Files.readString(Path.of(args[1])), true)));
      default -> {
        usage();
        System.exit(2);
      }
    }
  }

  private static boolean testConnection(Map<String, String> env, String name) {
    Provider provider = parseProvider(name);
    Connection connection;
    try {
      connection = connectionFor(env, provider);
    } catch (IllegalStateException e) {
      System.out.println("SKIP  " + provider.label + " — " + e.getMessage());
      return true;
    }
    System.out.print("TEST  " + provider.label + "  model=" + connection.model() + " ... ");
    try {
      String reply = ChatModels.create(connection).chat("Reply with the single word: ok");
      System.out.println("OK  replied=" + shorten(reply));
      return true;
    } catch (Exception e) {
      System.out.println("FAIL  " + Errors.describe(e));
      return false;
    }
  }

  private static boolean listModels(Map<String, String> env, String name) {
    Provider provider = parseProvider(name);
    Connection connection;
    try {
      connection = connectionFor(env, provider);
    } catch (IllegalStateException e) {
      System.out.println("SKIP  " + provider.label + " — " + e.getMessage());
      return true;
    }
    System.out.println("MODELS  " + provider.label + "  GET " + connection.baseUrl() + " …");
    try {
      List<String> raw = ModelLists.list(connection);
      List<String> picker = ModelLists.forPicker(provider, raw, connection.model());
      System.out.println(ModelLists.summarize(provider, raw.size(), picker));
      int shown = Math.min(picker.size(), 25);
      for (int i = 0; i < shown; i++) {
        System.out.println("  " + picker.get(i));
      }
      if (picker.size() > shown) {
        System.out.println("  … +" + (picker.size() - shown) + " more");
      }
      return true;
    } catch (Exception e) {
      System.out.println("FAIL\n" + Errors.describe(e));
      return false;
    }
  }

  private static void chat(Map<String, String> env, String name, Path file, Path projectDir, List<String> attach,
      boolean apply, String prompt) throws Exception {
    Provider provider = parseProvider(name);
    Connection connection = connectionFor(env, provider);
    if (modelOverride != null) {
      connection = new Connection(provider, connection.baseUrl(), modelOverride, connection.apiKey());
    }
    DiskProject project = projectDir == null ? null : new DiskProject(projectDir);
    List<Attachment> attachments = new ArrayList<>();
    if (file != null) {
      String shown = projectDir != null && file.toAbsolutePath().startsWith(projectDir.toAbsolutePath())
          ? projectDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/')
          : file.toString();
      attachments.add(Attachment.openFile(project == null ? null : project.name(), shown, Files.readString(file)));
    }
    if (!attach.isEmpty() && project == null) {
      throw new IllegalArgumentException("--attach and --lines need --project");
    }
    for (String spec : attach) {
      attachments.add(attachmentFor(project, spec));
    }
    System.out.println("CHAT  " + provider.label + "  model=" + connection.model()
        + (project == null ? "" : "  project=" + project.root));
    for (Attachment attachment : attachments) {
      System.out.println("  + " + attachment.label());
    }
    CostMeter meter = new CostMeter();
    Progress progress = new Progress() {
      @Override
      public void step(String message) {
        System.out.println("  · " + message);
      }

      @Override
      public void answered(CallUsage usage, long seconds) {
        Pricing.CallCost cost = Pricing.cost(provider, usage);
        meter.add(cost);
        System.out.println("  · Model answered in " + seconds + " s · " + usage.model() + " · "
            + CostMeter.describe(cost) + " [" + cost.basis().label + "]");
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
    ChatSession.Turn turn = new ChatSession().send(ChatModels.create(connection), mode, prompt, attachments, project,
        progress);
    System.out.println(turn.text());
    System.out.println("\nCOST  " + meter.summary());
    if (project == null) {
      return;
    }
    System.out.println("\nDID  " + turn.activity());
    for (FileChange change : turn.changes().all()) {
      System.out.println("\n== " + change.summary());
      System.out.print(LineDiff.unified(change.path(), change.before(), change.after(), 2));
      if (apply) {
        Path target = project.root.resolve(change.path());
        String onDisk = Files.isRegularFile(target) ? Files.readString(target) : null;
        if (!Objects.equals(onDisk, change.before())) {
          System.out.println("SKIP  " + change.path() + " changed on disk since it was proposed");
          continue;
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, change.after());
        System.out.println("APPLIED  " + change.path());
      }
    }
  }

  // Offline checks of the path guard and edit rules; no LLM call.
  private static void checkTools() throws Exception {
    Path root = Files.createTempDirectory("studio-ai-tools");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.writeString(root.resolve("src/main/mule/api.xml"), "<flow name=\"a\">\n  <logger/>\n  <logger/>\n</flow>\n");
    ChangeSet changes = new ChangeSet();
    ProjectTools tools = new ProjectTools(new DiskProject(root), changes);
    int failed = 0;
    failed += expect(tools, "read_file", "{\"path\":\"../secret\"}", "ERROR: Refusing path outside");
    failed += expect(tools, "write_file", "{\"path\":\"/etc/passwd\",\"content\":\"x\"}", "ERROR: Refusing absolute");
    failed += expect(tools, "write_file", "{\"path\":\"target/x.xml\",\"content\":\"x\"}", "ERROR: Refusing to write");
    failed += expect(tools, "list_files", "{\"directory\":\"\"}", "src/main/mule/api.xml");
    ProjectTools readOnly = new ProjectTools(new DiskProject(root), new ChangeSet(), false);
    failed += check("Ask/Plan offer only read tools", readOnly.specifications().stream()
        .map(spec -> spec.name()).toList().equals(List.of("list_files", "read_file")));
    failed += expect(readOnly, "write_file", "{\"path\":\"src/x.xml\",\"content\":\"x\"}",
        "ERROR: this mode cannot change files");
    failed += expect(tools, "edit_file",
        "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"<logger/>\",\"new_text\":\"<x/>\"}", "ERROR: old_text occurs");
    failed += expect(tools, "edit_file",
        "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"<flow name=\\\"a\\\">\",\"new_text\":\"<flow name=\\\"b\\\">\"}",
        "Proposed change");
    failed += expect(tools, "read_file", "{\"path\":\"src/main/mule/api.xml\"}", "<flow name=\"b\">");
    failed += expect(tools, "write_file", "{\"path\":\"./src/main/mule/new.xml\",\"content\":\"<mule/>\"}",
        "Proposed new file src/main/mule/new.xml");
    boolean diskUntouched = Files.readString(root.resolve("src/main/mule/api.xml")).contains("name=\"a\"")
        && !Files.exists(root.resolve("src/main/mule/new.xml"));
    System.out.println((diskUntouched ? "PASS" : "FAIL") + "  proposals did not touch disk");
    failed += diskUntouched ? 0 : 1;
    System.out.println(changes.all().size() == 2 ? "PASS  2 proposed files" : "FAIL  expected 2 proposed files");
    failed += changes.all().size() == 2 ? 0 : 1;
    System.exit(failed == 0 ? 0 : 1);
  }

  // "path" attaches a file or folder; "lines:path:10-20" attaches those lines.
  private static Attachment attachmentFor(ProjectFiles project, String spec) throws Exception {
    if (!spec.startsWith("lines:")) {
      return AttachmentReader.read(project, spec);
    }
    String rest = spec.substring("lines:".length());
    int colon = rest.lastIndexOf(':');
    String[] range = rest.substring(colon + 1).split("-");
    int first = Integer.parseInt(range[0]);
    int last = range.length > 1 ? Integer.parseInt(range[1]) : first;
    return AttachmentReader.lines(project, rest.substring(0, colon), first, last);
  }

  // Offline: what each kind of attachment looks like to the model, the size cap, and the cancel note.
  private static void checkAttach() throws Exception {
    Path dir = Files.createTempDirectory("msf-attach");
    Files.createDirectories(dir.resolve("src/main/mule"));
    Files.createDirectories(dir.resolve("target"));
    Files.writeString(dir.resolve("src/main/mule/app.xml"), "<mule>\n  <flow name=\"a\"/>\n  <flow name=\"b\"/>\n</mule>\n");
    Files.writeString(dir.resolve("src/main/mule/big.xml"), "x".repeat(Attachment.MAX_CHARS + 10));
    Files.writeString(dir.resolve("pom.xml"), "<project/>");
    Files.write(dir.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1});
    Files.writeString(dir.resolve("target/out.txt"), "built");
    DiskProject project = new DiskProject(dir);
    int failed = 0;

    Attachment file = AttachmentReader.read(project, "src/main/mule/app.xml");
    failed += check("file label", file.label().equals("file src/main/mule/app.xml"));
    Attachment big = AttachmentReader.read(project, "src/main/mule/big.xml");
    failed += check("large file truncated to " + Attachment.MAX_CHARS, big.truncated()
        && big.content().length() == Attachment.MAX_CHARS);
    Attachment folder = AttachmentReader.read(project, "");
    failed += check("project file list skips target/ and keeps pom.xml", folder.content().contains("pom.xml")
        && !folder.content().contains("target/"));
    failed += check("folder label counts files", folder.label().equals("folder / (4 files)"));
    Attachment lines = AttachmentReader.lines(project, "src/main/mule/app.xml", 2, 3);
    failed += check("lines label", lines.label().equals("lines 2–3 of src/main/mule/app.xml"));
    failed += check("lines content", lines.content().equals("  <flow name=\"a\"/>\n  <flow name=\"b\"/>"));
    failed += check("binary refused", throwsIllegalArgument(() -> AttachmentReader.read(project, "logo.png")));
    failed += check("build output refused", throwsIllegalArgument(() -> AttachmentReader.read(project, "target/out.txt")));
    failed += check("escape refused", throwsIllegalArgument(() -> AttachmentReader.read(project, "../etc/passwd")));

    List<Attachment.Problem> reported = new ArrayList<>();
    reported.add(new Attachment.Problem(Attachment.Problem.Severity.WARNING, "src/main/mule/app.xml", 9, "unused"));
    reported.add(new Attachment.Problem(Attachment.Problem.Severity.ERROR, "src/main/mule/app.xml", 12,
        "Attribute \",\" has no value"));
    reported.add(new Attachment.Problem(Attachment.Problem.Severity.ERROR, "src/main/mule/app.xml", 3,
        "Attribute \"(\" has no value"));
    Attachment problems = Attachment.problems("p", "", reported);
    failed += check("problems label counts by severity",
        problems.label().equals("problems in / (2 errors, 1 warning)"));
    failed += check("problems sorted errors first, then by line", problems.content().startsWith(
        "ERROR src/main/mule/app.xml line 3: Attribute \"(\" has no value\nERROR src/main/mule/app.xml line 12:")
        && problems.content().endsWith("WARNING src/main/mule/app.xml line 9: unused"));
    List<Attachment.Problem> flood = new ArrayList<>();
    for (int n = 1; n <= Attachment.MAX_PROBLEMS + 5; n++) {
      flood.add(new Attachment.Problem(Attachment.Problem.Severity.ERROR, "a.xml", n, "bad"));
    }
    Attachment capped = Attachment.problems("p", "a.xml", flood);
    failed += check("problems capped at " + Attachment.MAX_PROBLEMS + " but counted in full", capped.truncated()
        && capped.content().split("\n").length == Attachment.MAX_PROBLEMS
        && capped.label().equals("problems in a.xml (" + (Attachment.MAX_PROBLEMS + 5) + " errors)"));

    failed += check("problems with errors report a failure", problems.reportsFailure());
    String build = "[INFO] Scanning\n\u001B[1;31m[ERROR]\u001B[m Failed to execute goal ... employees-api.xml;"
        + " lineNumber: 170; Element type \"logger\" must be followed by either attribute specifications\n";
    Attachment console = Attachment.console("Maven Output", build, false);
    failed += check("console label", console.label().equals("console Maven Output"));
    failed += check("console colour codes removed", !console.content().contains("\u001B")
        && console.content().contains("[ERROR] Failed to execute goal"));
    failed += check("console with ERROR reports a failure", console.reportsFailure());
    failed += check("quiet console does not", !Attachment.console("Maven Output", "[INFO] BUILD SUCCESS", false)
        .reportsFailure());
    StringBuilder longLog = new StringBuilder();
    for (int n = 0; longLog.length() <= Attachment.MAX_CHARS + 5_000; n++) {
      longLog.append("[INFO] line ").append(n).append('\n');
    }
    longLog.append("[ERROR] the end");
    Attachment tail = Attachment.console("Mule", longLog.toString(), false);
    failed += check("long console keeps the end from a whole line", tail.truncated()
        && tail.content().endsWith("[ERROR] the end") && tail.content().startsWith("[INFO] line ")
        && tail.content().length() <= Attachment.MAX_CHARS && tail.label().equals("console Mule (last part)"));
    failed += check("selected console text label", Attachment.console("Mule", "x", true).label()
        .equals("selected text in console Mule"));

    List<String> prompts = new ArrayList<>();
    ChatModel recorder = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        prompts.add(((UserMessage) request.messages().get(request.messages().size() - 1)).singleText());
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
      }
    };
    ChatSession session = new ChatSession();
    session.send(recorder, "explain", List.of(Attachment.openFile("p", "a.xml", "<a/>"), lines, folder, problems,
        console), null);
    String sent = prompts.get(0);
    failed += check("problems block", sent.contains("PROBLEMS STUDIO REPORTS IN / (project p), 2 errors, 1 warning")
        && sent.contains("line 12: Attribute"));
    failed += check("console block", sent.contains("CONSOLE OUTPUT: Maven Output\n") && sent.contains("lineNumber: 170"));
    failed += check("open file block", sent.contains("OPEN FILE: a.xml (project p)"));
    failed += check("selected lines block", sent.contains("SELECTED LINES 2–3 OF src/main/mule/app.xml"));
    failed += check("folder block", sent.contains("FILES IN FOLDER /"));
    failed += check("prompt comes last", sent.endsWith("explain"));
    String savedUser = session.savedHistory().get(0).text();
    failed += check("saved chat keeps labels, not content", savedUser.startsWith("[Attached at the time: open file a.xml,")
        && !savedUser.contains("<a/>"));

    List<Attachment> many = new ArrayList<>();
    for (int n = 0; n < 4; n++) {
      many.add(Attachment.file("p", "big" + n + ".xml", "y".repeat(Attachment.MAX_CHARS)));
    }
    session.send(recorder, "too much", many, null);
    failed += check("over the total cap the rest are named, not sent", prompts.get(1).contains("NOT ATTACHED")
        && prompts.get(1).contains("file big3.xml") && prompts.get(1).length() < Attachment.MAX_TOTAL_CHARS + 1_000);

    session.noteCancelled("rename every flow");
    session.send(recorder, "next", List.of(), null);
    failed += check("cancel note reaches the next prompt", prompts.get(2).contains("cancelled an earlier request")
        && prompts.get(2).contains("rename every flow"));
    session.send(recorder, "after", List.of(), null);
    failed += check("cancel note sent only once", !prompts.get(3).contains("cancelled"));
    System.exit(failed == 0 ? 0 : 1);
  }

  // Offline: version comparison and latest.json parsing. With a URL, also fetches that latest.json.
  private static void checkUpdates(String url) throws Exception {
    int failed = 0;
    failed += check("qualifier dropped", Updates.shortVersion("0.1.0.202610281104").equals("0.1.0"));
    failed += check("short version kept", Updates.shortVersion("1.2.3").equals("1.2.3"));
    failed += check("patch is newer", Updates.isNewer("0.1.1", "0.1.0.202610281104"));
    failed += check("numeric, not text, order", Updates.isNewer("0.10.0", "0.9.9"));
    failed += check("same release with another qualifier is not newer", !Updates.isNewer("0.1.0", "0.1.0.202610281104"));
    failed += check("older is not newer", !Updates.isNewer("0.0.9", "0.1.0"));
    failed += check("missing parts count as 0", Updates.compare("1", "1.0.0") == 0);
    Updates.Latest latest = Updates.parse("{\"version\":\"0.2.0\",\"notes\":\"Console context\"}");
    failed += check("latest.json parsed", latest.version().equals("0.2.0") && latest.notes().equals("Console context"));
    failed += check("latest.json without version refused", throwsIOException(() -> Updates.parse("{}")));
    if (url != null) {
      Updates.Latest live = Updates.fetch(url);
      System.out.println("  " + url + " -> " + live.version() + " " + live.notes());
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static boolean throwsIOException(Action action) {
    try {
      action.run();
      return false;
    } catch (IOException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // Offline: the rich transcript's HTML. With a path, also writes a preview page to open in a browser.
  private static void checkHtml(Path preview) throws Exception {
    String markdown = """
        ## Result
        Use **bold** and `inline <code>` here. <script>alert(1)</script>
        ```xml
        <flow name="a">
          <logger message="#[payload]"/>
        </flow>
        ```
        Then a table:

        | Field | Type |
        |---|---|
        | id | <Number> |

        See [docs](https://docs.mulesoft.com) and [bad](javascript:alert(1)).
        """;
    HtmlRenderer.Html html = HtmlRenderer.markdown(markdown, 3);
    String h = html.html();
    int failed = 0;
    failed += check("model HTML is escaped", !h.contains("<script>") && h.contains("&lt;script&gt;"));
    failed += check("inline code escaped", h.contains("<code class=\"inline\">inline &lt;code&gt;</code>"));
    failed += check("code block numbered after earlier ones", h.contains("data-a=\"copy:3\"")
        && h.contains("data-a=\"insert:3\"") && html.codeBlocks().size() == 1);
    failed += check("code block language bar", h.contains("<span class=\"lang\">xml</span>"));
    failed += check("XML tokens coloured", h.contains("<span class=\"t-tag\">"));
    failed += check("expression coloured", h.contains("<span class=\"t-expression\">"));
    failed += check("no stray line break after a block", !h.contains("</pre></div><br>"));
    failed += check("table rendered with header", h.contains("<table><tr><th>Field</th><th>Type</th></tr>")
        && h.contains("<td>&lt;Number&gt;</td>"));
    failed += check("https link goes through Studio", h.contains("data-a=\"url:https://docs.mulesoft.com\""));
    failed += check("javascript: link is not clickable", !h.contains("javascript:alert(1)\"")
        && !h.contains("data-a=\"url:javascript"));
    failed += check("level-2 heading uses the large style", h.contains("<span class=\"h1\">Result</span>"));
    failed += check("plain text keeps line breaks, escaped", HtmlRenderer.plain("a<b>\nc").equals("a&lt;b&gt;<br>c"));
    String page = HtmlPage.page(new HtmlPage.Theme(Palette.LIGHT, new Palette.Rgb(255, 255, 255),
        new Palette.Rgb(0, 0, 0), "Helvetica", 12, "Menlo", 11));
    failed += check("page has a content security policy", page.contains("Content-Security-Policy")
        && page.contains("default-src 'none'"));
    failed += check("page colours filled in", page.contains(".t-tag { color: #3f7f7f; }") && !page.contains("%s"));
    if (preview != null) {
      String demo = page.replace("</script></body>", """
          msfMessage('you', 'You', %s, 'Agent, open file src/main/mule/app.xml, project employees-app');
          msfStep('Waiting for the model…'); msfStep('Reading src/main/mule/app.xml'); msfStep('Model answered in 4 s · $0.0012');
          msfMessage('ai', 'AI', %s, null);
          msfStep('Done in 5 s · read 1 file · this answer $0.0012');
          msfAction('Build this plan in Agent mode', 'build-plan');
          function msfClick(a) { console.log(a); }
          </script></body>""".formatted(jsString("How do I log the payload?"), jsString(h)));
      Files.writeString(preview, demo);
      System.out.println("Preview written to " + preview.toAbsolutePath());
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static String jsString(String value) throws Exception {
    return new ObjectMapper().writeValueAsString(value);
  }

  private interface Action {
    void run() throws Exception;
  }

  private static boolean throwsIllegalArgument(Action action) {
    try {
      action.run();
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // What the model picker shows: each provider's curated models with their list price.
  private static void prices(String[] names) {
    List<Provider> providers = new ArrayList<>();
    for (String name : names) {
      providers.add(parseProvider(name));
    }
    if (providers.isEmpty()) {
      providers.addAll(List.of(Provider.values()));
    }
    for (Provider provider : providers) {
      System.out.println(provider.label);
      for (String model : ModelLists.suggestionsOnly(provider, provider.defaultModel)) {
        System.out.printf("  %-32s %s%n", model, Pricing.priceText(provider, model));
      }
    }
  }

  // Certificate sources, a trusted HTTPS call, and the hint shown for an untrusted certificate.
  private static void checkTls() throws Exception {
    System.out.println(Network.report().describe());
    int failed = 0;
    HttpClient client = Network.builder().build();
    try {
      int status = client.send(HttpRequest.newBuilder(URI.create(Pricing.PRICE_LIST_URL)).GET().build(),
          HttpResponse.BodyHandlers.discarding()).statusCode();
      failed += check("trusted HTTPS call (" + status + ")", status == 200);
    } catch (Exception e) {
      failed += check("trusted HTTPS call: " + Errors.describe(e), false);
    }
    try {
      client.send(HttpRequest.newBuilder(URI.create("https://self-signed.badssl.com/")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
      failed += check("untrusted certificate rejected", false);
    } catch (Exception e) {
      String message = Errors.describe(e);
      failed += check("untrusted certificate rejected", true);
      failed += check("hint names Preferences > Network", message.contains("Preferences > MuleSoftForge AI > Network"));
      System.out.println("  " + message.replace("\n", "\n  "));
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  // Offline checks of chat saving, reloading, export, pruning and the cost ledger.
  private static void checkHistory() throws Exception {
    Path dir = Files.createTempDirectory("studio-ai-history");
    ChatStore store = new ChatStore(dir.resolve("chats"));
    UsageLog ledger = new UsageLog(dir.resolve("usage.jsonl"));
    int failed = 0;

    CostMeter meter = new CostMeter();
    Pricing.CallCost cost = new Pricing.CallCost(
        new CallUsage("openai/gpt-5-mini", 1000, 200, 0, 300, 50, null), 0.0012, Pricing.Basis.ESTIMATED);
    meter.add(cost);
    ChatSession session = new ChatSession();
    session.restore(List.of(new ChatRecord.Message("user", "[Open file at the time: a.xml]\nhello"),
        new ChatRecord.Message("ai", "hi")));
    String id = ChatStore.newId();
    String now = Instant.now().toString();
    ChatRecord record = new ChatRecord(ChatRecord.SCHEMA, id, ChatStore.titleFrom("  lets create   better error handling  "),
        "employees-app", now, now,
        List.of(new ChatRecord.Entry("You", "hello"), new ChatRecord.Entry("AI", "```xml\n<flow/>\n```"),
            new ChatRecord.Entry("Step", "Done in 3 s")),
        session.savedHistory(), meter.snapshot(), 1);
    store.save(record);
    ledger.append(id, "employees-app", "OPENAI", cost);

    failed += check("title normalised", record.title().equals("lets create better error handling"));
    failed += check("listed in index", store.list().size() == 1 && store.list().get(0).id().equals(id));
    ChatRecord loaded = store.load(id);
    failed += check("entries round-trip", loaded.entries().equals(record.entries()));
    failed += check("history round-trip", loaded.history().equals(record.history()));
    failed += check("cost round-trip", loaded.cost().all().usd() == 0.0012 && loaded.cost().byModel().size() == 1);
    CostMeter restored = new CostMeter();
    restored.restore(loaded.cost());
    failed += check("cost restores into meter", restored.summary().equals(meter.summary()));
    failed += check("index rebuilds if missing", Files.deleteIfExists(dir.resolve("chats/index.json"))
        && store.list().size() == 1);
    store.rename(id, "Renamed");
    failed += check("rename", store.list().get(0).title().equals("Renamed") && store.load(id).title().equals("Renamed"));
    String md = ChatStore.toMarkdown(store.load(id));
    failed += check("markdown export", md.startsWith("# Renamed") && md.contains("## AI") && md.contains("<flow/>"));
    failed += check("rejects path-like ids", rejects(() -> store.load("../secret")));
    failed += check("ledger today", ledger.today().calls() == 1 && ledger.today().usd() == 0.0012);
    failed += check("ledger per project", ledger.project("employees-app").calls() == 1);
    failed += check("ledger reloads from disk", new UsageLog(dir.resolve("usage.jsonl")).allTime().calls() == 1);
    failed += check("prune keeps recent", store.prune(90, Instant.now()) == 0 && store.list().size() == 1);
    failed += check("prune removes old", store.prune(90, Instant.now().plusSeconds(91L * 86_400)) == 1
        && store.list().isEmpty());
    failed += check("ledger kept after chats deleted", ledger.allTime().calls() == 1);
    System.exit(failed == 0 ? 0 : 1);
  }

  private static boolean rejects(ThrowingRunnable action) {
    try {
      action.run();
      return false;
    } catch (Exception e) {
      return e instanceof IllegalArgumentException;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void listChats(Path state) throws Exception {
    ChatStore store = new ChatStore(state.resolve("chats"));
    UsageLog ledger = new UsageLog(state.resolve("usage.jsonl"));
    System.out.println("CHATS  " + store.directory());
    for (ChatRecord.Summary s : store.list()) {
      System.out.println("  " + s.id() + "  " + s.updated() + "  " + (s.project() == null ? "-" : s.project())
          + "  " + (s.estimated() ? "~" : "") + CostMeter.usd(s.usd()) + "  " + s.title());
    }
    System.out.println("COST  today " + CostMeter.describe(ledger.today()) + " | month "
        + CostMeter.describe(ledger.thisMonth()) + " | all time " + CostMeter.describe(ledger.allTime()));
  }

  // Offline checks of Markdown rendering and code highlighting.
  private static void checkRender() {
    String md = String.join("\n",
        "### Fix",
        "Use **bold** and `inline` and *italic* and [docs](https://docs.mulesoft.com).",
        "- first",
        "```xml",
        "<!-- note -->",
        "<http:listener config-ref=\"cfg\" path=\"/api\" doc:name=\"#[vars.x]\"/>",
        "<ee:set-payload><![CDATA[%dw 2.0",
        "output application/json",
        "---",
        "{ok: true}]]></ee:set-payload>",
        "```",
        "```json",
        "{\"status\": \"UP\", \"count\": 2}",
        "```",
        "Done.");
    Rendered r = MarkdownRenderer.render(md, true);
    String text = r.text();
    int failed = 0;
    failed += check("no markdown markers left", !text.contains("**") && !text.contains("```") && !text.contains("###"));
    failed += check("bullet rendered", text.contains("•  first"));
    failed += check("link text kept, url hidden", text.contains("docs.") && !text.contains("(https://"));
    failed += check("two code blocks", r.blocks().size() == 2);
    failed += check("xml block detected", r.blocks().get(0).language() == Highlighter.Language.XML);
    failed += check("action links", text.contains("Copy   Insert at cursor"));
    failed += check("bold span", has(r, Span.Kind.BOLD, "bold"));
    failed += check("inline code span", has(r, Span.Kind.INLINE_CODE, "inline"));
    failed += check("heading span", has(r, Span.Kind.HEADING, "Fix"));
    failed += check("xml comment", has(r, Span.Kind.COMMENT, "<!-- note -->"));
    failed += check("xml tag", has(r, Span.Kind.TAG, "<http:listener"));
    failed += check("xml attribute name", has(r, Span.Kind.ATTR_NAME, "config-ref"));
    failed += check("xml attribute value", has(r, Span.Kind.ATTR_VALUE, "\"/api\""));
    failed += check("mule expression in attribute", has(r, Span.Kind.EXPRESSION, "#[vars.x]"));
    failed += check("dataweave header in CDATA", has(r, Span.Kind.DIRECTIVE, "%dw 2.0"));
    failed += check("dataweave keyword", has(r, Span.Kind.KEYWORD, "output"));
    failed += check("json key", has(r, Span.Kind.KEY, "\"status\""));
    failed += check("json string", has(r, Span.Kind.STRING, "\"UP\""));
    failed += check("json number", has(r, Span.Kind.NUMBER, "2"));
    failed += check("spans in bounds", r.spans().stream().allMatch(s -> s.start() >= 0 && s.start() + s.length() <= text.length()));
    System.out.println("\n" + ansi(r));
    System.exit(failed == 0 ? 0 : 1);
  }

  private static boolean has(Rendered r, Span.Kind kind, String text) {
    return r.spans().stream().anyMatch(s -> s.kind() == kind
        && r.text().substring(s.start(), s.start() + s.length()).equals(text));
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }

  // Terminal preview of the Studio styling.
  private static String ansi(Rendered r) {
    String text = r.text();
    String[] style = new String[text.length() + 1];
    List<Span> spans = new ArrayList<>(r.spans());
    spans.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::length).reversed()));
    for (Span s : spans) {
      String code = switch (s.kind()) {
        case BOLD, HEADING -> "1";
        case ITALIC, QUOTE -> "3";
        case INLINE_CODE, CODE_BLOCK, TABLE -> "36";
        case LINK, ACTION -> "4;34";
        case TAG -> "38;5;30";
        case ATTR_NAME -> "35";
        case ATTR_VALUE, STRING -> "38;5;124";
        case COMMENT -> "38;5;61";
        case NUMBER -> "32";
        case KEYWORD, DIRECTIVE -> "1;38;5;90";
        case KEY -> "34";
        case EXPRESSION -> "38;5;166";
      };
      for (int i = s.start(); i < s.start() + s.length(); i++) {
        style[i] = code;
      }
    }
    StringBuilder out = new StringBuilder();
    String current = null;
    for (int i = 0; i < text.length(); i++) {
      if (!Objects.equals(style[i], current)) {
        out.append("\u001b[0m");
        if (style[i] != null) {
          out.append("\u001b[").append(style[i]).append('m');
        }
        current = style[i];
      }
      out.append(text.charAt(i));
    }
    return out.append("\u001b[0m").toString();
  }

  private static int expect(ProjectTools tools, String name, String arguments, String expected) {
    String result = tools.execute(ToolExecutionRequest.builder().id("t").name(name).arguments(arguments).build());
    boolean ok = result.contains(expected);
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name + " " + arguments + " -> " + shorten(result));
    return ok ? 0 : 1;
  }

  private static final class DiskProject implements ProjectFiles {
    final Path root;

    DiskProject(Path root) {
      this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
      return root.getFileName().toString();
    }

    @Override
    public String read(String path) throws Exception {
      Path file = root.resolve(path).normalize();
      return file.startsWith(root) && Files.isRegularFile(file) ? Files.readString(file) : null;
    }

    @Override
    public List<String> list(String directory, int limit) throws Exception {
      Path start = root.resolve(directory).normalize();
      if (!start.startsWith(root) || !Files.isDirectory(start)) {
        return List.of();
      }
      try (Stream<Path> walk = Files.walk(start)) {
        return walk.filter(Files::isRegularFile)
            .map(p -> root.relativize(p).toString().replace('\\', '/'))
            .filter(p -> !ProjectPaths.isSkipped(p))
            .sorted()
            .limit(limit)
            .toList();
      }
    }
  }

  private static Connection connectionFor(Map<String, String> env, Provider provider) {
    String prefix = switch (provider) {
      case OPENROUTER -> "OPENROUTER";
      case OPENAI -> "OPENAI";
      case ANTHROPIC -> "ANTHROPIC";
      case MULESOFT_AI_GATEWAY -> "MULESOFT_AI_GATEWAY";
      case OLLAMA -> "OLLAMA";
      case CUSTOM -> "CUSTOM";
    };
    String baseUrl = firstNonBlank(env.get(prefix + "_BASE_URL"), provider.defaultBaseUrl);
    String model = firstNonBlank(env.get(prefix + "_MODEL"), provider.defaultModel);
    String apiKey = env.getOrDefault(prefix + "_API_KEY", "");
    if (baseUrl.isBlank()) {
      throw new IllegalStateException(prefix + "_BASE_URL is empty");
    }
    if (model.isBlank()) {
      throw new IllegalStateException(prefix + "_MODEL is empty");
    }
    if (provider.needsApiKey() && apiKey.isBlank()) {
      throw new IllegalStateException(prefix + "_API_KEY is empty");
    }
    return new Connection(provider, baseUrl, model, apiKey);
  }

  private static Provider parseProvider(String name) {
    String key = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return switch (key) {
      case "openrouter" -> Provider.OPENROUTER;
      case "openai" -> Provider.OPENAI;
      case "anthropic" -> Provider.ANTHROPIC;
      case "mulesoft", "mulesoft_ai_gateway", "gateway" -> Provider.MULESOFT_AI_GATEWAY;
      case "ollama" -> Provider.OLLAMA;
      case "custom" -> Provider.CUSTOM;
      default -> throw new IllegalArgumentException("Unknown provider: " + name);
    };
  }

  private static Map<String, String> loadEnv(Path path) throws Exception {
    Map<String, String> env = new LinkedHashMap<>();
    List<String> lines = Files.isRegularFile(path) ? Files.readAllLines(path) : List.of();
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      env.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    System.getenv().forEach(env::putIfAbsent);
    return env;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a.trim();
    }
    return b == null ? "" : b.trim();
  }

  private static String shorten(String value) {
    String text = value == null ? "" : value.strip().replace('\n', ' ');
    return text.length() > 80 ? text.substring(0, 80) + "…" : text;
  }

  private static String[] tail(String[] args) {
    return java.util.Arrays.copyOfRange(args, 1, args.length);
  }

  private static void usage() {
    System.err.println("""
        Usage:
          java Harness test-connection [provider...]
          java Harness list-models [provider...]
          java Harness chat [--provider name] [--model id] [--file path] [--mode ask|agent|plan] [--attach path] [--lines path:a-b] [--project dir [--apply]] "prompt"
          java Harness check-tools | check-render | check-history | check-attach | check-html [preview.html]
          java Harness check-updates [latest.json URL]
        Providers: openrouter openai anthropic mulesoft ollama custom
        Reads ../.env — never prints key values.
        """);
  }
}
