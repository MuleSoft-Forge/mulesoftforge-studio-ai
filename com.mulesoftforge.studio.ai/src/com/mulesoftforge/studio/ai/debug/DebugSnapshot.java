package com.mulesoftforge.studio.ai.debug;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A stopped event as text for the model: where it stopped, the error if there is one, then the payload, attributes,
// variables and the processor's settings. Java noise Studio includes (static fields, values it could not read,
// internal objects) is left out, and long values and wide or deep trees are cut so one stop fits in a message.
// Paths in [brackets] are DataWeave accessors the model can evaluate or expand.
public final class DebugSnapshot {
  public static final int MAX_CHARS = 30_000;
  private static final int MAX_VALUE = 4_000;
  private static final int MAX_PAYLOAD = 12_000;
  private static final int MAX_XML = 6_000;
  private static final int MAX_CHILDREN = 40;
  private static final int MAX_DEPTH = 3;
  static final String INACCESSIBLE = "Field value is inaccessible and could not obtain it throught any public method.";
  private static final Pattern NOT_ACCESSIBLE = Pattern.compile(
      "\\s*\\[Not accessible\\. Value obtained from method: [^\\]]*\\]\\s*$");
  private static final Pattern IDENTITY = Pattern.compile("^[\\w.$]+@[0-9a-f]{4,}$");
  private static final Pattern SIZE = Pattern.compile("^size = (\\d+)$");
  private static final Set<String> NOISE = Set.of("serialVersionUID", "lazyClientCertificateProvider", "TAB",
      "DOUBLE_TAB", "^mediaType");
  // Shown in their own lines, or noise when Studio could not read them.
  // DataWeave errors end with the whole script: "…" evaluating expression: "%dw 2.0 …".
  private static final String EVALUATING = "\" evaluating expression: \"";
  private static final Set<String> ERROR_SHOWN = Set.of("errorType", "failingComponent", "description", "dslSource",
      "detailedDescription", "exception", "muleMessage", "errors", "suppressedErrors");

  // title names the stop for labels, e.g. transform "Transform Message" in get:\employees:api-config.
  public record Snapshot(String title, String text, boolean failed, boolean truncated) {}

  private DebugSnapshot() {}

  // personal is null to leave email addresses and phone numbers as they are.
  public static Snapshot render(DebugStop stop, PersonalData personal) {
    return render(stop, personal, null);
  }

  // source is the processor's XML found in the project, or null. An error that carries the failing XML already
  // shows that instead.
  public static Snapshot render(DebugStop stop, PersonalData personal, ProcessorSource.Located source) {
    StringBuilder out = new StringBuilder();
    String processor = processorLabel(stop);
    out.append("STOPPED AT: ").append(processor).append('\n');
    out.append("Flow: ").append(stop.flowName()).append(" (processor path ").append(stop.processorPath())
        .append(")\n");
    out.append("App: ").append(stop.app());
    if (stop.correlationId() != null) {
      out.append(" · correlation ID ").append(stop.correlationId());
    }
    out.append('\n');
    DebugField error = stop.error();
    if (source != null && (error == null || value(error.child("dslSource")) == null)) {
      out.append("\nXML OF THE PROCESSOR (").append(source.path()).append(" line ").append(source.line())
          .append(source.truncated() ? ", cut" : "").append("):\n");
      block(out, source.xml(), 1);
    }
    if (error != null) {
      error(out, error);
    }
    section(out, "PAYLOAD", stop.field("payload"), MAX_PAYLOAD);
    section(out, "ATTRIBUTES", stop.field("attributes"), MAX_VALUE);
    section(out, "VARIABLES", stop.field("vars"), MAX_VALUE);
    DebugField settings = stop.processorNode();
    if (settings != null) {
      section(out, "PROCESSOR SETTINGS, AS THE RUNTIME EVALUATED THEM", settings, MAX_VALUE);
    }
    String rootId = value(stop.field("rootId"));
    if (rootId != null && !rootId.equals(stop.correlationId())) {
      out.append("\nRoot ID: ").append(rootId).append('\n');
    }
    String text = out.toString();
    boolean truncated = text.length() > MAX_CHARS;
    if (truncated) {
      text = text.substring(0, MAX_CHARS) + "\n(cut here: the event is longer than one message allows; evaluate"
          + " specific values instead)";
    }
    if (personal != null) {
      text = personal.mask(text);
    }
    return new Snapshot(processor + " in " + stop.flowName(), text, error != null, truncated);
  }

