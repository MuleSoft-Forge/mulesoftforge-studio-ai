import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mulesoftforge.studio.ai.feedback.LinkedInPost;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatModels;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.NewsFeed;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.Updates;
import com.mulesoftforge.studio.ai.render.Alerts;
import com.mulesoftforge.studio.ai.render.HtmlPage;
import com.mulesoftforge.studio.ai.render.LoadingPhrases;
import com.mulesoftforge.studio.ai.render.Palette;
import com.mulesoftforge.studio.ai.settings.AiSettings.Connection;
import com.mulesoftforge.studio.ai.settings.Provider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.model.chat.ChatModel;

// check-fun: what the view shows while the model works and after. Streamed reasoning becomes a "Thought for"
// block and is not sent back, loading phrases rotate as gemini-cli's do, the background badge, the shimmer and
// thought block in the page, and the 👍 LinkedIn post.
final class FunChecks {
  private FunChecks() {}

  static void run() throws Exception {
    int failed = 0;
    failed += thinking();
    failed += phrases();
    failed += alerts();
    failed += page();
    failed += linkedIn();
    failed += news();
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int thinking() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      try {
        reason(exchange);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    int failed = 0;
    try {
      Connection connection = new Connection(Provider.CUSTOM, "http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1", "think-model", "");
      StringBuffer pieces = new StringBuffer();
      StringBuffer answer = new StringBuffer();
      AtomicReference<String> thought = new AtomicReference<>();
      AtomicLong millis = new AtomicLong(-1);
      List<String> receiving = new CopyOnWriteArrayList<>();
      Progress progress = new Progress() {
        @Override
        public void step(String message) {}

        @Override
        public void receiving(String what) {
          receiving.add(what);
        }

        @Override
        public void partial(String text) {
          answer.append(text);
        }

        @Override
        public void thinking(String text) {
          pieces.append(text);
        }

        @Override
        public void thought(String text, long took) {
          thought.set(text);
          millis.set(took);
        }

        @Override
        public boolean isCancelled() {
          return false;
        }
      };
      ChatModel model = ChatModels.create(connection);
      ChatSession session = new ChatSession();
      // Through AutoRouter, as the view sends, so its progress wrapper passes reasoning on.
      ChatSession.Turn turn = AutoRouter.send(session, List.of(new AutoRouter.Option("think-model", model)),
          ChatMode.ASK, "why is the flow slow?", List.of(), null, progress);
      failed += check("reasoning streams to the view apart from the answer", pieces.toString()
          .equals("Checking the flow. It calls the API twice.") && answer.toString().equals("It calls the API twice."));
      failed += check("the reasoning arrives once, with how long it took, when the answer starts",
          "Checking the flow. It calls the API twice.".equals(thought.get()) && millis.get() >= 150
          && receiving.contains("Thinking…") && receiving.indexOf("Thinking…") < receiving.indexOf("Writing the answer…"));
      failed += check("the answer is the text, not the reasoning", turn.text().equals("It calls the API twice."));
      AutoRouter.send(session, List.of(new AutoRouter.Option("think-model", model)), ChatMode.ASK, "and now?",
          List.of(), null, Progress.NONE);
      failed += check("asking to read reasoning adds nothing to the request", bodies.size() == 2
          && !bodies.get(0).contains("reasoning") && !bodies.get(0).contains("thinking"));
      failed += check("reasoning is not sent back with the next message", !bodies.get(1).contains("Checking the flow")
          && bodies.get(1).contains("It calls the API twice."));
    } finally {
      server.stop(0);
    }
    failed += check("thought times read like Continue's", Progress.thoughtFor(2_340).equals("Thought for 2.3 s")
        && Progress.thoughtFor(14_900).equals("Thought for 14 s")
        && Progress.thoughtFor(65_000).equals("Thought for 1 min 5 s")
        && Progress.thoughtFor(120_000).equals("Thought for 2 min"));
    return failed;
  }

  private static void reason(HttpExchange exchange) throws IOException, InterruptedException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    send(out, chunk("{\"role\":\"assistant\",\"reasoning_content\":\"Checking the flow.\"}", null));
    Thread.sleep(200);
    send(out, chunk("{\"reasoning_content\":\" It calls the API twice.\"}", null));
    send(out, chunk("{\"content\":\"It calls the API twice.\"}", null));
    send(out, chunk("{}", "stop"));
    send(out, "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"think-model\","
        + "\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":12,\"total_tokens\":22}}");
    send(out, "[DONE]");
  }

