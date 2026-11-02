package com.mulesoftforge.studio.ai.render;

// Proposed file changes as a card in Eclipse's browser: one row per file with Review, Reject and Accept, and
// Accept all / Reject all when there are several. Rows arrive through msfRows; clicks go back to Studio through
// msfChanges(kind, row index). File names are set with textContent, never as HTML.
public final class ChangesPage {
  public static final String REVIEW = "review";
  public static final String ACCEPT = "accept";
  public static final String REJECT = "reject";
  public static final String ACCEPT_ALL = "accept-all";
  public static final String REJECT_ALL = "reject-all";

  // One proposed file. added and removed are line counts; newFile shows "new file" instead.
  public record Row(String name, String folder, int added, int removed, boolean newFile) {}

  private ChangesPage() {}

  public static String page(HtmlPage.Theme theme) {
    String css = """
        :root { %s color-scheme: var(--scheme); }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; background: var(--bg); color: var(--fg); overflow-x: hidden;
          font-family: %s, -apple-system, "Segoe UI", sans-serif; font-size: %dpt; -webkit-font-smoothing: antialiased; }
        #wrap { padding: 6px 10px 2px; }
        #card { background: var(--card); border: 1px solid var(--card-border); border-left: 3px solid var(--focus);
          border-radius: 12px; box-shadow: 0 1px 3px var(--shadow); overflow: hidden; }
        .head { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 8px 10px 8px 12px; }
        .title { font-weight: 600; }
        .sub { color: var(--muted); font-size: 0.85em; }
        .end { margin-left: auto; display: flex; gap: 6px; }
        .end:empty { display: none; }
        .row { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 6px 10px 6px 12px;
          border-top: 1px solid var(--card-border); }
        .row:hover { background: var(--pill); }
        .main { flex: 1 1 200px; min-width: 0; display: flex; align-items: center; gap: 8px; }
        .icon { width: 16px; height: 16px; flex: none; color: var(--muted); }
        .file { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 6px; overflow: hidden; cursor: pointer; }
        .name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: none;
          max-width: 100%%; }
        .folder { color: var(--muted); font-size: 0.85em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
          flex: 0 100 auto; min-width: 0; }
        .stats { font-family: %s, monospace; font-size: 0.85em; white-space: nowrap; flex: none; }
        .add { color: #1f9d55; } .del { color: #d64545; }
        .badge { font-size: 0.75em; padding: 1px 7px; border-radius: 999px; background: var(--note-bg); color: var(--focus);
          font-weight: 600; flex: none; }
        .actions { display: flex; gap: 4px; flex: none; margin-left: auto; }
        button { font: inherit; font-size: 0.85em; height: 24px; padding: 0 10px; border-radius: 999px; cursor: pointer;
          white-space: nowrap; }
        .ghost { border: 0; background: none; color: var(--muted); }
        .ghost:hover { background: var(--pill-hover); color: var(--fg); }
        .ghost.no:hover { color: #d64545; }
        .go { border: 0; color: #fff; font-weight: 600; background: linear-gradient(135deg, #00a3e0, #6d5dfc); }
        .go:hover { filter: brightness(1.08); }
        .outline { border: 1px solid var(--card-border); background: var(--card); color: var(--fg); }
        .outline:hover { border-color: var(--focus); }
        """.formatted(HtmlPage.themeVars(theme), quote(theme.font()), theme.fontPt(), quote(theme.mono()));
    return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>%s</style></head>
        <body><div id="wrap"><div id="card">
          <div class="head">
            <span class="title" id="title">Proposed changes</span>
            <span class="sub">Nothing is written until you accept</span>
            <span class="end" id="all"></span>
          </div>
          <div id="rows"></div>
        </div></div>
        <script>
        var rows = document.getElementById('rows');
        var all = document.getElementById('all');
        var lastHeight = 0;
        var fileIcon = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
            + ' stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10'
            + 'a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>';
        function tell(kind, value) { msfChanges(kind, value == null ? '' : String(value)); }
        function fit() {
          var h = document.getElementById('wrap').offsetHeight;
          if (h !== lastHeight) { lastHeight = h; tell('height', h); }
        }
        function button(cls, label, kind, i, title) {
          var b = document.createElement('button');
          b.className = cls;
          b.textContent = label;
          if (title) { b.title = title; }
          b.addEventListener('click', function (e) { e.stopPropagation(); tell(kind, i); });
          return b;
        }
        function msfRows(items) {
          rows.innerHTML = '';
          all.innerHTML = '';
          document.getElementById('title').textContent = items.length === 1 ? 'Proposed change'
              : 'Proposed changes (' + items.length + ' files)';
          if (items.length > 1) {
            all.appendChild(button('ghost no', 'Reject all', 'reject-all', -1));
            all.appendChild(button('outline', 'Accept all', 'accept-all', -1));
          }
          items.forEach(function (item, i) {
            var row = document.createElement('div');
            row.className = 'row';
            var main = document.createElement('div');
            main.className = 'main';
            main.innerHTML = fileIcon;
            row.appendChild(main);
            var file = document.createElement('span');
            file.className = 'file';
            file.title = 'Review the diff';
            var name = document.createElement('span');
            name.className = 'name';
            name.textContent = item.name;
            var folder = document.createElement('span');
            folder.className = 'folder';
            folder.textContent = item.folder;
            file.appendChild(name);
            file.appendChild(folder);
            file.addEventListener('click', function () { tell('review', i); });
            main.appendChild(file);
            if (item.newFile) {
              var badge = document.createElement('span');
              badge.className = 'badge';
              badge.textContent = 'new file';
              main.appendChild(badge);
            } else {
              var stats = document.createElement('span');
              stats.className = 'stats';
              stats.innerHTML = '<span class="add"></span> <span class="del"></span>';
              stats.querySelector('.add').textContent = '+' + item.added;
              stats.querySelector('.del').textContent = '\u2212' + item.removed;
              main.appendChild(stats);
            }
            var actions = document.createElement('span');
            actions.className = 'actions';
            actions.appendChild(button('ghost', 'Review', 'review', i, 'Open the diff in a compare editor'));
            actions.appendChild(button('ghost no', 'Reject', 'reject', i, 'Discard this change'));
            actions.appendChild(button('go', 'Accept', 'accept', i, 'Write this change to the file'));
            row.appendChild(actions);
            rows.appendChild(row);
          });
          fit();
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
        window.addEventListener('resize', fit);
        fit();
        </script></body></html>
        """.formatted(css);
  }

  private static String quote(String family) {
    return "\"" + family.replace("\"", "").replace("\\", "").replace("<", "") + "\"";
  }
}