  // One field on its own, as expand_field returns it.
  public static String field(DebugField field) {
    StringBuilder out = new StringBuilder();
    section(out, "FIELD " + field.name(), field, MAX_VALUE);
    return out.toString().strip();
  }

  public static String processorLabel(DebugStop stop) {
    String docName = stop.docName();
    String type = stop.processorType() == null ? "processor" : stop.processorType();
    return docName == null || docName.isBlank() ? type : type + " \"" + docName + "\"";
  }

  private static void error(StringBuilder out, DebugField error) {
    out.append("\nERROR: ").append(orDash(value(error.child("errorType")))).append('\n');
    String failing = value(error.child("failingComponent"));
    if (failing != null) {
      out.append("Failing component: ").append(failing).append('\n');
    }
    String description = value(error.child("description"));
    if (description == null) {
      description = value(error.child("detailedDescription"));
    }
    if (description == null) {
      description = value(error.child("exception"));
    }
    String xml = value(error.child("dslSource"));
    if (description != null && xml != null) {
      int script = description.indexOf(EVALUATING);
      if (script > 0) {
        description = description.substring(0, script + 1) + " (the script is in the XML below)";
      }
    }
    if (description != null) {
      out.append("Description:\n");
      block(out, cap(description, MAX_VALUE), 1);
    }
    for (DebugField child : visible(error)) {
      if (!ERROR_SHOWN.contains(child.name())) {
        line(out, child, 1, MAX_VALUE);
      }
    }
    if (xml != null) {
      out.append("XML of the failing component:\n");
      block(out, cap(xml, MAX_XML), 1);
    }
  }

  private static void section(StringBuilder out, String title, DebugField field, int maxValue) {
    if (field == null) {
      return;
    }
    out.append('\n').append(title).append(accessor(field)).append(summary(field)).append('\n');
    String value = value(field);
    List<DebugField> children = visible(field);
    boolean text = value != null && !SIZE.matcher(value).matches() && !isDump(value, field);
    if (text && value.isEmpty()) {
      out.append("  (empty)\n");
    } else if (text && (children.isEmpty() || isStream(field))) {
      block(out, cap(value, maxValue), 1);
    } else if (children.isEmpty()) {
      out.append(value == null ? "  (no value)\n" : "  " + value + "\n");
    } else {
      children(out, children, 1, maxValue);
    }
    if (field.unloaded()) {
      out.append("  (more not loaded: expand_field ").append(field.accessor()).append(")\n");
    }
  }

  private static void children(StringBuilder out, List<DebugField> children, int depth, int maxValue) {
    int shown = 0;
    for (DebugField child : children) {
      if (shown == MAX_CHILDREN) {
        out.append(indent(depth)).append("… ").append(children.size() - MAX_CHILDREN).append(" more\n");
        return;
      }
      line(out, child, depth, maxValue);
      shown++;
    }
  }

  private static void line(StringBuilder out, DebugField field, int depth, int maxValue) {
    DebugField entry = entry(field);
    if (entry != null) {
      field = entry;
    }
    String value = value(field);
    List<DebugField> children = visible(field);
    out.append(indent(depth)).append(field.name()).append(accessor(field));
    if (value != null && value.contains("\n") && !isDump(value, field)) {
      out.append(":\n");
      block(out, cap(value, maxValue), depth + 1);
    } else if (value != null && !value.isEmpty() && !isDump(value, field) && !SIZE.matcher(value).matches()) {
      out.append(" = ").append(cap(value, maxValue)).append('\n');
    } else {
      out.append(summary(field)).append(value != null && value.isEmpty() ? " (empty)" : "").append('\n');
    }
    if (field.unloaded()) {
      out.append(indent(depth + 1)).append("(not loaded: expand_field ").append(field.accessor()).append(")\n");
    } else if (!children.isEmpty()) {
      if (depth >= MAX_DEPTH) {
        out.append(indent(depth + 1)).append("(").append(children.size()).append(" fields not shown")
            .append(field.accessor() == null ? "" : "; evaluate " + field.accessor()).append(")\n");
      } else {
        children(out, children, depth + 1, maxValue);
      }
    }
  }