  private static int phrases() {
    int failed = 0;
    failed += check("tips and witty lines are on by default, and Off stays off once chosen",
        LoadingPhrases.Mode.fromName("") == LoadingPhrases.Mode.ALL
        && LoadingPhrases.Mode.fromName("nonsense") == LoadingPhrases.Mode.ALL
        && LoadingPhrases.Mode.fromName("OFF") == LoadingPhrases.Mode.OFF
        && LoadingPhrases.at(LoadingPhrases.Mode.OFF, 12, 3) == null);
    failed += check("witty lines change every 5 s", LoadingPhrases.at(LoadingPhrases.Mode.WITTY, 0, 7)
        .equals(LoadingPhrases.at(LoadingPhrases.Mode.WITTY, 4, 7))
        && !LoadingPhrases.at(LoadingPhrases.Mode.WITTY, 4, 7).equals(LoadingPhrases.at(LoadingPhrases.Mode.WITTY, 5, 7)));
    failed += check("tips change every 10 s", LoadingPhrases.at(LoadingPhrases.Mode.TIPS, 0, 2)
        .equals(LoadingPhrases.at(LoadingPhrases.Mode.TIPS, 9, 2))
        && !LoadingPhrases.at(LoadingPhrases.Mode.TIPS, 9, 2).equals(LoadingPhrases.at(LoadingPhrases.Mode.TIPS, 10, 2)));
    failed += check("both alternate a witty line and a tip", LoadingPhrases.WITTY.contains(
        LoadingPhrases.at(LoadingPhrases.Mode.ALL, 0, 1)) && LoadingPhrases.TIPS.contains(
        LoadingPhrases.at(LoadingPhrases.Mode.ALL, 5, 1)));
    Set<String> seen = new HashSet<>();
    for (long s = 0; s < 5L * LoadingPhrases.WITTY.size(); s += 5) {
      seen.add(LoadingPhrases.at(LoadingPhrases.Mode.WITTY, s, 11));
    }
    failed += check("every witty line comes round before one repeats", seen.size() == LoadingPhrases.WITTY.size());
    boolean short_ = true;
    for (String phrase : List.of(LoadingPhrases.WITTY, LoadingPhrases.TIPS).stream().flatMap(List::stream).toList()) {
      short_ &= phrase.length() <= 80;
    }
    failed += check("phrases fit on the status line", short_);
    return failed;
  }

  private static int alerts() {
    return check("the badge counts unseen answers, and a question shows ?", Alerts.badge(0, false).isEmpty()
        && Alerts.badge(1, false).equals("1") && Alerts.badge(12, false).equals("9+") && Alerts.badge(3, true).equals("?")
        && Alerts.Kind.QUESTION.message.contains("waiting"));
  }

  private static int page() {
    String page = HtmlPage.page(new HtmlPage.Theme(Palette.DARK, new Palette.Rgb(7, 11, 20),
        new Palette.Rgb(230, 236, 245), "Helvetica", 12, "Menlo", 11));
    int failed = 0;
    failed += check("the page folds reasoning under a Thought for line", page.contains("function msfThought(summary, text)")
        && page.contains("body.textContent = text") && page.contains("steps.insertBefore(block, live)"));
    failed += check("the live status shimmers, and stops for reduced motion", page.contains("@keyframes shimmer")
        && page.contains("animation: shimmer") && page.contains("prefers-reduced-motion: reduce"));
    failed += check("reasoning streams muted while the model thinks", page.contains(
        "live.classList.toggle('thinking', status.indexOf('" + ChatSession.WAITING_FOR_DATA + "') === 0)")
        && !page.contains("%s"));
    failed += check("the thumbs say where they go", page.contains("'Liked it? Share it on LinkedIn'")
        && page.contains("'Missed? Tell us what went wrong'"));
    failed += check("the You bar floats over the chat, so showing it does not move the page",
        page.contains("#sticky { position: fixed;") && !page.contains("position: sticky"));
    failed += check("new output is followed until the user scrolls up, and again after they send",
        page.contains("if (e.deltaY < 0) { following = false; }") && page.contains("if (nearBottom()) { following = true; }")
            && page.contains("if (kind === 'you') {\n    following = true;") && !page.contains("scrollIfNeeded"));
    failed += check("a long answer opens at its first line, not its end", page.contains(
        "div.offsetHeight > window.innerHeight * 0.6") && page.contains("div.getBoundingClientRect().top + window.scrollY"));
    failed += starterIcons(page);
    return failed;
  }

