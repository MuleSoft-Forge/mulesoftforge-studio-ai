package com.mulesoftforge.studio.ai.debug;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.llm.SecretMask;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

// The model's tools while Studio's debugger is stopped: run DataWeave against the live event, and load fields Studio
// has not sent yet. Read-only: scripts that could change something outside the expression are refused, and so is
// reading a payload stream that can only be read once. Results are masked like everything else sent to the model.
public final class DebugTools {
  public static final String EVALUATE = "evaluate_at_breakpoint";
  public static final String EXPAND = "expand_field";
  public static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int MAX_RESULT = 8_000;
  private static final int MAX_SCRIPT = 20_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final List<ToolSpecification> SPECIFICATIONS = List.of(
      ToolSpecification.builder()
          .name(EVALUATE)
          .description("Run a DataWeave expression or script against the live Mule event the debugger is stopped at,"
              + " in the app's own runtime, and get the result. Use it to check values (typeOf(payload),"
              + " payload.items[0], attributes.queryParams) and to test a DataWeave fix on this event before you"
              + " propose it. A bare expression is written as JSON; give a full script with an output directive for"
              + " another format. Read-only: scripts that read URLs, look up flows or call Java are refused.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("script", "DataWeave expression or script, e.g. typeOf(payload)")
              .required("script")
              .build())
          .build(),
      ToolSpecification.builder()
          .name(EXPAND)
          .description("Load the fields Studio has not sent yet for a field of the stopped event marked \"not"
              + " loaded\", by its DataWeave accessor, e.g. attributes.headers.")
          .parameters(JsonObjectSchema.builder()
              .addStringProperty("accessor", "DataWeave accessor shown in [brackets], e.g. attributes.headers")
              .required("accessor")
              .build())
          .build());

  private static final Pattern READ_URL = Pattern.compile("\\breadUrl\\s*\\(");
  private static final Pattern LOOKUP = Pattern.compile("\\blookup\\s*\\(");
  private static final Pattern JAVA = Pattern.compile("\\bjava!");
  private static final Pattern RUNTIME = Pattern.compile("\\bdw::Runtime\\b");
  private static final Pattern PAYLOAD = Pattern.compile("\\bpayload\\b");
  private static final Pattern HEADER = Pattern.compile("(?m)^\\s*---\\s*$|%dw\\s+\\d");

  private final DebugSession session;
  private final DebugStop asked;
  private final SecretMask secrets;
  private final PersonalData personal;
  private final Duration timeout;

  // personal is null to leave email addresses and phone numbers in results.
  public DebugTools(DebugSession session, SecretMask secrets, PersonalData personal) {
    this(session, secrets, personal, TIMEOUT);
  }

  public DebugTools(DebugSession session, SecretMask secrets, PersonalData personal, Duration timeout) {
    this.session = session;
    this.asked = session.current();
    this.secrets = secrets;
    this.personal = personal;
    this.timeout = timeout;
  }

  public static boolean handles(String tool) {
    return EVALUATE.equals(tool) || EXPAND.equals(tool);
  }

  public static String describe(ToolExecutionRequest request) {
    String argument = argument(request, EVALUATE.equals(request.name()) ? "script" : "accessor");
    String first = argument == null ? "" : argument.strip().lines().findFirst().orElse("");
    String shown = first.length() > 60 ? first.substring(0, 60) + "…" : first;
    return EVALUATE.equals(request.name()) ? "Evaluating at the breakpoint: " + shown : "Loading " + shown;
  }

