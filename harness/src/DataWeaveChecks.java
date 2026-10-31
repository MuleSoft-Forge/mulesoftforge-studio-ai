import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.dataweave.CliDownload;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveCli;
import com.mulesoftforge.studio.ai.dataweave.DataWeaveTools;
import com.mulesoftforge.studio.ai.dataweave.ScriptChecks;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.SecretMask;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

// check-dataweave: the DataWeave CLI integration. Parsing is checked from output the CLI produced on Studio's
// machine; validate and run need the CLI itself (DW_CLI, or dw on the PATH or in ~/.dw/bin) and are skipped
// without it.
final class DataWeaveChecks {
  private DataWeaveChecks() {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path EMPLOYEES = Path.of("fixtures", "debug", "employees-app");
  private static final String EMPLOYEES_XML = "src/main/mule/employees-api.xml";

  static void run() throws Exception {
    int failed = parsing();
    failed += extraction();
    failed += download();
    Path dw = DataWeaveCli.find(System.getenv("DW_CLI"));
    if (dw == null) {
      System.out.println("SKIP  DataWeave CLI not found: set DW_CLI or install dw in ~/.dw/bin");
    } else {
      failed += cli(DataWeaveCli.at(dw));
      failed += proposals(DataWeaveCli.at(dw));
      failed += tools(DataWeaveCli.at(dw));
      failed += loop(DataWeaveCli.at(dw));
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int extraction() throws Exception {
    int failed = 0;
    String xml = Files.readString(EMPLOYEES.resolve(EMPLOYEES_XML));
    List<ScriptChecks.Script> scripts = ScriptChecks.scripts(EMPLOYEES_XML, xml);
    ScriptChecks.Script transform = scripts.stream().filter(s -> s.text().contains("var isAdmin")).findFirst()
        .orElse(null);
    failed += check("Transform Message script found where its first line is", transform != null
        && transform.line() == 175 && transform.text().startsWith("%dw 2.0\noutput application/json")
        && transform.header() == 0);
    failed += check("expressions with their own header kept as they are", scripts.stream().anyMatch(s ->
        s.text().startsWith("%dw 2.0\noutput text\n---") && s.header() == 0 && s.text().contains("List Employees")));
    failed += check("commented-out expressions ignored", scripts.stream().noneMatch(s ->
        s.text().contains("attributes.headers.authorization")));
    List<ScriptChecks.Script> bare = ScriptChecks.scripts("src/main/mule/a.xml",
        "<mule>\n  <set-variable variableName=\"x\" value=\"#[payload.id ++ &quot;-&quot;]\"/>\n</mule>");
    failed += check("bare expression wrapped, entities decoded, line kept", bare.size() == 1
        && bare.get(0).line() == 2 && bare.get(0).header() == 3 && bare.get(0).text().endsWith("payload.id ++ \"-\""));
    List<ScriptChecks.Script> module = ScriptChecks.scripts("src/main/resources/modules/Utils.dwl",
        "%dw 2.0\nfun shout(s: String) = upper(s)\n");
    failed += check("module file given a body so it compiles", module.size() == 1
        && module.get(0).text().endsWith("\n---\nnull"));
    return failed;
  }

  private static int proposals(DataWeaveCli dw) throws Exception {
    int failed = 0;
    String before = Files.readString(EMPLOYEES.resolve(EMPLOYEES_XML));
    failed += check("unchanged scripts are not checked again", ScriptChecks.introduced(dw, EMPLOYEES_XML, before,
        before, ScriptChecks.TIMEOUT, () -> false).isEmpty());
    String broken = before.replace("upper(payload.scope default \"\")", "uppr(payload.scope default \"\")");
    List<Problem> problems = ScriptChecks.introduced(dw, EMPLOYEES_XML, before, broken, ScriptChecks.TIMEOUT,
        () -> false);
    failed += check("an invented function in a Transform is reported at its file line", problems.size() == 1
        && problems.get(0).line() == 204 && problems.get(0).message().contains("`uppr`"));
    String added = before.replace("</mule>", "<set-variable variableName=\"x\" value=\"#[payload.id +]\"/>\n"
        + "<set-variable variableName=\"y\" value=\"#[Soap::isGetWsdl(attributes.method)]\"/>\n</mule>");
    problems = ScriptChecks.introduced(dw, EMPLOYEES_XML, before, added, ScriptChecks.TIMEOUT, () -> false);
    int line = (int) added.substring(0, added.indexOf("#[payload.id +]")).chars().filter(c -> c == '\n').count() + 1;
    failed += check("a broken expression is reported; a runtime module is not", problems.size() == 1
        && problems.get(0).line() == line);
    String module = "%dw 2.0\nimport * from modules::Utils\noutput application/json\n---\nshout(payload.name)";
    failed += check("names from the project's own modules are not reported", ScriptChecks.introduced(dw,
        "src/main/resources/x.dwl", null, module, ScriptChecks.TIMEOUT, () -> false).isEmpty());
    failed += check("syntax errors in such scripts still are", !ScriptChecks.introduced(dw, "src/main/resources/x.dwl",
        null, module + " ++", ScriptChecks.TIMEOUT, () -> false).isEmpty());

    Path root = Files.createTempDirectory("studio-ai-dw");
    Files.createDirectories(root.resolve("src/main/mule"));
    Files.copy(EMPLOYEES.resolve(EMPLOYEES_XML), root.resolve(EMPLOYEES_XML));
    ProjectTools tools = new ProjectTools(DebugChecks.project(root, "employees-app"), new ChangeSet(), true);
    tools.useDataWeave(dw);
    String result = tools.execute(DebugChecks.call2("edit_file", Map.of("path", EMPLOYEES_XML,
        "old_text", "upper(payload.scope default \"\")", "new_text", "uppr(payload.scope default \"\")")));
    failed += check("an edit with broken DataWeave gets CHECKS back", result.contains("CHECKS:")
        && result.contains("DataWeave: Unable to resolve reference of: `uppr`") && result.contains("line 204"));
    ProjectTools fixed = new ProjectTools(DebugChecks.project(root, "employees-app"), new ChangeSet(), true);
    fixed.useDataWeave(dw);
    result = fixed.execute(DebugChecks.call2("edit_file", Map.of("path", EMPLOYEES_XML,
        "old_text", "contains \"ADMIN\"", "new_text", "contains \"ADMINISTRATOR\"")));
    failed += check("a valid DataWeave edit passes", result.contains("Checks passed."));
    return failed;
  }

  private static int parsing() {
    int failed = 0;
    String syntax = """
        WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
        Compiling `bad`...
        \u001B[31m[ERROR] Invalid input '\\n', expected `}` or ',' for the object expression. (line 4, column 60):


        4| payload map (item) -> { id: item.id, name: upper(item.name)
                                                                      ^
        Location:
        bad (line: 4, column:60)\u001B[0m
        1 errors found
        """;
    List<DataWeaveCli.Problem> problems = DataWeaveCli.problems(syntax);
    failed += check("syntax error: line, column and message", problems.size() == 1
        && problems.get(0).line() == 4 && problems.get(0).column() == 60
        && problems.get(0).message().startsWith("Invalid input '\\n', expected `}`"));
    String references = """
        [ERROR] Unable to resolve reference of: `payload`.

        4| payload map (item) -> { id: item.id }
           ^^^^^^^
        Location:
        mule-bindings (line: 4, column:1)
        [ERROR] Unable to resolve reference of: `trimz`.

        4| payload map (item) -> { name: trimz(item.name) }
                                         ^^^^^
        Location:
        mule-bindings (line: 4, column:31)
        2 errors found
        """;
    problems = DataWeaveCli.problems(references);
    failed += check("several errors each keep their own location", problems.size() == 2
        && problems.get(0).column() == 1 && problems.get(1).column() == 31
        && problems.get(1).message().equals("Unable to resolve reference of: `trimz`."));
    String runtime = """
        [ERROR] Error while executing the script:
        [ERROR] Cannot coerce String (12,50) to Number

        1| output application/json --- { amount: payload.amount as Number }
                                                 ^^^^^^^^^^^^^^^^^^^^^^^^
        Trace:
          at anonymous::main (line: 1, column: 39) at:
        """;
    problems = DataWeaveCli.problems(runtime);
    failed += check("runtime error: message and where it failed", problems.size() == 1
        && problems.get(0).message().equals("Cannot coerce String (12,50) to Number")
        && problems.get(0).line() == 1 && problems.get(0).column() == 39);
    failed += check("noise removed from output", DataWeaveCli.clean(syntax).startsWith("[ERROR] Invalid input")
        && !DataWeaveCli.clean(syntax).contains("WARNING") && !DataWeaveCli.clean(syntax).contains("\u001B"));
    return failed;
  }

  private static int cli(DataWeaveCli dw) throws Exception {
    int failed = 0;
    Duration timeout = DataWeaveCli.TIMEOUT;
    String version = dw.version();
    failed += check("CLI reports its version (" + version + ")", version != null && version.matches("\\d+\\.\\d+.*"));

    DataWeaveCli.Validation valid = dw.validate("%dw 2.0\noutput application/json\n---\npayload map { id: $.id }",
        DataWeaveCli.MULE_BINDINGS, timeout, () -> false);
    failed += check("valid script passes", valid.ok() && valid.problems().isEmpty());
    DataWeaveCli.Validation broken = dw.validate(
        "%dw 2.0\noutput application/json\n---\npayload map (item) -> { id: item.id, name: upper(item.name)",
        DataWeaveCli.MULE_BINDINGS, timeout, () -> false);
    failed += check("syntax error found with its line", !broken.ok() && broken.problems().get(0).line() == 4);
    DataWeaveCli.Validation invented = dw.validate("%dw 2.0\noutput application/json\n---\npayload map trimz($.name)",
        DataWeaveCli.MULE_BINDINGS, timeout, () -> false);
    failed += check("invented function caught", !invented.ok()
        && invented.problems().get(0).message().contains("trimz"));
    DataWeaveCli.Validation mule = dw.validate("%dw 2.0\noutput application/json\n---\n{ region: vars.region, path:"
        + " attributes.requestPath, url: p('api.url'), id: correlationId }", DataWeaveCli.MULE_BINDINGS, timeout,
        () -> false);
    failed += check("Mule bindings and p() are declared for validation", mule.ok());

    DataWeaveCli.Run json = dw.run("%dw 2.0\noutput application/json\n---\npayload map { id: $.id, region: vars.region }",
        List.of(new DataWeaveCli.Input("payload", "application/json", "[{\"id\":1},{\"id\":2}]"),
            new DataWeaveCli.Input("vars", "application/json", "{\"region\":\"EU\"}")), timeout, () -> false);
    failed += check("run on JSON input with vars", json.ok() && json.output().contains("\"region\": \"EU\"")
        && json.output().contains("\"id\": 2"));
    DataWeaveCli.Run csv = dw.run("output application/json --- payload map { total: $.amount as Number }",
        List.of(new DataWeaveCli.Input("payload", "application/csv", "id,amount\n1,10.5\n")), timeout, () -> false);
    failed += check("run on CSV input", csv.ok() && csv.output().contains("10.5"));
    DataWeaveCli.Run coercion = dw.run("%dw 2.0\noutput application/json\n---\n{ amount: payload.amount as Number }",
        List.of(new DataWeaveCli.Input("payload", "application/json", "{\"amount\":\"12,50\"}")), timeout,
        () -> false);
    failed += check("runtime error comes back with its line", !coercion.ok()
        && coercion.problems().get(0).message().contains("Cannot coerce") && coercion.problems().get(0).line() == 4);
    DataWeaveCli.Run outside = dw.run("output application/json --- readUrl(\"https://example.com\", \"text/plain\")",
        List.of(), timeout, () -> false);
    failed += check("untrusted run cannot read the network", !outside.ok() && outside.output().contains("privilege"));
    DataWeaveCli.Run stubbed = dw.run("%dw 2.0\noutput application/json\n---\n{ url: p('api.url'), x: payload.x }",
        List.of(new DataWeaveCli.Input("payload", "application/json", "{\"x\":1}")), timeout, () -> false);
    failed += check("p() has a stand-in for runs", stubbed.ok() && stubbed.output().contains("\"url\": null"));

    boolean timedOut = false;
    try {
      dw.run("output application/json --- sizeOf((1 to 900000000) map ($ * 2))", List.of(), Duration.ofSeconds(1),
          () -> false);
    } catch (TimeoutException e) {
      timedOut = true;
    }
    failed += check("a runaway script is stopped at the timeout", timedOut);
    boolean cancelled = false;
    try {
      dw.run("output application/json --- sizeOf((1 to 900000000) map ($ * 2))", List.of(), timeout, () -> true);
    } catch (CancellationException e) {
      cancelled = true;
    }
    failed += check("cancel stops a run", cancelled);
    return failed;
  }

  private static byte[] zip(Map<String, String> entries) throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] data) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
  }

