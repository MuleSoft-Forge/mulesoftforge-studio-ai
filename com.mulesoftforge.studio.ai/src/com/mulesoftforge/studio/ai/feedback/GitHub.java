package com.mulesoftforge.studio.ai.feedback;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Sends reports to the plugin's public repository as GitHub issues opened by the user. Sign-in is GitHub's device
// flow for the MuleSoftForge Studio AI GitHub App: the user enters a short code on github.com, and the token that
// comes back only works with issues in repositories where the app is installed. Device sign-in needs the app's
// client ID, which is public, and no client secret, so there is none in the plugin.
public final class GitHub {
  public static final String OWNER = "MuleSoft-Forge";
  public static final String REPO = "mulesoftforge-studio-ai";
  // Empty until the GitHub App exists. Without it the popup opens GitHub's New issue page instead of sending.
  public static final String CLIENT_ID = "";
  public static final Endpoints GITHUB = new Endpoints("https://github.com", "https://api.github.com");
  public static final String AUTHORIZATIONS_PAGE = "https://github.com/settings/apps/authorizations";
  public static final String EXPIRED = "The code expired before it was entered on GitHub. Sign in again.";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
  private static final String APP = "MuleSoftForge Studio AI GitHub App";

  public record Endpoints(String web, String api) {}

  // The code the user enters, where, and how often GitHub may be asked whether they have.
  public record SignIn(String deviceCode, String userCode, String verificationUri, Duration interval,
      Instant expires) {}

  public record Issue(int number, String url) {}

  @FunctionalInterface
  public interface Pause {
    void pause(Duration duration, BooleanSupplier cancelled) throws InterruptedException;
  }

  // A failure to show as it is. signedOut means the saved sign-in no longer works and should be forgotten.
  public static final class GitHubException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final boolean signedOut;

    GitHubException(int status, String message, boolean signedOut) {
      super(message);
      this.status = status;
      this.signedOut = signedOut;
    }

    public int status() {
      return status;
    }

