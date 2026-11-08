import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mulesoftforge.studio.ai.extend.Extension;
import com.mulesoftforge.studio.ai.extend.Extension.Kind;
import com.mulesoftforge.studio.ai.extend.Extension.Source;
import com.mulesoftforge.studio.ai.extend.Extensions;
import com.mulesoftforge.studio.ai.extend.Frontmatter;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.Memory;
import com.mulesoftforge.studio.ai.llm.Progress;
import com.mulesoftforge.studio.ai.llm.Prompts;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

// check-extend: rules, skills and workflows from the built-in, global and project levels. Frontmatter and
// disabled files, which level wins a name, the RULES and SKILLS blocks in the system prompt, use_skill with its
// folder sandbox and masking, and /workflow and /skill at the start of a message.
final class ExtendChecks {
  private ExtendChecks() {}

  static void run() throws Exception {
    Path home = Files.createTempDirectory("studio-ai-home");
    Path project = Files.createTempDirectory("studio-ai-extend");
    Extensions.overrideHome(home);
    Memory.overrideUserFile(home.resolve("memory.md"));
    Files.createDirectories(project.resolve("src/main/mule"));
    Files.writeString(project.resolve("src/main/mule/api.xml"), "<mule>\n  <flow name=\"orders\"/>\n</mule>\n");
    int failed = 0;
    failed += frontmatter();
    failed += empty(project);
    layout(home, project);
    failed += loading(home, project);
    failed += prompt(project);
    failed += inTurn(project);
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int frontmatter() {
    int failed = 0;
    Frontmatter parsed = Frontmatter.parse("---\nname: x\ndescription: \"Does x\"\ndisabled: true\n---\n# Body\n");
    failed += check("frontmatter gives its keys and the body after it", parsed.get("description").equals("Does x")
        && parsed.disabled() && parsed.body().equals("# Body\n"));
    failed += check("a file without frontmatter is all body", Frontmatter.parse("just text").body().equals("just text")
        && !Frontmatter.parse("just text").disabled());
    String off = Frontmatter.withDisabled("# Rule\n", true);
    failed += check("turning a plain file off adds disabled: true", off.equals("---\ndisabled: true\n---\n# Rule\n"));
    failed += check("turning it on again gives back the original text", Frontmatter.withDisabled(off, false)
        .equals("# Rule\n"));
    String kept = Frontmatter.withDisabled("---\ndescription: d\n---\nbody", true);
    failed += check("other frontmatter keys are kept", kept.equals("---\ndescription: d\ndisabled: true\n---\nbody")
        && Frontmatter.withDisabled(kept, false).equals("---\ndescription: d\n---\nbody"));
    return failed;
  }

  // Before the user adds anything: only the built-in skills, and no RULES block.
  private static int empty(Path project) {
    int failed = 0;
    Extensions none = Extensions.load(project, Set.of());
    failed += check("with no files only the built-in skills load", none.enabled(Kind.RULE).isEmpty()
        && none.enabled(Kind.WORKFLOW).isEmpty()
        && none.enabled(Kind.SKILL).stream().map(Extension::name).toList().equals(Extensions.BUILT_IN_SKILLS)
        && none.rulesText().isEmpty());
    failed += check("built-in skills have descriptions and no license comment in their text",
        none.enabled(Kind.SKILL).stream().allMatch(s -> !s.description().isBlank() && !s.body().contains("<!--")
            && s.source() == Source.BUILT_IN));
    Extensions off = Extensions.load(project, Set.of("skill:build-mule-integration", "skill:generate-doc-description"));
    failed += check("built-in skills can be turned off", off.enabled(Kind.SKILL).isEmpty()
        && off.skillsCatalog().isEmpty() && off.summary() == null);
    return failed;
  }

  private static void layout(Path home, Path project) throws Exception {
    Path global = home.resolve(".mulesoftforge/studio-ai");
    write(global.resolve("rules/naming.md"), "Name flows in kebab-case.\n");
    write(global.resolve("rules/team.md"), "Global team rule.\n");
    write(global.resolve("rules/paused.md"), "---\ndisabled: true\n---\nShould not be sent.\n");
    write(global.resolve("rules/notes.json"), "{\"not\":\"a rule\"}");
    write(project.resolve(".mulesoftforge/rules/team.txt"), "Project team rule. db.password=hunter2secret\n");
    write(global.resolve("skills/salesforce-sync/SKILL.md"),
        "---\nname: salesforce-sync\ndescription: Global version\n---\nGlobal steps.\n");
    write(project.resolve(".mulesoftforge/skills/salesforce-sync/SKILL.md"),
        "---\ndescription: Sync Salesforce accounts to the warehouse\n---\n1. Read the account flow.\n");
    write(project.resolve(".mulesoftforge/skills/salesforce-sync/references/fields.md"), "Account.Name maps to name\n"
        + "api.token=abcdef123456secret\n");
    write(project.resolve(".mulesoftforge/skills/build-mule-integration/SKILL.md"),
        "---\ndescription: Our own build steps\n---\nProject build steps.\n");
    write(home.resolve(".agents/skills/shared-skill/SKILL.md"), "---\ndescription: From .agents\n---\nShared.\n");
    write(project.resolve(".mulesoftforge/workflows/release-check.md"),
        "---\ndescription: Check the app before a release\n---\n1. Run check_project.\n2. List open TODOs.\n");
    write(home.resolve("outside.md"), "outside the skill folder\n");
  }

  private static int loading(Path home, Path project) {
    int failed = 0;
    Extensions loaded = Extensions.load(project, Set.of());
    List<String> rules = loaded.enabled(Kind.RULE).stream().map(r -> r.name() + ":" + r.source().label).toList();
    failed += check("rules: .md and .txt load, a disabled file and other types do not, project wins a name",
        rules.equals(List.of("team:project", "naming:global")));
    Extension sync = loaded.find(Kind.SKILL, "salesforce-sync");
    failed += check("a project skill wins over a global one with the same name", sync != null
        && sync.source() == Source.PROJECT && sync.description().equals("Sync Salesforce accounts to the warehouse"));
    Extension build = loaded.find(Kind.SKILL, "build-mule-integration");
    failed += check("a project skill replaces a built-in one with the same name", build != null
        && build.source() == Source.PROJECT && build.body().contains("Project build steps"));
    failed += check("~/.agents/skills is read as a global level", loaded.find(Kind.SKILL, "shared-skill") != null
        && loaded.find(Kind.SKILL, "shared-skill").source() == Source.GLOBAL);
    failed += check("workflows load from .mulesoftforge/workflows", loaded.find(Kind.WORKFLOW, "release-check") != null);
    failed += check("Preferences lists every file at a level, off ones too", Extensions.list(Kind.RULE, Source.GLOBAL,
        project, Set.of()).stream().map(Extension::name).toList().equals(List.of("naming", "paused", "team")));
    failed += check("the summary names the rule files and counts the skills", "2 rule files (team, naming) and 4 skills"
        .equals(loaded.summary()));
    return failed;
  }

  private static int prompt(Path project) {
    int failed = 0;
    Extensions loaded = Extensions.load(project, Set.of());
    Prompts.SystemParts with = Prompts.systemParts(ChatMode.AGENT, "p", "", "", "", false, false, false, false, false,
        "", loaded.rulesText(), loaded.skillsCatalog());
    String shared = with.shared();
    failed += check("RULES has one heading per file and comes under the user-instructions preamble",
        shared.contains("\nRULES (") && shared.contains("## team (project)\nProject team rule.")
            && shared.contains("## naming (global)\nName flows in kebab-case.")
            && shared.indexOf("The instructions below come from the user") < shared.indexOf("\nRULES ("));
    failed += check("SKILLS lists each skill with its description and tells the model to check it",
        shared.contains("- salesforce-sync (project): Sync Salesforce accounts to the warehouse")
            && shared.contains("- generate-doc-description (built-in): ")
            && shared.contains("call use_skill with its name first") && !shared.contains("Project build steps"));
    failed += check("rules and skills are in the cached part, not the per-request part",
        !with.perRequest().contains("RULES (") && !with.perRequest().contains("SKILLS ("));
    Prompts.SystemParts without = Prompts.systemParts(ChatMode.AGENT, "p", "", "", "", false, false, false, false,
        false, "");
    failed += check("without rules or skills the prompt has neither block nor the preamble",
        !without.shared().contains("RULES (") && !without.shared().contains("SKILLS (")
            && !without.shared().contains("The instructions below come from the user"));
    String big = "x".repeat(Prompts.MAX_RULES + 500);
    Prompts.Capped capped = Prompts.capped(big, Prompts.MAX_RULES);
    failed += check("rules are capped at " + Prompts.MAX_RULES + " characters in all", capped.cut()
        && capped.chars() == big.length() && capped.text().startsWith("x".repeat(Prompts.MAX_RULES) + "\n[cut at"));
    return failed;
  }

  private static int inTurn(Path project) throws Exception {
    int failed = 0;
    Harness.DiskProject files = new Harness.DiskProject(project);
    Extensions loaded = Extensions.load(project, Set.of());

    ChatSession session = new ChatSession();
    session.extensions(loaded);
    List<String> steps = new CopyOnWriteArrayList<>();
    Scripted model = new Scripted(List.of(
        calls(tool("use_skill", "{\"name\":\"salesforce-sync\"}")),
        calls(tool("use_skill", "{\"name\":\"salesforce-sync\",\"file\":\"references/fields.md\"}")),
        calls(tool("use_skill", "{\"name\":\"salesforce-sync\",\"file\":\"../../../../outside.md\"}")),
        calls(tool("use_skill", "{\"name\":\"no-such-skill\"}")),
        answer("Done.")));
    ChatSession.Turn turn = session.send(model, ChatMode.ASK, "sync the accounts", List.of(), files, recorder(steps));
    ChatRequest first = model.requests.get(0);
    String system = systemText(first);
    failed += check("the system prompt carries the rules, masked", system.contains("## team (project)")
        && !system.contains("hunter2secret"));
    failed += check("use_skill is offered when skills exist", first.toolSpecifications().stream()
        .anyMatch(s -> s.name().equals("use_skill")));
    List<ToolExecutionResultMessage> results = results(model.requests.get(model.requests.size() - 1));
    failed += check("use_skill returns the skill's text and its reference files", results.get(0).text()
        .startsWith("SKILL salesforce-sync (project)") && results.get(0).text().contains("1. Read the account flow.")
        && results.get(0).text().contains("- references/fields.md"));
    failed += check("a reference file is returned masked", results.get(1).text().contains("Account.Name maps to name")
        && !results.get(1).text().contains("abcdef123456secret"));
    failed += check("a path outside the skill's folder is refused", results.get(2).text().startsWith("ERROR:")
        && !results.get(2).text().contains("outside the skill folder"));
    failed += check("an unknown skill lists the available ones", results.get(3).text().startsWith("ERROR: there is no"
        + " skill named \"no-such-skill\"") && results.get(3).text().contains("salesforce-sync"));
    failed += check("the first step says which rules and skills are followed", steps.stream()
        .anyMatch(s -> s.startsWith("Following 2 rule files (team, naming) and 4 skills")));
    failed += check("the steps say a skill was loaded and the activity counts it", steps.contains(
        "Loading the skill salesforce-sync") && turn.activity().contains("loaded skills 4 times"));

    session = new ChatSession();
    session.extensions(loaded);
    steps.clear();
    model = new Scripted(List.of(answer("Checked.")));
    session.send(model, ChatMode.ASK, "/release-check before Friday", List.of(), files, recorder(steps));
    String sent = lastUser(model.requests.get(0));
    failed += check("/workflow sends the workflow's steps with the message", sent.contains(
        "Workflow 'release-check' (see below for its steps)\n\nbefore Friday")
        && sent.contains("<command name=\"release-check\">\nDescription: Check the app before a release\n\n1. Run"
            + " check_project.") && sent.contains("</command>"));
    failed += check("the user sees which workflow ran", steps.contains("Running the project workflow release-check"));

    session = new ChatSession();
    session.extensions(loaded);
    model = new Scripted(List.of(answer("Ok.")));
    session.send(model, ChatMode.ASK, "/generate-doc-description", List.of(), files, Progress.NONE);
    failed += check("/skill asks the model to load that skill", lastUser(model.requests.get(0))
        .contains("call use_skill with name \"generate-doc-description\" first"));

    session = new ChatSession();
    session.extensions(loaded);
    model = new Scripted(List.of(answer("Ok.")));
    session.send(model, ChatMode.ASK, "/nothing-here please", List.of(), files, Progress.NONE);
    failed += check("an unknown /name is sent as typed", lastUser(model.requests.get(0)).endsWith(
        "/nothing-here please"));

    session = new ChatSession();
    Extensions.overrideHome(Files.createTempDirectory("studio-ai-empty-home"));
    session.extensions(Extensions.load(null, Set.of("skill:build-mule-integration", "skill:generate-doc-description")));
    model = new Scripted(List.of(answer("Ok.")));
    session.send(model, ChatMode.ASK, "hello", List.of(), files, Progress.NONE);
    failed += check("without skills use_skill is not offered and no SKILLS block is sent", model.requests.get(0)
        .toolSpecifications().stream().noneMatch(s -> s.name().equals("use_skill"))
        && !systemText(model.requests.get(0)).contains("SKILLS ("));
    return failed;
  }

  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static String systemText(ChatRequest request) {
    return request.messages().stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
        .map(SystemMessage::text).findFirst().orElse("");
  }

  private static Progress recorder(List<String> steps) {
    return new Progress() {
      @Override
      public void step(String message) {
        steps.add(message);
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }

  static final class Scripted implements ChatModel {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final Iterator<ChatResponse> replies;

    Scripted(List<ChatResponse> replies) {
      this.replies = new ArrayList<>(replies).iterator();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
      requests.add(request);
      return replies.hasNext() ? replies.next() : answer("(script ended)");
    }
  }

  static ToolExecutionRequest tool(String name, String arguments) {
    return ToolExecutionRequest.builder().id("call_" + System.nanoTime()).name(name).arguments(arguments).build();
  }

  static ChatResponse calls(ToolExecutionRequest call) {
    return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).tokenUsage(new TokenUsage(100, 10)).build();
  }

  static ChatResponse answer(String text) {
    return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(100, 10)).build();
  }

  static List<ToolExecutionResultMessage> results(ChatRequest request) {
    return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).toList();
  }

  static List<String> users(ChatRequest request) {
    List<String> texts = new ArrayList<>();
    request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .forEach(u -> texts.add(u.hasSingleText() ? u.singleText() : u.contents().toString()));
    return texts;
  }

  static String lastUser(ChatRequest request) {
    List<String> texts = users(request);
    return texts.isEmpty() ? "" : texts.get(texts.size() - 1);
  }

  static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
