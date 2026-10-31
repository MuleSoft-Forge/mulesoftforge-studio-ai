import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.debug.DebugField;
import com.mulesoftforge.studio.ai.debug.DebugSession;
import com.mulesoftforge.studio.ai.debug.DebugSnapshot;
import com.mulesoftforge.studio.ai.debug.DebugStop;
import com.mulesoftforge.studio.ai.debug.DebugTools;
import com.mulesoftforge.studio.ai.debug.PersonalData;
import com.mulesoftforge.studio.ai.debug.ProcessorSource;
import com.mulesoftforge.studio.ai.debug.StudioObjects;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.SecretMask;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

// check-debug: the "Debug with AI" pieces that need no Studio, against events captured from Studio 7.28's debugger
// (fixtures/debug/stops, recorded with the development probe and trimmed).
final class DebugChecks {
  static final Path FIXTURES = Path.of("fixtures", "debug");
  private static final ObjectMapper JSON = new ObjectMapper();

  private DebugChecks() {}

  static void run() throws Exception {
    int failed = 0;
    failed += snapshots();
    failed += reader();
    failed += processorSource();
    failed += tools();
    failed += loop();
    System.exit(failed == 0 ? 0 : 1);
  }

  // A debugger that answers from the fixtures and records what it was asked.
  static final class FakeSession implements DebugSession {
    DebugStop stop;
    DebugSession.Evaluation next;
    DebugField expanded;
    boolean timeout;
    String lastScript;
    int evaluations;

    @Override
    public DebugStop current() {
      return stop;
    }

    @Override
    public DebugSession.Evaluation evaluate(String script, Duration limit) throws Exception {
      evaluations++;
      lastScript = script;
      if (timeout) {
        throw new TimeoutException();
      }
      return next;
    }

    @Override
    public DebugField expand(String accessor, Duration limit) {
      return expanded != null && accessor.equals(expanded.accessor()) ? expanded : null;
    }
  }

  static ToolExecutionRequest call(String tool, String argument, String value) throws Exception {
    return call2(tool, Map.of(argument, value));
  }

  static ToolExecutionRequest call2(String tool, Map<String, String> arguments) throws Exception {
    return ToolExecutionRequest.builder().id("t").name(tool).arguments(JSON.writeValueAsString(arguments)).build();
  }

  private static DebugSession.Evaluation capturedEvaluation() throws Exception {
    JsonNode eval = JSON.readTree(Files.readString(FIXTURES.resolve("evaluate-result.json")));
    return new DebugSession.Evaluation(true, eval.path("result").path("value").asText(), eval.path("mimeType").asText(),
        null, eval.path("ms").asLong());
  }

