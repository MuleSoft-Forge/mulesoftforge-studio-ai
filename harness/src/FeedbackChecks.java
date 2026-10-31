import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.feedback.Environment;
import com.mulesoftforge.studio.ai.feedback.GitHub;
import com.mulesoftforge.studio.ai.feedback.IssueLink;
import com.mulesoftforge.studio.ai.feedback.Report;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.sun.net.httpserver.HttpServer;

// check-feedback: bug reports and feedback sent from Studio. The report text and masking, the prefilled New
// issue link against the repository's issue forms, and GitHub sign-in and issue creation against a local server
// standing in for github.com and api.github.com.
final class FeedbackChecks {
  private FeedbackChecks() {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLIENT_ID = "Iv23liTestClient";
  private static final String TOKEN = "ghu_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
  private static final String ISSUES = "/repos/" + GitHub.OWNER + "/" + GitHub.REPO + "/issues";
  private static final Environment ENV = new Environment("0.5.7", "Anypoint Studio 7.28.0 (Eclipse 4.31.100)",
      "17.0.12 (Eclipse Adoptium)", "Mac OS X 15.1 (aarch64)", "OpenRouter", "openrouter/auto", "found");

  static void run() throws Exception {
    int failed = report();
    failed += link();
    failed += forms();
    failed += signIn();
    failed += issues();
    System.out.println("INFO  sign-in in Studio is " + (GitHub.signInAvailable() ? "on" : "off until"
        + " GitHub.CLIENT_ID is set; the popup opens GitHub's New issue page instead"));
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int report() {
    int failed = 0;
    Report bug = Report.of(Report.Kind.BUG, "  Accept fails\n for  a new file ", "It broke.\r\nTwice.", ENV);
    failed += check("title gets its prefix and stays on one line",
        bug.issueTitle().equals("Bug: Accept fails for a new file"));
    String body = bug.issueBody();
    failed += check("body is laid out like the bug form: heading, text, environment, footer",
        body.startsWith("### What happened\n\nIt broke.\nTwice.\n\n### Environment\n\n```text\nPlugin: 0.5.7\n"
            + "Studio: Anypoint Studio 7.28.0 (Eclipse 4.31.100)\nJava: 17.0.12 (Eclipse Adoptium)\n")
            && body.contains("Model: openrouter/auto\nDataWeave CLI: found\n```\n")
            && body.endsWith(Report.FOOTER + "\n"));
    Report feedback = Report.of(Report.Kind.FEEDBACK, "More shortcuts", "Love it.", null);
    failed += check("feedback has its own prefix and heading, and no environment when left out",
        feedback.issueTitle().equals("Feedback: More shortcuts") && feedback.issueBody().startsWith(
            "### Feedback\n\nLove it.\n") && !feedback.issueBody().contains("### Environment"));
    failed += check("a report needs a title and text", !Report.of(Report.Kind.BUG, " ", "x", null).complete()
        && !Report.of(Report.Kind.BUG, "x", "\n", null).complete() && bug.complete());

    Report huge = Report.of(Report.Kind.BUG, "t".repeat(300), "x".repeat(40_000), null);
    failed += check("long titles and text are cut to what an issue should hold",
        huge.title().length() == Report.MAX_TITLE + 1 && huge.text().startsWith("x".repeat(Report.MAX_TEXT))
            && huge.text().endsWith("cut at 30,000 characters"));
    failed += check("masking a cut report does not cut it again", huge.masked().report().text().equals(huge.text()));

    String key = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz0123456789";
    Report secrets = Report.of(Report.Kind.BUG, "Fails with " + key,
        "Config has password=hunter2secret and\nAuthorization: Bearer abcdef1234567890xyz\nand " + TOKEN,
        new Environment("0.5.7", "", "", "", "Other OpenAI-compatible", key, ""));
    Report.Masked masked = secrets.masked();
    String all = masked.report().issueTitle() + masked.report().issueBody();
    failed += check("secrets are masked in the title, text and details, and counted",
        !all.contains(key) && !all.contains("hunter2secret") && !all.contains("abcdef1234567890xyz")
            && !all.contains(TOKEN) && all.contains("«masked-") && masked.count() >= 4);
    failed += check("text without secrets is posted as typed", bug.masked().count() == 0
        && bug.masked().report().issueBody().equals(bug.issueBody()));

    Environment here = Environment.of("0.5.7", "Anypoint Studio 7.28.0", "OpenRouter", "Auto", "not set up");
    String home = System.getProperty("user.home");
    failed += check("environment has Java and the OS, and no home folder",
        here.text().contains("Java: " + System.getProperty("java.version"))
            && here.text().contains("OS: " + System.getProperty("os.name")) && !here.text().contains(home)
            && here.lines().size() == 7);
    failed += check("environment values stay on one line and short", new Environment("0.5.7\n beta", "", "", "",
        "", "m".repeat(400), "").text().equals("Plugin: 0.5.7 beta\nModel: " + "m".repeat(160) + "…"));
    return failed;
  }

  private static int link() {
    int failed = 0;
    Report bug = Report.of(Report.Kind.BUG, "Accept fails", "It broke & said #1 + ü 😀\nnext line", ENV);
    IssueLink.Link link = IssueLink.of(GitHub.OWNER, GitHub.REPO, bug);
    Map<String, String> query = query(link);
    failed += check("bug link opens the bug form with the title, uncut",
        link.url().startsWith("https://github.com/MuleSoft-Forge/mulesoftforge-studio-ai/issues/new?")
            && "bug_report.yml".equals(query.get("template")) && "Bug: Accept fails".equals(query.get("title"))
            && !link.cut());
    failed += check("bug link fills the form's fields by id", "0.5.7".equals(query.get("plugin-version"))
        && "OpenRouter".equals(query.get("provider")) && "openrouter/auto".equals(query.get("model"))
        && "Anypoint Studio 7.28.0 (Eclipse 4.31.100), Mac OS X 15.1 (aarch64)".equals(query.get("studio"))
        && ENV.text().equals(query.get("environment")));
    failed += check("text with &, #, +, accents, emoji and new lines arrives as typed",
        "It broke & said #1 + ü 😀\nnext line".equals(query.get("what-happened")) && !link.url().contains("+"));
    Map<String, String> feedback = query(IssueLink.of(GitHub.OWNER, GitHub.REPO,
        Report.of(Report.Kind.FEEDBACK, "Ideas", "More please", ENV)));
    failed += check("feedback link opens the feedback form", "feedback.yml".equals(feedback.get("template"))
        && "More please".equals(feedback.get("feedback")) && ENV.text().equals(feedback.get("environment"))
        && !feedback.containsKey("plugin-version") && !feedback.containsKey("what-happened"));
    failed += check("no environment fields when the details are left out", !query(IssueLink.of(GitHub.OWNER,
        GitHub.REPO, Report.of(Report.Kind.BUG, "x", "y", null))).containsKey("environment"));
    failed += check("providers the form does not list are left for the user to pick",
        IssueLink.of(GitHub.OWNER, GitHub.REPO, Report.of(Report.Kind.BUG, "x", "y", new Environment("0.5.7", "",
            "", "", Provider.MULESOFT_AI_GATEWAY.label, "", ""))).url().indexOf("provider=") < 0
            && query(IssueLink.of(GitHub.OWNER, GitHub.REPO, Report.of(Report.Kind.BUG, "x", "y",
                new Environment("", "", "", "", Provider.OLLAMA.label, "", "")))).get("provider").equals("Ollama"));

    String longText = "Steps 😀 & more ".repeat(1_500);
    Report longReport = Report.of(Report.Kind.BUG, "Long", longText, ENV);
    IssueLink.Link cut = IssueLink.of(GitHub.OWNER, GitHub.REPO, longReport);
    String sent = query(cut).get("what-happened");
    failed += check("a long report is cut to fit in a link, with a note", cut.cut()
        && cut.url().length() <= IssueLink.MAX_LENGTH && cut.url().length() > IssueLink.MAX_LENGTH - 200
        && sent.endsWith(IssueLink.CUT) && longText.startsWith(sent.substring(0, sent.length()
            - IssueLink.CUT.length())));
    IssueLink.Link windows = IssueLink.of(GitHub.OWNER, GitHub.REPO, longReport, IssueLink.WINDOWS_MAX_LENGTH);
    failed += check("the shorter Windows limit keeps the form, title and environment", windows.cut()
        && windows.url().length() <= IssueLink.WINDOWS_MAX_LENGTH && query(windows).get("what-happened")
            .endsWith(IssueLink.CUT) && "Bug: Long".equals(query(windows).get("title"))
        && ENV.text().equals(query(windows).get("environment")));
    // Every other char of this text is the second half of an emoji, so a careless cut would split one; a split
    // half is not valid UTF-8 and would arrive as "?". An emoji is 12 characters in a link, so titles of 12
    // lengths move the cut across every position within one.
    String emoji = "😀".repeat(3_000);
    boolean whole = true;
    for (int length = 1; length <= 12; length++) {
      String emojiSent = query(IssueLink.of(GitHub.OWNER, GitHub.REPO, Report.of(Report.Kind.FEEDBACK,
          "x".repeat(length), emoji, null))).get("feedback");
      String kept = emojiSent.substring(0, emojiSent.length() - IssueLink.CUT.length());
      whole &= emojiSent.endsWith(IssueLink.CUT) && !kept.isEmpty() && emoji.startsWith(kept) && !kept.contains("?");
    }
    failed += check("the cut never splits an emoji", whole);
    return failed;
  }

  // The link fills form fields by id, so the forms must keep those ids and list the providers it fills in.
  private static int forms() throws IOException {
    int failed = 0;
    Path templates = Path.of("..", ".github", "ISSUE_TEMPLATE");
    String bugForm = Files.readString(templates.resolve(IssueLink.BUG_FORM));
    String feedbackForm = Files.readString(templates.resolve(IssueLink.FEEDBACK_FORM));
    failed += check("the bug form has every field the link fills", List.of("what-happened", "plugin-version",
        "provider", "model", "studio", "environment").stream().allMatch(id -> bugForm.contains("id: " + id + "\n")));
    failed += check("the feedback form has every field the link fills", List.of("feedback", "environment")
        .stream().allMatch(id -> feedbackForm.contains("id: " + id + "\n")) && feedbackForm.contains(
            "labels: [feedback]"));
    List<String> missing = new ArrayList<>();
    for (Provider provider : Provider.values()) {
      Map<String, String> query = query(IssueLink.of(GitHub.OWNER, GitHub.REPO, Report.of(Report.Kind.BUG, "x",
          "y", new Environment("", "", "", "", provider.label, "", ""))));
      String option = query.get("provider");
      if (option != null && !bugForm.contains("        - " + option + "\n")) {
        missing.add(option);
      }
    }
    failed += check("every provider the link fills in is an option on the bug form" + (missing.isEmpty() ? ""
        : ": missing " + missing), missing.isEmpty());
    String workflow = Files.readString(Path.of("..", ".github", "workflows", "label-reports.yml"));
    failed += check("the label workflow matches the prefixes reports use", workflow.contains("'"
        + Report.Kind.BUG.prefix + ":'") && workflow.contains("'" + Report.Kind.FEEDBACK.prefix + ":'"));
    return failed;
  }

  private static int signIn() throws Exception {
    int failed = 0;
    try (Stub github = new Stub()) {
      List<Duration> pauses = new CopyOnWriteArrayList<>();
      AtomicBoolean cancel = new AtomicBoolean();
      GitHub client = github.client(pauses);
      github.reply("/login/device/code", Reply.json(200, "{\"device_code\":\"dev-1\",\"user_code\":\"WDJB-MJHT\","
          + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}"));
      GitHub.SignIn start = client.startSignIn();
      Seen asked = github.to("/login/device/code").get(0);
      failed += check("sign-in asks for a code with the client ID only, no secret",
          asked.body().equals("client_id=" + CLIENT_ID) && asked.header("accept").equals("application/json")
              && asked.header("content-type").equals("application/x-www-form-urlencoded")
              && asked.header("user-agent").startsWith("MuleSoftForge-Studio-AI"));
      failed += check("the code, the page and the interval come from GitHub", start.userCode().equals("WDJB-MJHT")
          && start.verificationUri().equals("https://github.com/login/device")
          && start.interval().equals(Duration.ofSeconds(5)) && start.expires().isAfter(Instant.now()));

      github.reply("/login/oauth/access_token", Reply.json(200, "{\"error\":\"authorization_pending\"}"),
          Reply.json(200, "{\"error\":\"slow_down\",\"interval\":12}"),
          Reply.json(200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"scope\":\"\"}"));
      String token = client.finishSignIn(start, cancel::get);
      List<Seen> polls = github.to("/login/oauth/access_token");
      failed += check("waits while the code is not entered, slows to the interval GitHub gives, then signs in",
          token.equals(TOKEN) && polls.size() == 3 && pauses.equals(List.of(Duration.ofSeconds(5),
              Duration.ofSeconds(5), Duration.ofSeconds(12))));
      pauses.clear();
      github.reply("/login/oauth/access_token", Reply.json(200, "{\"error\":\"slow_down\"}"),
          Reply.json(200, "{\"access_token\":\"" + TOKEN + "\"}"));
      client.finishSignIn(start, cancel::get);
      failed += check("without an interval, slowing down adds 5 seconds", pauses.equals(List.of(
          Duration.ofSeconds(5), Duration.ofSeconds(10))));
      failed += check("each poll sends the device code and grant type, never a secret", polls.stream().allMatch(p ->
          p.body().equals("client_id=" + CLIENT_ID + "&device_code=dev-1&grant_type="
              + "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")));

      github.reply("/login/oauth/access_token", Reply.json(200, "{\"error\":\"access_denied\"}"));
      failed += check("declining on GitHub says so", error(() -> client.finishSignIn(start, cancel::get))
          .equals("Sign-in was cancelled on GitHub."));
      github.reply("/login/oauth/access_token", Reply.json(200, "{\"error\":\"expired_token\"}"));
      failed += check("an expired code says to sign in again", error(() -> client.finishSignIn(start, cancel::get))
          .equals(GitHub.EXPIRED));
      int before = github.to("/login/oauth/access_token").size();
      GitHub.SignIn stale = new GitHub.SignIn("dev-1", "WDJB-MJHT", "x", Duration.ZERO, Instant.now());
      failed += check("a code past its expiry is not polled", error(() -> client.finishSignIn(stale, cancel::get))
          .equals(GitHub.EXPIRED) && github.to("/login/oauth/access_token").size() == before);

      github.reply("/login/oauth/access_token", Reply.json(200, "{\"error\":\"authorization_pending\"}"));
      GitHub cancelling = github.client((duration, cancelled) -> cancel.set(true));
      before = github.to("/login/oauth/access_token").size();
      String cancelled = error(() -> cancelling.finishSignIn(start, cancel::get));
      failed += check("Cancel stops waiting without asking GitHub again", cancelled.startsWith("Cancelled")
          && github.to("/login/oauth/access_token").size() == before);
      cancel.set(false);

      github.reply("/login/device/code", Reply.json(200, "{\"error\":\"device_flow_disabled\"}"));
      failed += check("device sign-in turned off for the app says so", error(client::startSignIn)
          .equals("Device sign-in is turned off for the MuleSoftForge Studio AI GitHub App."));
      github.reply("/login/device/code", Reply.json(404, "{\"error\":\"Not Found\"}"));
      failed += check("an unknown client ID is reported", error(client::startSignIn).startsWith(
          "GitHub sign-in failed: Not Found"));
      github.reply("/login/device/code", new Reply(502, "<html>" + "x".repeat(1_000) + "</html>", Map.of()));
      String gateway = error(client::startSignIn);
      failed += check("an HTML error page is reported briefly", gateway.startsWith("GitHub sign-in returned HTTP 502")
          && gateway.length() < 300);

      github.reply("/user", Reply.json(200, "{\"login\":\"octocat\",\"id\":1}"));
      String login = client.login(TOKEN);
      Seen user = github.to("/user").get(0);
      failed += check("the account name is read with the token", login.equals("octocat") && user.method()
          .equals("GET") && user.header("authorization").equals("Bearer " + TOKEN) && user.header(
              "x-github-api-version").equals("2022-11-28"));
    }
    return failed;
  }

  private static int issues() throws Exception {
    int failed = 0;
    List<String> messages = new ArrayList<>();
    try (Stub github = new Stub()) {
      GitHub client = github.client(new ArrayList<>());
      Report report = Report.of(Report.Kind.BUG, "Accept fails", "Steps, and password=hunter2secret", ENV);
      Report.Masked masked = report.masked();
      github.reply(ISSUES, Reply.json(201, "{\"number\":42,\"html_url\":\"https://github.com/" + GitHub.OWNER + "/"
          + GitHub.REPO + "/issues/42\"}"));
      GitHub.Issue issue = client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, masked.report().issueTitle(),
          masked.report().issueBody());
      Seen sent = github.to(ISSUES).get(0);
      JsonNode json = JSON.readTree(sent.body());
      failed += check("the issue is opened as the user and its number and link come back", issue.number() == 42
          && issue.url().endsWith("/issues/42") && sent.method().equals("POST")
          && sent.header("authorization").equals("Bearer " + TOKEN)
          && sent.header("accept").equals("application/vnd.github+json"));
      failed += check("GitHub gets the masked title and body, and nothing else", json.size() == 2
          && json.path("title").asText().equals("Bug: Accept fails")
          && json.path("body").asText().equals(masked.report().issueBody())
          && !sent.body().contains("hunter2secret"));

      github.reply(ISSUES, Reply.json(401, "{\"message\":\"Bad credentials\"}"));
      GitHub.GitHubException revoked = failure(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      messages.add(revoked.getMessage());
      failed += check("a revoked sign-in says to sign in again, marked as signed out", revoked.signedOut()
          && revoked.getMessage().equals("GitHub no longer accepts this sign-in. Sign in again."));

      long reset = Instant.now().plusSeconds(600).getEpochSecond();
      github.reply(ISSUES, new Reply(403, "{\"message\":\"API rate limit exceeded\"}", Map.of(
          "x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset))));
      String limited = error(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      messages.add(limited);
      failed += check("the rate limit says when to try again", limited.startsWith(
          "GitHub's rate limit for your account is used up. Try again after "));
      github.reply(ISSUES, new Reply(429, "{\"message\":\"secondary rate limit\"}", Map.of("retry-after", "60")));
      String secondary = error(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      failed += check("too many requests says how long to wait", secondary.endsWith("Try again in 60 seconds."));

      github.reply(ISSUES, Reply.json(403, "{\"message\":\"Resource not accessible by integration\"}"));
      String app = error(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      messages.add(app);
      failed += check("an app that is not installed on the repository is named", app.equals("The MuleSoftForge"
          + " Studio AI GitHub App cannot open issues in MuleSoft-Forge/mulesoftforge-studio-ai. It may not be"
          + " installed there."));
      github.reply(ISSUES, Reply.json(404, "{\"message\":\"Not Found\"}"));
      String missing = error(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      messages.add(missing);
      failed += check("a missing repository is named", missing.startsWith(
          "GitHub could not find MuleSoft-Forge/mulesoftforge-studio-ai"));
      github.reply(ISSUES, Reply.json(410, "{\"message\":\"Issues are disabled for this repo\"}"));
      failed += check("issues turned off is said plainly", error(() -> client.createIssue(TOKEN, GitHub.OWNER,
          GitHub.REPO, "t", "b")).equals("Issues are turned off for MuleSoft-Forge/mulesoftforge-studio-ai."));
      github.reply(ISSUES, Reply.json(422, "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"Issue\","
          + "\"code\":\"missing_field\",\"field\":\"title\"}]}"));
      String invalid = error(() -> client.createIssue(TOKEN, GitHub.OWNER, GitHub.REPO, "t", "b"));
      messages.add(invalid);
      failed += check("validation errors name the field", invalid.equals(
          "GitHub rejected the issue: Validation Failed; title missing_field"));
      github.reply("/user", Reply.json(401, "{\"message\":\"Bad credentials\"}"));
      GitHub.GitHubException account = failure(() -> client.login(TOKEN));
      failed += check("a token GitHub refuses when reading the account is marked as signed out",
          account.signedOut());
      failed += check("the token never appears in an error", messages.stream().noneMatch(m -> m.contains(TOKEN))
          && !account.getMessage().contains(TOKEN));
    }
    return failed;
  }

  private interface Call {
    Object call() throws Exception;
  }

  private static String error(Call call) {
    try {
      call.call();
      return "";
    } catch (CancellationException e) {
      return "Cancelled: " + e.getMessage();
    } catch (Exception e) {
      return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
  }

  private static GitHub.GitHubException failure(Call call) {
    try {
      call.call();
    } catch (GitHub.GitHubException e) {
      return e;
    } catch (Exception e) {
      throw new IllegalStateException("expected a GitHubException", e);
    }
    throw new IllegalStateException("expected a GitHubException");
  }

  private static Map<String, String> query(IssueLink.Link link) {
    String url = link.url();
    Map<String, String> values = new HashMap<>();
    for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
      int equals = pair.indexOf('=');
      values.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return values;
  }

  private record Reply(int status, String body, Map<String, String> headers) {
    static Reply json(int status, String body) {
      return new Reply(status, body, Map.of());
    }
  }

  private record Seen(String method, String path, Map<String, String> headers, String body) {
    String header(String name) {
      return headers.getOrDefault(name, "");
    }
  }

  // Stands in for github.com and api.github.com. Each path answers with its replies in turn, repeating the last.
  private static final class Stub implements AutoCloseable {
    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();

    Stub() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT),
            String.join(",", values)));
        String path = exchange.getRequestURI().getPath();
        seen.add(new Seen(exchange.getRequestMethod(), path, headers, body));
        Deque<Reply> queue = replies.get(path);
        Reply reply = queue == null || queue.isEmpty() ? Reply.json(404, "{\"message\":\"Not Found\"}")
            : queue.size() > 1 ? queue.poll() : queue.peek();
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("Content-Type", reply.body().startsWith("<") ? "text/html"
            : "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
          exchange.getResponseBody().write(bytes);
        }
        exchange.close();
      });
      server.start();
    }

    GitHub client(List<Duration> pauses) {
      return client((duration, cancelled) -> pauses.add(duration));
    }

    GitHub client(GitHub.Pause pause) {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      return new GitHub(HttpClient.newHttpClient(), new GitHub.Endpoints(base, base), CLIENT_ID,
          "MuleSoftForge-Studio-AI/0.5.7", pause);
    }

    void reply(String path, Reply... sequence) {
      replies.put(path, new ArrayDeque<>(List.of(sequence)));
    }

    List<Seen> to(String path) {
      return seen.stream().filter(s -> s.path().equals(path)).toList();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
