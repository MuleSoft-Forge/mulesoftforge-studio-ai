package com.mulesoftforge.studio.ai.render;

import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Updates;

// The rich transcript page. Content arrives through msfMessage/msfStep/msfAction calls with HTML that
// HtmlRenderer has already escaped; clicks go back to Studio through the msfClick browser function.
public final class HtmlPage {
  public static final String OFFER_BUTTON = "Draft selected changes for review";
  public static final String DONT_SUGGEST = "Don't suggest";

  public record Theme(Palette palette, Palette.Rgb background, Palette.Rgb foreground, String font, int fontPt,
      String mono, int monoPt) {}

  // Starter-card icons as PNG data URIs: Studio's own images, and our round logo for the website card. A null shows
  // the card without one.
  public record Icons(String flow, String project, String munit, String error, String website) {
    public static final Icons NONE = new Icons(null, null, null, null, null);
  }

  private static final String PNG_DATA = "data:image/png;base64,";

  private HtmlPage() {}

  // CSS custom-property values for a live theme switch via msfTheme().
  public static String themeVars(Theme theme) {
    Palette p = theme.palette();
    boolean dark = theme.background().isDark();
    String border = dark ? "rgba(255,255,255,0.10)" : "#d6e2ec";
    String bubble = dark ? "rgba(0,163,224,0.20)" : "#dff1fa";
    // Same atmosphere as https://mulesoftforge.com/studio-ai/: navy #070b14, blue/purple/cyan glows, 48px grid.
    String cards = dark
        ? "--card:rgba(255,255,255,0.05);--card-border:rgba(255,255,255,0.10);--pill:rgba(255,255,255,0.07);"
            + "--pill-hover:rgba(255,255,255,0.12);--focus:#38bdf8;--shadow:rgba(0,0,0,0.30);"
            + "--error-bg:rgba(214,69,69,0.14);--note-bg:rgba(56,189,248,0.10);"
            + "--glow-a:rgba(0,119,182,0.55);--glow-b:rgba(91,63,214,0.55);--glow-c:rgba(0,163,224,0.30);"
            + "--grid-line:rgba(255,255,255,0.04);"
        : "--card:#ffffff;--card-border:#d6e2ec;--pill:#eef4f8;--pill-hover:#e2edf5;--focus:#00a3e0;"
            + "--shadow:rgba(16,42,67,0.06);--error-bg:rgba(214,69,69,0.06);--note-bg:rgba(0,163,224,0.06);"
            + "--glow-a:rgba(0,163,224,0.16);--glow-b:rgba(109,93,252,0.14);--glow-c:rgba(0,119,182,0.08);"
            + "--grid-line:rgba(16,42,67,0.07);";
    // Dark chat always uses the site navy, even when Studio's panel colour drifts.
    String bg = dark ? "#070b14" : theme.background().css();
    String fg = dark ? "#e6ecf5" : theme.foreground().css();
    return cards + """
        --scheme:%s;--bg:%s;--fg:%s;--bubble:%s;--border:%s;--muted:%s;--error:%s;--note:%s;
        --inline:%s;--code:%s;--link:%s;--panel:%s;--tag:%s;--attr-name:%s;--attr-value:%s;
        --comment:%s;--string:%s;--number:%s;--keyword:%s;--key:%s;--expression:%s;--directive:%s;
        --you:%s;--ai:%s;
        """.formatted(dark ? "dark" : "light", bg, fg, bubble, border,
        p.muted().css(), p.error().css(), p.note().css(), p.inlineCodeBackground().css(), p.codeBackground().css(),
        p.link().css(), p.panel().css(), p.tag().css(), p.attrName().css(), p.attrValue().css(), p.comment().css(),
        p.string().css(), p.number().css(), p.keyword().css(), p.key().css(), p.expression().css(),
        p.directive().css(), p.you().css(), p.ai().css()).replace("\n", "");
  }

  public static String page(Theme theme) {
    return page(theme, Icons.NONE);
  }

