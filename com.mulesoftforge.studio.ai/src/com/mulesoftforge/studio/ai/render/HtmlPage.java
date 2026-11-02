package com.mulesoftforge.studio.ai.render;

// The rich transcript page. Content arrives through msfMessage/msfStep/msfAction calls with HTML that
// HtmlRenderer has already escaped; clicks go back to Studio through the msfClick browser function.
public final class HtmlPage {
  public static final String OFFER_BUTTON = "Draft selected changes for review";

  public record Theme(Palette palette, Palette.Rgb background, Palette.Rgb foreground, String font, int fontPt,
      String mono, int monoPt) {}

  private HtmlPage() {}

  // CSS custom-property values for a live theme switch via msfTheme().
  public static String themeVars(Theme theme) {
    Palette p = theme.palette();
    boolean dark = theme.background().isDark();
    String border = dark ? "rgba(255,255,255,0.10)" : "#d6e2ec";
    String bubble = dark ? "rgba(0,163,224,0.20)" : "#dff1fa";
    // The product site's look: glass cards over a navy base with blue and purple glows and, in dark, a faint grid.
    String cards = dark
        ? "--card:rgba(255,255,255,0.05);--card-border:rgba(255,255,255,0.10);--pill:rgba(255,255,255,0.07);"
            + "--pill-hover:rgba(255,255,255,0.12);--focus:#38bdf8;--shadow:rgba(0,0,0,0.30);"
            + "--error-bg:rgba(214,69,69,0.14);--note-bg:rgba(56,189,248,0.10);"
            + "--glow-a:rgba(0,119,182,0.38);--glow-b:rgba(91,63,214,0.34);--grid-line:rgba(255,255,255,0.04);"
        : "--card:#ffffff;--card-border:#d6e2ec;--pill:#eef4f8;--pill-hover:#e2edf5;--focus:#00a3e0;"
            + "--shadow:rgba(16,42,67,0.06);--error-bg:rgba(214,69,69,0.06);--note-bg:rgba(0,163,224,0.06);"
            + "--glow-a:rgba(0,163,224,0.14);--glow-b:rgba(109,93,252,0.13);--grid-line:transparent;";
    return cards + """
        --scheme:%s;--bg:%s;--fg:%s;--bubble:%s;--border:%s;--muted:%s;--error:%s;--note:%s;
        --inline:%s;--code:%s;--link:%s;--panel:%s;--tag:%s;--attr-name:%s;--attr-value:%s;
        --comment:%s;--string:%s;--number:%s;--keyword:%s;--key:%s;--expression:%s;--directive:%s;
        --you:%s;--ai:%s;
        """.formatted(dark ? "dark" : "light", theme.background().css(), theme.foreground().css(), bubble, border,
        p.muted().css(), p.error().css(), p.note().css(), p.inlineCodeBackground().css(), p.codeBackground().css(),
        p.link().css(), p.panel().css(), p.tag().css(), p.attrName().css(), p.attrValue().css(), p.comment().css(),
        p.string().css(), p.number().css(), p.keyword().css(), p.key().css(), p.expression().css(),
        p.directive().css(), p.you().css(), p.ai().css()).replace("\n", "");
  }

