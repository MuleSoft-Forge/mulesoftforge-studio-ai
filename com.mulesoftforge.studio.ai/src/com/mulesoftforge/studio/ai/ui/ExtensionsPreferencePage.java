package com.mulesoftforge.studio.ai.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.mulesoftforge.studio.ai.extend.Extension;
import com.mulesoftforge.studio.ai.extend.Extension.Kind;
import com.mulesoftforge.studio.ai.extend.Extension.Source;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.extend.Frontmatter;
import com.mulesoftforge.studio.ai.hooks.Hooks;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.Prompts;
import com.mulesoftforge.studio.ai.mcp.McpHub;
import com.mulesoftforge.studio.ai.mcp.McpServers;
import com.mulesoftforge.studio.ai.settings.AiSettings;

// Preferences > MuleSoftForge Studio AI > Rules, Skills, Workflows, Hooks and MCP. Each kind has built-in, global
// (~/.mulesoftforge/studio-ai) and project (.mulesoftforge) levels; every level may be empty. Turning a file on or
// off writes disabled: true into its frontmatter, as Cline does, so the choice travels with the file.
public class ExtensionsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  public static final String ID = "com.mulesoftforge.studio.ai.prefs.extensions";
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

  private Button fixChecks;
  private Button hooksEnabled;
  private Button mcpPlatform;
  private EclipseProject project;

  @Override
  public void init(IWorkbench workbench) {
    setDescription("Rules are sent with every message. Skills are listed, and the AI loads one when a task"
        + " matches it. Workflows run when you type /name in the chat. All three can be empty.");
  }

  @Override
  protected Control createContents(Composite parent) {
    IWorkbenchPage page = activePage();
    project = page == null ? null : EclipseProject.find(page);
    Composite body = new Composite(parent, SWT.NONE);
    body.setLayout(new GridLayout(1, false));

    Group correctness = new Group(body, SWT.NONE);
    correctness.setText("Enforces correctness");
    correctness.setLayout(new GridLayout(1, false));
    correctness.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    fixChecks = new Button(correctness, SWT.CHECK);
    fixChecks.setText("Fix check problems before finishing (recommended)");
    fixChecks.setSelection(AiSettings.fixChecksBeforeFinishing());
    wrapped(correctness, "When the AI proposes changes and the project checks (Mule XML, connectors, properties,"
        + " DataWeave) still fail on them, the problems go back to the AI, up to twice, before it may finish. If"
        + " they still fail, the answer says so and Accept asks you to confirm.");

    TabFolder tabs = new TabFolder(body, SWT.TOP);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    for (Kind kind : Kind.values()) {
      TabItem tab = new TabItem(tabs, SWT.NONE);
      tab.setText(title(kind));
      Composite content = new Composite(tabs, SWT.NONE);
      content.setLayout(new GridLayout(1, false));
      tab.setControl(content);
      fill(content, kind);
    }
    TabItem hooksTab = new TabItem(tabs, SWT.NONE);
    hooksTab.setText("Hooks");
    Composite hooks = new Composite(tabs, SWT.NONE);
    hooks.setLayout(new GridLayout(1, false));
    hooksTab.setControl(hooks);
    hooksEnabled = new Button(hooks, SWT.CHECK);
    hooksEnabled.setText("Run my hooks: commands for UserPromptSubmit, PreToolUse, PostToolUse, Stop and"
        + " Notification");
    hooksEnabled.setSelection(AiSettings.hooksEnabled());
    Link hooksFile = new Link(hooks, SWT.WRAP);
    hooksFile.setText("Read from " + Hooks.userFile() + ", in the format Codex and Copilot use for hooks.json."
        + " They run on your machine with your permissions, and see what the model sees, secrets masked."
        + " A PreToolUse hook can block a tool call, including use_skill. <a>Open hooks file</a>");
    hooksFile.setLayoutData(wide());
    hooksFile.addListener(SWT.Selection, e -> openHooksFile());

    TabItem mcpTab = new TabItem(tabs, SWT.NONE);
    mcpTab.setText("MCP servers");
    Composite mcp = new Composite(tabs, SWT.NONE);
    mcp.setLayout(new GridLayout(1, false));
    mcpTab.setControl(mcp);
    fillMcp(mcp);
    return body;
  }

  private void fillMcp(Composite mcp) {
    wrapped(mcp, "The AI looks up the tools it needs with mcp_find_tools and calls them with mcp_call. Tools that"
        + " only read run when the AI asks. Tools that change something run only in Agent mode, and Studio asks"
        + " you to allow each call. Your hooks run around every call.");
    mcpPlatform = new Button(mcp, SWT.CHECK);
    mcpPlatform.setText("MuleSoft Platform (hosted by MuleSoft)");
    mcpPlatform.setSelection(AiSettings.mcpPlatformEnabled());
    GridData platformData = new GridData(SWT.FILL, SWT.TOP, true, false);
    platformData.verticalIndent = 8;
    mcpPlatform.setLayoutData(platformData);
    wrapped(mcp, "Your APIs and their instances, policies, governance, monitoring, environments, Exchange assets,"
        + " agents and LLMs. It signs in with Studio's Anypoint login (Preferences > Anypoint Studio >"
        + " Authentication) and follows its region. Nothing is installed: no Node.js, no local server.");
    Link file = new Link(mcp, SWT.WRAP);
    file.setText("Your own remote servers are read from " + mcpFile() + " (\"mcpServers\", as in Cline and"
        + " Roo-Code; Streamable HTTP only). Write a token as ${env:NAME} to keep it out of the file."
        + " <a>Open mcp.json</a>");
    GridData fileData = wide();
    fileData.verticalIndent = 8;
    file.setLayoutData(fileData);
    file.addListener(SWT.Selection, e -> openMcpFile());
    Button test = new Button(mcp, SWT.PUSH);
    test.setText("Test connections");
    Label status = new Label(mcp, SWT.WRAP);
    status.setLayoutData(wide());
    status.setText(mcpSummary(McpServers.load(mcpFile(), AiSettings.mcpPlatformEnabled())));
    test.addListener(SWT.Selection, e -> {
      McpServers.Loaded loaded = McpServers.load(mcpFile(), mcpPlatform.getSelection());
      String[] text = new String[1];
      BusyIndicator.showWhile(getShell().getDisplay(), () -> text[0] = testMcp(loaded));
      status.setText(text[0]);
      mcp.layout(true, true);
    });
  }

  private static Path mcpFile() {
    return Extensions.userDir().resolve(McpServers.FILE);
  }

  private static String mcpSummary(McpServers.Loaded loaded) {
    StringBuilder out = new StringBuilder(loaded.servers().isEmpty() ? "No MCP servers are on."
        : "On: " + String.join(", ", loaded.servers().stream().map(McpServers.Server::name).toList()) + ".");
    loaded.problems().forEach(p -> out.append("\n").append(p));
    return out.toString();
  }

  private static String testMcp(McpServers.Loaded loaded) {
    if (loaded.servers().isEmpty()) {
      return mcpSummary(loaded);
    }
    McpHub.Listing listing = McpHub.live(loaded, StudioAnypoint::login).tools(null);
    StringBuilder out = new StringBuilder();
    for (McpServers.Server server : loaded.servers()) {
      List<McpHub.Tool> tools = listing.tools().stream().filter(t -> t.server().equals(server.name())).toList();
      if (!tools.isEmpty()) {
        long changing = tools.stream().filter(McpHub.Tool::changes).count();
        out.append(server.name()).append(": ").append(tools.size()).append(" tools (").append(changing)
            .append(" change something and ask you first)\n");
      }
    }
    listing.problems().forEach(p -> out.append(p).append('\n'));
    loaded.problems().forEach(p -> out.append(p).append('\n'));
    return out.toString().strip();
  }

  private void openMcpFile() {
    Path file = mcpFile();
    try {
      if (!Files.exists(file)) {
        Files.createDirectories(file.getParent());
        Files.writeString(file, McpServers.template());
      }
      open(file);
    } catch (IOException e) {
      setErrorMessage("Could not open " + file + ": " + Errors.describe(e));
    }
  }

  @Override
  public boolean performOk() {
    try {
      AiSettings.saveFixChecksBeforeFinishing(fixChecks.getSelection());
      AiSettings.saveHooksEnabled(hooksEnabled.getSelection());
      AiSettings.saveMcpPlatformEnabled(mcpPlatform.getSelection());
      return true;
    } catch (Exception e) {
      setErrorMessage("Could not save: " + Errors.describe(e));
      return false;
    }
  }

  @Override
  protected void performDefaults() {
    fixChecks.setSelection(true);
    hooksEnabled.setSelection(false);
    mcpPlatform.setSelection(false);
    super.performDefaults();
  }

  private void fill(Composite content, Kind kind) {
    for (Control child : content.getChildren()) {
      child.dispose();
    }
    wrapped(content, help(kind));
    Path location = project == null ? null : project.location();
    for (Source source : Source.values()) {
      if (source == Source.BUILT_IN && kind != Kind.SKILL) {
        continue;
      }
      Label heading = new Label(content, SWT.NONE);
      heading.setText(heading(source));
      heading.setFont(JFaceResources.getBannerFont());
      GridData headingData = new GridData(SWT.FILL, SWT.TOP, true, false);
      headingData.verticalIndent = 8;
      heading.setLayoutData(headingData);
      if (source == Source.PROJECT && project == null) {
        wrapped(content, "Select a file of a Mule project in Studio to see and add its " + kind.folder + ".");
        continue;
      }
      List<Extension> items = Extensions.list(kind, source, location, AiSettings.disabledBuiltIns());
      if (items.isEmpty()) {
        wrapped(content, "None yet.");
      }
      for (Extension item : items) {
        row(content, item);
      }
      if (source != Source.BUILT_IN) {
        newRow(content, kind, source, location);
      }
    }
    content.layout(true, true);
  }

  private void row(Composite content, Extension item) {
    Composite row = new Composite(content, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginHeight = 0;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    Button toggle = new Button(row, SWT.CHECK);
    String description = item.description().isBlank() ? "" : " — " + shorten(item.description());
    toggle.setText((item.kind() == Kind.WORKFLOW ? "/" : "") + item.name() + description);
    toggle.setToolTipText(item.file() == null ? "Built into the plugin" : item.file().toString());
    toggle.setSelection(item.enabled());
    toggle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    toggle.addListener(SWT.Selection, e -> setEnabled(item, toggle.getSelection()));
    Link actions = new Link(row, SWT.NONE);
    actions.setText(item.file() == null ? "" : "<a href=\"open\">Open</a>   <a href=\"delete\">Delete</a>");
    actions.addListener(SWT.Selection, e -> {
      if ("open".equals(e.text)) {
        open(item.file());
      } else if ("delete".equals(e.text)) {
        delete(item, content);
      }
    });
  }

  private void newRow(Composite content, Kind kind, Source source, Path location) {
    List<Path> folders = Extensions.folders(kind, source, location);
    if (folders.isEmpty()) {
      return;
    }
    Composite row = new Composite(content, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginHeight = 0;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    Text name = new Text(row, SWT.BORDER);
    name.setMessage("name, e.g. " + example(kind));
    GridData nameData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    nameData.widthHint = 200;
    name.setLayoutData(nameData);
    Button add = new Button(row, SWT.PUSH);
    add.setText("+ New " + kind.noun);
    Label where = new Label(row, SWT.NONE);
    where.setText("in " + folders.get(0));
    add.addListener(SWT.Selection, e -> create(kind, source, folders.get(0), name.getText().strip(), content));
  }

  private void create(Kind kind, Source source, Path folder, String name, Composite content) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (!NAME.matcher(lower).matches()) {
      setErrorMessage("Use lowercase letters, digits and hyphens for the name, e.g. " + example(kind) + ".");
      return;
    }
    setErrorMessage(null);
    Path file = kind == Kind.SKILL ? folder.resolve(lower).resolve(Extensions.SKILL_FILE)
        : folder.resolve(lower + ".md");
    if (Files.exists(file)) {
      setErrorMessage(file + " already exists.");
      return;
    }
    try {
      write(source, file, template(kind, lower));
      fill(content, kind);
      open(file);
    } catch (Exception e) {
      setErrorMessage("Could not create " + file + ": " + Errors.describe(e));
    }
  }

  private void setEnabled(Extension item, boolean on) {
    try {
      if (item.source() == Source.BUILT_IN) {
        AiSettings.saveBuiltInEnabled(Extensions.builtInKey(item.kind(), item.name()), on);
        return;
      }
      String text = Files.readString(item.file(), StandardCharsets.UTF_8);
      write(item.source(), item.file(), Frontmatter.withDisabled(text, !on));
    } catch (Exception e) {
      setErrorMessage("Could not change " + item.name() + ": " + Errors.describe(e));
    }
  }

  private void delete(Extension item, Composite content) {
    Path target = item.kind() == Kind.SKILL ? item.folder() : item.file();
    String what = item.kind() == Kind.SKILL ? "the skill folder " + target : target.toString();
    if (!MessageDialog.openConfirm(getShell(), "Delete " + item.kind().noun, "Delete " + what + "?")) {
      return;
    }
    try {
      IResource resource = workspaceResource(item.source(), target);
      if (resource != null && resource.exists()) {
        resource.delete(true, null);
      } else {
        try (Stream<Path> walk = Files.walk(target)) {
          for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
      }
      fill(content, item.kind());
    } catch (Exception e) {
      setErrorMessage("Could not delete " + target + ": " + Errors.describe(e));
    }
  }

  // Project files go through the workspace, so the Project Explorer and git see them at once.
  private void write(Source source, Path file, String text) throws Exception {
    IProject resource = source == Source.PROJECT && project != null ? project.resource() : null;
    Path root = project == null ? null : project.location();
    if (resource != null && root != null && file.startsWith(root)) {
      WorkspaceWrites.write(resource, root.relativize(file).toString().replace('\\', '/'), text);
      return;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private IResource workspaceResource(Source source, Path path) {
    Path root = project == null ? null : project.location();
    if (source != Source.PROJECT || root == null || !path.startsWith(root)) {
      return null;
    }
    String relative = root.relativize(path).toString().replace('\\', '/');
    return Files.isDirectory(path) ? project.resource().getFolder(relative) : project.resource().getFile(relative);
  }

  private void open(Path file) {
    IWorkbenchPage page = activePage();
    if (page == null || file == null) {
      return;
    }
    try {
      IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toUri()));
    } catch (Exception e) {
      setErrorMessage("Could not open " + file + ": " + Errors.describe(e));
    }
  }

  // Creates the file with every event empty and two examples when it does not exist yet.
  private void openHooksFile() {
    Path file = Hooks.userFile();
    try {
      if (!Files.exists(file)) {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Hooks.template());
      }
      open(file);
    } catch (IOException e) {
      setErrorMessage("Could not open " + file + ": " + Errors.describe(e));
    }
  }

  static String template(Kind kind, String name) {
    return switch (kind) {
      case RULE -> "# " + name + "\n\nOne rule per line. The AI follows these in every chat"
          + " (turn the file off here to pause it).\n\n- Example: name flows in kebab-case, ending in -flow.\n";
      case SKILL -> "---\nname: " + name + "\ndescription: Use when ... (the AI reads this line to decide when"
          + " to load the skill)\n---\n\n# " + name + "\n\n1. First step.\n2. Next step.\n\nPut longer reference"
          + " material in files next to this one, e.g. references/notes.md; the AI reads them only when needed.\n";
      case WORKFLOW -> "---\ndescription: What this workflow does\n---\n\nRun it by typing /" + name
          + " in the chat, optionally followed by more detail.\n\n1. First step.\n2. Next step.\n";
    };
  }

  private static String title(Kind kind) {
    return switch (kind) {
      case RULE -> "Rules";
      case SKILL -> "Skills";
      case WORKFLOW -> "Workflows";
    };
  }

  private static String help(Kind kind) {
    return switch (kind) {
      case RULE -> "Markdown or text files sent with every message, after AGENTS.md and your instructions. Up to "
          + String.format(Locale.ROOT, "%,d", Prompts.MAX_RULES)
          + " characters in all. A project file wins over a global one with the same name.";
      case SKILL -> "A folder with a SKILL.md (name and description in its frontmatter) and any reference files."
          + " The AI sees each skill's description and loads the full text with use_skill when a task matches."
          + " Skills in .agents/skills, which other agents read too, are included.";
      case WORKFLOW -> "Markdown files you run by typing /name at the start of a message; the file's steps are"
          + " sent with it. /skill-name asks the AI to use that skill.";
    };
  }

  private static String heading(Source source) {
    return switch (source) {
      case BUILT_IN -> "Built-in";
      case GLOBAL -> "Global (yours, every project)";
      case PROJECT -> "Project (shared through git)";
    };
  }

  private static String example(Kind kind) {
    return switch (kind) {
      case RULE -> "naming";
      case SKILL -> "salesforce-sync";
      case WORKFLOW -> "release-check";
    };
  }

  private static String shorten(String text) {
    String flat = text.replaceAll("\\s+", " ").strip();
    return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
  }

  private static void wrapped(Composite parent, String text) {
    Label label = new Label(parent, SWT.WRAP);
    label.setText(text);
    label.setLayoutData(wide());
  }

  private static GridData wide() {
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 460;
    return data;
  }

  private static IWorkbenchPage activePage() {
    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    return window == null ? null : window.getActivePage();
  }
}
