package com.mulesoftforge.studio.ai.render;

// The message box as a page in Eclipse's browser: a card with the text area, attachment chips, and one row of
// pickers and buttons. State arrives through msf* calls; everything the user does goes back to Studio through the
// msfComposer(kind, value) browser function. Labels and model ids are set with textContent, never as HTML.
public final class ComposerPage {
  public static final String SEND = "send";
  public static final String ENTER = "enter";
  public static final String TEXT = "text";
  public static final String MODE = "mode";
  public static final String MODEL_MENU = "models";
  public static final String CONTEXT = "context";
  public static final String INCLUDE = "include";
  public static final String NEW_CHAT = "new";
  public static final String MIC = "mic";
  public static final String REMOVE = "remove";
  public static final String HEIGHT = "height";

  private ComposerPage() {}

  // The same colours as the transcript, so the card and the messages above it match.
  public static String themeVars(HtmlPage.Theme theme) {
    return HtmlPage.themeVars(theme);
  }

  public static String page(HtmlPage.Theme theme, String placeholder, String contextHelp, String micHelp) {
    String css = """
        :root { %s color-scheme: var(--scheme); }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; background: var(--bg); color: var(--fg); overflow: hidden;
          font-family: %s, -apple-system, "Segoe UI", sans-serif; font-size: %dpt; }
        #wrap { padding: 6px 10px 10px; }
        #card { background: var(--card); border: 1px solid var(--card-border); border-radius: 12px;
          padding: 8px 10px 8px; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06); transition: border-color .15s; }
        #card:focus-within { border-color: var(--focus); box-shadow: 0 0 0 3px rgba(0, 163, 224, 0.15); }
        #chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
        #chips:empty { display: none; }
        .chip { display: inline-flex; align-items: center; gap: 4px; max-width: 100%%; min-width: 0; padding: 2px 4px 2px 9px;
          border-radius: 999px; background: var(--pill); font-size: 0.85em; }
        .chip .label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .chip button { border: 0; background: none; color: var(--muted); cursor: pointer; padding: 0 4px;
          font-size: 1.05em; line-height: 1; }
        .chip button:hover { color: var(--fg); }
        .clear { font-size: 0.85em; color: var(--muted); align-self: center; cursor: pointer; }
        .clear:hover { color: var(--fg); }
        textarea { width: 100%%; border: 0; outline: none; resize: none; background: transparent; color: var(--fg);
          font: inherit; line-height: 1.45; min-height: 3.2em; max-height: 12em; overflow-y: auto; padding: 2px 0; }
        textarea::placeholder { color: var(--muted); }
        #bar, .group { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-width: 0; }
        #bar { margin-top: 6px; }
        #bar button, #bar select { white-space: nowrap; flex: none; }
        .end { margin-left: auto; flex-wrap: nowrap; }
        .pill { appearance: none; -webkit-appearance: none; border: 0; border-radius: 999px; background: var(--pill);
          color: var(--fg); font: inherit; font-size: 0.9em; height: 26px; padding: 0 10px; cursor: pointer; }
        .pill:hover { background: var(--pill-hover); }
        .pill:focus { outline: 2px solid var(--focus); outline-offset: 1px; }
        select.pill, #model { padding-right: 24px; background-image: linear-gradient(45deg, transparent 50%%, var(--muted) 50%%),
          linear-gradient(135deg, var(--muted) 50%%, transparent 50%%); background-position: right 12px center,
          right 8px center; background-size: 4px 4px; background-repeat: no-repeat; }
        #bar #model { flex: 0 1 auto; max-width: 220px; min-width: 90px; overflow: hidden; text-overflow: ellipsis;
          text-align: left; }
        .pill:disabled { opacity: 0.55; cursor: default; }
        .ghost { border: 0; background: none; color: var(--muted); font: inherit; font-size: 0.9em; height: 26px;
          padding: 0 8px; border-radius: 999px; cursor: pointer; }
        .ghost:hover { background: var(--pill); color: var(--fg); }
        .toggle.on { color: var(--link); }
        .toggle::before { content: "☐ "; }
        .toggle.on::before { content: "☑ "; }
        .icon { border: 0; background: none; color: var(--muted); width: 30px; height: 30px; border-radius: 999px;
          display: inline-flex; align-items: center; justify-content: center; cursor: pointer; }
        .icon:hover { background: var(--pill); color: var(--fg); }
        #send { border: 0; color: #fff; height: 30px; min-width: 30px; padding: 0 14px; border-radius: 999px;
          font: inherit; font-size: 0.9em; font-weight: 600; cursor: pointer;
          background: linear-gradient(135deg, #00a3e0, #6d5dfc); display: inline-flex; align-items: center; gap: 6px; }
        #send:hover { filter: brightness(1.08); }
        #send.stop { background: #d64545; }
        #send:disabled { opacity: 0.6; cursor: default; filter: none; }
        svg { display: block; }
        """.formatted(themeVars(theme), quote(theme.font()), theme.fontPt());
    String micIcon = """
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/>
          <path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/></svg>""";
    String sendIcon = """
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"
          stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5"/><path d="M5 12l7-7 7 7"/></svg>""";
    return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>%s</style></head>
        <body><div id="wrap"><div id="card">
          <div id="chips"></div>
          <textarea id="input" rows="2" placeholder="%s"></textarea>
          <div id="bar">
            <div class="group">
              <select id="mode" class="pill"></select>
              <button id="model" class="pill"></button>
              <button id="ctx" class="ghost" title="%s">+ Context</button>
              <button id="file" class="ghost toggle on" role="checkbox" aria-checked="true"
                title="Send the file open in the editor with the message. Click to turn off.">Include open file</button>
            </div>
            <div class="group end">
              <button id="new" class="ghost" title="Start a new chat">New chat</button>
              <button id="mic" class="icon" title="%s" aria-label="Dictate">%s</button>
              <button id="send" title="Send (Enter). Shift+Enter adds a line.">%s<span id="sendLabel">Send</span></button>
            </div>
          </div>
        </div></div>
        <script>
        var input = document.getElementById('input');
        var mode = document.getElementById('mode');
        var model = document.getElementById('model');
        var chips = document.getElementById('chips');
        var send = document.getElementById('send');
        var sendLabel = document.getElementById('sendLabel');
        var file = document.getElementById('file');
        var lastHeight = 0;
        function tell(kind, value) { msfComposer(kind, value == null ? '' : String(value)); }
        function fit() {
          input.style.height = 'auto';
          input.style.height = input.scrollHeight + 'px';
          var h = document.getElementById('wrap').offsetHeight;
          if (h !== lastHeight) { lastHeight = h; tell('height', h); }
        }
        input.addEventListener('input', function () { tell('text', input.value); fit(); });
        input.addEventListener('keydown', function (e) {
          if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            tell('enter', input.value);
          }
        });
        send.addEventListener('click', function () { tell('send', input.value); });
        mode.addEventListener('change', function () { tell('mode', mode.selectedIndex); });
        function below(e) {
          var r = e.currentTarget.getBoundingClientRect();
          return Math.round(r.left) + ',' + Math.round(r.bottom);
        }
        model.addEventListener('click', function (e) { tell('models', below(e)); });
        document.getElementById('ctx').addEventListener('click', function (e) { tell('context', below(e)); });
        file.addEventListener('click', function () {
          var on = file.classList.toggle('on');
          file.setAttribute('aria-checked', String(on));
          tell('include', on);
        });
        document.getElementById('new').addEventListener('click', function () { tell('new'); });
        document.getElementById('mic').addEventListener('click', function () { tell('mic'); });
        function msfSetText(text) { input.value = text; fit(); input.selectionStart = input.selectionEnd = text.length; }
        function msfFocus() { input.focus(); input.selectionStart = input.selectionEnd = input.value.length; }
        function msfInsert(text) {
          input.focus();
          var s = input.selectionStart, e = input.selectionEnd;
          input.value = input.value.substring(0, s) + text + input.value.substring(e);
          input.selectionStart = input.selectionEnd = s + text.length;
          tell('text', input.value);
          fit();
        }
        function msfModes(labels, selected, help) {
          mode.innerHTML = '';
          labels.forEach(function (label) {
            var o = document.createElement('option');
            o.textContent = label;
            mode.appendChild(o);
          });
          mode.selectedIndex = selected;
          mode.title = help;
        }
        function msfSelectMode(i) { mode.selectedIndex = i; }
        function msfModels(items, current) { model.textContent = current; }
        function msfModelText(text) { model.textContent = text; }
        function msfModelHelp(help) { model.title = help; }
        function msfAttached(labels, help) {
          chips.innerHTML = '';
          labels.forEach(function (label, i) {
            var chip = document.createElement('span');
            chip.className = 'chip';
            chip.title = label;
            var text = document.createElement('span');
            text.className = 'label';
            text.textContent = label;
            var x = document.createElement('button');
            x.textContent = '×';
            x.title = 'Do not send this';
            x.addEventListener('click', function () { tell('remove', i); });
            chip.appendChild(text);
            chip.appendChild(x);
            chips.appendChild(chip);
          });
          if (labels.length > 1) {
            var clear = document.createElement('span');
            clear.className = 'clear';
            clear.textContent = 'Clear all';
            clear.addEventListener('click', function () { tell('remove', -1); });
            chips.appendChild(clear);
          }
          chips.title = help || '';
          fit();
        }
        function msfBusy(busy) {
          send.disabled = false;
          send.classList.toggle('stop', busy);
          sendLabel.textContent = busy ? 'Cancel' : 'Send';
          mode.disabled = busy;
          model.disabled = busy;
        }
        function msfCancelling() { send.disabled = true; sendLabel.textContent = 'Cancelling…'; }
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
        window.addEventListener('resize', fit);
        fit();
        </script></body></html>
        """.formatted(css, attr(placeholder), attr(contextHelp), attr(micHelp), micIcon, sendIcon);
  }

  static String attr(String value) {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String quote(String family) {
    return "\"" + family.replace("\"", "").replace("\\", "").replace("<", "") + "\"";
  }
}