  public static String page(Theme theme) {
    String css = """
        :root { %s color-scheme: var(--scheme); }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; color: var(--fg);
          font-family: %s, -apple-system, "Segoe UI", sans-serif; font-size: %dpt; line-height: 1.5;
          -webkit-font-smoothing: antialiased; }
        html { background: var(--bg); }
        body { background: transparent; min-height: 100vh; }
        body::before, body::after { content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none; }
        body::before { background:
          radial-gradient(45%% 55%% at 12%% 0%%, var(--glow-a), transparent 70%%),
          radial-gradient(40%% 50%% at 90%% 6%%, var(--glow-b), transparent 70%%); }
        body::after { background-image: linear-gradient(var(--grid-line) 1px, transparent 1px),
          linear-gradient(90deg, var(--grid-line) 1px, transparent 1px); background-size: 48px 48px;
          -webkit-mask-image: radial-gradient(ellipse 80%% 60%% at 50%% 0%%, #000 30%%, transparent 75%%);
          mask-image: radial-gradient(ellipse 80%% 60%% at 50%% 0%%, #000 30%%, transparent 75%%); }
        ::-webkit-scrollbar { width: 8px; height: 8px; }
        ::-webkit-scrollbar-thumb { background: var(--card-border); border-radius: 999px; }
        ::-webkit-scrollbar-track { background: transparent; }
        #log { padding: 12px 12px 18px; max-width: 900px; margin: 0 auto; }
        #log:empty { display: flex; align-items: center; justify-content: center; min-height: 80vh; }
        #log:empty::before { content: "Ask about this Mule project, or ask for a change."; color: var(--muted);
          font-size: 1.05em; padding: 18px 22px; border: 1px dashed var(--card-border); border-radius: 14px; }
        @keyframes rise { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
        .msg, .offer, .actions, .raw { animation: rise .18s ease-out; }
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
        .offer label:hover { background: var(--pill); }
        .offer input { accent-color: var(--focus); margin-top: 0.3em; flex: none; }
        .offer .button { margin-top: 8px; color: #fff; border: 0; background: linear-gradient(135deg, #00a3e0, #6d5dfc); }
        .offer .button:hover { filter: brightness(1.08); }
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
          content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>%s</style></head>
        <body><div id="log"></div>
        <script>
        var log = document.getElementById('log');
        var steps = null;
        function nearBottom() {
          return window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
        }
        function scrollIfNeeded(was) { if (was) { window.scrollTo(0, document.body.scrollHeight); } }
        function closeSteps() {
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
          var was = nearBottom();
          closeSteps();
          var div = document.createElement('div');
          div.className = 'msg ' + kind;
          div.innerHTML = '<div class="who"></div><div class="body"></div>'
              + (ctx ? '<div class="ctx"></div>' : '');
          div.querySelector('.who').textContent = who;
          div.querySelector('.body').innerHTML = html;
          if (ctx) { div.querySelector('.ctx').textContent = ctx; }
          log.appendChild(div);
          scrollIfNeeded(was);
        }
        function msfStep(text) {
          var was = nearBottom();
          if (!steps) {
            steps = document.createElement('div');
            steps.className = 'steps';
            log.appendChild(steps);
          }
          var line = document.createElement('div');
          line.className = 'step';
          line.textContent = text;
          steps.appendChild(line);
          scrollIfNeeded(was);
        }
        function msfAction(label, id) {
          var was = nearBottom();
          closeSteps();
          var div = document.createElement('div');
          div.className = 'actions';
          var a = document.createElement('a');
          a.className = 'button';
          a.setAttribute('data-a', 'cmd:' + id);
          a.textContent = label;
          div.appendChild(a);
          log.appendChild(div);
          scrollIfNeeded(was);
        }
        function msfOffer(id, items, label, suggested) {
          var was = nearBottom();
          closeSteps();
          var div = document.createElement(suggested ? 'details' : 'div');
          div.className = 'offer' + (suggested ? ' suggested' : '');
          var title = document.createElement(suggested ? 'summary' : 'div');
          title.className = 'title';
          title.textContent = suggested
              ? items.length + (items.length === 1 ? ' possible fix' : ' possible fixes') + ' (Agent mode can draft them for review)'
              : 'Changes it can make';
          div.appendChild(title);
          items.forEach(function (text, i) {
            var row = document.createElement('label');
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = !suggested;
            box.value = String(i);
            row.appendChild(box);
            row.appendChild(document.createTextNode(' ' + text));
            div.appendChild(row);
          });
          var a = document.createElement('a');
          a.className = 'button';
          a.setAttribute('data-offer', id);
          a.textContent = label;
          div.appendChild(a);
          log.appendChild(div);
          scrollIfNeeded(was);
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
          var was = nearBottom();
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
          scrollIfNeeded(was);
        }
        function msfShowRaw(on) {
          showRaw = on;
          document.querySelectorAll('.raw').forEach(function (b) { b.classList.toggle('hidden', !on); });
        }
        function msfClear() { log.innerHTML = ''; steps = null; }
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
        """.formatted(css);
  }

  private static String quote(String family) {
    return "\"" + family.replace("\"", "").replace("\\", "").replace("<", "") + "\"";
  }
}