  // Never throws: failures go back to the model as text.
  public String execute(ToolExecutionRequest request) {
    DebugStop stop = session.current();
    if (stop == null) {
      return "ERROR: the debugger is not stopped at a processor now, so there is no event to use. Ask the user to"
          + " stop at the breakpoint again and attach the event.";
    }
    try {
      return moved(stop) + (EVALUATE.equals(request.name()) ? evaluate(stop, argument(request, "script"))
          : EXPAND.equals(request.name()) ? expand(argument(request, "accessor"))
          : "ERROR: unknown tool " + request.name());
    } catch (TimeoutException e) {
      return "ERROR: no answer from the debugger after " + timeout.toSeconds() + " s. The app may have moved on;"
          + " ask the user whether it is still stopped.";
    } catch (Exception e) {
      return "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  // The user can step, or another event can stop, while the model is still working on the event it was given.
  private String moved(DebugStop stop) {
    if (asked == null || Objects.equals(asked.correlationId(), stop.correlationId())
        && Objects.equals(asked.processorPath(), stop.processorPath())) {
      return "";
    }
    return "NOTE: the debugger has moved since this message was sent. It is now stopped at "
        + DebugSnapshot.processorLabel(stop) + " in " + stop.flowName() + ", and this answer is from there.\n";
  }

  private String evaluate(DebugStop stop, String script) throws Exception {
    if (script == null || script.isBlank()) {
      return "ERROR: script is empty.";
    }
    String refused = refusal(stop, script);
    if (refused != null) {
      return "REFUSED: " + refused;
    }
    String sent = HEADER.matcher(script).find() ? script : "output application/json --- " + script.strip();
    DebugSession.Evaluation result = session.evaluate(sent, timeout);
    if (!result.ok()) {
      return mask("DATAWEAVE ERROR (" + result.millis() + " ms):\n" + cap(result.error()));
    }
    String type = result.mimeType() == null || result.mimeType().isBlank() ? "no media type" : result.mimeType();
    return mask("RESULT (" + type + ", " + result.millis() + " ms):\n" + cap(result.value()));
  }

  // Why the script may not run, or null when it may.
  static String refusal(DebugStop stop, String script) {
    if (script.length() > MAX_SCRIPT) {
      return "the script is longer than " + MAX_SCRIPT + " characters.";
    }
    if (READ_URL.matcher(script).find()) {
      return "readUrl reads from outside the event. Evaluations are read-only.";
    }
    if (LOOKUP.matcher(script).find()) {
      return "lookup runs another flow. Evaluations are read-only.";
    }
    if (JAVA.matcher(script).find()) {
      return "calling Java is not allowed in evaluations.";
    }
    if (RUNTIME.matcher(script).find()) {
      return "dw::Runtime functions are not allowed in evaluations.";
    }
    DebugField payload = stop.field("payload");
    if (payload != null && PAYLOAD.matcher(script).find() && payload.type() != null
        && payload.type().contains("InputStream") && !payload.type().contains("CursorStreamProvider")) {
      return "the payload is a stream that can be read only once, so reading it here would take it from the app."
          + " Use what the event already shows, or ask the user to make the stream repeatable.";
    }
    return null;
  }

  private String expand(String accessor) throws Exception {
    if (accessor == null || accessor.isBlank()) {
      return "ERROR: accessor is empty.";
    }
    DebugField field = session.expand(accessor.strip(), timeout);
    if (field == null) {
      return "ERROR: no field " + accessor + " in the event the debugger showed. Use an accessor shown in"
          + " [brackets], or evaluate_at_breakpoint.";
    }
    return mask(DebugSnapshot.field(field));
  }

  private String mask(String text) {
    String out = secrets.mask(null, text).text();
    return personal == null ? out : personal.mask(out);
  }

  private static String cap(String text) {
    if (text == null) {
      return "(no value)";
    }
    return text.length() <= MAX_RESULT ? text : text.substring(0, MAX_RESULT) + " … (cut, "
        + (text.length() - MAX_RESULT) + " more characters)";
  }

  private static String argument(ToolExecutionRequest request, String name) {
    try {
      JsonNode args = JSON.readTree(request.arguments() == null || request.arguments().isBlank() ? "{}"
          : request.arguments());
      JsonNode value = args.get(name);
      return value == null || value.isNull() ? null : value.asText();
    } catch (Exception e) {
      return null;
    }
  }
}