  private static int tools() throws Exception {
    int failed = 0;
    FakeSession session = new FakeSession();
    session.stop = stop("transform-error.json");
    session.next = capturedEvaluation();
    DebugTools tools = new DebugTools(session, new SecretMask(), new PersonalData());
    String out = tools.execute(call(DebugTools.EVALUATE, "script", "typeOf(payload)"));
    failed += check("bare expression sent as JSON", "output application/json --- typeOf(payload)".equals(session.lastScript));
    failed += check("result with media type and time", out.startsWith("RESULT (application/json, 17 ms):")
        && out.contains("\"probe\": \"ok\""));
    tools.execute(call(DebugTools.EVALUATE, "script", "%dw 2.0\noutput application/java\n---\npayload"));
    failed += check("full script sent unchanged", session.lastScript.startsWith("%dw 2.0\noutput application/java"));
    failed += check("progress names the expression", DebugTools.describe(call(DebugTools.EVALUATE, "script",
        "typeOf(payload)")).equals("Evaluating at the breakpoint: typeOf(payload)"));

    int before = session.evaluations;
    boolean refused = true;
    for (String script : List.of("readUrl(\"https://example.com\", \"text/plain\")", "lookup(\"other-flow\", payload)",
        "java!java::lang::System::exit(0)", "dw::Runtime::fail(\"x\")")) {
      refused &= tools.execute(call(DebugTools.EVALUATE, "script", script)).startsWith("REFUSED:");
    }
    failed += check("side effects refused and never sent", refused && session.evaluations == before);

    FakeSession streaming = new FakeSession();
    streaming.stop = new DebugStop("app", "c1", "flow/processors/0", "logger", List.of(), List.of(new DebugField(
        "payload", "java.io.ByteArrayInputStream", "", "payload", false, false, false, List.of())));
    streaming.next = new DebugSession.Evaluation(true, "\"x\"", "application/json", null, 1);
    DebugTools streamTools = new DebugTools(streaming, new SecretMask(), null);
    failed += check("a read-once payload stream is not consumed", streamTools.execute(call(DebugTools.EVALUATE,
        "script", "sizeOf(payload)")).startsWith("REFUSED:") && streaming.evaluations == 0);
    failed += check("other values still evaluate with a read-once payload", streamTools.execute(call(
        DebugTools.EVALUATE, "script", "vars.region")).startsWith("RESULT") && streaming.evaluations == 1);

    session.next = new DebugSession.Evaluation(false, null, null, "Unable to resolve reference of: sortBy", 5);
    out = tools.execute(call(DebugTools.EVALUATE, "script", "sortBy(payload, $)"));
    failed += check("DataWeave errors come back to the model", out.startsWith("DATAWEAVE ERROR (5 ms):")
        && out.contains("sortBy"));
    session.timeout = true;
    failed += check("a silent debugger times out", tools.execute(call(DebugTools.EVALUATE, "script", "payload"))
        .contains("no answer from the debugger after 15 s"));
    session.timeout = false;

    session.next = new DebugSession.Evaluation(true, "{\"email\": \"jane.doe@acme.com\", \"password\": \"s3cret-Value1\"}",
        "application/json", null, 3);
    out = tools.execute(call(DebugTools.EVALUATE, "script", "payload"));
    failed += check("results masked: personal data and secrets", !out.contains("jane.doe@acme.com")
        && out.contains("«email-1»") && !out.contains("s3cret-Value1") && out.contains("«masked-"));

    JsonNode expand = JSON.readTree(Files.readString(FIXTURES.resolve("expand-result.json"))).path("field");
    session.expanded = DebugStop.fieldFromJson(expand);
    out = tools.execute(call(DebugTools.EXPAND, "accessor", "attributes.lazyClientCertificateProvider"));
    failed += check("expand_field lists the loaded children without statics", out.startsWith("FIELD attributes")
        && out.contains("serializableLazyValue") && !out.contains("SERIALIZABLE_LAZY_VALUE_CLASS_NAME"));
    failed += check("expand_field on an unknown accessor explains", tools.execute(call(DebugTools.EXPAND, "accessor",
        "attributes.nothing")).startsWith("ERROR: no field attributes.nothing"));

    DebugStop stepped = stop("logger-get.json");
    session.stop = stepped;
    session.next = new DebugSession.Evaluation(true, "\"x\"", "application/json", null, 1);
    out = tools.execute(call(DebugTools.EVALUATE, "script", "vars.region"));
    failed += check("a result after the user stepped says where it is from", out.startsWith(
        "NOTE: the debugger has moved since this message was sent. It is now stopped at "
            + DebugSnapshot.processorLabel(stepped) + " in " + stepped.flowName()) && out.contains("\nRESULT ("));

    session.stop = null;
    failed += check("tools say when the debugger is not stopped", tools.execute(call(DebugTools.EVALUATE, "script",
        "payload")).contains("not stopped at a processor"));

    DebugSnapshot.Snapshot snapshot = DebugSnapshot.render(stop("transform-error.json"), null);
    Attachment event = Attachment.debugEvent("employees-app", snapshot.title(), snapshot.failed(), snapshot.text(),
        snapshot.truncated());
    failed += check("debug event attachment label", event.label().equals(
        "debug event at transform \"Transform Message\" in get:\\employees:employees-api-config (error)"));
    failed += check("an error stop suggests a fix", event.reportsFailure());
    Attachment withSecret = Attachment.debugEvent("app", "logger in main", false,
        "headers:\n  authorization = Bearer abcdefgh12345678\n  password=Sup3rSecret!", false);
    Attachment protectedEvent = new SecretMask().protect(withSecret);
    failed += check("secrets masked in debug events", !protectedEvent.content().contains("abcdefgh12345678")
        && !protectedEvent.content().contains("Sup3rSecret!"));
    return failed;
  }

