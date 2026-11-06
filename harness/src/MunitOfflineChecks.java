import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.check.MunitSchemas;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectTools;
import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.launch.MunitRunner;
import com.mulesoftforge.studio.ai.launch.MunitTools;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Prompts;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

// check-munit: MUnit suite static checks, when the MUnit prompt is included, and run_munit with a fake runner.
// Offline.
final class MunitOfflineChecks {
  private MunitOfflineChecks() {}

  static void run() throws Exception {
    int failed = 0;
    failed += suites();
    failed += prompt();
    failed += runTool();
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int suites() throws Exception {
    int failed = 0;
    String main = """
        <mule xmlns="http://www.mulesoft.org/schema/mule/core"
            xmlns:doc="http://www.mulesoft.org/schema/mule/documentation"
            xmlns:http="http://www.mulesoft.org/schema/mule/http"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd
              http://www.mulesoft.org/schema/mule/http http://www.mulesoft.org/schema/mule/http/current/mule-http.xsd">
          <http:request-config name="HTTP_Request_config">
            <http:request-connection host="localhost" port="8081"/>
          </http:request-config>
          <flow name="hello-flow">
            <http:request method="GET" doc:name="Request" doc:id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                config-ref="HTTP_Request_config" path="/api"/>
            <logger level="INFO" doc:name="Logger" doc:id="11111111-2222-3333-4444-555555555555" message="done"/>
          </flow>
        </mule>
        """;
    String goodPom = """
        <project>
          <properties><munit.version>3.7.5</munit.version></properties>
          <dependencies>
            <dependency>
              <groupId>com.mulesoft.munit</groupId>
              <artifactId>munit-runner</artifactId>
              <version>${munit.version}</version>
              <classifier>mule-plugin</classifier>
              <scope>test</scope>
            </dependency>
            <dependency>
              <groupId>com.mulesoft.munit</groupId>
              <artifactId>munit-tools</artifactId>
              <version>${munit.version}</version>
              <classifier>mule-plugin</classifier>
              <scope>test</scope>
            </dependency>
          </dependencies>
          <build><plugins>
            <plugin>
              <groupId>com.mulesoft.munit.tools</groupId>
              <artifactId>munit-maven-plugin</artifactId>
              <version>${munit.version}</version>
              <executions><execution><goals><goal>test</goal></goals></execution></executions>
            </plugin>
          </plugins></build>
        </project>
        """;
    String goodSuite = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mule xmlns="http://www.mulesoft.org/schema/mule/core"
            xmlns:munit="http://www.mulesoft.org/schema/mule/munit"
            xmlns:munit-tools="http://www.mulesoft.org/schema/mule/munit-tools"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="
              http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd
              http://www.mulesoft.org/schema/mule/munit http://www.mulesoft.org/schema/mule/munit/current/mule-munit.xsd
              http://www.mulesoft.org/schema/mule/munit-tools http://www.mulesoft.org/schema/mule/munit-tools/current/mule-munit-tools.xsd">
          <munit:config name="hello-test-suite"/>
          <munit:test name="hello-flow-happy-test" description="Returns the mocked payload">
            <munit:behavior>
              <munit-tools:mock-when processor="http:request">
                <munit-tools:with-attributes>
                  <munit-tools:with-attribute attributeName="doc:id"
                      whereValue="#['aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee']"/>
                </munit-tools:with-attributes>
                <munit-tools:then-return>
                  <munit-tools:payload value="#[MunitTools::getResourceAsStream('sample_data/hello.json')]"
                      mediaType="application/json"/>
                </munit-tools:then-return>
              </munit-tools:mock-when>
            </munit:behavior>
            <munit:execution>
              <flow-ref name="hello-flow"/>
            </munit:execution>
            <munit:validation>
              <munit-tools:assert-that expression="#[payload]" is="#[MunitTools::notNullValue()]"/>
            </munit:validation>
          </munit:test>
        </mule>
        """;
    Map<String, String> good = new LinkedHashMap<>();
    good.put("pom.xml", goodPom);
    good.put("src/main/mule/hello.xml", main);
    good.put("src/test/munit/hello-test-suite.xml", goodSuite);
    good.put("src/test/resources/sample_data/hello.json", "{\"ok\":true}");
    List<Problem> ok = MuleChecks.check(good);
    failed += check("good suite: no MUnit problems", ok.stream().noneMatch(p ->
        p.message().contains("munit") || p.message().contains("doc:id") || p.message().contains("readUrl")
            || p.message().contains("description") || p.message().contains("cannot be mocked")));

    String badSuite = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mule xmlns="http://www.mulesoft.org/schema/mule/core"
            xmlns:munit="http://www.mulesoft.org/schema/mule/munit"
            xmlns:munit-tools="http://www.mulesoft.org/schema/mule/munit-tools"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="
              http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd
              http://www.mulesoft.org/schema/mule/munit http://www.mulesoft.org/schema/mule/munit/current/mule-munit.xsd
              http://www.mulesoft.org/schema/mule/munit-tools http://www.mulesoft.org/schema/mule/munit-tools/current/mule-munit-tools.xsd">
          <munit:test name="dup-test">
            <munit:execution><flow-ref name="hello-flow"/></munit:execution>
          </munit:test>
          <munit:test name="dup-test" description="second">
            <munit:behavior>
              <munit-tools:mock-when processor="mule:logger">
                <munit-tools:with-attributes>
                  <munit-tools:with-attribute attributeName="doc:id"
                      whereValue="#['00000000-0000-0000-0000-000000000000']"/>
                </munit-tools:with-attributes>
              </munit-tools:mock-when>
            </munit:behavior>
            <munit:execution><flow-ref name="hello-flow"/></munit:execution>
            <munit:validation>
              <munit-tools:assert-that expression="#[payload]"
                  is="#[MunitTools::equalTo(readUrl('classpath://missing.json'))]"/>
            </munit:validation>
          </munit:test>
        </mule>
        """;
    Map<String, String> bad = new LinkedHashMap<>();
    bad.put("pom.xml", "<project><dependencies></dependencies></project>");
    bad.put("src/main/mule/hello.xml", main);
    bad.put("src/test/munit/hello-test-suite.xml", badSuite);
    List<Problem> problems = MuleChecks.check(bad);
    failed += check("bad suite: missing munit:config", problems.stream()
        .anyMatch(p -> p.message().contains("<munit:config>")));
    failed += check("bad suite: missing description", problems.stream()
        .anyMatch(p -> p.message().contains("needs a description")));
    failed += check("bad suite: duplicate test name", problems.stream()
        .anyMatch(p -> p.message().contains("used more than once")));
    failed += check("bad suite: logger cannot be mocked", problems.stream()
        .anyMatch(p -> p.message().contains("cannot be mocked")));
    failed += check("bad suite: unknown doc:id", problems.stream()
        .anyMatch(p -> p.message().contains("does not match any element")));
    failed += check("bad suite: missing classpath resource", problems.stream()
        .anyMatch(p -> p.message().contains("classpath://missing.json")));
    failed += check("bad suite: pom missing munit-runner", problems.stream()
        .anyMatch(p -> p.path().equals("pom.xml") && p.message().contains("munit-runner")));
    failed += check("bad suite: pom missing munit-tools", problems.stream()
        .anyMatch(p -> p.path().equals("pom.xml") && p.message().contains("munit-tools")));
    failed += check("bad suite: pom missing munit-maven-plugin", problems.stream()
        .anyMatch(p -> p.path().equals("pom.xml") && p.message().contains("munit-maven-plugin")));

    // The shapes a model wrote in Studio, which MUnit's runtime rejected while parsing the suite.
    String wrongShapes = goodSuite.replace("""
              <munit-tools:mock-when processor="http:request">
                <munit-tools:with-attributes>
                  <munit-tools:with-attribute attributeName="doc:id"
                      whereValue="#['aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee']"/>
                </munit-tools:with-attributes>
                <munit-tools:then-return>
                  <munit-tools:payload value="#[MunitTools::getResourceAsStream('sample_data/hello.json')]"
                      mediaType="application/json"/>
                </munit-tools:then-return>
              </munit-tools:mock-when>
        """, """
              <munit-tools:mock-when doc:name="Mock token validation">
                <munit-tools:with-attributes attributeName="doc:name" whereValue="OAuth-Token-Validate" />
                <munit-tools:then-return>
                  <munit-tools:payload>#[{scope: "ADMIN"}]</munit-tools:payload>
                </munit-tools:then-return>
              </munit-tools:mock-when>
        """).replace("xmlns:munit=", "xmlns:doc=\"http://www.mulesoft.org/schema/mule/documentation\"\n    xmlns:munit=");
    Map<String, String> wrong = new LinkedHashMap<>(good);
    wrong.put("src/test/munit/hello-test-suite.xml", wrongShapes);
    failed += check("wrong shapes: the fixture really changed", !wrongShapes.equals(goodSuite));
    failed += shapes("shape checks", wrong, good);
    failed += beyondSchema("shape checks", good, goodSuite);
    failed += resourcesInSnapshot(good);
    List<Path> studio = MunitSchemas.studioRepositories(STUDIO_WORKSPACE);
    if (studio.isEmpty()) {
      System.out.println("SKIP  MUnit schema checks: no generated schemas under " + STUDIO_WORKSPACE
          + "/.mule (set STUDIO_WORKSPACE)");
    } else {
      MunitSchemas.searchIn(studio);
      try {
        failed += check("MUnit schemas found in the Studio workspace" + (MunitSchemas.available() ? ""
            : " (" + MunitSchemas.unavailable() + ")"), MunitSchemas.available());
        failed += shapes("MUnit schema", wrong, good);
        failed += beyondSchema("MUnit schema", good, goodSuite);
        failed += check("MUnit schema: errors are labelled as schema errors", MuleChecks.check(wrong).stream()
            .anyMatch(p -> p.message().startsWith("MUnit schema:") && p.message().contains("processor")));
      } finally {
        MunitSchemas.searchIn(List.of());
      }
    }
    return failed;
  }

  private static final Path STUDIO_WORKSPACE = Path.of(System.getenv().getOrDefault("STUDIO_WORKSPACE",
      System.getProperty("user.home") + "/AnypointStudio/729"));

  // Suites MUnit's schema accepts that still fail when run: the mock payloads that failed on MUnit 3.7.5 /
  // Mule 4.13.1 while a JSON-typed one passed, and a test written without the munit: prefix.
  private static int beyondSchema(String label, Map<String, String> good, String goodSuite) {
    int failed = 0;
    String fileMock = "#[MunitTools::getResourceAsStream('sample_data/hello.json')]";
    for (String readUrl : List.of("#[readUrl('classpath://sample_data/hello.json')]",
        "#[readUrl('classpath://sample_data/hello.json', 'application/json')]")) {
      failed += check(label + ": a mock " + readUrl + " with mediaType JSON is reported", suiteProblems(good,
          goodSuite.replace(fileMock, readUrl)).stream().anyMatch(p -> p.message().contains("readUrl(…)")
              && p.message().contains("getResourceAsString")));
    }
    List<Problem> typedRead = suiteProblems(good, goodSuite.replace(fileMock, "#[%dw 2.0&#10;output application/json"
        + "&#10;---&#10;readUrl('classpath://sample_data/hello.json', 'application/json')]"));
    failed += check(label + ": readUrl under an output application/json header is clean"
        + (typedRead.isEmpty() ? "" : " " + typedRead), typedRead.isEmpty());
    failed += check(label + ": getResourceAsStream of a missing file is reported", suiteProblems(good,
        goodSuite.replace("sample_data/hello.json", "sample_data/missing.json")).stream()
        .anyMatch(p -> p.message().contains("getResourceAsStream('sample_data/missing.json')")));
    String objectMock = goodSuite.replace(fileMock, "#[{active: true, scope: 'read'}]");
    String writeMock = goodSuite.replace(fileMock, "#[write({active: true, scope: 'read'}, 'application/json')]");
    String jsonMock = goodSuite.replace(fileMock,
        "#[%dw 2.0&#10;output application/json&#10;---&#10;{active: true, scope: 'read'}]");
    String eventMock = jsonMock.replace("<munit:behavior>", "<munit:behavior>\n"
        + "<munit:set-event><munit:payload value=\"#[{id: 1}]\" mediaType=\"application/json\"/></munit:set-event>");
    failed += check(label + ": a mock DataWeave object with mediaType JSON is reported", suiteProblems(good,
        objectMock).stream().anyMatch(p -> p.message().contains("munit-tools:payload")
            && p.message().contains("Java map") && p.message().contains("output application/json")));
    failed += check(label + ": a mock write() with mediaType JSON is reported", suiteProblems(good, writeMock)
        .stream().anyMatch(p -> p.message().contains("write()") && p.message().contains("payload.field")));
    failed += check(label + ": a set-event object with mediaType JSON is reported", suiteProblems(good,
        eventMock).stream().anyMatch(p -> p.message().contains("munit:payload") && p.message().contains("Java map")));
    String streamEvent = eventMock.replace("#[{id: 1}]", "#[MunitTools::getResourceAsStream('sample_data/hello.json')]");
    failed += check(label + ": a set-event getResourceAsStream is reported", suiteProblems(good, streamEvent)
        .stream().anyMatch(p -> p.message().contains("Stream closed")));
    List<Problem> stringEvent = suiteProblems(good, streamEvent.replace("getResourceAsStream", "getResourceAsString"));
    failed += check(label + ": a set-event getResourceAsString is clean"
        + (stringEvent.isEmpty() ? "" : " " + stringEvent), stringEvent.isEmpty());
    List<Problem> jsonProblems = suiteProblems(good, jsonMock);
    failed += check(label + ": a JSON-typed mock is clean" + (jsonProblems.isEmpty() ? "" : " " + jsonProblems),
        jsonProblems.isEmpty());
    failed += check(label + ": a DataWeave object without mediaType is left alone", suiteProblems(good,
        objectMock.replaceAll("\\s+mediaType=\"application/json\"", "")).isEmpty());
    String unprefixed = goodSuite.replace("<munit:test ", "<test ").replace("</munit:test>", "</test>");
    List<Problem> unprefixedProblems = suiteProblems(good, unprefixed);
    failed += check(label + ": <test> without munit: is reported, and that no test runs", unprefixedProblems
        .stream().anyMatch(p -> p.message().startsWith("<test> is not an MUnit test"))
        && unprefixedProblems.stream().anyMatch(p -> p.message().contains("has no <munit:test>")));
    return failed;
  }

  // Through the snapshot the edit tools check, not a hand-built map: JSON samples on disk or proposed in the same
  // turn (here after the suite, as a model did in Studio) count as present.
  private static int resourcesInSnapshot(Map<String, String> good) throws Exception {
    int failed = 0;
    String json = "src/test/resources/sample_data/hello.json";
    String suite = "src/test/munit/hello-test-suite.xml";
    Map<String, String> onDisk = new LinkedHashMap<>(good);
    failed += check("snapshot: a sample JSON on disk is found", missingResources(
        MuleChecks.check(MuleChecks.snapshot(memory(onDisk), null))).isEmpty());

    Map<String, String> before = new LinkedHashMap<>(good);
    before.remove(json);
    before.remove(suite);
    ChangeSet changes = new ChangeSet();
    ProjectTools tools = new ProjectTools(memory(before), changes, true, new SecretMask());
    String first = tools.execute(write(suite, good.get(suite)));
    failed += check("snapshot: the suite alone reports the missing sample", first.contains("is not under"));
    tools.execute(write(json, good.get(json)));
    List<Problem> after = missingResources(tools.problemsIntroduced());
    failed += check("snapshot: a sample proposed after the suite clears it" + (after.isEmpty() ? "" : " " + after),
        after.isEmpty());
    return failed;
  }

  private static List<Problem> missingResources(List<Problem> problems) {
    return problems.stream().filter(p -> p.message().contains("is not under src/test/resources")).toList();
  }

  private static ToolExecutionRequest write(String path, String content) throws Exception {
    return ToolExecutionRequest.builder().id("w").name("write_file")
        .arguments(new ObjectMapper().writeValueAsString(Map.of("path", path, "content", content))).build();
  }

  private static ProjectFiles memory(Map<String, String> disk) {
    return new ProjectFiles() {
      @Override
      public String name() {
        return "demo";
      }

      @Override
      public String read(String path) {
        return disk.get(path);
      }

      @Override
      public List<String> list(String directory, int limit) {
        return disk.keySet().stream().filter(p -> p.startsWith(directory + "/")).limit(limit).toList();
      }
    };
  }

  private static List<Problem> suiteProblems(Map<String, String> good, String suite) {
    Map<String, String> project = new LinkedHashMap<>(good);
    project.put("src/test/munit/hello-test-suite.xml", suite);
    return MuleChecks.check(project).stream().filter(p -> p.path().startsWith("src/test/munit/")).toList();
  }

  // The good suite has no shape problems; the wrong one has each that MUnit's runtime reported in Studio.
  private static int shapes(String label, Map<String, String> wrong, Map<String, String> good) {
    int failed = 0;
    List<Problem> problems = MuleChecks.check(wrong);
    List<Problem> goodProblems = MuleChecks.check(good).stream()
        .filter(p -> p.path().startsWith("src/test/munit/")).toList();
    failed += check(label + ": good suite is clean" + (goodProblems.isEmpty() ? "" : " " + goodProblems),
        goodProblems.isEmpty());
    failed += check(label + ": mock-when without processor", problems.stream()
        .anyMatch(p -> p.message().contains("processor") && p.message().contains("mock-when")));
    failed += check(label + ": attributes on with-attributes", problems.stream()
        .anyMatch(p -> p.message().contains("with-attributes") && p.message().contains("attributeName")));
    failed += check(label + ": payload as text instead of value", problems.stream()
        .anyMatch(p -> p.message().contains("munit-tools:payload") && p.message().contains("value")));
    failed += check(label + ": doc:name that matches nothing", problems.stream()
        .anyMatch(p -> p.message().contains("OAuth-Token-Validate")) || label.equals("MUnit schema"));
    return failed;
  }

  private static int prompt() {
    int failed = 0;
    failed += check("MUnit prompt is not in a plain Ask turn",
        !Prompts.system(ChatMode.ASK, "demo", "", "").contains("You create MUnit 3.7 tests"));
    failed += check("MUnit prompt is included when asked for",
        Prompts.system(ChatMode.AGENT, "demo", "", "", false, false, false, true, "")
            .contains("You create MUnit 3.7 tests")
            && Prompts.system(ChatMode.AGENT, "demo", "", "", false, false, false, true, "")
                .contains("offer_changes with one item per scenario"));
    failed += check("Show built-in prompt lists MUnit",
        Prompts.builtIn().contains("When creating or editing MUnit tests"));
    failed += check("wantsMunit: message about MUnit",
        ChatSession.wantsMunit("Create MUnit tests for flow X", List.of()));
    failed += check("wantsMunit: unit tests wording",
        ChatSession.wantsMunit("write unit tests for the login flow", List.of()));
    failed += check("wantsMunit: attached suite path", ChatSession.wantsMunit("please review",
        List.of(Attachment.file("demo", "src/test/munit/hello-test-suite.xml", "<mule/>"))));
    failed += check("wantsMunit: unrelated message is false",
        !ChatSession.wantsMunit("Does GET /api/employees work?", List.of()));
    failed += check("wantsMunit: run the tests", ChatSession.wantsMunit("now run the tests", List.of()));
    failed += check("MUnit prompt says how to run tests",
        Prompts.system(ChatMode.AGENT, "demo", "", "", false, false, false, true, "").contains("run_munit"));
    String munit = Prompts.system(ChatMode.AGENT, "demo", "", "", false, false, false, true, "");
    failed += check("MUnit prompt's mock example returns JSON, not a Java map",
        munit.contains("<munit-tools:payload value=\"#[%dw 2.0&#10;output application/json&#10;---&#10;{ok: true}]\"")
            && !munit.contains("value=\"#[{ok: true}]\"") && munit.contains("Stream Compatible"));
    failed += check("MUnit prompt reads sample files with getResourceAsString",
        munit.contains("value=\"#[MunitTools::getResourceAsString('….json')]\"")
            && !munit.contains("value=\"#[MunitTools::getResourceAsStream("));
    return failed;
  }

  private static int runTool() throws Exception {
    int failed = 0;
    Map<String, String> disk = new LinkedHashMap<>();
    disk.put("pom.xml", "<project/>");
    disk.put("src/main/mule/hello.xml", "<mule/>");
    disk.put("src/test/munit/hello-test-suite.xml", "<mule/>");
    disk.put("src/test/munit/other-test-suite.xml", "<mule/>");
    ProjectFiles files = new ProjectFiles() {
      @Override
      public String name() {
        return "demo";
      }

      @Override
      public String read(String path) {
        return disk.get(path);
      }

      @Override
      public List<String> list(String directory, int limit) {
        return disk.keySet().stream().filter(p -> p.startsWith(directory + "/")).limit(limit).toList();
      }
    };

    FakeRunner passing = new FakeRunner(new MunitRunner.Run(true, 2, List.of(
        new MunitRunner.Test("hello-test-suite.xml", "hello-flow-happy-test", MunitRunner.Outcome.PASSED, null),
        new MunitRunner.Test("hello-test-suite.xml", "hello-flow-error-test", MunitRunner.Outcome.PASSED, null)),
        null));
    String out = tools(files, passing, new ChangeSet()).execute(call(
        "{\"suites\":[\"src/test/munit/hello-test-suite.xml\"]}"));
    failed += check("run_munit: passing run reports PASSED", out.startsWith("PASSED: 2 passed, 0 failed"));
    failed += check("run_munit: runs the named suite only, in run mode",
        passing.suites.equals(List.of("hello-test-suite.xml")) && "run".equals(passing.mode));

    FakeRunner all = new FakeRunner(passing.result);
    tools(files, all, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: no suites runs every suite under src/test/munit",
        all.suites.equals(List.of("hello-test-suite.xml", "other-test-suite.xml")));

    FakeRunner failing = new FakeRunner(new MunitRunner.Run(true, 2, List.of(
        new MunitRunner.Test("hello-test-suite.xml", "hello-flow-happy-test", MunitRunner.Outcome.PASSED, null),
        new MunitRunner.Test("hello-test-suite.xml", "hello-flow-error-test", MunitRunner.Outcome.FAILED,
            "Expected 200 but was 500")), null));
    out = tools(files, failing, new ChangeSet()).execute(call(
        "{\"tests\":[\"hello-flow-error-test\"]}"));
    failed += check("run_munit: failing run names the test and its cause", out.startsWith("FAILED: 1 passed, 1 failed")
        && out.contains("hello-flow-error-test") && out.contains("Expected 200 but was 500"));
    failed += check("run_munit: passes the test names", failing.tests.equals(List.of("hello-flow-error-test")));

    FakeRunner unknown = new FakeRunner(passing.result);
    out = tools(files, unknown, new ChangeSet()).execute(call("{\"suites\":[\"missing-test-suite.xml\"]}"));
    failed += check("run_munit: unknown suite is an error and starts nothing",
        out.startsWith("ERROR: no suite") && unknown.mode == null);

    ChangeSet pending = new ChangeSet();
    pending.stage("src/test/munit/hello-test-suite.xml", "<mule/>", "<mule><!-- new --></mule>");
    FakeRunner unsaved = new FakeRunner(passing.result);
    out = tools(files, unsaved, pending).execute(call("{}"));
    failed += check("run_munit: refuses while this turn's proposals are not saved",
        out.startsWith("NOT SAVED") && unsaved.mode == null);

    FakeRunner container = new FakeRunner(new MunitRunner.Run(true, 0, List.of(),
        "Could not start the app: Address already in use"));
    out = tools(files, container, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: app that does not start is reported with the reason",
        out.startsWith("ERROR: MUnit could not run") && out.contains("Address already in use"));

    FakeRunner debug = new FakeRunner(null);
    debug.runningAfterStart = true;
    out = tools(files, debug, new ChangeSet()).execute(call("{\"mode\":\"debug\"}"));
    failed += check("run_munit: debug mode returns once started", out.startsWith("STARTED") && "debug".equals(debug.mode));

    FakeRunner ended = new FakeRunner(null);
    ended.console = "[ERROR] Failed to execute goal munit-maven-plugin";
    ended.runningPolls = 2;
    out = tools(files, ended, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: launch ending without results shows the console",
        out.startsWith("ENDED") && out.contains("Failed to execute goal"));

    FakeRunner waiting = new FakeRunner(passing.result);
    waiting.runningPolls = 3;
    out = tools(files, waiting, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: a finished run is reported only once the MUnit launch has ended",
        out.startsWith("PASSED: 2 passed") && waiting.runningPolls < 0);

    FakeRunner empty = new FakeRunner(new MunitRunner.Run(true, 0, List.of(), null));
    empty.console = "[INFO] Running employees-api-test-suite.xml";
    out = tools(files, empty, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: a finished run with no tests is not reported as passing",
        out.startsWith("ENDED") && out.contains("employees-api-test-suite.xml"));

    // As in a real run: eight HTTP:TIMEOUT errors with long stack traces, and the cause logged mid-console.
    String frames = "\tat org.mule.munit.runner.flow.TestFlow.run(TestFlow.java:320)\n".repeat(30);
    List<MunitRunner.Test> timeouts = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      timeouts.add(new MunitRunner.Test("hello-test-suite.xml", "timeout-test-" + i, MunitRunner.Outcome.ERROR,
          "org.mule.munit.runner.model.TestExecutionException: Error [HTTP:TIMEOUT] while running test 'timeout-test-"
              + i + "': Timeout exceeded.\n" + frames));
    }
    FakeRunner hanging = new FakeRunner(new MunitRunner.Run(true, 8, timeouts, null));
    hanging.console = "[INFO] Building jeeves-erp-app\n".repeat(300)
        + "ERROR 2026-11-06 [main] org.mule.module.apikit: Could not load the API spec resource::eca25329:jeeves-erp-api\n"
        + "Caused by: java.io.FileNotFoundException: jeeves-erp-api.yaml\n" + frames
        + "[INFO] MUnit run finished\n".repeat(300);
    out = tools(files, hanging, new ChangeSet()).execute(call("{}"));
    failed += check("run_munit: every failing test keeps its message, without the stack frames",
        out.contains("timeout-test-8': Timeout exceeded.") && out.contains("stack frames left out")
            && !out.contains("\tat org.mule"));
    failed += check("run_munit: failing tests come back with the run's console errors, even mid-console",
        out.contains("CONSOLE of the MUnit run:") && out.contains("Could not load the API spec")
            && out.contains("Caused by: java.io.FileNotFoundException") && out.contains("Do not guess a cause"));
    failed += check("console digest: keeps WARN lines from the middle of a long console", AppConsole.digest(
        "[INFO] ok\n".repeat(1_000) + "WARN  [main] mock-when matched no processor\n" + "[INFO] ok\n".repeat(1_000), 2_000)
        .contains("mock-when matched no processor"));
    failed += check("console digest: no error lines falls back to the end of the console",
        AppConsole.digest("[INFO] ok\n".repeat(1_000), 500).startsWith("… (earlier lines cut)"));

    failed += check("run_munit: is offered under that name", MunitTools.SPECIFICATIONS.get(0).name().equals("run_munit"));
    return failed;
  }

  private static MunitTools tools(ProjectFiles files, MunitRunner runner, ChangeSet changes) {
    return new MunitTools(files, runner, changes, new SecretMask(), () -> false, Duration.ofSeconds(30));
  }

  private static ToolExecutionRequest call(String arguments) {
    return ToolExecutionRequest.builder().id("1").name(MunitTools.RUN).arguments(arguments).build();
  }

  // Reports result as soon as a run starts; with result null, reports nothing and runs for runningPolls polls.
  private static final class FakeRunner implements MunitRunner {
    final Run result;
    List<String> suites;
    List<String> tests;
    String mode;
    String console;
    boolean runningAfterStart;
    int runningPolls;

    FakeRunner(Run result) {
      this.result = result;
    }

    @Override
    public void start(String project, List<String> suites, List<String> tests, String mode) {
      this.suites = suites;
      this.tests = tests;
      this.mode = mode;
    }

    @Override
    public Run run() {
      return mode == null ? null : result;
    }

    @Override
    public boolean running() {
      if (mode == null) {
        return false;
      }
      if (runningAfterStart) {
        return true;
      }
      return runningPolls-- > 0;
    }

    @Override
    public String console() {
      return console;
    }
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