  // A map entry Studio shows as "0 = key=value" with key and value children, as the value named by its key.
  private static DebugField entry(DebugField field) {
    DebugField key = field.child("key");
    DebugField value = field.child("value");
    if (key == null || value == null || field.children().size() != 2 || key.value() == null) {
      return null;
    }
    return new DebugField(key.value(), value.type(), value.value(),
        field.accessor() != null ? field.accessor() : value.accessor(), false, value.unloaded(), value.error(),
        value.children());
  }

  // Children worth showing: no static fields, no Java internals, nothing Studio could not read.
  static List<DebugField> visible(DebugField field) {
    return field.children().stream()
        .filter(c -> !c.isStatic() && !NOISE.contains(c.name()) && !INACCESSIBLE.equals(c.value()))
        .toList();
  }

  // " — media type, Java type, N entries"
  private static String summary(DebugField field) {
    StringBuilder out = new StringBuilder();
    String mediaType = field.childValue("^mediaType");
    if (mediaType != null && !mediaType.isBlank()) {
      out.append(mediaType);
    }
    String type = shortType(field.type());
    if (type != null) {
      out.append(out.length() == 0 ? "" : ", ").append(type);
    }
    String value = field.value();
    Matcher size = value == null ? null : SIZE.matcher(value.strip());
    if (size != null && size.matches()) {
      out.append(out.length() == 0 ? "" : ", ").append(size.group(1)).append(" entries");
    }
    if (isStream(field) && !field.type().contains("CursorStreamProvider")) {
      out.append(", a stream that can be read only once: evaluating payload would consume it");
    }
    return out.length() == 0 ? "" : " — " + out;
  }

  private static boolean isStream(DebugField field) {
    return field.type() != null && (field.type().contains("CursorStreamProvider")
        || field.type().contains("InputStream"));
  }

  // Values that are an object's toString rather than data: "Class@1a2b", or a multi-line dump of a Java object whose
  // fields are listed as children anyway.
  private static boolean isDump(String value, DebugField field) {
    String v = value.strip();
    if (IDENTITY.matcher(v).matches()) {
      return true;
    }
    String type = field.type();
    return type != null && !type.isEmpty() && !visible(field).isEmpty() && (v.startsWith(type) || v.startsWith("\n"))
        && !isStream(field);
  }

  // Studio's value with "[Not accessible. Value obtained from method: …]" removed, or null when it has none it
  // could read.
  static String value(DebugField field) {
    if (field == null || field.value() == null || INACCESSIBLE.equals(field.value())) {
      return null;
    }
    return NOT_ACCESSIBLE.matcher(field.value()).replaceFirst("");
  }

  private static String accessor(DebugField field) {
    return field.accessor() == null || field.accessor().isBlank() ? "" : " [" + field.accessor() + "]";
  }

  private static String shortType(String type) {
    if (type == null || type.isBlank()) {
      return null;
    }
    String bare = type.substring(type.lastIndexOf('.') + 1);
    return bare.substring(bare.lastIndexOf('$') + 1);
  }

  private static void block(StringBuilder out, String text, int depth) {
    for (String line : text.strip().split("\n", -1)) {
      out.append(indent(depth)).append(line).append('\n');
    }
  }

  private static String cap(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + " … (cut, " + (text.length() - max)
        + " more characters)";
  }

  private static String indent(int depth) {
    return "  ".repeat(depth);
  }

  private static String orDash(String text) {
    return text == null || text.isBlank() ? "(type not reported)" : text;
  }
}
