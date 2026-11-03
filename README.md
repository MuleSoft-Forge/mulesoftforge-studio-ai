# MuleSoftForge Studio AI (proof of concept)

AI chat inside Anypoint Studio. It uses [LangChain4j](https://docs.langchain4j.dev/) so one plugin can talk to:

- OpenRouter (model `openrouter/auto` lets OpenRouter choose)
- MuleSoft AI Gateway LLM Proxy (implemented, not offered in the UI yet — proxy has no usable model list)
- OpenAI, Anthropic, Google Gemini, Azure OpenAI, Amazon Bedrock, Ollama (local)
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
3. **Window > Show View > Other… > MuleSoftForge > MuleSoftForge Studio AI**.
4. Click **Settings** in the view (or **Preferences > MuleSoftForge Studio AI**), pick a provider, enter the API key (or client id and secret for MuleSoft AI Gateway), and click **Test connection**.

To remove it: **Help > About Anypoint Studio > Installation Details**, select it, **Uninstall**.

The view's header shows the installed version (`v0.1.0`). Once per Studio session it reads `https://docs.mulesoftforge.com/studio-ai/updates/latest.json`; when that names a newer version the label becomes **Update to x.y.z** and opens **Help > Check for Updates**. A copy in `dropins` cannot be updated that way, so the link opens the install guide instead. Turn the check off in Preferences > MuleSoftForge Studio AI > Chat view.

`./install-dropins.sh` (below) is for your own build-and-try loop. Remove the dropins jar before installing from the update site; two copies conflict.

## Release

1. Bump the version: `mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=0.2.0-SNAPSHOT` (updates the poms, `MANIFEST.MF` and `feature.xml`).
2. `./publish-update-site.sh --notes "What changed"` builds, runs the offline checks, copies the update site into `../website/docs/public/studio-ai/updates/0.2.0/`, and rewrites the composite index, `p2.index` and `latest.json`. Every version stays, so users can roll back. It refuses to overwrite a published version unless you pass `--replace`.
3. In `website/`, commit, open a PR (its preview deploy serves the site at the preview URL for a test install), merge, then run **Deploy to Production**. Users see the update after that deploy.

### Sign in with GitHub for feedback

While `GitHub.CLIENT_ID` in `feedback/GitHub.java` is empty, the Feedback popup only opens GitHub's New issue page. To let it send:

1. **MuleSoft-Forge org > Settings > Developer settings > GitHub Apps > New GitHub App**. Name it "MuleSoftForge Studio AI", homepage `https://docs.mulesoftforge.com/studio-ai/`. Clear **Webhook > Active**, tick **Enable Device Flow**, and clear **Expire user authorization tokens** (refreshing a token needs the client secret, so users would sign in again every 8 hours). Under **Repository permissions** set **Issues** to **Read and write**, and choose **Only on this account**.
2. **Install App** on `mulesoftforge-studio-ai` only.
3. Put the app's **Client ID** in `GitHub.CLIENT_ID` and release. The client ID is public; do not generate a client secret.

## Use

Open a Mule file, type a question, press Enter. **Include open file** sends the active editor's content (unsaved edits included when the editor exposes text) with your message. **New chat** clears the conversation.

The first drop-down picks the mode:

- **Ask**: answers and explains. It can read the project but never changes files. When you ask for a review or a fix, it lists possible fixes, collapsed and unticked, for Agent mode to make.
- **Agent**: reads the project and proposes changes for review (below).
- **Plan**: reads the project and writes a plan (goal, changes per file, risks, how to test), then lists its changes as a checklist (below).
- **Diagnose**: finds why something goes wrong, with evidence. It reproduces the problem (calls the running app, with any method, or reads its console), reads the Mule XML at the file and line the error names, checks its cause with a second call, the debugger, a DataWeave run or the project checks, and looks up the error type in the docs. Every answer has the same shape: symptom, quoted evidence, cause, how it was checked, then the fixes as a checklist for Agent mode. It attaches the project's Problems by itself, and the Mule app's console when that shows a failure it has not sent yet. Once Agent has drafted fixes from a diagnosis, **check the fix in Diagnose mode** repeats the reproduction after you accept them and Studio redeploys. Attaching a console that shows a failure switches Ask or Agent to Diagnose, and when an Ask or Agent message describes an error, the answer offers **Find the cause in Diagnose mode** once per chat.

Ask, Plan and Diagnose only get the read tools, and the tool layer refuses edits in those modes as well. Diagnose is the one read-only mode that can send POST, PUT, PATCH and DELETE to the app on this machine, so it can reproduce a failing write.

**Secrets are hidden from the model.** Before anything is sent (your message, the open file, attachments, console output, and every file the AI reads), passwords, keys, tokens and client ids become `«masked-N»`. That covers `.properties` and YAML values, hardcoded attributes in Mule XML (`password="…"`, `clientSecret`, `keyPassword`, `Authorization` headers), JSON and DataWeave string literals, credentials in URLs and JDBC strings, `-Dsecure.key=` in `.launch` files, and known token formats (JWTs, private keys, AWS, OpenAI, Anthropic, GitHub, Slack and Google keys). References such as `${db.password}`, `${secure::x}`, `p('…')` and encrypted `![…]` values are kept, so the AI can still follow the configuration. When it edits a line with a masked value, the real value is put back before you review the change; Copy, Insert at cursor and the DataWeave fix get the real values too. A masked literal in a Mule file is a hardcoded secret, and the AI is told to suggest moving it to secure properties. Files that hold only secrets (`.env`, `*.pem`, `*.key`, keystores, `credentials*.json`) are never read or attached. Turn masking off in **Preferences > MuleSoftForge Studio AI > Chat view**; those files stay blocked either way. Screenshots are sent as they are, and a secret under an unusual name (`db.pw2=…`) can be missed, so keep real credentials in secure properties.

**Picking which suggestions to make.** When the AI recommends changes without making them (a review such as "study my project and suggest improvements", an Ask answer, or a plan), it lists them under the answer as **Changes it can make**, each ticked, with the files it touches. Untick the ones you don't want and click **Draft selected changes for review**. That switches to Agent and asks for just those changes, which come back as proposals with diffs to review (below); nothing is written until you accept. In Agent mode, when you ask for a change and the model pastes code instead of proposing it, the plugin asks it once, in the same turn, to propose that code as edits, so the diffs arrive with the answer. If that still produces nothing, **Draft these changes as diffs** asks again; a plan without a list gets **Draft this plan for review in Agent mode**. The list belongs to the open chat: a chat reopened from History shows the answer but not the checklist.

**Instructions.** Put team conventions in `AGENTS.md` at the Mule project's root (the same file other AI tools read); a `## Studio AI` section is a good place for anything only meant for Studio. Put your own preferences in Preferences > MuleSoftForge Studio AI > Instructions > **Your instructions (every chat, every project)**. Both are sent with every message, after secrets are masked, up to 8,000 characters each (longer text is cut with a note), labelled as project and personal instructions below the built-in prompt. They override the built-in guidance (persona, style, Mule conventions) but not the rules the plugin relies on: tools, «masked-N» values, edits as reviewable proposals, fixing CHECKS problems, `offer_changes`, and answering "what can you do" as MuleSoftForge Studio AI (not a generic Mule FAQ). The first message of a chat shows a step such as **Following AGENTS.md (1,240 characters) and your instructions (85 characters)**, and Raw shows the exact text. **Show built-in prompt** under the box shows everything the plugin sends before your instructions; the files are in `llm/prompts/`.

The chat uses Eclipse's built-in browser (WebKit on macOS, Edge WebView2 on Windows) for message bubbles, code blocks with Copy / Insert at cursor, real tables and collapsible progress steps. If the browser cannot start, or **Preferences > MuleSoftForge Studio AI > Chat view** is turned off, the plain text view is used instead. Model output is escaped, the page cannot navigate, and links open through Studio.

**+ Context** attaches more to the next message: files (their content), folders (a list of the files inside), the lines selected in the editor, or the project's whole file list. Attachments show above the input and clear once sent. Large attachments are cut at 40k characters each and 120k in total; anything over the total is named so the AI can read it with its tools.

**Send to MuleSoftForge Studio AI** (MuleSoftForge anvil on the toolbar, right-click menu in editors including the Configuration XML tab, Project/Package Explorer, Problems and Console, or Cmd/Ctrl+Alt+I) opens the view with the selected lines, files or folders attached.

**Processors on the canvas.** Select one or more processors, scopes or flows on the Message Flow canvas (Shift- or Cmd/Ctrl-click for several), right-click, and choose **Send to MuleSoftForge Studio AI** (Cmd/Ctrl+Alt+I does the same). Their XML is attached as **canvas selection in &lt;file&gt; (2 elements: logger "Log request", http:request)**. A processor inside a selected flow is sent once, with the flow. The XML comes from Studio's model, so attribute order and spacing can differ from the file; in Agent mode the AI reads the file before proposing an edit. The item uses Studio's `org.mule.tooling.messageflow.nodecontextmenu` extension point and reaches Studio's classes by reflection, so a Studio release that changes them hides the item rather than breaking the plugin.

**Fixing problems.** + Context > **Problems in &lt;file&gt;** or **Problems in &lt;project&gt;** attaches what Studio's Problems view lists (severity, file, line, message; up to 200). You can also select rows in the Problems view, right-click, and choose **Send to MuleSoftForge Studio AI**. If the message box is empty it fills in "Fix these problems." and switches from Ask to Agent mode, so the AI proposes the fix as a reviewable change.

**Console output.** + Context > **Console: &lt;name&gt;** attaches a console's text, such as Maven Output from a failed build or a running Mule app's log; the console showing in the Console view is listed first. In the Console view itself, right-click (or Cmd/Ctrl+Alt+I) and choose **Send to MuleSoftForge Studio AI** to attach the selected text, or the whole console when nothing is selected. Long output keeps its last 40k characters, where errors usually are. If it shows a failure (ERROR, BUILD FAILURE, an exception) and the message box is empty, it switches Ask or Agent to Diagnose mode and fills in "Find the cause of the error in this console output." (in Plan it fills in "Explain the error in this console output and fix its cause.").

**DataWeave editors.** Right-click in the script of a Transform Message (or another DataWeave editor) for two items under Format:

- **Send to MuleSoftForge Studio AI** attaches the selected DataWeave, or the whole script when nothing is selected, to the next message. It is the text in the editor, saved or not.
- **Fix with MuleSoftForge Studio AI** sends the whole script, the errors and warnings the editor marks in it, and any selection as the part to focus on, in Ask mode. When the answer contains a corrected script, **Review diff** shows it beside the current one in a dialog, so the Mule editor and Transform Message stay as they were, and **Apply to the DataWeave editor** replaces the script in that editor (Cmd/Ctrl+Z there undoes it; save the Mule file to keep it). If Studio rebuilt the DataWeave editor in the meantime (for example after you switched to another file and back), Apply uses the new one when the same Mule file is active and it shows the same script. Apply refuses if the script was edited after Fix was chosen.

These use Studio's `org.mule.tooling.dfl.ui.weaveEmbeddedContextMenuAction` extension point, because the DataWeave editor builds its own menu. Studio does not document that extension point, so check both items after each Studio upgrade.

**DataWeave CLI.** With MuleSoft's [DataWeave CLI](https://github.com/mulesoft/data-weave-cli) the AI gets two tools: `run_dataweave` runs a script on sample input (pasted, attached or a project file) and, given the output you want, says whether it matches and where it differs; `validate_dataweave` checks a script compiles. Proposals are compiled too (see the checks below). Runs use `dw run --untrusted`, so scripts cannot read files or the network, and stop after 20 seconds; Mule-only `p()` and `lookup()` return null. Set it up in **Preferences > MuleSoftForge Studio AI > DataWeave CLI**: leave the path empty to look for `dw` on the PATH, in `~/.dw/bin` and in Homebrew; **Check** shows the version found; **Download 2.12.3** fetches MuleSoft's GitHub release for macOS arm64, Windows x64 or Linux x64, verifies the SHA-256 pinned in `CliDownload`, and installs it under the plugin's state folder. Without the CLI everything else works as before. When `dw` is not found, or is found but does not run (wrong CPU type, blocked by macOS), the panel says why in red and the two DataWeave tools are left out of the chat until **Check** finds one that runs.

**Debug with AI.** When Studio's Mule debugger stops at a breakpoint or an error, a row above the message box says where (**Debugger stopped on an error at Transform Message in get-employees**). **Ask about this event**, the MuleSoftForge button on the **Mule Debugger** view's toolbar, or Cmd/Ctrl+Alt+I in a debugger view attaches the live event: where it stopped, any error with the failing component's XML, the payload, attributes, variables, and the processor's settings as the runtime evaluated them, plus the processor's XML from the project. While the debugger is stopped the AI can run DataWeave on that event in the app's own runtime (`evaluate_at_breakpoint`) and load fields Studio has not sent yet (`expand_field`). Evaluations are read-only: `readUrl`, `lookup`, Java calls and `dw::Runtime` are refused, and so is reading a payload stream that can be read only once. If you step or another event stops while the AI is working, its results say where the debugger is now. Secrets in the event are masked as usual, and email addresses and phone numbers become `«email-N»` and `«phone-N»` unless you clear **Hide email addresses and phone numbers in live debug events**. The view is added beside **Evaluate** in the Mule Debug perspective. Studio's debugger is reached by reflection (`ui/StudioDebugger`), listening on its event bus from startup; closing the Mule Debugger view clears every listener on that bus, so the plugin registers again when the view opens or closes and when a debug launch starts. If Studio's debugger classes change, the feature turns itself off.

**Call the running app, read the docs.** With a project open the AI gets these tools. `list_endpoints` reads the project's HTTP listeners and APIkit flows and lists the URL each answers on in Studio (`GET http://localhost:8081/api/employees`), resolving `${http.port}` and other values from `src/main/resources`. `call_endpoint` sends a request to the app you are running in Studio and returns the status, headers and body, and, given an expected status or body, says whether they match and where they differ; the AI also shows the request as a `curl` command. When Studio has a console for the app, the result also carries what that console printed while the call ran (the error type, cause and `Element: flow/processors/2 @ file.xml:23` that a bare `500` leaves out): it waits for Mule's asynchronous log to go quiet, up to 2 seconds, and keeps the last 4k characters. When nothing answers on the port, it adds the end of the console instead, so the AI can tell an app that is not running from one that failed to deploy. `read_console` reads the end of that console on its own, for example after a redeploy. Console text is masked like everything else. It only reaches this machine (`localhost`, `127.0.0.1`, `[::1]`, a listener's `0.0.0.0`), never uses the proxy, accepts the self-signed certificates local HTTPS listeners use, follows redirects only while they stay local, and stops after 30 seconds. While you debug, a call that stops at one of your breakpoints does not keep the chat running: as soon as Studio's debugger stops, `call_endpoint` returns PAUSED with the stopped event, the debugger tools join that same turn, and the request stays open (up to 10 minutes) while you step through it. Ask the AI to call an endpoint "without waiting" and it sends the request and returns at once (`wait: false`). Either way, the response that arrives later shows as a note in the chat and goes to the AI with your next message. Cancel stops a waiting call straight away. In Agent and Diagnose mode the AI can also start the app when you ask it to run, debug, restart or stop it: `run_app` does what **Run As** or **Debug As > Mule Application** does (the project's own Mule launch configuration, or Studio's shortcut when there is none), stops an app running in the other mode first, and waits until the app is started or has failed, up to 3 minutes. It learns that from the app events Studio's runtime reports, and from the console's `DEPLOYED` / `FAILED` table on a Studio that reports none; a failure comes back with the console's reason. `stop_app` terminates it. Ask and Plan mode do not get these tools, and the AI is told not to start the app on its own just because a call could not connect. Ask and Plan mode send only GET, HEAD and OPTIONS; POST, PUT, PATCH and DELETE need Agent or Diagnose mode. `«masked-N»` values in a request are sent as their real values, since the request stays on this machine, and are masked again if the app echoes them; files that hold only secrets are never sent as a body. `fetch_docs` reads a documentation page as text, with the links on it, so the AI can check a connector's parameters instead of answering from memory. By default it may read `docs.mulesoft.com`, `developer.mulesoft.com`, `dataweave.mulesoft.com`, [`dev-portal.mulesoft.com`](https://dev-portal.mulesoft.com/) and `help.salesforce.com`; change that list in **Preferences > MuleSoftForge Studio AI > HTTP and documentation**. It follows [MuleSoft's AI docs guidance](https://docs.mulesoft.com/general/use-mulesoft-docs-with-ai): on `docs.mulesoft.com` it prefers the Markdown twin (`…/page.md`, or content negotiation), keeps `text/markdown` and `llms.txt` / `llms-full.txt` indexes as plain text, and surfaces a response's `Link: rel=describedby` llms.txt as `INDEX:`. The prompt tells the model to start from product or site `llms.txt` indexes, and on the developer portal from `AGENTS.md`, `registry.json` and `llms.txt`. It sends GET with no headers or body of the model's choosing, refuses other sites and redirects to them, and never puts masked values in a URL. The same Preferences group has optional **HTTP and docs instructions** appended after the built-in HTTP guidance (they cannot override masking, localhost-only calls or the host list). These limits are in the code (`http/HttpTools`), not only the prompt: a model steered by something it read cannot send project content to another site. Adding a docs host does not open `call_endpoint` to that host.

**Screenshots and images.** Copy a screenshot or picture, then press Cmd/Ctrl+V in the chat view; it is attached as **pasted image 1 (width×height)** instead of pasting text. + Context > **Clipboard image** does the same. Up to 5 images go with one message. Images larger than 1568 px on their longest side are scaled down, and sent as PNG (JPEG when the PNG would be over 3.5 MB). An image is sent only with the message it was pasted into; later messages and saved chats keep just its label. The model must accept images: Claude, GPT-4o/GPT-5 and Gemini do, and in Ollama a vision model such as llava. Otherwise the error says so.

**Speak instead of typing.** The mic button next to **Send** starts the operating system's dictation in the message box: macOS Dictation (turn it on in System Settings > Keyboard > Dictation), or Windows voice typing (Windows+H). The plugin never records audio itself and sends none to the AI provider; the system turns speech into text and the text is sent like typing. Press Enter, or click Send, when it reads right.

If you cancel a request, the AI is told with your next message that it was dropped, so it does not assume the work was done.

**Feedback and bug reports.** **Feedback** in the view's header opens a popup: Bug or Feedback, a title, what happened, and the details sent with it (plugin, Studio, Eclipse, Java and OS versions, provider and model, and whether the DataWeave CLI is set up; no paths, URLs, project names or keys). It becomes a public issue on this repository, so the title, text and details are always masked as they are for the model, whatever the masking setting, and the popup says when something was masked. **Sign in with GitHub** shows a short code, copies it and opens github.com/login/device; after **Authorize** the popup shows **Sending as @name**, and **Send** opens the issue as that user and shows it in the browser, where screenshots can be added. Sign-in is GitHub's device flow for the MuleSoftForge Studio AI GitHub App, so there is no client secret in the plugin; the token can only open issues in repositories where the app is installed, and is kept in Eclipse secure storage. **Open on GitHub instead** (just **Open on GitHub** while `GitHub.CLIENT_ID` is empty) opens the New issue page with `.github/ISSUE_TEMPLATE/bug_report.yml` or `feedback.yml` filled in by field id; text that does not fit in the link (8,000 characters, 2,000 on Windows) is cut and copied to the clipboard. GitHub drops labels on issues opened by people without write access, so `.github/workflows/label-reports.yml` labels `Bug:` and `Feedback:` titles.

The box next to **New chat** picks the model for the current provider. Choose a suggestion or type any model id and press Enter. Underneath it shows the list price per 1M input and output tokens; hover for the prices of every suggestion. You can switch models mid-chat, and the conversation carries over.

### Changing files

Ask for a change ("add a /health flow", "log the request method"). The AI can list and read files in the project of the active editor (or the one selected in Project Explorer), and proposes edits through tools. Nothing is written until you review it:

- Proposals appear under **Proposed changes**. **Review diff** (or double-click) opens the Eclipse compare editor: current file on the left, proposal on the right.
- **Accept** writes the file with local history kept, creating it and any missing folders for a new file. **Accept all** writes every pending proposal in one Studio job, locking only what it writes (`ui/WorkspaceWrites`). If a write fails, the chat says what was not written. Undo with right-click > **Replace With > Local History**.
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
- DataWeave that does not compile, when the DataWeave CLI is set up: Transform Message scripts, `#[...]` values and `.dwl` files. Only scripts the proposal changed are compiled, and errors are reported at the XML line.

**Connector lookup.** In every mode the AI can look up the connectors in `pom.xml` (`connector_reference`): their configs, connections, operations, sources, parameters (attribute or child element, defaults, allowed values) and error types. The answers come from the models Studio generates for each connector version under `<workspace>/.mule/plugins-tmp/repository/`, so they match the version the project uses. If Studio has not generated a model yet (a new dependency), the AI is told to save `pom.xml` or refresh the project rather than guess, and the connector element checks skip that connector.

Problems already in the project are not blamed on a proposal. Whatever the AI leaves unfixed shows as a note under its answer. In any mode the AI can also run the same checks on the whole project (`check_project`), which helps with "why won't this deploy?". The checks read files only; they do not build or run the app.

### Saved chats and cost

Chats save automatically after every answer to `<workspace>/.metadata/.plugins/com.mulesoftforge.studio.ai/chats/` (one JSON file per chat plus `index.json`). Open-file contents and API keys are not saved. **History ▾** in the view lists recent chats: Open (continue where you left off), Rename, Export as Markdown, Open raw log, Delete. Unreviewed proposals expire when a chat is closed.

**Raw.** Tick **Raw** in the view's top bar (or Preferences > MuleSoftForge Studio AI > Chat history > **Keep a raw log of every request and response**; off by default) to record every model call from the next message on. Each answer then gets a collapsed **Raw · N calls** block: one entry per call (tool rounds included) with method, URL, HTTP status, model and time, expanding to the exact request and response bodies, pretty-printed, with long image data shortened for reading. OpenAI-compatible providers, Anthropic, Gemini and Ollama are captured at the HTTP client (`DiagnosingHttpClient`), so the body is byte-for-byte what went on the wire, after secrets are masked. When a gateway returns several `choices` for one turn (Grok has been seen to split text and tool calls), the raw log still shows that body, and `ChatCompletions.collapseChoices` keeps a single choice for LangChain4j — preferring tool calls and copying any text from the other choice. Azure OpenAI and Bedrock use their own SDK clients, so for them the block shows the request and response as LangChain4j's `ChatModelListener` sees them. Headers are never recorded, so API keys and gateway `client_secret` stay out; key-like query parameters are hidden. Calls are stored in full in `raw/<chat id>.jsonl` next to `chats/`, so reopened chats show their raw blocks again. **Open raw log** (in the block, or in **History ▾**) opens a readable copy in Studio's text editor. Unticking Raw hides the blocks and stops recording. Raw logs are deleted and pruned with their chats.

Every model call is also appended to `usage.jsonl` (tokens and amounts only). The column to the right of the transcript shows the model in use with its list price, and what this chat, today and this month have cost; the tooltip adds the Studio session, all time, and the chat's project. Deleting chats does not remove their cost. Below that, **Show prices** unfolds **Prices per 1M tokens**, which lists every model in the picker with its input and output list price. The **Source** line names the data (public list prices via OpenRouter); hover the help icon for how that relates to **Cost** above. **Refresh** fetches the list again. When the list cannot be fetched, or a model is not in it, the column says so instead of showing a price. For Azure OpenAI, Bedrock, MuleSoft AI Gateway and other OpenAI-compatible endpoints the list price is only a guide, since they set their own prices.

### Corporate networks (Zscaler and similar)

If the company proxy re-signs HTTPS, calls fail with "certificate is not trusted". **Preferences > MuleSoftForge Studio AI > Network**:

- **Trust the operating system's certificates** (on by default) adds the Windows certificate store, the macOS keychains, or the Linux CA bundle. That usually covers a proxy root CA that IT has pushed to laptops.
- **Corporate CA (PEM)**: a `.pem`/`.crt` file, or a folder of them, from IT.
- **Check certificates** shows how many certificates came from each source and any file problems.

Test connection and Refresh models use the settings on screen, before you click Apply.

**Auto** (first in every model list) picks per message between **Auto fast model** and **Auto strong model**, set per provider below Model (defaults in `AutoRouter.defaults`). Rules in `AutoRouter.choose`: Agent and Plan, DataWeave fixes, live debug events, DataWeave questions and `.dwl` attachments, images, attachments over 24k characters, messages over 1,500 characters, and wording such as review, fix, error, write, test go to the strong model; other Ask questions go to the fast one. The chat shows "Auto picked … : reason". If the chosen model fails with 408, 429, 5xx, a timeout or a dropped connection before any call answered, the turn runs once on the other model (`AutoRouter.send`); 4xx errors such as a bad key are not retried. A turn never switches model between tool rounds. Test connection on Auto tests the strong model. `openrouter/auto` stays available for OpenRouter, and with either Auto the step under each answer names the model that replied.

Each provider keeps its own base URL, model and key. Switching provider on the page keeps what you typed for the others, and Apply saves them all. Under the key, the page shows its start and end (`sk-or-…a1b2, 73 characters`) and warns when it looks like another provider's key, the usual cause of a 401. The models Refresh models finds are saved for that provider and offered in the chat view's model picker, and the chat view updates when settings change, however Preferences was opened.

**Preferences > MuleSoftForge Studio AI > Chat history**: turn saving off, set how many days to keep chats (default 90, 0 = forever), or delete all chats.

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
./run.sh check-raw-log                                 # offline: chat via a local stub; exact HTTP bodies captured, masked, no API key
./run.sh check-auto                                    # offline: Auto routing rules and failover via a local stub
./run.sh check-draft                                   # offline: Agent answers that paste code are drafted as diffs in the same turn
./run.sh check-instructions                            # offline: built-in rules always sent; AGENTS.md and your instructions added masked and capped
./run.sh check-attach                                  # offline: attachments, size caps, cancel note
./run.sh check-html /tmp/preview.html                  # offline: rich chat HTML and escaping; writes a preview page
./run.sh check-updates [latest.json URL]               # offline: version compare and latest.json; with a URL, fetches it
./run.sh check-debug                                   # offline: debug events from captured fixtures, evaluate/expand tools on a fake session
./run.sh debug-snapshot logger-get.json                # what the AI sees for a captured debugger stop
./run.sh check-dataweave                               # DataWeave CLI parsing, proposal checks, tools, download (CLI parts skip without dw)
./run.sh check-http                                    # offline: endpoint discovery, localhost and docs-site rules, calls to a local stub (MSF_LIVE_DOCS=1 reads a real docs page)
./run.sh dataweave-download [dir]                      # download and verify the real CLI release (network)
./run.sh check-workspace                               # Accept's writes in a real Eclipse workspace booted from Studio's plugins (STUDIO_APP)
./run.sh check-feedback                                # offline: report masking, the issue-form link, GitHub sign-in and issues via a local stub
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
  src/.../llm/prompts/ built-in system prompt: rules.md, about.md and the mode files always apply; guidance.md and
                      mule-edits.md can be overridden by AGENTS.md and the user's instructions
  src/.../edit/       project tools, path guard, staged changes, line diff (no Eclipse imports)
  src/.../check/      static checks of Mule XML, pom.xml and properties (no Eclipse imports)
  src/.../connector/  connector models Studio generated, lookup for the AI (no Eclipse imports)
  src/.../history/    saved chats, cost ledger, Markdown export (no Eclipse imports)
  src/.../render/     Markdown rendering and code highlighting (no Eclipse imports)
  src/.../debug/      live debugger event, snapshot text, evaluate/expand tools (no Eclipse imports)
  src/.../dataweave/  DataWeave CLI: find, download, run, validate, proposal checks (no Eclipse imports)
  src/.../feedback/   bug reports and feedback: issue text, issue-form link, GitHub sign-in and issues (no Eclipse imports)
  src/.../ui/         chat view, change review, preferences page, open-file capture, Studio debugger adapter
  lib/                LangChain4j jars, copied by Maven at build time (not in git)
com.mulesoftforge.studio.ai.feature/    installable feature
com.mulesoftforge.studio.ai.updatesite/ update site for Install New Software
install-dropins.sh                      build, check, copy into Studio's dropins (development)
publish-update-site.sh                  build, check, add a release to the website's update site
harness/                                offline checks; harness/workspace/ runs inside Equinox for check-workspace
```
