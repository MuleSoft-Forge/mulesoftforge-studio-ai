import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mulesoftforge.studio.ai.check.MuleChecks;
import com.mulesoftforge.studio.ai.check.Problem;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
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

  private static int suites() {
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
                  <munit-tools:payload value="#[readUrl('classpath://sample_data/hello.json')]"
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
