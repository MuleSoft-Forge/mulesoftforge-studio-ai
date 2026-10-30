import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.connector.ConnectorCatalog;
import com.mulesoftforge.studio.ai.edit.AttachmentReader;
import com.mulesoftforge.studio.ai.edit.ChangeOffer;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.edit.LineDiff;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectPaths;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.history.ChatRecord;
import com.mulesoftforge.studio.ai.history.ChatStore;
import com.mulesoftforge.studio.ai.history.RawLog;
import com.mulesoftforge.studio.ai.history.UsageLog;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.CallUsage;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.CostMeter;
import com.mulesoftforge.studio.ai.llm.DataWeaveFix;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.ModelLists;
import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.RawCapture;
import com.mulesoftforge.studio.ai.llm.SecretMask;
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
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * CLI smoke tests for the Studio AI LLM layer, outside Eclipse.
 *
 *   java Harness test-connection [provider...]
 *   java Harness list-models [provider...]
 *   java Harness chat [--provider name] [--model id] [--file path] [--mode ask|agent|plan] [--attach path] [--lines path:a-b] [--image file.png] [--project dir [--apply]] "prompt"
 *   java Harness check-tools
 *   java Harness check-mule
 *   java Harness check-project /path/to/mule-app [studio-workspace]
 *   java Harness check-connectors
 *   java Harness connector /path/to/mule-app ["db" | "db:select"] [studio-workspace]
 *
 * Reads keys from ../.env (KEY=value lines). Never prints secret values.
 */
