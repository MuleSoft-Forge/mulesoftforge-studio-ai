# MuleSoftForge Studio AI (proof of concept)

AI chat inside Anypoint Studio. It uses [LangChain4j](https://docs.langchain4j.dev/) so one plugin can talk to:

- OpenRouter (model `openrouter/auto` lets OpenRouter choose)
- MuleSoft AI Gateway LLM Proxy (Flex Gateway)
- OpenAI, Anthropic, Ollama (local)
- any other OpenAI-compatible endpoint (LiteLLM, vLLM, …)

Built for Anypoint Studio 7.28 (Eclipse 4.31, Java 17).

## Build

Needs JDK 17 and Maven.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn -DskipTests clean package
```

The installable update site is `com.mulesoftforge.studio.ai.updatesite/target/repository`.

## Install into Studio

Users install from the update site on the MuleSoftForge docs site; the install guide is [docs.mulesoftforge.com/studio-ai](https://docs.mulesoftforge.com/studio-ai/).

1. **Help > Install New Software… > Add…** and paste `https://docs.mulesoftforge.com/studio-ai/updates/` (or **Local…** and choose `com.mulesoftforge.studio.ai.updatesite/target/repository` for a local build).
2. Tick **MuleSoftForge Studio AI**, finish, and restart Studio. Installing also adds the site to Available Software Sites (`com.mulesoftforge.studio.ai.feature/p2.inf`), so **Help > Check for Updates** finds new releases.
3. **Window > Show View > Other… > MuleSoftForge > MuleSoftForge AI**.
4. Click **Settings** in the view (or **Preferences > MuleSoftForge AI**), pick a provider, enter the API key, and click **Test connection**.

To remove it: **Help > About Anypoint Studio > Installation Details**, select it, **Uninstall**.

The view's header shows the installed version (`v0.1.0`). Once per Studio session it reads `https://docs.mulesoftforge.com/studio-ai/updates/latest.json`; when that names a newer version the label becomes **Update to x.y.z** and opens **Help > Check for Updates**. A copy in `dropins` cannot be updated that way, so the link opens the install guide instead. Turn the check off in Preferences > MuleSoftForge AI > Chat view.

`./install-dropins.sh` (below) is for your own build-and-try loop. Remove the dropins jar before installing from the update site; two copies conflict.

## Release

1. Bump the version: `mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=0.2.0-SNAPSHOT` (updates the poms, `MANIFEST.MF` and `feature.xml`).
2. `./publish-update-site.sh --notes "What changed"` builds, runs the offline checks, copies the update site into `../website/docs/public/studio-ai/updates/0.2.0/`, and rewrites the composite index, `p2.index` and `latest.json`. Every version stays, so users can roll back. It refuses to overwrite a published version unless you pass `--replace`.
3. In `website/`, commit, open a PR (its preview deploy serves the site at the preview URL for a test install), merge, then run **Deploy to Production**. Users see the update after that deploy.

## Use

Open a Mule file, type a question, press Enter. **Include open file** sends the active editor's content (unsaved edits included when the editor exposes text) with your message. **New chat** clears the conversation.

The first drop-down picks the mode:

- **Ask**: answers and explains. It can read the project but never proposes changes.
- **Agent**: reads the project and proposes changes for review (below).
- **Plan**: reads the project and writes a plan (goal, changes per file, risks, how to test). **Build this plan in Agent mode** under the answer switches to Agent and asks it to propose the changes.

Ask and Plan only get the read tools, and the tool layer refuses edits in those modes as well.

The chat uses Eclipse's built-in browser (WebKit on macOS, Edge WebView2 on Windows) for message bubbles, code blocks with Copy / Insert at cursor, real tables and collapsible progress steps. If the browser cannot start, or **Preferences > MuleSoftForge AI > Chat view** is turned off, the plain text view is used instead. Model output is escaped, the page cannot navigate, and links open through Studio.

**+ Context** attaches more to the next message: files (their content), folders (a list of the files inside), the lines selected in the editor, or the project's whole file list. Attachments show above the input and clear once sent. Large attachments are cut at 40k characters each and 120k in total; anything over the total is named so the AI can read it with its tools.

**Send to MuleSoftForge AI** (MuleSoftForge anvil on the toolbar, right-click menu in editors including the Configuration XML tab, Project/Package Explorer, Problems and Console, or Cmd/Ctrl+Alt+I) opens the view with the selected lines, files or folders attached.

**Processors on the canvas.** Select one or more processors, scopes or flows on the Message Flow canvas (Shift- or Cmd/Ctrl-click for several), right-click, and choose **Send to MuleSoftForge AI** (Cmd/Ctrl+Alt+I does the same). Their XML is attached as **canvas selection in &lt;file&gt; (2 elements: logger "Log request", http:request)**. A processor inside a selected flow is sent once, with the flow. The XML comes from Studio's model, so attribute order and spacing can differ from the file; in Agent mode the AI reads the file before proposing an edit. The item uses Studio's `org.mule.tooling.messageflow.nodecontextmenu` extension point and reaches Studio's classes by reflection, so a Studio release that changes them hides the item rather than breaking the plugin.

**Fixing problems.** + Context > **Problems in &lt;file&gt;** or **Problems in &lt;project&gt;** attaches what Studio's Problems view lists (severity, file, line, message; up to 200). You can also select rows in the Problems view, right-click, and choose **Send to MuleSoftForge AI**. If the message box is empty it fills in "Fix these problems." and switches from Ask to Agent mode, so the AI proposes the fix as a reviewable change.

**Console output.** + Context > **Console: &lt;name&gt;** attaches a console's text, such as Maven Output from a failed build or a running Mule app's log; the console showing in the Console view is listed first. In the Console view itself, right-click (or Cmd/Ctrl+Alt+I) and choose **Send to MuleSoftForge AI** to attach the selected text, or the whole console when nothing is selected. Long output keeps its last 40k characters, where errors usually are. If it shows a failure (ERROR, BUILD FAILURE, an exception) and the message box is empty, it fills in "Explain the error in this console output and fix its cause."

**DataWeave editors.** Right-click in the script of a Transform Message (or another DataWeave editor) for two items under Format:

- **Send to MuleSoftForge AI** attaches the selected DataWeave, or the whole script when nothing is selected, to the next message. It is the text in the editor, saved or not.
- **Fix with MuleSoftForge AI** sends the whole script, the errors and warnings the editor marks in it, and any selection as the part to focus on, in Ask mode. When the answer contains a corrected script, **Review diff** shows it beside the current one and **Apply to the DataWeave editor** replaces the script in that editor (Cmd/Ctrl+Z there undoes it; save the Mule file to keep it). Apply refuses if the script was edited, or the editor closed, after Fix was chosen.

These use Studio's `org.mule.tooling.dfl.ui.weaveEmbeddedContextMenuAction` extension point, because the DataWeave editor builds its own menu. Studio does not document that extension point, so check both items after each Studio upgrade.

**Screenshots and images.** Copy a screenshot or picture, then press Cmd/Ctrl+V in the chat view; it is attached as **pasted image 1 (width×height)** instead of pasting text. + Context > **Clipboard image** does the same. Up to 5 images go with one message. Images larger than 1568 px on their longest side are scaled down, and sent as PNG (JPEG when the PNG would be over 3.5 MB). An image is sent only with the message it was pasted into; later messages and saved chats keep just its label. The model must accept images: Claude, GPT-4o/GPT-5 and Gemini do, and in Ollama a vision model such as llava. Otherwise the error says so.

**Speak instead of typing.** The mic button next to **Send** starts the operating system's dictation in the message box: macOS Dictation (turn it on in System Settings > Keyboard > Dictation), or Windows voice typing (Windows+H). The plugin never records audio itself and sends none to the AI provider; the system turns speech into text and the text is sent like typing. Press Enter, or click Send, when it reads right.

If you cancel a request, the AI is told with your next message that it was dropped, so it does not assume the work was done.

The box next to **New chat** picks the model for the current provider. Choose a suggestion or type any model id and press Enter. Underneath it shows the list price per 1M input and output tokens; hover for the prices of every suggestion. You can switch models mid-chat, and the conversation carries over.

### Changing files

Ask for a change ("add a /health flow", "log the request method"). The AI can list and read files in the project of the active editor (or the one selected in Project Explorer), and proposes edits through tools. Nothing is written until you review it:

- Proposals appear under **Proposed changes**. **Review diff** (or double-click) opens the Eclipse compare editor: current file on the left, proposal on the right.
- **Accept** writes the file with local history kept. Undo with right-click > **Replace With > Local History**.
- **Reject** discards it. The AI is told what you accepted or rejected with your next message.
- Accept refuses if the file has unsaved edits or changed on disk since it was proposed.
- Paths must stay inside the project. Absolute paths, `..`, and `target/`, `.git/`, `.mule/`, `.settings/`, `bin/` are refused.

**Checks on every proposal.** When the AI proposes a change to Mule XML under `src/main/mule` or `src/test/munit`, `pom.xml`, or a `.properties`/`.yaml` file under `src/main/resources`, the plugin checks the project as it would be with the proposals applied, and returns any new problems to the AI so it fixes them before you see the change:

- XML that is not well-formed, or uses a prefix without declaring it.
- A namespace missing from `xsi:schemaLocation`, the doc namespace listed there, or a schema URL that does not follow `…/<name>/current/mule-<name>.xsd`.
- A connector or module namespace with no matching dependency in `pom.xml`.
- `config-ref`, `tlsContext`, `objectStore`, `listenerConfig`, `cachingStrategy-ref`, `defaultErrorHandler-ref`, `error-handler ref` or `flow-ref` pointing at a name that does not exist. This check is skipped when a file uses `<import>` or the project uses a domain.
- `${property}` or `${secure::property}` not defined in a `.properties`/`.yaml` file or a `global-property`, and `configuration-properties` naming a file that is not there.
- Error types: `MULE:` types that do not exist, and `raise-error` or `error-mapping targetType` using a connector's namespace.
- Connector elements, checked against the connector version in `pom.xml`: an operation, source or scope that does not exist, an attribute the component does not have, and an error type the connector does not define.

**Connector lookup.** In every mode the AI can look up the connectors in `pom.xml` (`connector_reference`): their configs, connections, operations, sources, parameters (attribute or child element, defaults, allowed values) and error types. The answers come from the models Studio generates for each connector version under `<workspace>/.mule/plugins-tmp/repository/`, so they match the version the project uses. If Studio has not generated a model yet (a new dependency), the AI is told to save `pom.xml` or refresh the project rather than guess, and the connector element checks skip that connector.

Problems already in the project are not blamed on a proposal. Whatever the AI leaves unfixed shows as a note under its answer. In any mode the AI can also run the same checks on the whole project (`check_project`), which helps with "why won't this deploy?". The checks read files only; they do not build or run the app.

### Saved chats and cost

Chats save automatically after every answer to `<workspace>/.metadata/.plugins/com.mulesoftforge.studio.ai/chats/` (one JSON file per chat plus `index.json`). Open-file contents and API keys are not saved. **History ▾** in the view lists recent chats: Open (continue where you left off), Rename, Export as Markdown, Delete. Unreviewed proposals expire when a chat is closed.

Every model call is also appended to `usage.jsonl` (tokens and amounts only). The column to the right of the transcript shows the model in use with its list price, and what this chat, today and this month have cost; the tooltip adds the Studio session, all time, and the chat's project. Deleting chats does not remove their cost.

### Corporate networks (Zscaler and similar)

If the company proxy re-signs HTTPS, calls fail with "certificate is not trusted". **Preferences > MuleSoftForge AI > Network**:

- **Trust the operating system's certificates** (on by default) adds the Windows certificate store, the macOS keychains, or the Linux CA bundle. That usually covers a proxy root CA that IT has pushed to laptops.
- **Corporate CA (PEM)**: a `.pem`/`.crt` file, or a folder of them, from IT.
- **Check certificates** shows how many certificates came from each source and any file problems.

Test connection and Refresh models use the settings on screen, before you click Apply.

Each provider keeps its own base URL, model and key. Switching provider on the page keeps what you typed for the others, and Apply saves them all. Under the key, the page shows its start and end (`sk-or-…a1b2, 73 characters`) and warns when it looks like another provider's key, the usual cause of a 401. The models Refresh models finds are saved for that provider and offered in the chat view's model picker, and the chat view updates when settings change, however Preferences was opened.

**Preferences > MuleSoftForge AI > Chat history**: turn saving off, set how many days to keep chats (default 90, 0 = forever), or delete all chats.

## Test without Studio

`harness/run.sh` uses the same code with keys from `.env`:

```bash
cd harness
./run.sh test-connection openrouter openai
./run.sh list-models openai
./run.sh check-tools                                   # offline: path guard and edit rules
./run.sh check-mule                                    # offline: the proposal checks
./run.sh check-connectors                              # offline: connector models, lookup and element checks
./run.sh check-project /abs/path/to/mule-app [ws]      # run the proposal checks on a real project
./run.sh connector /abs/path/to/mule-app "db:select"   # what connector_reference returns ("" lists all, "db" one connector)
./run.sh check-render                                  # offline: Markdown and code colouring
./run.sh check-history                                 # offline: save, reload, export, prune, cost ledger
./run.sh check-attach                                  # offline: attachments, size caps, cancel note
./run.sh check-html /tmp/preview.html                  # offline: rich chat HTML and escaping; writes a preview page
./run.sh check-updates [latest.json URL]               # offline: version compare and latest.json; with a URL, fetches it
./run-checks.sh                                        # every offline check, as install and publish run them
./run.sh chat --mode plan --project /tmp/proj "add a /health endpoint"   # modes: ask, agent, plan
./run.sh chat --project /tmp/proj --attach src --lines src/main/mule/app.xml:1-12 "explain this"
./run.sh chat --image /abs/path/screenshot.png "what does this error dialog say?"
./run.sh prices openai anthropic                       # what the model picker shows, with list prices
./run.sh check-tls                                     # certificate sources, trusted and untrusted HTTPS
CA_PEM=~/corp-root.pem ./run.sh test-connection openai # try a corporate CA (USE_SYSTEM_CA=false to skip OS certs)
./run.sh list-chats                                    # chats and totals saved by Studio
./run.sh show-chat <id>                                # one saved chat as Markdown
./run.sh chat --provider openai --project /tmp/proj "change the listener path to /api"
./run.sh chat --provider openai --project /tmp/proj --apply "add a health flow"
```

`--project` prints each proposed diff; `--apply` also writes them to that folder. Shell variables fill in anything `.env` does not set.

`./install-dropins.sh` runs the offline checks before copying into Studio and stops if any fail (`--skip-checks` to bypass).

## Layout

```
com.mulesoftforge.studio.ai/            the plugin
  src/.../settings/   providers, saved settings (API keys in Eclipse secure storage)
  src/.../llm/        LangChain4j model creation, chat loop with tool calls
  src/.../edit/       project tools, path guard, staged changes, line diff (no Eclipse imports)
  src/.../check/      static checks of Mule XML, pom.xml and properties (no Eclipse imports)
  src/.../connector/  connector models Studio generated, lookup for the AI (no Eclipse imports)
  src/.../history/    saved chats, cost ledger, Markdown export (no Eclipse imports)
  src/.../render/     Markdown rendering and code highlighting (no Eclipse imports)
  src/.../ui/         chat view, change review, preferences page, open-file capture
  lib/                LangChain4j jars, copied by Maven at build time (not in git)
com.mulesoftforge.studio.ai.feature/    installable feature
com.mulesoftforge.studio.ai.updatesite/ update site for Install New Software
install-dropins.sh                      build, check, copy into Studio's dropins (development)
publish-update-site.sh                  build, check, add a release to the website's update site
```