  // The download, against a local server standing in for GitHub: redirect, checksum, unpacking.
  private static int download() throws Exception {
    int failed = 0;
    failed += check("downloads exist for Apple silicon, Windows x64 and Linux x64",
        CliDownload.forPlatform("Mac OS X", "aarch64") != null && CliDownload.forPlatform("Windows 11", "amd64") != null
            && CliDownload.forPlatform("Linux", "amd64") != null
            && CliDownload.forPlatform("Mac OS X", "aarch64").url().endsWith("dw-cli-2.12.3-macos-arm64.zip"));
    failed += check("no download for Intel Macs or ARM Linux", CliDownload.forPlatform("Mac OS X", "x86_64") == null
        && CliDownload.forPlatform("Linux", "aarch64") == null);

    String binary = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "dw.exe" : "dw";
    byte[] release = zip(Map.of("bin/" + binary, "#!/bin/sh\necho 'Command Line : V9.9.9'\n", "libs/a.jar", "x"));
    byte[] escaping = zip(Map.of("../escaped.txt", "x"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/release", exchange -> {
      exchange.getResponseHeaders().add("Location", "/files/release.zip");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/files/", exchange -> {
      byte[] body = exchange.getRequestURI().getPath().endsWith("escaping.zip") ? escaping : release;
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
      Path target = Files.createTempDirectory("studio-ai-dwcli");
      Path installed = CliDownload.install(http, new CliDownload.Asset("9.9.9", "test", base + "/release",
          sha256(release)), target, () -> false);
      boolean runs = binary.equals("dw.exe") || "9.9.9".equals(DataWeaveCli.at(installed).version());
      failed += check("download follows the redirect, checks the checksum and unpacks a runnable dw",
          installed.equals(target.resolve("9.9.9").resolve("bin").resolve(binary)) && runs);

      Path refusedTarget = Files.createTempDirectory("studio-ai-dwcli");
      String message = "";
      try {
        CliDownload.install(http, new CliDownload.Asset("9.9.9", "test", base + "/release", "00" + sha256(release)
            .substring(2)), refusedTarget, () -> false);
      } catch (IllegalStateException e) {
        message = e.getMessage();
      }
      failed += check("a download that does not match its checksum is not installed", message.contains("checksum")
          && !Files.exists(refusedTarget.resolve("9.9.9")));
      message = "";
      try {
        CliDownload.install(http, new CliDownload.Asset("9.9.9", "test", base + "/files/escaping.zip",
            sha256(escaping)), refusedTarget, () -> false);
      } catch (IllegalStateException e) {
        message = e.getMessage();
      }
      failed += check("an entry that escapes its folder is refused", message.contains("outside its folder")
          && !Files.exists(refusedTarget.getParent().resolve("escaped.txt")));
    } finally {
      server.stop(0);
    }
    return failed;
  }

  private static ToolExecutionRequest request(String tool, Map<String, Object> arguments) throws Exception {
    return ToolExecutionRequest.builder().id("t").name(tool).arguments(JSON.writeValueAsString(arguments)).build();
  }

  private static int tools(DataWeaveCli dw) throws Exception {
    int failed = 0;
    Path root = Files.createTempDirectory("studio-ai-dwt");
    Files.createDirectories(root.resolve("src/test/resources"));
    Files.writeString(root.resolve("src/test/resources/orders.json"),
        "[{\"id\":1,\"name\":\" ann \"},{\"id\":2,\"name\":\"bob\"}]");
    Files.writeString(root.resolve(".env"), "API_KEY=abc");
    DataWeaveTools tools = new DataWeaveTools(dw, DebugChecks.project(root, "demo"), new SecretMask());
    String script = "%dw 2.0\noutput application/json\n---\npayload map { id: $.id, name: trim($.name) }";
    String expected = "[{\"id\":1,\"name\":\"ann\"},{\"id\":2,\"name\":\"bob\"}]";
    String out = tools.execute(request(DataWeaveTools.RUN, Map.of("script", script, "expected", expected,
        "inputs", List.of(Map.of("name", "payload", "file", "src/test/resources/orders.json")))));
    failed += check("run on a project sample file matches the expected output", out.startsWith("OUTPUT (")
        && out.contains("MATCHES the expected output"));
    out = tools.execute(request(DataWeaveTools.RUN, Map.of("script", script,
        "expected", expected.replace("\"bob\"", "\"Bob\""),
        "inputs", List.of(Map.of("name", "payload", "content", "[{\"id\":1,\"name\":\"ann\"},{\"id\":2,\"name\":\"bob\"}]")))));
    failed += check("a difference is located", out.contains(
        "DIFFERS from the expected output at $[1].name: expected \"Bob\", got \"bob\"."));
    out = tools.execute(request(DataWeaveTools.RUN, Map.of("script", script,
        "inputs", List.of(Map.of("name", "payload", "file", ".env")))));
    failed += check("files that hold secrets are not used as samples", out.contains("WITHHELD"));
    out = tools.execute(request(DataWeaveTools.RUN, Map.of("script",
        "%dw 2.0\noutput application/json\n---\npayload map trimz($.name)",
        "inputs", List.of(Map.of("name", "payload", "content", "[{\"name\":\"a\"}]")))));
    failed += check("run errors come back with their line", out.startsWith("DATAWEAVE ERROR")
        && out.contains("line 4") && out.contains("trimz"));
    out = tools.execute(request(DataWeaveTools.VALIDATE, Map.of("script", "payload.id ++")));
    failed += check("validate: a bare expression's error at its own line", out.startsWith("PROBLEMS:")
        && out.contains("- line 1"));
    out = tools.execute(request(DataWeaveTools.VALIDATE, Map.of("script", "vars.region ++ p('suffix')")));
    failed += check("validate: Mule bindings are known", out.startsWith("VALID"));
    out = tools.execute(request(DataWeaveTools.RUN, Map.of("script",
        "output application/json --- { password: \"s3cret-Value1\" }")));
    failed += check("run output is masked", !out.contains("s3cret-Value1") && out.contains("«masked-"));
    return failed;
  }

  // The chat loop offers the DataWeave tools when the CLI is there, and the model's run goes through.
  private static int loop(DataWeaveCli dw) throws Exception {
    int failed = 0;
    List<String> bodies = new ArrayList<>();
    String toolCall = DebugChecks.reply(Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call_1",
        "type", "function", "function", Map.of("name", DataWeaveTools.RUN, "arguments", JSON.writeValueAsString(
            Map.of("script", "output application/json --- payload.a + 1", "inputs",
                List.of(Map.of("name", "payload", "content", "{\"a\":41}")))))))), "tool_calls");
    String answer = DebugChecks.reply(Map.of("role", "assistant", "content", "Ran it: 42."), "stop");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      byte[] bytes = (body.contains("tool_call_id") ? answer : toolCall).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1", "m", "");
      ChatSession chat = new ChatSession();
      chat.dataWeave(dw);
      ChatSession.Turn turn = chat.send(ChatModels.create(connection), ChatMode.ASK, "What does this return?",
          List.of(), null, Progress.NONE);
      failed += check("loop: DataWeave tools and guidance offered with the CLI", DebugChecks.tool(bodies.get(0),
          DataWeaveTools.RUN) && DebugChecks.tool(bodies.get(0), DataWeaveTools.VALIDATE)
          && bodies.get(0).contains("DataWeave CLI is available"));
      failed += check("loop: the run's output went back to the model", bodies.size() == 2
          && bodies.get(1).contains("OUTPUT (") && bodies.get(1).contains("42"));
      failed += check("loop: activity says DataWeave ran", turn.activity().contains("ran DataWeave once"));
      bodies.clear();
      new ChatSession().send(ChatModels.create(connection), ChatMode.ASK, "Hi", List.of(), null, Progress.NONE);
      failed += check("loop: no DataWeave tools without the CLI", !DebugChecks.tool(bodies.get(0), DataWeaveTools.RUN)
          && !bodies.get(0).contains("DataWeave CLI is available"));
    } finally {
      server.stop(0);
    }
    return failed;
  }

  static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