  private static int starterIcons(String plain) {
    HtmlPage.Theme theme = new HtmlPage.Theme(Palette.LIGHT, new Palette.Rgb(255, 255, 255),
        new Palette.Rgb(16, 42, 67), "Helvetica", 12, "Menlo", 11);
    String flow = "data:image/png;base64,iVBORw0KGgo=";
    String page = HtmlPage.page(theme, new HtmlPage.Icons(flow, "https://evil.example/x.png",
        "data:image/png;base64,\"><script>x()</script>", null, flow));
    int failed = 0;
    failed += check("without Studio the starter cards have no icons", !plain.contains("class=\"ico\"")
        && plain.contains("<div class=\"card\" data-starter=\"Explain how the main flow in this project works.\">"
            + "<b>Explain this flow</b>"));
    failed += check("Studio's flow icon shows on its starter card", page.contains(
        "<div class=\"card icon\" data-starter=\"Explain how the main flow in this project works.\">"
            + "<img class=\"ico\" alt=\"\" src=\"" + flow + "\"><div><b>Explain this flow</b>"));
    failed += check("the website card shows our round logo and still opens the site", page.contains(
        "<div class=\"card help icon\" data-a=\"url:" + Updates.PAGE + "\"><img class=\"ico\" alt=\"\" src=\"" + flow
            + "\"><div><b>Visit the website</b>") && page.contains("#starters .help.icon { justify-content: center;"));
    failed += check("only base64 PNG data becomes an icon", page.split("class=\"ico\"", -1).length == 3
        && !page.contains("evil.example") && !page.contains("<script>x()"));
    failed += check("the page lets data images in and nothing else", page.contains("img-src data:\">")
        && page.contains("default-src 'none'"));
    return failed;
  }

  private static int linkedIn() {
    String url = LinkedInPost.url();
    String text = URLDecoder.decode(url.substring(url.indexOf("text=") + 5), StandardCharsets.UTF_8);
    return check("👍 opens LinkedIn's composer with the post filled in",
        url.startsWith("https://www.linkedin.com/feed/?shareActive=true&text=") && !url.contains("+")
            && text.equals(LinkedInPost.TEXT) && text.contains("I used MuleSoftForge Studio AI")
            && text.contains("liked the result") && text.contains(LinkedInPost.SITE));
  }

  private static int news() {
    String feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>MuleSoftForge: What's New</title>
          <item><title>Studio AI 0.5.27: chat polish</title>
            <link>https://mulesoftforge.com/studio-ai/use-the-chat</link>
            <pubDate>Fri, 06 Nov 2026 12:00:00 GMT</pubDate>
            <description>Thought blocks &amp; hooks.</description></item>
          <item><title>Old link</title><link>javascript:alert(1)</link><description>%s</description></item>
          <item><title></title><link>https://mulesoftforge.com/x</link></item>
        </channel></rss>
        """.formatted("x".repeat(400));
    int failed = 0;
    try {
      List<NewsFeed.Item> items = NewsFeed.parse(feed);
      NewsFeed.Item first = items.get(0);
      failed += check("news shows each item's title, description, link and date", items.size() == 2
          && first.title().equals("Studio AI 0.5.27: chat polish") && first.description().equals("Thought blocks & hooks.")
          && first.link().equals("https://mulesoftforge.com/studio-ai/use-the-chat") && first.date().equals("6 Nov 2026"));
      failed += check("news keeps only https links and short descriptions", items.get(1).link() == null
          && items.get(1).date() == null && items.get(1).description().length() == NewsFeed.MAX_DESCRIPTION
          && items.get(1).description().endsWith("…"));
    } catch (Exception e) {
      failed += check("news parses the feed: " + e, false);
    }
    boolean refused;
    try {
      NewsFeed.parse("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
          + "<rss><channel><item><title>&x;</title></item></channel></rss>");
      refused = false;
    } catch (Exception e) {
      refused = true;
    }
    failed += check("a feed with a DOCTYPE is refused, so no entity reads a file", refused);
    return failed;
  }

  private static String chunk(String delta, String finish) {
    return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"think-model\",\"choices\":"
        + "[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"")
        + "}]}";
  }

  private static void send(OutputStream out, String data) throws IOException {
    out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