  static boolean tool(String body, String name) {
    return java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"" + name + "\"").matcher(body).find();
  }

  static String reply(Map<String, Object> message, String finish) throws Exception {
    return JSON.writeValueAsString(Map.of("id", "x", "object", "chat.completion", "created", 1, "model", "m",
        "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
        "usage", Map.of("prompt_tokens", 5, "completion_tokens", 2, "total_tokens", 7)));
  }

  // The real chat loop against a local stub model: the model evaluates at the breakpoint, then answers.
  private static int loop() throws Exception {
    int failed = 0;
    List<String> bodies = new ArrayList<>();
    String toolCall = reply(Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call_1", "type",
        "function", "function", Map.of("name", DebugTools.EVALUATE, "arguments",
            JSON.writeValueAsString(Map.of("script", "typeOf(payload)")))))), "tool_calls");
    String answer = reply(Map.of("role", "assistant", "content", "The payload is Binary."), "stop");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      byte[] bytes = (body.contains("tool_call_id") || !body.contains(DebugTools.EVALUATE) ? answer : toolCall)
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1", "m", "");
      List<String> steps = new ArrayList<>();
      Progress progress = new Progress() {
        @Override
        public void step(String message) {
          steps.add(message);
        }

        @Override
        public boolean isCancelled() {
          return false;
        }
      };
      FakeSession session = new FakeSession();
      session.stop = stop("json-body.json");
      session.next = new DebugSession.Evaluation(true, "\"Binary\"", "application/json", null, 12);
      DebugSnapshot.Snapshot snapshot = DebugSnapshot.render(session.stop, null);
      Attachment event = Attachment.debugEvent("json-black-hole-app", snapshot.title(), snapshot.failed(),
          snapshot.text(), snapshot.truncated());

      ChatSession chat = new ChatSession();
      chat.debugger(session);
      ChatSession.Turn turn = chat.send(ChatModels.create(connection), ChatMode.ASK, "Why is this happening?",
          List.of(event), null, progress);
      String first = bodies.get(0);
      failed += check("loop: debug tools offered without a project", tool(first, DebugTools.EVALUATE)
          && tool(first, DebugTools.EXPAND) && !tool(first, "read_file"));
      failed += check("loop: debug guidance in the system prompt", first.contains("debugging a Mule app in Studio"));
      failed += check("loop: the event is attached and personal data masked",
          first.contains("LIVE MULE EVENT FROM STUDIO'S DEBUGGER") && !first.contains("jane.doe@acme.com")
              && first.contains("«email-1»"));
      failed += check("loop: the evaluation ran against the session",
          "output application/json --- typeOf(payload)".equals(session.lastScript));
      failed += check("loop: the result went back to the model", bodies.size() == 2
          && bodies.get(1).contains("RESULT (application/json, 12 ms)"));
      failed += check("loop: answer and activity", turn.text().equals("The payload is Binary.")
          && turn.activity().contains("evaluated 1 expression at the breakpoint"));
      failed += check("loop: steps name the evaluation and the masking",
          steps.contains("Evaluating at the breakpoint: typeOf(payload)")
              && steps.stream().anyMatch(s -> s.startsWith("Hid ") && s.endsWith("from the live event before sending")));

      bodies.clear();
      session.stop = null;
      new ChatSession() {
        {
          debugger(session);
        }
      }.send(ChatModels.create(connection), ChatMode.ASK, "Hello", List.of(), null, progress);
      failed += check("loop: no debug tools or guidance once the debugger moves on", bodies.size() == 1
          && !bodies.get(0).contains(DebugTools.EVALUATE) && !bodies.get(0).contains("debugging a Mule app"));

      bodies.clear();
      session.stop = stop("json-body.json");
      ChatSession unmasked = new ChatSession();
      unmasked.debugger(session);
      unmasked.maskPersonalData(false);
      unmasked.send(ChatModels.create(connection), ChatMode.ASK, "Explain", List.of(event), null, progress);
      failed += check("loop: personal data sent when masking is off", bodies.get(0).contains("jane.doe@acme.com"));
    } finally {
      server.stop(0);
    }
    return failed;
  }

  // debug-snapshot <fixture>: what the model would see for a captured stop.
  static void print(String name) throws Exception {
    System.out.println(DebugSnapshot.render(stop(name), new PersonalData()).text());
  }

  static DebugStop stop(String name) throws Exception {
    return DebugStop.fromJson(Files.readString(FIXTURES.resolve("stops").resolve(name)));
  }

  private static int snapshots() throws Exception {
    int failed = 0;
    DebugSnapshot.Snapshot s = DebugSnapshot.render(stop("logger-get.json"), null);
    String t = s.text();
    failed += check("logger stop: processor and flow", t.contains("STOPPED AT: logger \"Logger\"")
        && t.contains("Flow: get:\\employees:employees-api-config"));
    failed += check("logger stop is not an error", !s.failed() && !t.contains("ERROR:"));
    failed += check("empty GET body shown as empty with its media type",
        t.contains("PAYLOAD [payload] — */*; charset=UTF-8") && t.contains("(empty)"));
    failed += check("attributes listed field by field", t.contains("method [attributes.method] = GET")
        && t.contains("requestPath [attributes.requestPath] = /api/employees"));
    failed += check("unloaded headers point at expand_field", t.contains("headers [attributes.headers]")
        && t.contains("expand_field attributes.headers"));
    failed += check("variables listed", t.contains("outboundHeaders [vars.outboundHeaders]"));
    failed += check("Java noise left out", !t.contains("serialVersionUID") && !t.contains("lazyClientCertificate")
        && !t.contains("inaccessible") && !t.contains("[Not accessible"));
    failed += check("processor settings shown", t.contains("PROCESSOR SETTINGS, AS THE RUNTIME EVALUATED THEM")
        && t.contains("level = INFO"));

    s = DebugSnapshot.render(stop("transform-error.json"), null);
    t = s.text();
    failed += check("error stop flagged", s.failed());
    failed += check("error type and failing line", t.contains("ERROR: MULE:EXPRESSION")
        && t.contains("employees-api.xml:173"));
    failed += check("DataWeave error line kept",
        t.contains("var isAdmin = upper(payload.scope default \"\") contains \"ADMIN\""));
    failed += check("failing XML included once", t.contains("XML of the failing component:")
        && count(t, "doc:id=\"28e05cd1-81e5-45c3-b8bb-51e00217f348\"") == 1);
    failed += check("Java exception dump left out", !t.contains("java.lang.Throwable") && !t.contains("backtrace")
        && !t.contains("serialVersionUID"));
    failed += check("script not repeated in the description when the XML has it",
        count(t, "var employees = [") == 1 && t.contains("(the script is in the XML below)"));
    failed += check("title names processor and flow",
        s.title().equals("transform \"Transform Message\" in get:\\employees:employees-api-config"));

    t = DebugSnapshot.render(stop("error-handler.json"), null).text();
    failed += check("error handler stop keeps its flow",
        t.contains("Flow: employees-api-main (processor path employees-api-main/errorHandler/7/processors/0)"));

    DebugStop body = stop("json-body.json");
    t = DebugSnapshot.render(body, null).text();
    failed += check("JSON body shown as text with its media type", t.contains("\"binType\": \"ant\"")
        && t.contains("application/json; charset=UTF-8"));
    failed += check("personal data kept when masking is off", t.contains("jane.doe@acme.com"));
    PersonalData personal = new PersonalData();
    t = DebugSnapshot.render(body, personal).text();
    failed += check("emails masked when masking is on", !t.contains("jane.doe@acme.com") && t.contains("«email-1»"));
    failed += check("masked values counted", personal.total() > 0);

    s = DebugSnapshot.render(stop("db-insert.json"), null);
    t = s.text();
    failed += check("database insert shows the SQL it ran", t.contains("INSERT INTO payload_events"));
    failed += check("Java map payload listed by key",
        t.contains("PAYLOAD [payload] — application/java; charset=UTF-8, LinkedHashMap, 9 entries")
            && t.contains("bin_type [payload.bin_type] = ant"));
    failed += check("object identities left out", !t.contains("PgConnection@") && !t.contains("StatementResult@"));
    failed += check("large event cut to fit one message", t.length() <= DebugSnapshot.MAX_CHARS + 200);

    DebugField huge = new DebugField("payload", "java.lang.String", "x".repeat(50_000), "payload", false, false, false,
        List.of());
    t = DebugSnapshot.render(new DebugStop("app", "c1", "flow/processors/0", "logger", List.of(), List.of(huge)),
        null).text();
    failed += check("long payload cut with a note", t.length() < 14_000 && t.contains("(cut, 38000 more characters)"));

    PersonalData phones = new PersonalData();
    String masked = phones.mask("call +44 20 7946 0958 or (555) 123-4567; order 2026-10-31 id 12345678");
    failed += check("phone numbers masked, dates and ids kept",
        masked.equals("call «phone-1» or «phone-2»; order 2026-10-31 id 12345678"));
    failed += check("same value, same placeholder", phones.mask("again +44 20 7946 0958").equals("again «phone-1»"));
    return failed;
  }

  // Stand-ins with the getter names of Studio's debugger objects.
  public static final class Definition {
    private final String name;
    private final String className;
    private final String value;
    private final String accessor;
    private final int modifiers;
    private final boolean unloaded;
    private final List<Definition> children;

    Definition(String name, String className, String value, String accessor, int modifiers, boolean unloaded,
        List<Definition> children) {
      this.name = name;
      this.className = className;
      this.value = value;
      this.accessor = accessor;
      this.modifiers = modifiers;
      this.unloaded = unloaded;
      this.children = children;
    }

    public String getName() {
      return name;
    }

    public String getClassName() {
      return className;
    }

    public String getValue() {
      return value;
    }

    public String getDwAccessor() {
      return accessor;
    }

    public int getModifiers() {
      return modifiers;
    }

    public boolean isHasUnloadedChildren() {
      return unloaded;
    }

    public boolean isError() {
      return false;
    }

    public List<Definition> getInnerElements() {
      return children;
    }

    public String getPath() {
      return "FieldPath{" + accessor + "}";
    }
  }

  public static final class Processor {
    public String getPath() {
      return "main/processors/2";
    }

    public String getIdentifier() {
      return "set-payload";
    }

    public List<Definition> getFields() {
      return List.of(new Definition("doc:name", "java.lang.String", "Set Payload", null, 0, false, List.of()));
    }
  }

  public static final class MessageId {
    public String getCorrelationId() {
      return "corr-1";
    }
  }

  public static final class MessageInfo {
    public String getAppName() {
      return "demo-app";
    }

    public MessageId getMuleMessageInfoId() {
      return new MessageId();
    }

    public Processor getMessageProcessorInfo() {
      return new Processor();
    }

    public List<Definition> getDefinitions() {
      Definition headers = new Definition("headers", "java.util.Map", "size = 3", "attributes.headers", 0, true,
          List.of());
      Definition id = new Definition("serialVersionUID", "java.lang.Long", "1", "attributes.serialVersionUID", 26, false,
          List.of());
      return List.of(new Definition("payload", "java.lang.String", "hello", "payload", 0, false, List.of()),
          new Definition("attributes", "org.example.Attributes", "org.example.Attributes@1a2b3c", "attributes", 0,
              false, List.of(id, headers)));
    }
  }

  private static int reader() {
    int failed = 0;
    Map<String, Object> handles = new HashMap<>();
    DebugStop stop = StudioObjects.stop(new MessageInfo(), handles);
    failed += check("reader: app, correlation ID and processor", "demo-app".equals(stop.app())
        && "corr-1".equals(stop.correlationId()) && "main/processors/2".equals(stop.processorPath())
        && "set-payload".equals(stop.processorType()) && "Set Payload".equals(stop.docName()));
    DebugField attributes = stop.field("attributes");
    failed += check("reader: nested fields, static flag and unloaded flag", attributes != null
        && attributes.child("serialVersionUID").isStatic() && attributes.child("headers").unloaded());
    failed += check("reader: keeps Studio's field paths by accessor for loading more",
        "FieldPath{attributes.headers}".equals(handles.get("attributes.headers")));
    String text = DebugSnapshot.render(stop, null).text();
    failed += check("reader output renders like a captured event", text.contains("PAYLOAD [payload] — String")
        && text.contains("hello") && text.contains("expand_field attributes.headers")
        && !text.contains("serialVersionUID") && !text.contains("@1a2b3c"));
    return failed;
  }

  // A project folder under fixtures/debug, read the way Studio's project view would.
  static ProjectFiles project(String name) {
    return project(FIXTURES.resolve(name), name);
  }

  static ProjectFiles project(Path folder, String name) {
    Path root = folder.toAbsolutePath().normalize();
    return new ProjectFiles() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String read(String path) throws Exception {
        Path file = root.resolve(path).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? Files.readString(file) : null;
      }

      @Override
      public List<String> list(String directory, int limit) throws Exception {
        Path start = root.resolve(directory).normalize();
        if (!Files.isDirectory(start)) {
          return List.of();
        }
        try (Stream<Path> walk = Files.walk(start)) {
          return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
              .sorted().limit(limit).toList();
        }
      }
    };
  }

  private static int processorSource() throws Exception {
    int failed = 0;
    ProjectFiles employees = project("employees-app");
    ProcessorSource.Located transform = ProcessorSource.byDocId(employees, "28e05cd1-81e5-45c3-b8bb-51e00217f348");
    failed += check("finds the Transform Message by doc:id at the line Studio reported", transform != null
        && transform.path().equals("src/main/mule/employees-api.xml") && transform.line() == 173
        && transform.xml().startsWith("<ee:transform") && transform.xml().endsWith("</ee:transform>")
        && transform.xml().contains("var isAdmin"));
    ProcessorSource.Located logger = ProcessorSource.byDocId(employees, "4c369358-a880-499c-8b79-9b514362769e");
    failed += check("finds a self-closing processor", logger != null && logger.line() == 171
        && logger.xml().startsWith("<logger") && logger.xml().endsWith("/>"));
    failed += check("ignores elements inside comments",
        ProcessorSource.byDocId(employees, "5444c035-fcb0-4388-a908-1e6c90fd6560") == null);
    failed += check("unknown doc:id finds nothing", ProcessorSource.byDocId(employees, "no-such-id") == null);

    ProjectFiles blackHole = project("json-black-hole-app");
    ProcessorSource.Located choice = ProcessorSource.byDocId(blackHole, "aafc78c1-7dc7-4ca7-9590-bb7b8505aba3");
    failed += check("a scope comes back whole, with its routes", choice != null && choice.line() == 134
        && choice.xml().endsWith("</choice>") && choice.xml().contains("<when expression='#[payload.binType == \"ant\"]'>")
        && choice.xml().contains("<db:insert"));
    DebugStop insert = stop("db-insert.json");
    ProcessorSource.Located located = ProcessorSource.find(blackHole, insert);
    failed += check("a captured stop finds its processor inside a scope", located != null && located.line() == 156
        && located.xml().startsWith("<db:insert") && located.xml().endsWith("</db:insert>"));
    ProcessorSource.Located flow = ProcessorSource.byFlowName(blackHole,
        "post:\\webhook:application\\json:json-black-hole-config");
    failed += check("falls back to the flow by name", flow != null && flow.line() == 132
        && flow.xml().startsWith("<flow"));

    String text = DebugSnapshot.render(stop("logger-get.json"), null,
        ProcessorSource.find(employees, stop("logger-get.json"))).text();
    failed += check("snapshot includes the processor's XML with file and line",
        text.contains("XML OF THE PROCESSOR (src/main/mule/employees-api.xml line 171):") && text.contains("<logger level=\"INFO\""));
    text = DebugSnapshot.render(stop("transform-error.json"), null,
        ProcessorSource.find(employees, stop("transform-error.json"))).text();
    failed += check("error stops use the debugger's XML, not a second copy", !text.contains("XML OF THE PROCESSOR")
        && count(text, "<ee:transform") == 1);
    return failed;
  }

  static int count(String text, String part) {
    int n = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
      n++;
    }
    return n;
  }

  static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
