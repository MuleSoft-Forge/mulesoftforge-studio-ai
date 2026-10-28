package com.mulesoftforge.studio.ai.render;

// The rich transcript page. Content arrives through msfMessage/msfStep/msfAction calls with HTML that
// HtmlRenderer has already escaped; clicks go back to Studio through the msfClick browser function.
public final class HtmlPage {
  public record Theme(Palette palette, Palette.Rgb background, Palette.Rgb foreground, String font, int fontPt,
      String mono, int monoPt) {}

  private HtmlPage() {}

  public static String page(Theme theme) {
    Palette p = theme.palette();
    boolean dark = theme.background().isDark();
    String border = dark ? "#3c3f44" : "#dcdfe4";
    String bubble = dark ? "#2f3338" : "#eef3fb";
    String css = """
        :root { color-scheme: %s; }
        html, body { margin: 0; padding: 0; background: %s; color: %s;
          font-family: %s, -apple-system, "Segoe UI", sans-serif; font-size: %dpt; line-height: 1.45; }
        #log { padding: 8px 10px 16px; }
        .msg { margin: 10px 0 4px; }
        .who { font-weight: 600; font-size: 0.85em; margin-bottom: 2px; }
        .you .body { background: %s; border: 1px solid %s; border-radius: 8px; padding: 6px 9px;
          white-space: normal; }
        .ctx { color: %s; font-size: 0.85em; margin-top: 3px; }
        .error .body { font-family: %s, monospace; font-size: %dpt; white-space: pre-wrap;
          border-left: 3px solid %s; padding-left: 8px; }
        .note .body { border-left: 3px solid %s; padding-left: 8px; }
        .steps { color: %s; font-style: italic; font-size: 0.9em; margin: 3px 0 3px 2px; }
        .steps summary { cursor: pointer; }
        .step::before { content: "· "; }
        .h1 { font-size: 1.25em; font-weight: 600; }
        .h2 { font-size: 1.05em; font-weight: 600; }
        .quote { color: %s; font-style: italic; }
        code.inline { font-family: %s, monospace; font-size: %dpt; background: %s; border-radius: 3px;
          padding: 0 3px; }
        .block { border: 1px solid %s; border-radius: 6px; margin: 6px 0; overflow: hidden; }
        .bar { display: flex; gap: 12px; align-items: center; padding: 3px 8px; font-size: 0.8em;
          background: %s; border-bottom: 1px solid %s; }
        .lang { flex: 1; color: %s; text-transform: uppercase; letter-spacing: 0.04em; }
        pre { margin: 0; padding: 8px; overflow-x: auto; background: %s; font-family: %s, monospace;
          font-size: %dpt; line-height: 1.35; }
        a { color: %s; text-decoration: none; cursor: pointer; }
        a:hover { text-decoration: underline; }
        .actions { margin: 6px 0; }
        .button { display: inline-block; border: 1px solid %s; border-radius: 5px; padding: 2px 10px;
          color: %s; }
        table { border-collapse: collapse; margin: 6px 0; font-size: 0.95em; }
        th, td { border: 1px solid %s; padding: 3px 8px; text-align: left; vertical-align: top; }
        th { background: %s; }
        .t-tag, .t-attr-name, .t-attr-value, .t-comment, .t-string, .t-number, .t-keyword, .t-key,
          .t-expression, .t-directive { font-family: %s, monospace; }
        .t-tag { color: %s; } .t-attr-name { color: %s; } .t-attr-value { color: %s; }
        .t-comment { color: %s; font-style: italic; } .t-string { color: %s; } .t-number { color: %s; }
        .t-keyword { color: %s; font-weight: 600; } .t-key { color: %s; } .t-expression { color: %s; }
        .t-directive { color: %s; }
        .you .who { color: %s; } .ai .who { color: %s; } .error .who { color: %s; } .note .who { color: %s; }
        """.formatted(dark ? "dark" : "light", theme.background().css(), theme.foreground().css(),
        quote(theme.font()), theme.fontPt(),
        bubble, border, p.muted().css(),
        quote(theme.mono()), theme.monoPt(), p.error().css(),
        p.note().css(),
        p.muted().css(),
        p.muted().css(),
        quote(theme.mono()), theme.monoPt(), p.inlineCodeBackground().css(),
        border,
        p.inlineCodeBackground().css(), border,
        p.muted().css(),
        p.codeBackground().css(), quote(theme.mono()), theme.monoPt(),
        p.link().css(),
        border, p.link().css(),
        border, p.codeBackground().css(),
        quote(theme.mono()),
        p.tag().css(), p.attrName().css(), p.attrValue().css(),
        p.comment().css(), p.string().css(), p.number().css(),
        p.keyword().css(), p.key().css(), p.expression().css(),
        p.directive().css(),
        p.you().css(), p.ai().css(), p.error().css(), p.note().css());
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
        function msfClear() { log.innerHTML = ''; steps = null; }
        document.addEventListener('click', function (e) {
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