    public boolean signedOut() {
      return signedOut;
    }
  }

  private final HttpClient http;
  private final Endpoints endpoints;
  private final String clientId;
  private final String userAgent;
  private final Pause pause;

  public GitHub(HttpClient http, String userAgent) {
    this(http, GITHUB, CLIENT_ID, userAgent, GitHub::sleep);
  }

  public GitHub(HttpClient http, Endpoints endpoints, String clientId, String userAgent, Pause pause) {
    this.http = http;
    this.endpoints = endpoints;
    this.clientId = clientId;
    this.userAgent = userAgent;
    this.pause = pause;
  }

  public static boolean signInAvailable() {
    return !CLIENT_ID.isBlank();
  }

  public SignIn startSignIn() throws IOException, InterruptedException {
    JsonNode reply = postForm(endpoints.web() + "/login/device/code", form("client_id", clientId));
    String error = reply.path("error").asText("");
    if (!error.isEmpty()) {
      throw signInError(error, reply);
    }
    String deviceCode = reply.path("device_code").asText("");
    String userCode = reply.path("user_code").asText("");
    if (deviceCode.isEmpty() || userCode.isEmpty()) {
      throw new GitHubException(0, "GitHub did not return a sign-in code.", false);
    }
    return new SignIn(deviceCode, userCode,
        reply.path("verification_uri").asText(endpoints.web() + "/login/device"),
        Duration.ofSeconds(Math.max(0, reply.path("interval").asLong(5))),
        Instant.now().plusSeconds(Math.max(0, reply.path("expires_in").asLong(900))));
  }

  // Asks GitHub, at the interval it set, until the user has entered the code; returns the token.
  public String finishSignIn(SignIn signIn, BooleanSupplier cancelled) throws IOException, InterruptedException {
    Duration interval = signIn.interval();
    while (true) {
      if (!Instant.now().isBefore(signIn.expires())) {
        throw new GitHubException(0, EXPIRED, false);
      }
      pause.pause(interval, cancelled);
      if (cancelled.getAsBoolean()) {
        throw new CancellationException("Sign-in cancelled");
      }
      JsonNode reply = postForm(endpoints.web() + "/login/oauth/access_token",
          form("client_id", clientId, "device_code", signIn.deviceCode(), "grant_type", DEVICE_GRANT));
      String token = reply.path("access_token").asText("");
      if (!token.isEmpty()) {
        return token;
      }
      String error = reply.path("error").asText("");
      switch (error) {
        case "authorization_pending" -> {
        }
        case "slow_down" -> interval = reply.has("interval") ? Duration.ofSeconds(reply.path("interval").asLong())
            : interval.plusSeconds(5);
        default -> throw signInError(error, reply);
      }
    }
  }

  // The account a token belongs to, such as "octocat".
  public String login(String token) throws IOException, InterruptedException {
    HttpResponse<String> response = send(api("/user", token).GET().build());
    if (response.statusCode() != 200) {
      throw failure(response, null);
    }
    String login = JSON.readTree(response.body()).path("login").asText("");
    if (login.isEmpty()) {
      throw new GitHubException(response.statusCode(), "GitHub did not say which account signed in.", false);
    }
    return login;
  }

  public Issue createIssue(String token, String owner, String repo, String title, String body)
      throws IOException, InterruptedException {
    Map<String, String> issue = new LinkedHashMap<>();
    issue.put("title", title);
    issue.put("body", body);
    HttpRequest request = api("/repos/" + owner + "/" + repo + "/issues", token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(issue)))
        .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 201) {
      throw failure(response, owner + "/" + repo);
    }
    JsonNode created = JSON.readTree(response.body());
    return new Issue(created.path("number").asInt(), created.path("html_url").asText(""));
  }

  private HttpRequest.Builder api(String path, String token) {
    return HttpRequest.newBuilder(URI.create(endpoints.api() + path))
        .timeout(TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", userAgent)
        .header("Authorization", "Bearer " + token);
  }

  private JsonNode postForm(String url, Map<String, String> fields) throws IOException, InterruptedException {
    StringJoiner body = new StringJoiner("&");
    fields.forEach((name, value) -> body.add(encode(name) + "=" + encode(value)));
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(TIMEOUT)
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("User-Agent", userAgent)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
    HttpResponse<String> response = send(request);
    JsonNode reply = parse(response.body());
    if (!reply.isObject() || (response.statusCode() >= 400 && !reply.has("error"))) {
      String message = messageOf(response.body());
      throw new GitHubException(response.statusCode(), "GitHub sign-in returned HTTP " + response.statusCode()
          + (message.isEmpty() ? "." : ": " + message), false);
    }
    return reply;
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return http.send(request, BodyHandlers.ofString());
  }

  private static GitHubException signInError(String error, JsonNode reply) {
    String description = reply.path("error_description").asText("");
    String message = switch (error) {
      case "expired_token", "token_expired" -> EXPIRED;
      case "access_denied" -> "Sign-in was cancelled on GitHub.";
      case "device_flow_disabled" -> "Device sign-in is turned off for the " + APP + ".";
      case "incorrect_client_credentials", "unauthorized_client" -> "GitHub does not recognise this plugin's"
          + " client ID.";
      default -> "GitHub sign-in failed: " + error + (description.isEmpty() ? "" : " (" + description + ")");
    };
    return new GitHubException(0, message, false);
  }

  // Repo is null for calls about the account rather than a repository.
  private static GitHubException failure(HttpResponse<String> response, String repo) {
    int status = response.statusCode();
    String message = messageOf(response.body());
    String detail = message.isEmpty() ? "" : ": " + message;
    if (status == 401) {
      return new GitHubException(status, "GitHub no longer accepts this sign-in. Sign in again.", true);
    }
    if (status == 429 || (status == 403 && "0".equals(header(response, "x-ratelimit-remaining")))) {
      return new GitHubException(status, "GitHub's rate limit for your account is used up." + retry(response),
          false);
    }
    if (repo != null && status == 403
        && message.toLowerCase(Locale.ROOT).contains("not accessible by integration")) {
      return new GitHubException(status, "The " + APP + " cannot open issues in " + repo + ". It may not be"
          + " installed there.", false);
    }
    if (repo != null && status == 404) {
      return new GitHubException(status, "GitHub could not find " + repo + ", or the " + APP + " is not installed"
          + " there.", false);
    }
    if (repo != null && status == 410) {
      return new GitHubException(status, "Issues are turned off for " + repo + ".", false);
    }
    if (status == 422) {
      return new GitHubException(status, "GitHub rejected the issue" + detail, false);
    }
    return new GitHubException(status, "GitHub returned HTTP " + status + detail, false);
  }

  private static String retry(HttpResponse<String> response) {
    String after = header(response, "retry-after");
    if (after.matches("\\d+")) {
      return " Try again in " + after + " seconds.";
    }
    String reset = header(response, "x-ratelimit-reset");
    if (reset.matches("\\d+")) {
      return " Try again after " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
          .format(Instant.ofEpochSecond(Long.parseLong(reset))) + ".";
    }
    return "";
  }

  // GitHub's "message", with any field errors, or the start of a body that is not JSON.
  private static String messageOf(String body) {
    JsonNode json = parse(body);
    if (!json.isObject()) {
      String text = body == null ? "" : body.strip();
      return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
    StringBuilder out = new StringBuilder(json.path("message").asText(json.path("error").asText("")));
    for (JsonNode error : json.path("errors")) {
      String text = error.isTextual() ? error.asText() : error.path("message").asText(
          (error.path("field").asText("") + " " + error.path("code").asText("")).strip());
      if (!text.isEmpty()) {
        out.append(out.isEmpty() ? "" : "; ").append(text);
      }
    }
    return out.toString();
  }

  private static JsonNode parse(String body) {
    try {
      return body == null || body.isBlank() ? JSON.missingNode() : JSON.readTree(body);
    } catch (IOException e) {
      return JSON.missingNode();
    }
  }

  private static String header(HttpResponse<String> response, String name) {
    return response.headers().firstValue(name).orElse("").strip();
  }

  private static Map<String, String> form(String... pairs) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      fields.put(pairs[i], pairs[i + 1]);
    }
    return fields;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void sleep(Duration duration, BooleanSupplier cancelled) throws InterruptedException {
    long end = System.nanoTime() + duration.toNanos();
    while (!cancelled.getAsBoolean()) {
      long left = end - System.nanoTime();
      if (left <= 0) {
        return;
      }
      Thread.sleep(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(left), 250)));
    }
  }
}
