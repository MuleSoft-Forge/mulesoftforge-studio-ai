import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mulesoftforge.studio.ai.exchange.ExchangeSearch;
import com.mulesoftforge.studio.ai.exchange.ExchangeTools;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Memory;
import com.mulesoftforge.studio.ai.llm.Progress;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;

// check-exchange: search_connectors on Exchange answers recorded from the live v2 search API (fixtures/exchange,
// recorded 2026-11-08). Ranking, the dependency to propose, what the project already uses, falling back to the
// public search when Studio's login is refused, and the token never reaching the model.
final class ExchangeChecks {
  private static final Path FIXTURES = Path.of("fixtures", "exchange");
  private static final String TOKEN = "studio-token-0f3c9a7e";

  private ExchangeChecks() {}

  static void run() throws Exception {
    Path home = Files.createTempDirectory("studio-ai-exchange-home");
    Extensions.overrideHome(home);
    Memory.overrideUserFile(home.resolve("memory.md"));
    int failed = 0;
    failed += ranking();
    failed += login();
    failed += formatting();
    failed += inTurn();
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int ranking() throws Exception {
    int failed = 0;
    failed += check("jira: MuleSoft's mule4-jira-connector ranks above a community one with a shorter id",
        top("jira").assetId().equals("mule4-jira-connector") && top("jira").groupId().equals("com.mulesoft.connectors"));
    failed += check("servicenow: the fuzzy search's other systems rank below it", top("servicenow").assetId()
        .equals("mule-servicenow-connector"));
    failed += check("salesforce: the main connector ranks above Pub/Sub and Composite", top("salesforce").assetId()
        .equals("mule-salesforce-connector"));
    List<ExchangeSearch.Asset> netsuite = ranked("netsuite");
    failed += check("netsuite: a deprecated asset sorts last whatever its score", netsuite.get(netsuite.size() - 1)
        .deprecated() && !netsuite.get(0).deprecated() && netsuite.get(0).assetId().equals("mule-netsuite-connector"));
    failed += check("slack: com.mulesoft.connectors wins a tie with org.mule.connectors", top("slack").groupId()
        .equals("com.mulesoft.connectors"));
    failed += check("only releases are kept, the highest version of each asset", ExchangeSearch.rank(List.of(
        asset("x-connector", "1.2.0"), asset("x-connector", "1.10.0"), asset("x-connector", "2.0.0-SNAPSHOT")), "x")
        .stream().map(ExchangeSearch.Asset::version).toList().equals(List.of("1.10.0")));
    return failed;
  }

  private static int login() throws Exception {
    int failed = 0;
    List<String> seen = new CopyOnWriteArrayList<>();
    ExchangeSearch refused = new ExchangeSearch((uri, bearer) -> {
      seen.add((bearer == null ? "anonymous " : "bearer ") + uri.getHost() + uri.getPath());
      return bearer != null ? new ExchangeSearch.Response(401, "{}") : fixture("jira");
    }, () -> new ExchangeSearch.Login("https://eu1.anypoint.mulesoft.com", TOKEN, "org-1", "Acme"));
    ExchangeSearch.Result result = refused.search("jira");
    failed += check("a refused login falls back to the public search and says why", result.problem() == null
        && result.scope().contains("HTTP 401") && seen.equals(List.of(
            "bearer eu1.anypoint.mulesoft.com/exchange/api/v2/assets/search",
            "anonymous anypoint.mulesoft.com/exchange/api/v2/assets/search")));
    ExchangeSearch signedIn = new ExchangeSearch((uri, bearer) -> TOKEN.equals(bearer) ? fixture("jira")
        : new ExchangeSearch.Response(500, ""), () -> new ExchangeSearch.Login("https://anypoint.mulesoft.com", TOKEN,
        "org-1", "Acme"));
    failed += check("signed in, the organization is searched and named, the token is not",
        signedIn.search("jira").scope().startsWith("Exchange as Acme") && !signedIn.search("jira").scope()
            .contains(TOKEN));
    ExchangeSearch anonymous = new ExchangeSearch((uri, bearer) -> fixture("jira"), () -> null);
    failed += check("not signed in, the public search says private assets are missing", anonymous.search("jira")
        .scope().contains("not signed in to Anypoint Platform in Studio"));
    ExchangeSearch down = new ExchangeSearch((uri, bearer) -> {
      throw new ConnectException("refused");
    }, () -> null);
    failed += check("no network gives a problem, not an empty list", down.search("jira").problem() != null);
    failed += check("the term is URL-encoded", new ExchangeSearch((uri, bearer) -> {
      seen.add(uri.getRawQuery());
      return fixture("acme-crm-zzz");
    }, () -> null).search("service now").ranked().isEmpty() && seen.get(seen.size() - 1)
        .startsWith("search=service+now&types=extension"));
    return failed;
  }

  private static int formatting() throws Exception {
    int failed = 0;
    ExchangeTools.clearCache();
    ExchangeTools tools = new ExchangeTools(new ExchangeSearch((uri, bearer) -> fixture("jira"), () -> null), null);
    String text = tools.execute(call("jira"));
    failed += check("the answer gives the dependency to propose, with the mule-plugin classifier", text.contains(
        "<groupId>com.mulesoft.connectors</groupId>\n  <artifactId>mule4-jira-connector</artifactId>\n  <version>1.2.17"
            + "</version>\n  <classifier>mule-plugin</classifier>"));
    Path dir = Files.createTempDirectory("studio-ai-exchange");
    Files.writeString(dir.resolve("pom.xml"), "<project><dependencies><dependency><groupId>com.mulesoft.connectors"
        + "</groupId><artifactId>mule4-jira-connector</artifactId><version>1.2.10</version><classifier>mule-plugin"
        + "</classifier></dependency></dependencies></project>");
    ExchangeTools.clearCache();
    String used = new ExchangeTools(new ExchangeSearch((uri, bearer) -> fixture("jira"), () -> null),
        new Harness.DiskProject(dir)).execute(call("jira"));
    failed += check("a connector the project uses is marked with its version and not proposed again",
        used.contains("(this project already depends on it, version 1.2.10)") && !used.contains("<dependency>"));
    ExchangeTools.clearCache();
    String none = new ExchangeTools(new ExchangeSearch((uri, bearer) -> fixture("acme-crm-zzz"), () -> null), null)
        .execute(call("acme crm"));
    failed += check("no match says to use http:request", none.contains("No Mule 4 connector matches")
        && none.contains("http:request"));
    int[] fetched = {0};
    ExchangeTools.clearCache();
    ExchangeTools counting = new ExchangeTools(new ExchangeSearch((uri, bearer) -> {
      fetched[0]++;
      return fixture("slack");
    }, () -> null), null);
    counting.execute(call("Slack"));
    counting.execute(call("slack"));
    failed += check("the same term is searched once within a few minutes", fetched[0] == 1);
    failed += check("the tool says when to call it", ExchangeTools.SPECIFICATION.description()
        .contains("before you use http:request"));
    return failed;
  }

  private static int inTurn() throws Exception {
    int failed = 0;
    ExchangeTools.clearCache();
    Path dir = Files.createTempDirectory("studio-ai-exchange-turn");
    Files.createDirectories(dir.resolve("src/main/mule"));
    Files.writeString(dir.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");
    ChatSession session = new ChatSession();
    session.exchange(new ExchangeSearch((uri, bearer) -> TOKEN.equals(bearer) ? fixture("servicenow")
        : new ExchangeSearch.Response(403, ""), () -> new ExchangeSearch.Login("https://anypoint.mulesoft.com", TOKEN,
        "org-1", "Acme")));
    ExtendChecks.Scripted model = new ExtendChecks.Scripted(List.of(
        ExtendChecks.calls(ExtendChecks.tool("search_connectors", "{\"term\":\"servicenow\"}")),
        ExtendChecks.answer("Use mule-servicenow-connector.")));
    ChatSession.Turn turn = session.send(model, ChatMode.ASK, "which connector for servicenow?", List.of(),
        new Harness.DiskProject(dir), Progress.NONE);
    failed += check("search_connectors is offered and its result goes back to the model", model.requests.get(0)
        .toolSpecifications().stream().anyMatch(s -> s.name().equals("search_connectors"))
        && ExtendChecks.results(model.requests.get(1)).get(0).text().contains("mule-servicenow-connector"));
    boolean leaked = model.requests.stream().flatMap(r -> r.messages().stream()).map(ChatMessage::toString)
        .anyMatch(m -> m.contains(TOKEN));
    failed += check("Studio's token never reaches the model", !leaked);
    failed += check("the activity counts the search", turn.activity().contains("searched Exchange for connectors once"));
    ChatSession without = new ChatSession();
    ExtendChecks.Scripted plain = new ExtendChecks.Scripted(List.of(ExtendChecks.answer("Hi.")));
    without.send(plain, ChatMode.ASK, "hi", List.of(), new Harness.DiskProject(dir), Progress.NONE);
    failed += check("without Exchange the tool is not offered", plain.requests.get(0).toolSpecifications().stream()
        .noneMatch(s -> s.name().equals("search_connectors")));
    return failed;
  }

  private static ExchangeSearch.Asset top(String term) throws Exception {
    return ranked(term).get(0);
  }

  private static List<ExchangeSearch.Asset> ranked(String term) throws Exception {
    return new ExchangeSearch((uri, bearer) -> fixture(term), () -> null).search(term).ranked();
  }

  private static ExchangeSearch.Asset asset(String id, String version) {
    return new ExchangeSearch.Asset("com.mulesoft.connectors", id, version, id, "", false, 0);
  }

  private static ExchangeSearch.Response fixture(String term) throws Exception {
    return new ExchangeSearch.Response(200, Files.readString(FIXTURES.resolve(term + ".json")));
  }

  private static ToolExecutionRequest call(String term) {
    return ExtendChecks.tool("search_connectors", "{\"term\":\"" + term + "\"}");
  }

  private static int check(String name, boolean ok) {
    return ExtendChecks.check(name, ok);
  }
}