  public static String page(Theme theme, Icons icons) {
    String css = """
        :root { %s color-scheme: var(--scheme); }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; color: var(--fg);
          font-family: %s, -apple-system, "Segoe UI", sans-serif; font-size: %dpt; line-height: 1.5;
          -webkit-font-smoothing: antialiased; }
        html { background: var(--bg); }
        body { background: transparent; min-height: 100vh; }
        body::before, body::after { content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none; }
        /* Glows match the Studio AI hero: blue left, purple right, soft cyan lower. */
        body::before { background:
          radial-gradient(ellipse 55%% 60%% at 8%% -8%%, var(--glow-a), transparent 70%%),
          radial-gradient(ellipse 50%% 55%% at 94%% 10%%, var(--glow-b), transparent 70%%),
          radial-gradient(ellipse 45%% 45%% at 42%% 88%%, var(--glow-c), transparent 70%%); }
        body::after { background-image: linear-gradient(var(--grid-line) 1px, transparent 1px),
          linear-gradient(90deg, var(--grid-line) 1px, transparent 1px); background-size: 48px 48px;
          -webkit-mask-image: radial-gradient(ellipse 80%% 60%% at 50%% 20%%, #000 30%%, transparent 75%%);
          mask-image: radial-gradient(ellipse 80%% 60%% at 50%% 20%%, #000 30%%, transparent 75%%); }
        ::-webkit-scrollbar { width: 8px; height: 8px; }
        ::-webkit-scrollbar-thumb { background: var(--card-border); border-radius: 999px; }
        ::-webkit-scrollbar-track { background: transparent; }
        #sticky { position: fixed; top: 0; left: 0; right: 0; z-index: 5; display: none; padding: 6px 12px; margin: 0;
          background: var(--card); border-bottom: 1px solid var(--card-border); color: var(--muted);
          font-size: 0.82em; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        #sticky.show { display: block; }
        #quote-btn { position: absolute; display: none; z-index: 6; padding: 4px 10px; border-radius: 999px;
          border: 1px solid var(--card-border); background: var(--card); color: var(--link); font-size: 0.78em;
          box-shadow: 0 2px 8px var(--shadow); cursor: pointer; }
        #quote-btn:hover { border-color: var(--focus); }
        #starters { display: none; max-width: 640px; margin: 18vh auto 0; padding: 12px; text-align: center; }
        #starters.show { display: block; }
        #starters .hint { color: var(--muted); margin-bottom: 14px; font-size: 1.05em; }
        #starters .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
        #starters .card { text-align: left; padding: 10px 12px; border: 1px solid var(--card-border);
          border-radius: 12px; background: var(--card); cursor: pointer; color: var(--fg); }
        #starters .card:hover { border-color: var(--focus); background: var(--pill); }
        #starters .card b { display: block; margin-bottom: 2px; }
        #starters .card span { color: var(--muted); font-size: 0.85em; }
        #starters .card.icon { display: flex; align-items: center; gap: 10px; }
        #starters .card .ico { width: 24px; height: 24px; flex: none; object-fit: contain; }
        #starters .help.icon { justify-content: center; text-align: left; }
        #starters .help { width: calc(50%% - 4px); box-sizing: border-box; margin: 8px auto 0; text-align: center; }
        #log { padding: 12px 12px 18px; max-width: 900px; margin: 0 auto; }
        @keyframes rise { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
        .msg, .offer, .ask, .actions, .raw, .todos { animation: rise .18s ease-out; }
        .msg { margin: 14px 0 6px; display: flex; flex-direction: column; }
        .who { font-weight: 600; font-size: 0.8em; margin: 0 0 4px 2px; display: flex; align-items: center; gap: 6px;
          letter-spacing: 0.01em; }
        .ai .who::before { content: ""; width: 16px; height: 16px; border-radius: 50%%; flex: none;
          background: linear-gradient(135deg, #00a3e0, #6d5dfc); }
        .you { align-items: flex-end; }
        .you .who { margin-right: 4px; }
        .you .body { background: var(--bubble); border-radius: 14px 14px 4px 14px; padding: 8px 12px;
          white-space: normal; max-width: 88%%; }
        .ctx { color: var(--muted); font-size: 0.8em; margin: 4px 4px 0; }
        .error .body, .note .body { border-radius: 10px; padding: 8px 12px; }
        .error .body { font-family: %s, monospace; font-size: %dpt; white-space: pre-wrap;
          background: var(--error-bg); border-left: 3px solid var(--error); }
        .note .body { background: var(--note-bg); border-left: 3px solid var(--note); }
        .steps { color: var(--muted); font-size: 0.85em; margin: 6px 0 6px 10px; padding-left: 12px;
          border-left: 2px solid var(--card-border); }
        .steps summary { cursor: pointer; list-style: none; }
        .steps summary::-webkit-details-marker { display: none; }
        .steps summary::before { content: "▸ "; }
        .steps[open] summary::before { content: "▾ "; }
        .step { position: relative; padding: 1px 0; }
        .step::before { content: ""; position: absolute; left: -19px; top: 0.55em; width: 8px; height: 8px;
          border-radius: 50%%; background: var(--bg); border: 2px solid var(--card-border); }
        .steps > .step:last-child::before { border-color: var(--focus); }
        .live .preview { white-space: pre-wrap; color: var(--fg); font-size: 1.15em; max-height: 16em;
          overflow-y: auto; margin-top: 2px; }
        .live.thinking .preview { color: var(--muted); font-style: italic; font-size: 1em; }
        /* A light band sweeps across the status while the model works, as in Cline and Codex. */
        @keyframes shimmer { from { background-position: 100%% 0; } to { background-position: -100%% 0; } }
        .live .status { color: transparent; background: linear-gradient(90deg, var(--muted) 0%%, var(--muted) 40%%,
          var(--fg) 50%%, var(--muted) 60%%, var(--muted) 100%%); background-size: 200%% 100%%;
          -webkit-background-clip: text; background-clip: text; animation: shimmer 2.2s linear infinite; }
        @media (prefers-reduced-motion: reduce) {
          .live .status { animation: none; background: none; color: var(--muted); }
        }
        .thought .text { white-space: pre-wrap; font-style: italic; margin: 4px 0 2px; max-height: 18em;
          overflow-y: auto; }
        .h1 { font-size: 1.2em; font-weight: 650; }
        .h2 { font-size: 1.05em; font-weight: 650; }
        .quote { color: var(--muted); font-style: italic; border-left: 3px solid var(--card-border);
          padding-left: 8px; display: inline-block; }
        code.inline { font-family: %s, monospace; font-size: %dpt; background: var(--inline); border-radius: 4px;
          padding: 1px 5px; }
        .block { border: 1px solid var(--card-border); border-radius: 10px; margin: 8px 0; overflow: hidden; }
        .bar { display: flex; gap: 6px; align-items: center; padding: 4px 6px 4px 10px; font-size: 0.78em;
          background: var(--inline); border-bottom: 1px solid var(--card-border); }
        .bar a { padding: 1px 8px; border-radius: 999px; }
        .bar a:hover { background: var(--pill-hover); text-decoration: none; }
        .lang { flex: 1; color: var(--muted); text-transform: uppercase; letter-spacing: 0.06em; font-weight: 600; }
        pre { margin: 0; padding: 10px 12px; overflow-x: auto; background: var(--code); font-family: %s, monospace;
          font-size: %dpt; line-height: 1.45; }
        a { color: var(--link); text-decoration: none; cursor: pointer; }
        a:hover { text-decoration: underline; }
        .actions { margin: 8px 0; }
        .button { display: inline-block; border: 1px solid var(--card-border); border-radius: 999px;
          padding: 4px 14px; color: var(--link); background: var(--card); font-weight: 500;
          box-shadow: 0 1px 2px var(--shadow); transition: background .12s, border-color .12s; }
        .button:hover { text-decoration: none; border-color: var(--focus); background: var(--pill); }
        table { border-collapse: separate; border-spacing: 0; margin: 8px 0; font-size: 0.95em;
          border: 1px solid var(--card-border); border-radius: 8px; overflow: hidden; }
        th, td { border-bottom: 1px solid var(--card-border); padding: 5px 10px; text-align: left; vertical-align: top; }
        tr:last-child td { border-bottom: 0; }
        th { background: var(--inline); font-weight: 600; }
        .t-tag, .t-attr-name, .t-attr-value, .t-comment, .t-string, .t-number, .t-keyword, .t-key,
          .t-expression, .t-directive { font-family: %s, monospace; }
        .t-tag { color: var(--tag); } .t-attr-name { color: var(--attr-name); } .t-attr-value { color: var(--attr-value); }
        .t-comment { color: var(--comment); font-style: italic; } .t-string { color: var(--string); } .t-number { color: var(--number); }
        .t-keyword { color: var(--keyword); font-weight: 600; } .t-key { color: var(--key); } .t-expression { color: var(--expression); }
        .t-directive { color: var(--directive); }
        .you .who { color: var(--you); } .ai .who { color: var(--ai); } .error .who { color: var(--error); } .note .who { color: var(--note); }
        .ai .body { background: var(--card); border: 1px solid var(--card-border); border-radius: 4px 14px 14px 14px;
          padding: 10px 14px; box-shadow: 0 1px 3px var(--shadow); }
        .offer { margin: 8px 0; padding: 10px 14px; background: var(--card); border: 1px solid var(--card-border);
          border-left: 3px solid var(--focus); border-radius: 12px; box-shadow: 0 1px 3px var(--shadow); }
        .offer .title { font-weight: 600; font-size: 0.9em; margin-bottom: 6px; }
        .offer.suggested { border-left-color: var(--card-border); }
        .offer.suggested .title { cursor: pointer; font-weight: normal; color: var(--muted); }
        .offer.suggested:not([open]) .title { margin-bottom: 0; }
        .offer label { display: flex; gap: 8px; align-items: flex-start; margin: 4px 0; padding: 3px 6px;
          border-radius: 6px; cursor: pointer; }
        .offer label .text { flex: 1 1 auto; min-width: 0; }
        .offer .ignore { flex: none; color: var(--muted); font-size: 0.85em; cursor: pointer;
          text-decoration: none; white-space: nowrap; margin-top: 0.15em; }
        .offer .ignore:hover { color: var(--link); text-decoration: underline; }
        .offer label.ignored { opacity: 0.45; }
        .offer label.ignored .ignore { pointer-events: none; }
        .offer label:hover { background: var(--pill); }
        .offer input { accent-color: var(--focus); margin-top: 0.3em; flex: none; }
        .offer .button { margin-top: 8px; color: #fff; border: 0; background: linear-gradient(135deg, #00a3e0, #6d5dfc); }
        .offer .button:hover { filter: brightness(1.08); }
        .ask { margin: 8px 0; padding: 10px 14px; background: var(--card); border: 1px solid var(--card-border);
          border-left: 3px solid var(--focus); border-radius: 12px; box-shadow: 0 1px 3px var(--shadow); }
        .ask .title { font-weight: 600; font-size: 0.95em; margin-bottom: 8px; }
        .ask .choice { display: block; width: 100%%; text-align: left; margin: 4px 0; padding: 8px 12px;
          border: 1px solid var(--card-border); border-radius: 10px; background: var(--pill); color: var(--fg);
          cursor: pointer; font: inherit; }
        .ask .choice:hover:not(:disabled) { border-color: var(--focus); background: var(--pill-hover); }
        .ask .choice:disabled { opacity: 0.55; cursor: default; }
        .ask .choice.picked { border-color: var(--focus); background: var(--bubble); }
        .ask .hint { color: var(--muted); font-size: 0.78em; margin-top: 6px; }
        .todos { margin: 6px 0; padding: 6px 10px; background: var(--card); border: 1px solid var(--card-border);
          border-radius: 10px; font-size: 0.88em; }
        .todos summary { cursor: pointer; list-style: none; display: flex; gap: 8px; align-items: center; }
        .todos summary::-webkit-details-marker { display: none; }
        .todos .count { color: var(--muted); margin-left: auto; font-size: 0.85em; }
        .todos ul { margin: 6px 0 0; padding: 0 0 0 4px; list-style: none; max-height: 12em; overflow: auto; }
        .todos li { display: flex; gap: 6px; padding: 2px 0; }
        .todos li.in_progress { color: var(--focus); font-weight: 600; }
        .todos li.completed { color: var(--muted); text-decoration: line-through; }
        .msg-actions { display: flex; gap: 8px; margin: 2px 0 8px 4px; opacity: 0.55; }
        .msg-actions:hover { opacity: 1; }
        .msg-actions a { font-size: 0.78em; color: var(--muted); }
        .msg-actions a:hover { color: var(--link); }
        .raw { margin: 4px 0 8px; font-size: 0.82em; color: var(--muted); }
        .raw.hidden { display: none; }
        .raw summary { cursor: pointer; }
        .raw .open { margin-left: 10px; }
        .raw details { margin: 3px 0 3px 14px; }
        .raw pre { white-space: pre-wrap; word-break: break-all; max-height: 420px; overflow: auto;
          border: 1px solid var(--card-border); border-radius: 8px; color: var(--fg); }
        """.formatted(themeVars(theme), quote(theme.font()), theme.fontPt(), quote(theme.mono()), theme.monoPt(),
        quote(theme.mono()), theme.monoPt(), quote(theme.mono()), theme.monoPt(), quote(theme.mono()));
    return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:">
        <style>%s</style></head>
        <body>
        <div id="sticky"></div>
        <div id="starters">
          <div class="hint">Ask about this Mule project, or pick a start:</div>
          <div class="grid">
            %s
            %s
            %s
            %s
          </div>
          %s
        </div>
        <div id="log"></div>
        <button id="quote-btn" type="button">Quote</button>
        <script>
        var log = document.getElementById('log');
        var sticky = document.getElementById('sticky');
        var starters = document.getElementById('starters');
        var quoteBtn = document.getElementById('quote-btn');
        var steps = null;
        var lastYou = null;
        var lastYouText = '';
        var quoteText = '';
        starters.classList.add('show');
        // Follows new output until the user scrolls up, and again once they scroll back to the end or send, as
        // Continue's useAutoScroll and Cline's useScrollBehavior do.
        var following = true;
        var lastY = 0;
        function nearBottom() {
          return window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
        }
        function keepAtEnd() { if (following) { window.scrollTo(0, document.body.scrollHeight); } }
        window.addEventListener('scroll', function () {
          if (nearBottom()) { following = true; }
          else if (window.scrollY < lastY) { following = false; }
          lastY = window.scrollY;
        }, { passive: true });
        window.addEventListener('wheel', function (e) {
          if (e.deltaY < 0) { following = false; }
        }, { passive: true });
        function dropLive() {
          var live = document.getElementById('live');
          if (live) { live.remove(); }
        }
        function hideStarters() { starters.classList.remove('show'); }
        function updateSticky() {
          if (!lastYou || !lastYouText) { sticky.classList.remove('show'); return; }
          var rect = lastYou.getBoundingClientRect();
          if (rect.bottom < 8) {
            sticky.textContent = 'You: ' + lastYouText;
            sticky.classList.add('show');
          } else {
            sticky.classList.remove('show');
          }
        }
        window.addEventListener('scroll', updateSticky, { passive: true });
        sticky.addEventListener('click', function () {
          if (lastYou) { lastYou.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
        });
        document.addEventListener('mouseup', function () {
          var sel = window.getSelection();
          var text = sel && !sel.isCollapsed ? String(sel).trim() : '';
          if (!text || text.length < 2 || text.length > 4000) {
            quoteBtn.style.display = 'none';
            return;
          }
          var range = sel.getRangeAt(0);
          var rect = range.getBoundingClientRect();
          quoteText = text;
          quoteBtn.style.display = 'block';
          quoteBtn.style.top = (window.scrollY + rect.top - 28) + 'px';
          quoteBtn.style.left = Math.min(window.scrollX + rect.left, window.scrollX + window.innerWidth - 80) + 'px';
        });
        quoteBtn.addEventListener('click', function (e) {
          e.preventDefault();
          quoteBtn.style.display = 'none';
          if (quoteText) { msfClick('quote:' + quoteText); }
        });
        function msfLive(status, preview) {
          if (status === null) { dropLive(); return; }
          var live = document.getElementById('live');
          if (!live) {
            if (!steps) {
              steps = document.createElement('div');
              steps.className = 'steps';
              log.appendChild(steps);
            }
            live = document.createElement('div');
            live.id = 'live';
            live.className = 'step live';
            live.innerHTML = '<div class="status"></div><div class="preview"></div>';
            steps.appendChild(live);
          }
          live.querySelector('.status').textContent = status;
          live.classList.toggle('thinking', status.indexOf(%s) === 0);
          var text = live.querySelector('.preview');
          text.textContent = preview;
          text.scrollTop = text.scrollHeight;
          keepAtEnd();
        }
        function closeSteps() {
          dropLive();
          if (steps && steps.children.length >= 3) {
            var details = document.createElement('details');
            details.className = 'steps';
            var summary = document.createElement('summary');
            summary.textContent = steps.children.length + ' steps';
            details.appendChild(summary);
            while (steps.firstChild) { details.appendChild(steps.firstChild); }
            steps.replaceWith(details);
          }
          steps = null;
        }
        function msfMessage(kind, who, html, ctx) {
          closeSteps();
          hideStarters();
          var div = document.createElement('div');
          div.className = 'msg ' + kind;
          div.innerHTML = '<div class="who"></div><div class="body"></div>'
              + (ctx ? '<div class="ctx"></div>' : '');
          div.querySelector('.who').textContent = who;
          div.querySelector('.body').innerHTML = html;
          if (ctx) { div.querySelector('.ctx').textContent = ctx; }
          log.appendChild(div);
          if (kind === 'you') {
            following = true;
            lastYou = div;
            var plain = div.querySelector('.body').textContent || '';
            lastYouText = plain.length > 120 ? plain.substring(0, 117) + '…' : plain;
            updateSticky();
          }
          if (kind === 'ai') {
            var actions = document.createElement('div');
            actions.className = 'msg-actions';
            [['Retry', 'cmd:retry'], ['Edit last message', 'cmd:edit-last'],
             ['👍', 'cmd:feedback:up', 'Liked it? Share it on LinkedIn'],
             ['👎', 'cmd:feedback:down', 'Missed? Tell us what went wrong']].forEach(function (item) {
              var a = document.createElement('a');
              a.setAttribute('data-a', item[1]);
              if (item[2]) { a.title = item[2]; }
              a.textContent = item[0];
              actions.appendChild(a);
            });
            log.appendChild(actions);
            // The answer arrives whole, so a long one opens at its first line, and what follows it (the raw
            // log) does not pull the view past it. Scrolling to the end follows again.
            if (following && div.offsetHeight > window.innerHeight * 0.6) {
              window.scrollTo(0, div.getBoundingClientRect().top + window.scrollY - 8);
              following = false;
              return;
            }
          }
          keepAtEnd();
        }
        function msfStep(text) {
          dropLive();
          if (!steps) {
            steps = document.createElement('div');
            steps.className = 'steps';
            log.appendChild(steps);
          }
          var line = document.createElement('div');
          line.className = 'step';
          line.textContent = text;
          steps.appendChild(line);
          keepAtEnd();
        }
        function msfThought(summary, text) {
          if (!steps) {
            steps = document.createElement('div');
            steps.className = 'steps';
            log.appendChild(steps);
          }
          var block = document.createElement(text ? 'details' : 'div');
          block.className = 'step thought';
          var title = document.createElement(text ? 'summary' : 'span');
          title.textContent = summary;
          block.appendChild(title);
          if (text) {
            var body = document.createElement('div');
            body.className = 'text';
            body.textContent = text;
            block.appendChild(body);
          }
          var live = document.getElementById('live');
          if (live && live.parentNode === steps) { steps.insertBefore(block, live); }
          else { steps.appendChild(block); }
          keepAtEnd();
        }
        function msfAction(label, id) {
          closeSteps();
          var div = document.createElement('div');
          div.className = 'actions';
          var a = document.createElement('a');
          a.className = 'button';
          a.setAttribute('data-a', 'cmd:' + id);
          a.textContent = label;
          div.appendChild(a);
          log.appendChild(div);
          keepAtEnd();
        }
        function msfOffer(id, items, label, suggested) {
          closeSteps();
          var div = document.createElement(suggested ? 'details' : 'div');
          div.className = 'offer' + (suggested ? ' suggested' : '');
          div.setAttribute('data-offer-id', id);
          var title = document.createElement(suggested ? 'summary' : 'div');
          title.className = 'title';
          title.textContent = suggested
              ? items.length + (items.length === 1 ? ' possible fix' : ' possible fixes') + ' (Agent mode can draft them for review)'
              : 'Changes it can make';
          div.appendChild(title);
          items.forEach(function (text, i) {
            var row = document.createElement('label');
            row.setAttribute('data-offer-row', String(i));
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = !suggested;
            box.value = String(i);
            row.appendChild(box);
            var body = document.createElement('span');
            body.className = 'text';
            body.textContent = text;
            row.appendChild(body);
            var ignore = document.createElement('a');
            ignore.className = 'ignore';
            ignore.href = '#';
            ignore.setAttribute('data-ignore', id + ':' + i);
            ignore.textContent = "Don't suggest";
            row.appendChild(ignore);
            div.appendChild(row);
          });
          var a = document.createElement('a');
          a.className = 'button';
          a.setAttribute('data-offer', id);
          a.textContent = label;
          div.appendChild(a);
          log.appendChild(div);
          keepAtEnd();
        }
        function msfIgnoreOffer(id, index) {
          var root = document.querySelector('[data-offer-id=\"' + id + '\"]');
          if (!root) { return; }
          var row = root.querySelector('[data-offer-row=\"' + index + '\"]');
          if (row) { row.classList.add('ignored'); }
        }
        function msfAsk(id, question, choices) {
          closeSteps();
          hideStarters();
          var div = document.createElement('div');
          div.className = 'ask';
          div.setAttribute('data-ask', id);
          var title = document.createElement('div');
          title.className = 'title';
          title.textContent = question;
          div.appendChild(title);
          choices.forEach(function (text, i) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'choice';
            btn.setAttribute('data-ask-choice', id + ':' + i);
            btn.textContent = text;
            div.appendChild(btn);
          });
          var hint = document.createElement('div');
          hint.className = 'hint';
          hint.textContent = 'Click an answer to send it. Shift-click to edit it first.';
          div.appendChild(hint);
          log.appendChild(div);
          keepAtEnd();
        }
        function msfTodos(items) {
          var existing = document.getElementById('todos');
          if (!items || !items.length) {
            if (existing) { existing.remove(); }
            return;
          }
          var done = 0;
          var current = '';
          items.forEach(function (t) {
            if (t.status === 'completed') { done++; }
            else if (!current && t.status === 'in_progress') { current = t.content; }
            else if (!current && t.status === 'pending') { current = t.content; }
          });
          var details = existing || document.createElement('details');
          details.id = 'todos';
          details.className = 'todos';
          details.innerHTML = '';
          var summary = document.createElement('summary');
          var label = document.createElement('span');
          label.textContent = current || (done === items.length ? 'All tasks done' : 'Tasks');
          var count = document.createElement('span');
          count.className = 'count';
          count.textContent = done + '/' + items.length;
          summary.appendChild(label);
          summary.appendChild(count);
          details.appendChild(summary);
          var ul = document.createElement('ul');
          items.forEach(function (t) {
            var li = document.createElement('li');
            li.className = t.status;
            var mark = t.status === 'completed' ? '✓' : (t.status === 'in_progress' ? '→' : '○');
            li.textContent = mark + ' ' + t.content;
            ul.appendChild(li);
          });
          details.appendChild(ul);
          if (!existing) {
            if (steps && steps.parentNode) { log.insertBefore(details, steps); }
            else { log.appendChild(details); }
          }
          keepAtEnd();
        }
        var showRaw = true;
        function details(title) {
          var d = document.createElement('details');
          var s = document.createElement('summary');
          s.textContent = title;
          d.appendChild(s);
          return d;
        }
        function msfRaw(title, calls, open) {
          closeSteps();
          var block = details(title);
          block.className = 'raw' + (showRaw ? '' : ' hidden');
          if (open) {
            var a = document.createElement('a');
            a.className = 'open';
            a.setAttribute('data-a', 'cmd:' + open);
            a.textContent = 'Open raw log';
            block.querySelector('summary').appendChild(a);
          }
          calls.forEach(function (call) {
            var one = details(call.heading);
            [['Request', call.request], ['Response', call.response]].forEach(function (part) {
              var d = details(part[0]);
              var pre = document.createElement('pre');
              pre.textContent = part[1] || '(empty)';
              d.appendChild(pre);
              one.appendChild(d);
            });
            block.appendChild(one);
          });
          log.appendChild(block);
          keepAtEnd();
        }
        function msfShowRaw(on) {
          showRaw = on;
          document.querySelectorAll('.raw').forEach(function (b) { b.classList.toggle('hidden', !on); });
        }
        function msfClear() {
          log.innerHTML = '';
          steps = null;
          lastYou = null;
          lastYouText = '';
          following = true;
          sticky.classList.remove('show');
          starters.classList.add('show');
          var todos = document.getElementById('todos');
          if (todos) { todos.remove(); }
          // WebKit keeps the old offset and scrollbar size after the content shrinks until the page scrolls.
          window.scrollTo(0, 0);
          lastY = 0;
        }
        function msfTheme(cssText) {
          var root = document.documentElement;
          cssText.split(';').forEach(function (part) {
            var i = part.indexOf(':');
            if (i > 0) { root.style.setProperty(part.substring(0, i).trim(), part.substring(i + 1).trim()); }
          });
          if (root.style.getPropertyValue('--scheme')) {
            root.style.colorScheme = root.style.getPropertyValue('--scheme');
          }
        }
        document.addEventListener('click', function (e) {
          var starter = e.target.closest('[data-starter]');
          if (starter) {
            e.preventDefault();
            msfClick('fill:' + starter.getAttribute('data-starter'));
            return;
          }
          var choice = e.target.closest('[data-ask-choice]');
          if (choice) {
            e.preventDefault();
            if (choice.disabled) { return; }
            var value = choice.getAttribute('data-ask-choice');
            var ask = choice.closest('.ask');
            if (ask) {
              ask.querySelectorAll('.choice').forEach(function (b) { b.disabled = true; });
              choice.classList.add('picked');
            }
            if (e.shiftKey) {
              msfClick('fill:' + choice.textContent);
            } else {
              msfClick('cmd:ask:' + value);
            }
            return;
          }
          var ignore = e.target.closest('[data-ignore]');
          if (ignore) {
            e.preventDefault();
            e.stopPropagation();
            msfClick('cmd:ignore:' + ignore.getAttribute('data-ignore'));
            return;
          }
          var offer = e.target.closest('[data-offer]');
          if (offer) {
            e.preventDefault();
            var ticked = [];
            offer.parentNode.querySelectorAll('input:checked').forEach(function (box) { ticked.push(box.value); });
            msfClick('cmd:offer:' + offer.getAttribute('data-offer') + ':' + ticked.join(','));
            return;
          }
          var a = e.target.closest('[data-a]');
          if (a) { e.preventDefault(); msfClick(a.getAttribute('data-a')); }
        });
        </script></body></html>
        """.formatted(css,
        starter("Explain how the main flow in this project works.", "Explain this flow",
            "Summarise the message processors", icons.flow()),
        starter("Review this project and suggest improvements.", "Review the project", "Checklist of possible fixes",
            icons.project()),
        starter("Create MUnit tests for the main flow.", "Add MUnit tests", "Cover the happy path and errors",
            icons.munit()),
        starter("Find the cause of the last run failure.", "Diagnose last run", "Use console and problems",
            icons.error()),
        card("help", "data-a=\"url:" + Updates.PAGE + "\"", "Visit the website", "Help and docs", icons.website()),
        jsString(ChatSession.WAITING_FOR_DATA));
  }

  private static String starter(String prompt, String title, String hint, String icon) {
    return card("", "data-starter=\"" + prompt + "\"", title, hint, icon);
  }

  private static String card(String kind, String data, String title, String hint, String icon) {
    String text = "<b>" + title + "</b><span>" + hint + "</span>";
    String classes = ("card " + kind).trim();
    if (!isPngData(icon)) {
      return "<div class=\"" + classes + "\" " + data + ">" + text + "</div>";
    }
    return "<div class=\"" + classes + " icon\" " + data + "><img class=\"ico\" alt=\"\" src=\"" + icon
        + "\"><div>" + text + "</div></div>";
  }

  // Only base64 PNG data goes into the page, so an icon can never carry markup or a remote URL.
  static boolean isPngData(String uri) {
    return uri != null && uri.startsWith(PNG_DATA) && uri.length() > PNG_DATA.length()
        && uri.substring(PNG_DATA.length()).matches("[A-Za-z0-9+/]+={0,2}");
  }

  private static String jsString(String text) {
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private static String quote(String family) {
    return "\"" + family.replace("\"", "").replace("\\", "").replace("<", "") + "\"";
  }
}