public class Harness {
  private static final Path ENV = Path.of("..").resolve(".env").toAbsolutePath().normalize();
  private static String modelOverride;
  private static final String IMAGE_SPEC = "image:";
  private static ChatMode mode = ChatMode.AGENT;
  private static final Path DEFAULT_WORKSPACE = Path.of(System.getProperty("user.home"),
      "AnypointStudio", "studio-workspace-728");
  private static final Path DEFAULT_STATE = DEFAULT_WORKSPACE.resolve(".metadata").resolve(".plugins")
      .resolve("com.mulesoftforge.studio.ai");
  private static Path workspace = DEFAULT_WORKSPACE;

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
            case "--image" -> attach.add(IMAGE_SPEC + args[++i]);
            case "--project" -> project = Path.of(args[++i]);
            case "--apply" -> apply = true;
            case "--model" -> modelOverride = args[++i];
            case "--workspace" -> workspace = Path.of(args[++i]);
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
      case "check-mule" -> checkMule();
      case "check-connectors" -> checkConnectors();
      case "check-project" -> {
        Path models = models(args.length > 2 ? Path.of(args[2]) : DEFAULT_WORKSPACE);
        List<Problem> problems = MuleChecks.check(MuleChecks.snapshot(new DiskProject(Path.of(args[1])), null),
            models == null ? null : ConnectorCatalog.at(models));
        System.out.print(problems.isEmpty() ? "No problems found\n" : MuleChecks.describe(problems));
      }
      case "connector" -> {
        Path models = models(args.length > 3 ? Path.of(args[3]) : DEFAULT_WORKSPACE);
        ProjectTools tools = new ProjectTools(new DiskProject(Path.of(args[1]), models), new ChangeSet(), false);
        System.out.println(tools.execute(ToolExecutionRequest.builder().id("t").name("connector_reference")
            .arguments(new ObjectMapper().writeValueAsString(Map.of("component", args.length > 2 ? args[2] : "")))
            .build()));
      }
      case "check-attach" -> checkAttach();
      case "check-secrets" -> checkSecrets();
      case "check-html" -> checkHtml(args.length > 1 ? Path.of(args[1]) : null);
      case "check-render" -> checkRender();
      case "check-history" -> checkHistory();
      case "check-raw-log" -> checkRawLog();
      case "check-auto" -> checkAuto();
      case "check-draft" -> checkDraft();
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
      connection = connection.withModel(modelOverride);
    }
    DiskProject project = projectDir == null ? null : new DiskProject(projectDir, models(workspace));
    List<Attachment> attachments = new ArrayList<>();
    if (file != null) {
      String shown = projectDir != null && file.toAbsolutePath().startsWith(projectDir.toAbsolutePath())
          ? projectDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/')
          : file.toString();
      attachments.add(Attachment.openFile(project == null ? null : project.name(), shown, Files.readString(file)));
    }
    int images = 0;
    for (String spec : attach) {
      if (spec.startsWith(IMAGE_SPEC)) {
        Path picture = Path.of(spec.substring(IMAGE_SPEC.length()));
        BufferedImage pixels = ImageIO.read(picture.toFile());
        if (pixels == null) {
          throw new IllegalArgumentException("Not a readable image: " + picture);
        }
        String fileName = picture.toString().toLowerCase(Locale.ROOT);
        String mime = fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ? "image/jpeg" : "image/png";
        attachments.add(Attachment.image("pasted image " + ++images, pixels.getWidth(), pixels.getHeight(), mime,
            Files.readAllBytes(picture)));
      } else if (project == null) {
        throw new IllegalArgumentException("--attach and --lines need --project");
      } else {
        attachments.add(attachmentFor(project, spec));
      }
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
    for (ChangeOffer offer : turn.offers()) {
      System.out.println("OFFERED  " + offer.label());
    }
    if (!turn.problems().isEmpty()) {
      System.out.print("\nCHECKS  still failing:\n" + MuleChecks.describe(turn.problems()));
    }
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
    failed += check("Ask/Plan offer only read tools and offer_changes", readOnly.specifications().stream()
        .map(spec -> spec.name()).toList()
        .equals(List.of("list_files", "read_file", "connector_reference", "check_project", "offer_changes")));
    failed += expect(readOnly, "write_file", "{\"path\":\"src/x.xml\",\"content\":\"x\"}",
        "ERROR: this mode cannot change files");
    failed += expect(readOnly, "offer_changes", "{\"items\":[]}", "ERROR: items must be");
    failed += expect(readOnly, "offer_changes", "{\"items\":[{\"files\":[\"pom.xml\"]}]}", "ERROR: every item");
    failed += expect(readOnly, "offer_changes", "{\"items\":[{\"title\":\"Fix the classifier\",\"files\":[\"pom.xml\"]},"
        + "{\"title\":\"Remove the unused sockets connector\"}]}", "Offered 2 changes");
    List<ChangeOffer> offered = readOnly.offers();
    failed += check("offers are recorded, with files", offered.size() == 2
        && offered.get(0).label().equals("Fix the classifier (pom.xml)")
        && offered.get(1).label().equals("Remove the unused sockets connector"));
    failed += check("offering changes writes nothing", Files.readString(root.resolve("src/main/mule/api.xml"))
        .contains("name=\"a\""));
    failed += expect(readOnly, "offer_changes", "{\"items\":[\"Use secure properties\"]}", "Offered 1 change");
    failed += check("a later offer replaces the earlier one", readOnly.offers().size() == 1);
    String picked = ChangeOffer.prompt(offered.subList(1, 2));
    failed += check("the request names only the ticked changes", picked.contains("1. Remove the unused sockets")
        && !picked.contains("classifier"));
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

    String openRouterKey = "sk-or-v1-" + "0123456789abcdef".repeat(4);
    String anthropicKey = "sk-ant-api03-" + "x".repeat(95);
    failed += check("an OpenRouter key fits OpenRouter", Provider.OPENROUTER.keyProblem(openRouterKey) == null);
    failed += check("an Anthropic key under OpenRouter is named",
        "This looks like an Anthropic key, not an OpenRouter key.".equals(Provider.OPENROUTER.keyProblem(anthropicKey)));
    failed += check("an OpenRouter key under OpenAI is named",
        "This looks like an OpenRouter key, not an OpenAI key.".equals(Provider.OPENAI.keyProblem(openRouterKey)));
    failed += check("an OpenAI project key fits OpenAI", Provider.OPENAI.keyProblem("sk-proj-" + "a".repeat(150)) == null);
    failed += check("a key with a line break is caught",
        Provider.ANTHROPIC.keyProblem("sk-ant-abc\ndef").startsWith("The key contains spaces"));
    failed += check("a missing key is caught only where one is needed",
        Provider.OPENROUTER.keyProblem("") != null && Provider.OLLAMA.keyProblem("") == null);
    failed += check("a Google AI key fits Gemini",
        Provider.GEMINI.keyProblem("AIza" + "x".repeat(35)) == null
            && Provider.GEMINI.keyProblem("AQ." + "x".repeat(40)) == null);
    failed += check("an OpenAI key under Gemini is named",
        Provider.GEMINI.keyProblem("sk-proj-" + "a".repeat(20)).contains("OpenAI"));
    failed += check("describeKey shows only the start and end",
        Provider.describeKey(openRouterKey).equals("sk-or-…cdef, 73 characters"));
    failed += check("gateway needs client credentials, not an API key",
        Provider.MULESOFT_AI_GATEWAY.needsClientCredentials() && !Provider.MULESOFT_AI_GATEWAY.needsApiKey());
    failed += check("gateway missing client credentials is caught",
        Provider.MULESOFT_AI_GATEWAY.credentialsProblem("", "", "") != null
            && Provider.MULESOFT_AI_GATEWAY.credentialsProblem("", "id", "secret") == null);
    failed += check("gateway headers are client_id and client_secret",
        ChatModels.gatewayHeaders(new Connection(Provider.MULESOFT_AI_GATEWAY, "https://gw.example/v1", "m", "", "cid",
            "csecret")).equals(Map.of("client_id", "cid", "client_secret", "csecret")));
    failed += check("non-gateway has no client headers",
        ChatModels.gatewayHeaders(new Connection(Provider.OPENAI, "https://api.openai.com/v1", "gpt-5-mini", "sk"))
            .isEmpty());
    failed += check("bedrock region from plain id",
        "us-west-2".equals(ChatModels.bedrockRegion("us-west-2")));
    failed += check("bedrock region from runtime URL",
        "eu-west-1".equals(ChatModels.bedrockRegion("https://bedrock-runtime.eu-west-1.amazonaws.com")));
    System.exit(failed == 0 ? 0 : 1);
  }

  private static final String MULE_HEAD = """
      <?xml version="1.0" encoding="UTF-8"?>
      <mule xmlns="http://www.mulesoft.org/schema/mule/core"
            xmlns:http="http://www.mulesoft.org/schema/mule/http"
            xmlns:doc="http://www.mulesoft.org/schema/mule/documentation"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"%s
            xsi:schemaLocation="http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd
              http://www.mulesoft.org/schema/mule/http http://www.mulesoft.org/schema/mule/http/current/mule-http.xsd%s">
      """;
  private static final String GLOBAL_BODY = """
        <configuration-properties doc:name="Configuration properties" file="config.yaml"/>
        <http:listener-config name="HTTP_Listener_config">
          <http:listener-connection host="0.0.0.0" port="${http.port}"/>
        </http:listener-config>
        <os:object-store name="store"/>
      """;
  private static final String API_BODY = """
        <flow name="main">
          <http:listener doc:name="Listener" config-ref="HTTP_Listener_config" path="/orders"/>
          <flow-ref doc:name="Helper" name="helper"/>
          <error-handler>
            <on-error-propagate type="MULE:CONNECTIVITY"/>
          </error-handler>
        </flow>
        <sub-flow name="helper">
          <logger level="INFO" message="${api.timeout}"/>
        </sub-flow>
      """;
  private static final String OS_NS = "\n      xmlns:os=\"http://www.mulesoft.org/schema/mule/os\"";
  private static final String OS_LOCATION =
      "\n        http://www.mulesoft.org/schema/mule/os http://www.mulesoft.org/schema/mule/os/current/mule-os.xsd";
  private static final String KAFKA_NS = "\n      xmlns:kafka=\"http://www.mulesoft.org/schema/mule/kafka\"";
  private static final String KAFKA_LOCATION = "\n        http://www.mulesoft.org/schema/mule/kafka"
      + " http://www.mulesoft.org/schema/mule/kafka/current/mule-kafka.xsd";
  private static final String POM = """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <dependencies>
          <dependency><groupId>org.mule.connectors</groupId><artifactId>mule-http-connector</artifactId><classifier>mule-plugin</classifier></dependency>
          <dependency><groupId>org.mule.connectors</groupId><artifactId>mule-objectstore-connector</artifactId><classifier>mule-plugin</classifier></dependency>
          %s
        </dependencies>
      </project>
      """;

  private static String muleFile(String namespaces, String locations, String body) {
    return MULE_HEAD.formatted(namespaces, locations) + body + "</mule>\n";
  }

  // Offline: each Mule check, that proposals answer only for problems they add, and the tool wiring.
  private static void checkMule() throws Exception {
    Map<String, String> base = new TreeMap<>();
    base.put("pom.xml", POM.formatted(""));
    base.put("src/main/resources/config.yaml", "http:\n  port: \"8081\"\napi:\n  # seconds\n  timeout: \"30\"\n");
    base.put("src/main/resources/secure.properties", "db.password=![abc]\n");
    base.put("src/main/mule/global.xml", muleFile(OS_NS, OS_LOCATION, GLOBAL_BODY));
    base.put("src/main/mule/api.xml", muleFile("", "", API_BODY));
    String api = "src/main/mule/api.xml";
    int failed = 0;

    failed += check("the sample project passes", MuleChecks.check(base).isEmpty());
    failed += expectProblem("dangling config-ref", base, api,
        muleFile("", "", API_BODY.replace("HTTP_Listener_config", "Missing_config")),
        "config-ref=\"Missing_config\" does not match the name of any global element");
    failed += expectProblem("dangling flow-ref", base, api,
        muleFile("", "", API_BODY.replace("name=\"helper\"/>", "name=\"nowhere\"/>")),
        "flow-ref name=\"nowhere\" does not match any flow or sub-flow");
    failed += expectProblem("undefined property", base, api,
        muleFile("", "", API_BODY.replace("${api.timeout}", "${api.missing}")), "${api.missing} is not defined");
    failed += expectProblem("secure property defined in a .properties file", base, api,
        muleFile("", "", API_BODY.replace("${api.timeout}", "${secure::db.password}")), null);
    failed += expectProblem("invented MULE error type", base, api,
        muleFile("", "", API_BODY.replace("MULE:CONNECTIVITY", "MULE:NOT_FOUND")),
        "MULE:NOT_FOUND is not a Mule core error type");
    failed += expectProblem("raise-error with a connector namespace", base, api,
        muleFile("", "", API_BODY.replace("<logger", "<raise-error type=\"HTTP:NOT_FOUND\"/>\n    <logger")),
        "HTTP belongs to a connector or module");
    failed += expectProblem("raise-error with an app namespace", base, api,
        muleFile("", "", API_BODY.replace("<logger", "<raise-error type=\"APP:NOT_FOUND\"/>\n    <logger")), null);
    failed += expectProblem("a processor after the flow's error-handler", base, api,
        muleFile("", "", API_BODY.replace("    </error-handler>\n", "    </error-handler>\n    <logger level=\"INFO\"/>\n")),
        "<logger> comes after the <error-handler> of <flow>");
    failed += expectProblem("an error-handler in a sub-flow", base, api, muleFile("", "", API_BODY.replace(
        "</sub-flow>", "  <error-handler><on-error-continue type=\"MULE:ANY\"/></error-handler>\n  </sub-flow>")),
        "a sub-flow cannot have an <error-handler>");
    failed += expectProblem("an error-handler last in a try scope", base, api, muleFile("", "", API_BODY.replace(
        "<logger level=\"INFO\" message=\"${api.timeout}\"/>", "<try><logger level=\"INFO\"/>"
            + "<error-handler><on-error-continue type=\"MULE:ANY\"/></error-handler></try>")), null);
    failed += expectProblem("MULE:VALIDATION is a core error type", base, api,
        muleFile("", "", API_BODY.replace("MULE:CONNECTIVITY", "MULE:VALIDATION")), null);
    failed += expectProblem("error-mapping into a connector namespace", base, api, muleFile("", "", API_BODY.replace(
        "path=\"/orders\"/>", "path=\"/orders\">\n      <error-mapping sourceType=\"MULE:ANY\" targetType=\"HTTP:BAD\"/>\n"
            + "    </http:listener>")), "error-mapping targetType \"HTTP:BAD\": HTTP belongs to a connector");
    Map<String, String> exchange = new TreeMap<>(base);
    exchange.put("pom.xml", POM.formatted(
        "<dependency><groupId>0b1e2f</groupId><artifactId>mule-plugin-openrouter-llm-api</artifactId></dependency>"));
    failed += expectProblem("Exchange connector named after its asset, mule-mule-*.xsd", exchange, api, muleFile(
        "\n      xmlns:open-router-api=\"http://www.mulesoft.org/schema/mule/mule-open-router-api\"",
        "\n        http://www.mulesoft.org/schema/mule/mule-open-router-api"
            + " http://www.mulesoft.org/schema/mule/mule-open-router-api/current/mule-mule-open-router-api.xsd",
        API_BODY.replace("<logger", "<open-router-api:chat config-ref=\"HTTP_Listener_config\"/>\n    <logger")), null);
    failed += expectProblem("unbound prefix", base, "src/main/mule/db.xml",
        muleFile("", "", "  <flow name=\"query\">\n    <db:select/>\n  </flow>\n"), "is not bound");
    failed += expectProblem("malformed XML", base, api,
        muleFile("", "", API_BODY.replaceFirst("</flow>", "")), "not well-formed XML");
    String kafkaBody = API_BODY.replace("<logger", "<kafka:publish config-ref=\"HTTP_Listener_config\"/>\n    <logger");
    failed += expectProblem("connector namespace without a pom.xml dependency", base, api,
        muleFile(KAFKA_NS, KAFKA_LOCATION, kafkaBody), "namespace \"kafka\" is used but pom.xml has no dependency");
    Map<String, String> withKafka = new TreeMap<>(base);
    withKafka.put("pom.xml", POM.formatted(
        "<dependency><groupId>com.mulesoft.connectors</groupId><artifactId>mule-kafka-connector</artifactId></dependency>"));
    failed += expectProblem("connector namespace with its dependency", withKafka, api,
        muleFile(KAFKA_NS, KAFKA_LOCATION, kafkaBody), null);
    failed += expectProblem("namespace missing from xsi:schemaLocation", withKafka, api,
        muleFile(KAFKA_NS, "", kafkaBody), "kafka is used but missing from xsi:schemaLocation");
    failed += expectProblem("doc namespace in xsi:schemaLocation", base, api, muleFile("",
        "\n        http://www.mulesoft.org/schema/mule/documentation http://www.mulesoft.org/schema/mule/documentation/current/mule-documentation.xsd",
        API_BODY), "lists the doc namespace");
    failed += expectProblem("wrong schema URL", base, api,
        muleFile("", "", API_BODY).replace("current/mule-http.xsd", "current/http.xsd"),
        "should be http://www.mulesoft.org/schema/mule/http/current/mule-http.xsd");
    failed += expectProblem("missing configuration-properties file", base, "src/main/mule/global.xml",
        muleFile(OS_NS, OS_LOCATION, GLOBAL_BODY.replace("config.yaml", "missing.yaml")),
        "file=\"missing.yaml\" is not in src/main/resources");
    failed += expectProblem("malformed pom.xml", base, "pom.xml", "<project><dependencies></project>",
        "pom.xml line 1: not well-formed XML");

    Map<String, String> imported = new TreeMap<>(base);
    imported.put("src/main/mule/global.xml",
        muleFile(OS_NS, OS_LOCATION, GLOBAL_BODY + "  <import file=\"shared.xml\"/>\n"));
    imported.put(api, muleFile("", "", API_BODY.replace("HTTP_Listener_config", "Shared_config")));
    failed += check("references are not checked when a file imports another",
        MuleChecks.check(imported).stream().noneMatch(p -> p.message().contains("does not match")));
    Map<String, String> broken = new TreeMap<>(base);
    broken.put(api, muleFile("", "", API_BODY.replace("HTTP_Listener_config", "Missing_config")));
    failed += expectProblem("a problem already in the project is not blamed on a proposal", broken, api,
        broken.get(api).replace("level=\"INFO\"", "level=\"DEBUG\""), null);

    Path root = Files.createTempDirectory("msf-mule-checks");
    for (Map.Entry<String, String> file : broken.entrySet()) {
      Files.createDirectories(root.resolve(file.getKey()).getParent());
      Files.writeString(root.resolve(file.getKey()), file.getValue());
    }
    ChangeSet changes = new ChangeSet();
    ProjectTools tools = new ProjectTools(new DiskProject(root), changes);
    failed += expect(tools, "check_project", "{}", "1 problem found");
    failed += expect(tools, "edit_file", "{\"path\":\"" + api + "\",\"old_text\":\"${api.timeout}\","
        + "\"new_text\":\"${api.nope}\"}", "CHECKS: with your proposals applied, the project has 1 problem");
    failed += check("the turn reports the unfixed problem", tools.problemsIntroduced().size() == 1);
    failed += expect(tools, "edit_file", "{\"path\":\"" + api + "\",\"old_text\":\"${api.nope}\","
        + "\"new_text\":\"${api.timeout}\"}", "Checks passed.");
    failed += check("fixed proposals leave nothing to report", tools.problemsIntroduced().isEmpty());
    failed += expect(tools, "write_file", "{\"path\":\"README.md\",\"content\":\"hi\"}", "not written yet.");
    System.exit(failed == 0 ? 0 : 1);
  }

  private static Path models(Path studioWorkspace) {
    Path models = studioWorkspace.resolve(".mule").resolve("plugins-tmp").resolve("repository");
    return Files.isDirectory(models) ? models : null;
  }

  private static final String DB_MODEL = """
      {"minMuleVersion": "4.1.1", "extensionModel": {
        "name": "Database", "version": "9.9.9",
        "xmlDsl": {"prefix": "db", "namespace": "http://www.mulesoft.org/schema/mule/db"},
        "errors": [{"error": "DB:CONNECTIVITY"}, {"error": "DB:QUERY_EXECUTION"}, {"error": "MULE:ANY"}],
        "configurations": [{
          "name": "config", "description": "Database configuration.", "parameterGroupModels": [],
          "connectionProviders": [{"name": "my-sql", "description": "MySQL connection.", "parameterGroupModels": [
            {"name": "General", "parameters": [{"name": "host", "type": {"type": "String"}, "required": true,
              "expressionSupport": "SUPPORTED", "role": "BEHAVIOUR"}]}]}],
          "operations": [
            {"name": "bulkInsert", "description": "Inserts many rows. More detail here.", "errors": ["DB:CONNECTIVITY"],
             "parameterGroupModels": [{"name": "General", "parameters": [{"name": "config-ref",
               "type": {"type": "Object"}, "dslConfiguration": {"allowsReferences": true},
               "expressionSupport": "NOT_SUPPORTED", "role": "BEHAVIOUR"}]}]},
            {"name": "select", "description": "Selects data from a database. Streaming applies.",
             "errors": ["DB:CONNECTIVITY", "DB:QUERY_EXECUTION"],
             "parameterGroupModels": [{"name": "General", "parameters": [
               {"name": "config-ref", "type": {"type": "Object"}, "dslConfiguration": {"allowsReferences": true},
                "expressionSupport": "NOT_SUPPORTED", "role": "BEHAVIOUR"},
               {"name": "sql", "type": {"type": "String"}, "defaultValue": "", "layoutModel": {"text": true},
                "expressionSupport": "SUPPORTED", "role": "BEHAVIOUR",
                "description": "The text of the SQL query to be executed."},
               {"name": "inputParameters", "type": {"type": "Object"}, "role": "PRIMARY_CONTENT",
                "expressionSupport": "SUPPORTED"},
               {"name": "queryTimeout", "type": {"type": "Number"}, "defaultValue": "0",
                "expressionSupport": "SUPPORTED", "role": "BEHAVIOUR"},
               {"name": "fetchMode", "type": {"type": "String", "annotations": {"enum": {"values": ["ALL", "FIRST"], "type": "[Ljava.lang.String;"}}},
                "expressionSupport": "NOT_SUPPORTED", "role": "BEHAVIOUR"},
               {"name": "reconnectionStrategy", "type": {"type": "Object"}, "role": "BEHAVIOUR", "modelProperties":
                 {"org.mule.runtime.extension.api.property.InfrastructureParameterModelProperty": {}}}]}]}],
          "messageSources": [{"name": "listener", "description": "Polls a table.", "parameterGroupModels": []}]}],
        "operations": [], "messageSources": [], "constructs": [], "connectionProviders": []}}
      """;
  private static final String DB_NS = "\n      xmlns:db=\"http://www.mulesoft.org/schema/mule/db\"";
  private static final String DB_LOCATION =
      "\n        http://www.mulesoft.org/schema/mule/db http://www.mulesoft.org/schema/mule/db/current/mule-db.xsd";
  private static final String DB_BODY = """
        <db:config name="Database_Config">
          <db:my-sql-connection host="localhost"/>
        </db:config>
        <flow name="orders">
          <db:select config-ref="Database_Config" queryTimeout="5">
            <db:sql>SELECT 1</db:sql>
          </db:select>
          <error-handler>
            <on-error-continue type="DB:CONNECTIVITY"/>
          </error-handler>
        </flow>
      """;

  // Offline: reading Studio's connector models, the connector_reference tool, and the checks that use them.
  private static void checkConnectors() throws Exception {
    Path models = Files.createTempDirectory("msf-models");
    Path modelDir = models.resolve("4.12.3/org/mule/connectors/mule-db-connector/9.9.9");
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("mule-db-connector-9.9.9-mule-plugin.json"), DB_MODEL);
    String pom = POM.formatted("<dependency><groupId>org.mule.connectors</groupId><artifactId>mule-db-connector"
        + "</artifactId><version>${db.version}</version><classifier>mule-plugin</classifier></dependency>")
        .replace("<dependencies>", "<properties><db.version>9.9.9</db.version></properties>\n  <dependencies>");
    Path root = Files.createTempDirectory("msf-connectors");
    Files.writeString(root.resolve("pom.xml"), pom);
    ProjectTools tools = new ProjectTools(new DiskProject(root, models), new ChangeSet(), false);
    int failed = 0;
    failed += expect(tools, "connector_reference", "{}",
        "db: Database 9.9.9 (org.mule.connectors:mule-db-connector) — 1 config, 2 operations, 1 source");
    failed += expect(tools, "connector_reference", "{\"component\":\"db\"}", "db:bulk-insert — Inserts many rows.");
    failed += expect(tools, "connector_reference", "{\"component\":\"db\"}", "db:my-sql-connection (in db:config)");
    failed += expect(tools, "connector_reference", "{\"component\":\"db\"}",
        "Error types: DB:CONNECTIVITY, DB:QUERY_EXECUTION");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "sql: child element <db:sql>. The text of the SQL query to be executed.");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}", "queryTimeout: attribute, default 0");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "fetchMode: attribute, one of ALL, FIRST, no expressions");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "inputParameters: child element <db:input-parameters>");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "standard Mule elements for: reconnectionStrategy");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "Error types it can raise: DB:CONNECTIVITY, DB:QUERY_EXECUTION");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:bulkInsert\"}", "<db:bulk-insert> — operation");
    failed += expect(tools, "connector_reference", "{\"component\":\"db:selekt\"}",
        "No component db:selekt in Database 9.9.9");
    Files.writeString(root.resolve("pom.xml"), pom.replace("9.9.9", "1.0.0"));
    failed += expect(tools, "connector_reference", "{\"component\":\"db:select\"}",
        "Studio has not generated a model for org.mule.connectors:mule-db-connector:1.0.0");
    failed += expect(new ProjectTools(new DiskProject(root), new ChangeSet(), false), "connector_reference", "{}",
        "Connector models are not available");

    ConnectorCatalog catalog = ConnectorCatalog.at(models);
    Map<String, String> base = new TreeMap<>();
    base.put("pom.xml", pom);
    String flow = "src/main/mule/orders.xml";
    base.put(flow, muleFile(DB_NS, DB_LOCATION, DB_BODY));
    failed += check("the sample passes the model checks", MuleChecks.check(base, catalog).isEmpty());
    failed += expectProblem("unknown connector operation", base, catalog, flow,
        muleFile(DB_NS, DB_LOCATION, DB_BODY.replace("db:select", "db:selekt")),
        "<db:selekt> is not an operation, source or scope of Database 9.9.9");
    failed += expectProblem("unknown operation attribute", base, catalog, flow,
        muleFile(DB_NS, DB_LOCATION, DB_BODY.replace("queryTimeout=", "queryTimout=")),
        "<db:select> has no parameter \"queryTimout\" in Database 9.9.9");
    failed += expectProblem("connector error type that does not exist", base, catalog, flow,
        muleFile(DB_NS, DB_LOCATION, DB_BODY.replace("DB:CONNECTIVITY", "DB:NOT_REAL")),
        "Database 9.9.9 has no error type DB:NOT_REAL. Its error types are: DB:CONNECTIVITY, DB:QUERY_EXECUTION.");
    failed += expectProblem("a real connector error type", base, catalog, flow,
        muleFile(DB_NS, DB_LOCATION, DB_BODY.replace("DB:CONNECTIVITY", "DB:QUERY_EXECUTION")), null);
    failed += expectProblem("without models the connector checks are skipped", base, null, flow,
        muleFile(DB_NS, DB_LOCATION, DB_BODY.replace("db:select", "db:selekt")), null);
    System.exit(failed == 0 ? 0 : 1);
  }

  // expected null: the change must not add any problem.
  private static int expectProblem(String name, Map<String, String> base, String path, String content,
      String expected) {
    return expectProblem(name, base, null, path, content, expected);
  }

  private static int expectProblem(String name, Map<String, String> base, ConnectorCatalog catalog, String path,
      String content, String expected) {
    Map<String, String> after = new TreeMap<>(base);
    after.put(path, content);
    List<Problem> problems = MuleChecks.introduced(MuleChecks.check(base, catalog), MuleChecks.check(after, catalog));
    boolean ok = expected == null ? problems.isEmpty()
        : problems.stream().anyMatch(problem -> problem.toString().contains(expected));
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name + (ok && expected == null ? ""
        : "\n" + (problems.isEmpty() ? "      (no problems)\n" : MuleChecks.describe(problems).indent(4))));
    return ok ? 0 : 1;
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

    String script = "%dw 2.0\noutput application/json\n---\npayload map (e) -> e.name";
    Attachment dataWeave = Attachment.dataWeave("p", "src/main/mule/api.xml", null, null, script);
    failed += check("DataWeave script label", dataWeave.label().equals("DataWeave script in src/main/mule/api.xml"));
    failed += check("DataWeave selection label", Attachment.dataWeave("p", "src/main/mule/api.xml", 4, 4, "x")
        .label().equals("DataWeave line 4 in src/main/mule/api.xml"));
    failed += check("DataWeave does not report a failure", !dataWeave.reportsFailure());
    failed += check("Fix prompt names the selection and problems", DataWeaveFix.prompt("lines 2–4", true)
        .contains("problems Studio reports") && DataWeaveFix.prompt("lines 2–4", true).contains("Focus on lines 2–4."));
    failed += check("fix taken from the dataweave block", script.equals(DataWeaveFix.script(
        "Here:\n```json\n{\"a\":1}\n```\n\n```dataweave\n" + script + "\n```\n- fixed the lambda")));
    failed += check("last dataweave block wins", "%dw 2.0\n---\n2".equals(DataWeaveFix.script(
        "```dw\n%dw 2.0\n---\n1\n```\nthen\n```dw\n%dw 2.0\n---\n2\n```")));
    failed += check("a lone untagged block is taken", "%dw 2.0\n---\n1".equals(DataWeaveFix.script(
        "```\n%dw 2.0\n---\n1\n```")));
    failed += check("no block, nothing to apply", DataWeaveFix.script("The script is already correct.") == null);
    failed += check("only other languages, nothing to apply", DataWeaveFix.script("```json\n{}\n```") == null);

    String logger = "<logger level=\"INFO\" doc:name=\"Log request\" message=\"#[payload]\"/>";
    String request = "<http:request method=\"GET\" config-ref=\"HTTP\" path=\"/a\"/>";
    String flow = "<flow name=\"get-a\">\n  " + logger + "\n  " + request + "\n</flow>";
    Attachment canvas = Attachment.processors("p", "src/main/mule/api.xml", List.of(logger, request));
    failed += check("canvas label names elements and doc:name", canvas.label().equals(
        "canvas selection in src/main/mule/api.xml (2 elements: logger \"Log request\", http:request)"));
    failed += check("canvas XML kept in order", canvas.content().equals(logger + "\n\n" + request));
    Attachment nested = Attachment.processors("p", "src/main/mule/api.xml", List.of(flow, logger));
    failed += check("element inside a selected flow is not sent twice", nested.content().equals(flow)
        && nested.label().endsWith("(1 element: flow \"get-a\")"));
    failed += check("canvas does not report a failure", !canvas.reportsFailure());

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
        console, dataWeave, canvas), null);
    String sent = prompts.get(0);
    failed += check("canvas block", sent.contains("SELECTED ON THE CANVAS OF src/main/mule/api.xml (project p),"
        + " 2 elements") && sent.contains("doc:name=\"Log request\""));
    failed += check("DataWeave block", sent.contains("DATAWEAVE SCRIPT FROM STUDIO'S DATAWEAVE EDITOR, FOR"
        + " src/main/mule/api.xml (project p)") && sent.contains("e.name"));
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

    List<ChatRequest> requests = new ArrayList<>();
    ChatModel looker = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
      }
    };
    Attachment picture = Attachment.image("pasted image 1", 2, 1, "image/png", new byte[] {1, 2, 3});
    failed += check("image label", picture.label().equals("pasted image 1 (2×1)"));
    failed += check("image is not a failure", !picture.reportsFailure());
    ChatSession withImage = new ChatSession();
    withImage.send(looker, "what is wrong here", List.of(picture), null);
    UserMessage asked = (UserMessage) requests.get(0).messages().get(requests.get(0).messages().size() - 1);
    ImageContent part = asked.contents().stream().filter(ImageContent.class::isInstance).map(ImageContent.class::cast)
        .findFirst().orElse(null);
    failed += check("image sent as an image part", part != null && "AQID".equals(part.image().base64Data())
        && "image/png".equals(part.image().mimeType()));
    String askedText = asked.contents().stream().filter(TextContent.class::isInstance)
        .map(c -> ((TextContent) c).text()).findFirst().orElse("");
    failed += check("image named in the prompt without its data", askedText.contains("ATTACHED IMAGE: pasted image 1 (2×1)")
        && !askedText.contains("AQID") && askedText.endsWith("what is wrong here"));
    withImage.send(looker, "and now?", List.of(), null);
    boolean resent = requests.get(1).messages().stream().anyMatch(m -> m instanceof UserMessage u
        && u.contents().stream().anyMatch(ImageContent.class::isInstance));
    failed += check("image not resent on later turns", !resent);
    failed += check("saved chat keeps the image label, not the image", withImage.savedHistory().get(0).text()
        .startsWith("[Attached at the time: pasted image 1 (2×1)]") && !withImage.savedHistory().get(0).text().contains("AQID"));
    System.exit(failed == 0 ? 0 : 1);
  }

  // Offline: what is hidden from the model, what is kept so it can still help, and that edits get the real values.
  private static void checkSecrets() throws Exception {
    int failed = 0;
    SecretMask mask = new SecretMask();

    String props = mask.mask("src/main/resources/config.properties", "db.host=localhost\ndb.password=Sup3r!Pass\n"
        + "client_secret = abcDEF123456\nhttp.port=8081\napi.key=${secure::api.key}\nsecure.key=k3yMaterial99\n"
        + "other.password=Sup3r!Pass\n").text();
    failed += check("properties: password hidden", !props.contains("Sup3r!Pass") && props.contains("db.password=«masked-"));
    failed += check("properties: client secret hidden", !props.contains("abcDEF123456"));
    failed += check("properties: secure.key hidden", !props.contains("k3yMaterial99"));
    failed += check("properties: host and port kept", props.contains("db.host=localhost") && props.contains("http.port=8081"));
    failed += check("properties: secure reference kept", props.contains("api.key=${secure::api.key}"));
    failed += check("same value, same placeholder", props.split("«masked-1»", -1).length == 3);

    String yaml = mask.mask("src/main/resources/dev.yaml", "db:\n  host: \"db.example.com\"\n  user: app\n"
        + "  password: hunter2pass\nsalesforce:\n  token: 'tok_9f8e7d6c5b'\n"
        + "  tokenUrl: https://login.salesforce.com/services/oauth2/token\n").text();
    failed += check("yaml: nested password hidden", !yaml.contains("hunter2pass"));
    failed += check("yaml: quoted token hidden", !yaml.contains("tok_9f8e7d6c5b"));
    failed += check("yaml: host, user and token URL kept", yaml.contains("db.example.com") && yaml.contains("user: app")
        && yaml.contains("login.salesforce.com"));

    String xml = mask.mask("src/main/mule/api.xml",
        "<db:my-sql-connection host=\"${db.host}\" user=\"app\" password=\"Plain0ldPass\"/>\n"
            + "<oauth:client-credentials-grant-type clientId=\"3MVG9abc\" clientSecret=\"S3cr3tValue\""
            + " tokenUrl=\"https://x/token\"/>\n"
            + "<http:header key=\"Authorization\" value=\"Basic dXNlcjpwYXNzd29yZA==\"/>\n"
            + "<tls:key-store path=\"keystore.jks\" keyPassword=\"kp4ssw0rd\" password=\"${secure::ks.password}\"/>\n"
            + "<logger doc:name=\"Log token\" message=\"#[vars.token]\"/>\n"
            + "<db:generic-connection url=\"jdbc:sqlserver://h;user=sa;password=SqlP4ss\"/>").text();
    for (String secret : List.of("Plain0ldPass", "3MVG9abc", "S3cr3tValue", "dXNlcjpwYXNzd29yZA==", "kp4ssw0rd",
        "SqlP4ss")) {
      failed += check("mule xml: " + secret + " hidden", !xml.contains(secret));
    }
    for (String kept : List.of("${db.host}", "user=\"app\"", "tokenUrl=\"https://x/token\"", "${secure::ks.password}",
        "#[vars.token]", "doc:name=\"Log token\"", "key=\"Authorization\"", "path=\"keystore.jks\"", "user=sa")) {
      failed += check("mule xml: " + kept + " kept", xml.contains(kept));
    }

    String dw = mask.mask("src/main/resources/dwl/auth.dwl", "%dw 2.0\noutput application/json\n---\n{ username: \"svc\","
        + " password: \"DwP4ss!\", token: vars.token, \"Authorization\": \"Bearer \" ++ vars.jwt }").text();
    failed += check("dataweave: password literal hidden", !dw.contains("DwP4ss!"));
    failed += check("dataweave: expressions and the Bearer prefix kept", dw.contains("token: vars.token")
        && dw.contains("\"Bearer \" ++ vars.jwt"));

    String json = mask.mask("src/test/resources/token-request.json",
        "{\"client_id\":\"cid-123456\",\"client_secret\":\"cs-abcdef\",\"grant_type\":\"client_credentials\"}").text();
    failed += check("json: client id and secret hidden", !json.contains("cid-123456") && !json.contains("cs-abcdef"));
    failed += check("json: grant type kept", json.contains("\"grant_type\":\"client_credentials\""));

    String launch = mask.mask("my-app.launch", "<stringAttribute key=\"org.eclipse.jdt.launching.VM_ARGUMENTS\""
        + " value=\"-M-Dsecure.key=MyEncKey123 -M-Dmule.env=dev\"/>").text();
    failed += check("launch file: secure properties key hidden", !launch.contains("MyEncKey123"));
    failed += check("launch file: mule.env kept", launch.contains("-Dmule.env=dev"));

    String console = mask.mask(null, "INFO Request Authorization=Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
        + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U\nconnecting with password=ConsoleP4ss to db\n"
        + "Caused by: java.net.ConnectException").text();
    failed += check("console: JWT and password hidden", !console.contains("eyJhbGci") && !console.contains("ConsoleP4ss"));
    failed += check("console: the error kept", console.contains("Caused by: java.net.ConnectException"));

    String keys = mask.mask(null, "keys sk-ant-api03-abcdefghijklmnopqrstuvwxyz and AKIAIOSFODNN7EXAMPLE, broker"
        + " amqp://guest:GuestP4ss@rabbit:5672\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----").text();
    failed += check("API keys, URL password and private key hidden", !keys.contains("abcdefghijklmnop")
        && !keys.contains("AKIAIOSFODNN7EXAMPLE") && !keys.contains("GuestP4ss") && !keys.contains("MIIEow"));
    String prose = "Why does the token: refresh fail, and Basic authentication break?";
    failed += check("ordinary prose left alone", mask.mask(null, prose).text().equals(prose));

    failed += check("secret-only files are blocked", SecretMask.isBlocked(".env") && SecretMask.isBlocked("src/main/resources/server.pem")
        && SecretMask.isBlocked("src/main/resources/keystore.jks") && SecretMask.isBlocked("credentials.json")
        && !SecretMask.isBlocked("src/main/resources/config.properties"));
    failed += check("blocked file content withheld", mask.mask(".env", "TOKEN=abc").text().startsWith("WITHHELD"));
    SecretMask off = new SecretMask();
    off.setEnabled(false);
    failed += check("masking off sends values", off.mask("a.properties", "db.password=x1y2z3").text().contains("x1y2z3"));
    failed += check("masking off still withholds .env", off.mask(".env", "TOKEN=abc").text().startsWith("WITHHELD"));

    Path root = Files.createTempDirectory("studio-ai-secrets");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.writeString(root.resolve("src/main/mule/api.xml"),
        "<db:my-sql-connection user=\"app\" password=\"Plain0ldPass\"/>\n");
    Files.writeString(root.resolve(".env"), "TOKEN=abc\n");
    ChangeSet changes = new ChangeSet();
    ProjectTools tools = new ProjectTools(new DiskProject(root), changes, true, new SecretMask());
    String read = tools.execute(ToolExecutionRequest.builder().id("t").name("read_file")
        .arguments("{\"path\":\"src/main/mule/api.xml\"}").build());
    failed += check("read_file hides the password", read.contains("password=\"«masked-1»\"") && !read.contains("Plain0ldPass"));
    failed += expect(tools, "read_file", "{\"path\":\".env\"}", "WITHHELD");
    failed += expect(tools, "edit_file", "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"user=\\\"app\\\""
        + " password=\\\"«masked-1»\\\"\",\"new_text\":\"user=\\\"svc\\\" password=\\\"«masked-1»\\\"\"}", "Proposed change");
    failed += check("an edit around a placeholder keeps the real value", changes.staged("src/main/mule/api.xml")
        .contains("user=\"svc\" password=\"Plain0ldPass\""));
    failed += expect(tools, "edit_file", "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"password=\\\"«masked-1»\\\"\","
        + "\"new_text\":\"password=\\\"${secure::db.password}\\\"\"}", "Proposed change");
    failed += check("moving to secure properties removes the value", changes.staged("src/main/mule/api.xml")
        .contains("${secure::db.password}") && !changes.staged("src/main/mule/api.xml").contains("Plain0ldPass"));
    failed += expect(tools, "edit_file", "{\"path\":\"src/main/mule/api.xml\",\"old_text\":\"«masked-99»\","
        + "\"new_text\":\"x\"}", "ERROR: a «masked-N» placeholder");

    List<String> prompts = new ArrayList<>();
    ChatModel recorder = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        prompts.add(((UserMessage) request.messages().get(request.messages().size() - 1)).singleText());
        return ChatResponse.builder().aiMessage(AiMessage.from("Use «masked-1» here")).build();
      }
    };
    ChatSession session = new ChatSession();
    ChatSession.Turn turn = session.send(recorder, "my password=PromptP4ss is wrong",
        List.of(Attachment.openFile("p", "src/main/resources/config.properties", "db.password=Sup3r!Pass")), null);
    failed += check("sent message hides the file and typed secrets", !prompts.get(0).contains("Sup3r!Pass")
        && !prompts.get(0).contains("PromptP4ss"));
    failed += check("saved chat hides the typed secret", !session.savedHistory().get(0).text().contains("PromptP4ss"));
    failed += check("turn reports what was hidden", turn.masked() == 2 && turn.activity().contains("hid 2 secret values"));
    failed += check("answer unmasks for copy and insert", session.unmask(turn.text()).equals("Use PromptP4ss here"));
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
    failed += check("offer checklist sends the ticked items", page.contains("function msfOffer(")
        && page.contains("'cmd:offer:'") && page.contains("box.checked = !suggested"));
    failed += check("Ask-mode offers are collapsed and unticked", page.contains("createElement(suggested ? 'details'")
        && page.contains("possible fixes"));
    failed += check("Ask mode says it can suggest fixes", ChatMode.ASK.description.contains("Never changes files")
        && ChatMode.ASK.description.contains("suggest fixes"));
    failed += check("raw blocks are text, collapsed and hideable", page.contains("function msfRaw(")
        && page.contains("pre.textContent = part[1]") && page.contains("function msfShowRaw(")
        && page.contains(".raw.hidden { display: none; }"));
    if (preview != null) {
      String demo = page.replace("</script></body>", """
          msfMessage('you', 'You', %s, 'Agent, open file src/main/mule/app.xml, project employees-app');
          msfStep('Waiting for the model…'); msfStep('Reading src/main/mule/app.xml'); msfStep('Model answered in 4 s · $0.0012');
          msfMessage('ai', 'AI', %s, null);
          msfStep('Done in 5 s · read 1 file · this answer $0.0012');
          msfAction('Draft this plan for review in Agent mode', 'build-plan');
          msfOffer('0', ['Move the OAuth credentials to secure properties (src/main/mule/app.xml, pom.xml)',
              'Stop logging the Authorization header (src/main/mule/app.xml)'], 'Draft selected changes for review', false);
          msfOffer('1', ['Set mule-maven-plugin classifier to mule-application (pom.xml)',
              'Move API keys to Mule secure properties (src/main/resources/local.properties)'], 'Draft selected changes for review', true);
          msfRaw('Raw · 2 calls', [
            {heading: 'Step 1 · POST https://openrouter.ai/api/v1/chat/completions · HTTP 200 · openai/gpt-5-mini · 3.1 s',
             request: '{\\n  "model" : "openai/gpt-5-mini",\\n  "messages" : [ {\\n    "role" : "user",\\n    "content" : "How do I log the payload? <b>not bold</b>"\\n  } ]\\n}',
             response: '{\\n  "id" : "gen-1",\\n  "choices" : [ ]\\n}'},
            {heading: 'Step 2 · POST https://openrouter.ai/api/v1/chat/completions · HTTP 200 · openai/gpt-5-mini · 1.2 s',
             request: '{ }', response: '{ }'}], 'raw-log');
          document.querySelectorAll('.raw, .raw details').forEach(function (d) { d.open = true; });
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

  // A real chat through the OpenAI-compatible client against a local stub, with raw capture on: the HTTP exchange
  // is recorded exactly, without headers, and stored per chat.
  private static void checkRawLog() throws Exception {
    int failed = 0;
    Path dir = Files.createTempDirectory("studio-ai-raw");
    RawLog raw = new RawLog(dir.resolve("raw"));
    List<String> bodies = new ArrayList<>();
    List<String> authorizations = new ArrayList<>();
    String okReply = "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"stub-model\","
        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong from stub\"},"
        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":3,"
        + "\"total_tokens\":45}}";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      boolean fail = bodies.size() > 1;
      byte[] reply = (fail ? "{\"error\":{\"message\":\"stub overloaded\"}}" : okReply)
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(fail ? 500 : 200, reply.length);
      exchange.getResponseBody().write(reply);
      exchange.close();
    });
    server.start();
    try {
      String id = ChatStore.newId();
      List<Exception> writeErrors = new ArrayList<>();
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      Connection connection = new Connection(Provider.CUSTOM, base, "stub-model", "sk-harness-NEVER-LOGGED-123");
      RawCapture first = new RawCapture(1, "Stub", call -> {
        try {
          raw.append(id, call);
        } catch (Exception e) {
          writeErrors.add(e);
        }
      });
      ChatSession session = new ChatSession();
      session.maskSecrets(true);
      ChatSession.Turn turn = session.send(ChatModels.create(connection, first), ChatMode.ASK,
          "Why does this fail?\ndb.password=Sup3r!Pass", List.of(), null, Progress.NONE);

      failed += check("chat answered through stub", turn.text().contains("pong from stub"));
      failed += check("one call captured", first.calls().size() == 1);
      RawCapture.Call call = first.calls().get(0);
      failed += check("http exchange captured", call.http() != null);
      failed += check("exact request body", call.http().request().equals(bodies.get(0)));
      failed += check("exact response body", call.http().response().equals(okReply));
      failed += check("url and method", call.http().url().equals(base + "/chat/completions")
          && call.http().method().equals("POST"));
      failed += check("status", call.http().status() == 200);
      failed += check("heading", call.heading().startsWith("Step 1 · POST " + base + "/chat/completions · HTTP 200")
          && call.heading().contains("stub-model"));
      failed += check("turn and step", call.turn() == 1 && call.step() == 1);
      failed += check("langchain4j view kept", call.request().contains("\"SYSTEM\"") && call.response().contains("STOP"));
      failed += check("secret masked in body", call.http().request().contains("db.password=«masked-1»")
          && !call.http().request().contains("Sup3r!Pass"));
      failed += check("api key sent on the wire", authorizations.get(0).contains("sk-harness-NEVER-LOGGED-123"));
      failed += check("no write errors", writeErrors.isEmpty());
      failed += check("log file created", raw.exists(id));
      String stored = Files.readString(raw.file(id));
      failed += check("api key not stored", !stored.contains("sk-harness-NEVER-LOGGED-123") && !stored.contains("Bearer"));
      failed += check("secret not stored", !stored.contains("Sup3r!Pass"));

      RawCapture second = new RawCapture(2, "Stub", call2 -> {
        try {
          raw.append(id, call2);
        } catch (Exception e) {
          writeErrors.add(e);
        }
      });
      try {
        session.send(ChatModels.create(connection, second), ChatMode.ASK, "again", List.of(), null, Progress.NONE);
      } catch (Exception expected) {
        // The stub fails the second call so the error path can be checked.
      }
      RawCapture.Call failedCall = second.calls().isEmpty() ? null : second.calls().get(0);
      failed += check("failed call captured", failedCall != null && failedCall.http() != null
          && failedCall.http().status() == 500 && failedCall.http().response().contains("stub overloaded")
          && failedCall.error() != null && failedCall.heading().contains("HTTP 500 ") );

      List<RawCapture.Call> loaded = raw.load(id);
      failed += check("jsonl round trip", loaded.size() == 2 && loaded.get(0).equals(call)
          && loaded.get(1).turn() == 2);
      String readable = Files.readString(raw.writeReadable(id));
      failed += check("readable view", readable.contains("===== Turn 1 · Step 1 · POST ")
          && readable.contains("===== Turn 2 · Step 1 · POST ") && readable.contains("\"content\" : \"pong from stub\""));
      failed += check("readable view has no key", !readable.contains("sk-harness-NEVER-LOGGED-123"));

      RawCapture.Call sdkOnly = new RawCapture.Call(3, 1, "now", "Amazon Bedrock", "claude", 1.0, null,
          "{\"messages\":[]}", "{\"message\":{}}", null);
      failed += check("sdk provider falls back", sdkOnly.heading().contains("HTTP not visible")
          && sdkOnly.requestText().contains("\"messages\""));
      String image = "A".repeat(5_000);
      RawCapture.Call withImage = new RawCapture.Call(1, 1, "now", "Stub", "m", 1.0,
          new RawCapture.Http("POST", base + "/chat/completions?key=abc", 200,
              "{\"url\":\"data:image/png;base64," + image + "\",\"text\":\"" + "word ".repeat(1_000) + "\"}", "{}"),
          "{}", "{}", null);
      failed += check("image data shortened for reading", !withImage.requestText().contains(image)
          && withImage.requestText().contains("characters of encoded data") && withImage.requestText().contains("word word"));

      failed += check("rejects path-like ids", rejects(() -> raw.file("../secret")));
      failed += check("prune keeps recent", raw.prune(90, Instant.now()) == 0 && raw.exists(id));
      failed += check("prune removes old", raw.prune(90, Instant.now().plusSeconds(91L * 86_400)) == 1
          && !raw.exists(id));
      Files.writeString(raw.file(id), "x");
      failed += check("delete all", raw.deleteAll() == 1 && !raw.exists(id));
    } finally {
      server.stop(0);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  // Auto: which model each kind of message gets, which failures move to the other model, and a real failover
  // against a local stub where the fast model is overloaded.
  private static void checkAuto() throws Exception {
    int failed = 0;
    AutoRouter.Models pair = new AutoRouter.Models("fast-model", "strong-model");
    List<Attachment> none = List.of();
    failed += check("plain question goes fast", AutoRouter.choose(ChatMode.ASK, "tell me about this project", none,
        false, pair).model().equals("fast-model"));
    failed += check("fast has strong as fallback", "strong-model".equals(AutoRouter.choose(ChatMode.ASK, "what is a flow",
        none, false, pair).fallback()));
    failed += check("agent mode goes strong", AutoRouter.choose(ChatMode.AGENT, "hi", none, false, pair).model()
        .equals("strong-model"));
    failed += check("plan mode goes strong", AutoRouter.choose(ChatMode.PLAN, "hi", none, false, pair).model()
        .equals("strong-model"));
    failed += check("review question goes strong", AutoRouter.choose(ChatMode.ASK, "Review this flow please", none,
        false, pair).model().equals("strong-model"));
    failed += check("error question goes strong", AutoRouter.choose(ChatMode.ASK, "why do I get this error?", none,
        false, pair).model().equals("strong-model"));
    failed += check("DataWeave fix goes strong", AutoRouter.choose(ChatMode.ASK, "hello", none, true, pair).model()
        .equals("strong-model"));
    failed += check("image goes strong", AutoRouter.choose(ChatMode.ASK, "what is this",
        List.of(Attachment.image("shot.png", 10, 10, "image/png", new byte[] {1, 2, 3})), false, pair).model()
        .equals("strong-model"));
    failed += check("large context goes strong", AutoRouter.choose(ChatMode.ASK, "what does it do",
        List.of(Attachment.file("p", "big.xml", "x".repeat(30_000))), false, pair).reason().contains("context"));
    failed += check("same model twice has no fallback", AutoRouter.choose(ChatMode.ASK, "hi", none, false,
        new AutoRouter.Models("m", "m")).fallback() == null);
    failed += check("unset models are explained", rejectsState(() -> AutoRouter.choose(ChatMode.ASK, "hi", none, false,
        new AutoRouter.Models("", "strong-model"))));
    failed += check("Auto is first-class in the picker", ModelLists.suggestionsOnly(Provider.ANTHROPIC, "claude-sonnet-4-6")
        .contains(AutoRouter.AUTO));
    failed += check("defaults for every provider", Arrays.stream(Provider.values())
        .allMatch(p -> p == Provider.CUSTOM || !AutoRouter.defaults(p).fast().isBlank()));
    failed += check("Auto never sent as a model id", rejectsState(() -> ChatModels.create(new Connection(Provider.CUSTOM,
        "http://127.0.0.1:1/v1", "Auto", ""))));

    failed += check("429 is retryable", AutoRouter.retryable(new HttpException(429, "slow down")));
    failed += check("503 is retryable", AutoRouter.retryable(new RuntimeException("wrapped",
        new HttpException(503, "overloaded"))));
    failed += check("401 is not retryable", !AutoRouter.retryable(new HttpException(401, "bad key")));
    failed += check("400 is not retryable", !AutoRouter.retryable(new HttpException(400, "bad")));
    failed += check("timeout is retryable", AutoRouter.retryable(new RuntimeException(new HttpTimeoutException("t"))));
    failed += check("cancel is not retryable", !AutoRouter.retryable(new CancellationException("c")));

    List<String> asked = new ArrayList<>();
    int[] badRequest = {0};
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String model = body.contains("\"fast-model\"") ? "fast-model" : "strong-model";
      asked.add(model);
      int status = badRequest[0] == 1 ? 400 : model.equals("fast-model") ? 503 : 200;
      byte[] reply = (status != 200 ? "{\"error\":{\"message\":\"nope\"}}"
          : "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"strong-model\","
              + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"from strong\"},"
              + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,"
              + "\"total_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, reply.length);
      exchange.getResponseBody().write(reply);
      exchange.close();
    });
    server.start();
    try {
      Connection base = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
          "fast-model", "");
      List<AutoRouter.Option> options = List.of(
          new AutoRouter.Option("fast-model", ChatModels.create(base, null)),
          new AutoRouter.Option("strong-model", ChatModels.create(base.withModel("strong-model"), null)));
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
      ChatSession session = new ChatSession();
      ChatSession.Turn turn = AutoRouter.send(session, options, ChatMode.ASK, "what is a flow", List.of(), null, progress);
      failed += check("failover answered from strong", turn.text().contains("from strong"));
      failed += check("fast was tried first", asked.get(0).equals("fast-model") && asked.contains("strong-model"));
      failed += check("switch shown as a step", steps.stream().anyMatch(s -> s.startsWith("fast-model failed (HTTP 503")
          && s.endsWith("trying strong-model")));
      failed += check("history kept once", session.savedHistory().size() == 2);

      badRequest[0] = 1;
      asked.clear();
      boolean threw = false;
      try {
        AutoRouter.send(new ChatSession(), options, ChatMode.ASK, "what is a flow", List.of(), null, progress);
      } catch (RuntimeException e) {
        threw = true;
      }
      failed += check("400 does not move to the other model", threw && !asked.isEmpty()
          && asked.stream().allMatch("fast-model"::equals));
    } finally {
      server.stop(0);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  // Agent mode: an answer that pastes code for a change request is drafted as reviewable changes in the same turn;
  // a question answered with a code example is left alone.
  private static void checkDraft() throws Exception {
    int failed = 0;
    List<String> bodies = new ArrayList<>();
    String pasted = "Add this file:\\n```properties\\na=1\\n```";
    String toolCall = "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"write_file\",\"arguments\":\"{\\\"path\\\":\\\"src/main/resources/health.properties\\\","
        + "\\\"content\\\":\\\"a=1\\\\n\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],"
        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      String reply;
      if (body.contains("tool_call_id")) {
        reply = textReply("Proposed health.properties.");
      } else if (body.contains("You put the changes in your reply as code")) {
        reply = toolCall;
      } else {
        reply = textReply(pasted);
      }
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      Path root = Files.createTempDirectory("studio-ai-draft");
      Files.createDirectories(root.resolve("src/main/resources"));
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
          "m", "");
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
      ChatSession.Turn turn = new ChatSession().send(ChatModels.create(connection), ChatMode.AGENT,
          "Add a health properties file", List.of(), new DiskProject(root), progress);
      failed += check("pasted code drafted as a change", turn.changes().all().size() == 1
          && turn.changes().all().get(0).path().equals("src/main/resources/health.properties"));
      failed += check("nudged once, in the same turn", bodies.size() == 3
          && bodies.stream().filter(b -> b.contains("You put the changes in your reply as code")).count() == 2);
      failed += check("answer keeps the explanation and the summary", turn.text().contains("Add this file")
          && turn.text().contains("Proposed health.properties."));
      failed += check("drafting shown as a step", steps.contains("Drafting the code from the answer as changes to review…"));

      bodies.clear();
      ChatSession.Turn question = new ChatSession().send(ChatModels.create(connection), ChatMode.AGENT,
          "How does a properties file look?", List.of(), new DiskProject(root), progress);
      failed += check("a question with an example is not drafted", bodies.size() == 1 && question.changes().isEmpty());

      bodies.clear();
      new ChatSession().send(ChatModels.create(connection), ChatMode.ASK, "Add a health properties file", List.of(),
          new DiskProject(root), progress);
      failed += check("Ask mode is not drafted", bodies.size() == 1);
    } finally {
      server.stop(0);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static String textReply(String content) {
    return "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
  }

  private static boolean rejectsState(ThrowingRunnable action) {
    try {
      action.run();
      return false;
    } catch (Exception e) {
      return e instanceof IllegalStateException;
    }
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
    private final Path models;

    DiskProject(Path root) {
      this(root, null);
    }

    // models: a Studio workspace's .mule/plugins-tmp/repository, or null.
    DiskProject(Path root, Path models) {
      this.root = root.toAbsolutePath().normalize();
      this.models = models;
    }

    @Override
    public Path connectorModels() {
      return models;
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
      case GEMINI -> "GEMINI";
      case AZURE_OPENAI -> "AZURE_OPENAI";
      case BEDROCK -> "BEDROCK";
      case MULESOFT_AI_GATEWAY -> "MULESOFT_AI_GATEWAY";
      case OLLAMA -> "OLLAMA";
      case CUSTOM -> "CUSTOM";
    };
    String baseUrl = firstNonBlank(env.get(prefix + "_BASE_URL"), provider.defaultBaseUrl);
    String model = firstNonBlank(env.get(prefix + "_MODEL"), provider.defaultModel);
    String apiKey = env.getOrDefault(prefix + "_API_KEY", "");
    String clientId = env.getOrDefault(prefix + "_CLIENT_ID", "");
    String clientSecret = env.getOrDefault(prefix + "_CLIENT_SECRET", "");
    if (baseUrl.isBlank()) {
      throw new IllegalStateException(prefix + "_BASE_URL is empty");
    }
    if (model.isBlank()) {
      throw new IllegalStateException(prefix + "_MODEL is empty");
    }
    if (provider.needsApiKey() && apiKey.isBlank()) {
      throw new IllegalStateException(prefix + "_API_KEY is empty");
    }
    if (provider.needsClientCredentials() && (clientId.isBlank() || clientSecret.isBlank())) {
      throw new IllegalStateException(prefix + "_CLIENT_ID / " + prefix + "_CLIENT_SECRET are empty");
    }
    return new Connection(provider, baseUrl, model, apiKey, clientId, clientSecret);
  }

  private static Provider parseProvider(String name) {
    String key = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return switch (key) {
      case "openrouter" -> Provider.OPENROUTER;
      case "openai" -> Provider.OPENAI;
      case "anthropic" -> Provider.ANTHROPIC;
      case "gemini", "google", "google_ai_gemini" -> Provider.GEMINI;
      case "azure", "azure_openai", "azureopenai" -> Provider.AZURE_OPENAI;
      case "bedrock", "amazon_bedrock", "amazon" -> Provider.BEDROCK;
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
          java Harness chat [--provider name] [--model id] [--file path] [--mode ask|agent|plan] [--attach path] [--lines path:a-b] [--image file.png] [--project dir [--apply]] "prompt"
          java Harness check-tools | check-render | check-history | check-attach | check-secrets | check-html [preview.html]
          java Harness check-updates [latest.json URL]
        Providers: openrouter openai anthropic gemini azure bedrock mulesoft ollama custom
        Reads ../.env — never prints key values.
        """);
  }
}
