package com.mulesoftforge.studio.ai.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

// What one connector version offers, reduced from the extension model Studio generates: the components
// by XML element name, how each parameter is written in XML, and the error types.
public record ConnectorModel(String name, String version, String prefix, String namespace,
    Map<String, Component> components, Set<String> errors) {

  public enum Kind {
    CONFIG("config"),
    CONNECTION("connection"),
    OPERATION("operation"),
    SOURCE("source"),
    CONSTRUCT("scope or router");

    public final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  // config: the config element an operation, source or connection belongs to, or null.
  public record Component(Kind kind, String element, String description, String config,
      List<Parameter> parameters, List<String> errors) {}

  // form: how it is written in XML, e.g. "attribute" or "child element <db:sql>".
  // attribute: whether it may appear as an attribute of the component element itself.
  public record Parameter(String name, String form, boolean attribute, boolean infrastructure, boolean required,
      String defaultValue, boolean expressions, List<String> values, String description) {}

  private static final Set<String> SIMPLE_TYPES = Set.of("String", "Boolean", "Number", "DateTime", "Date", "Time",
      "LocalDateTime", "LocalDate", "LocalTime");
  private static final int DESCRIPTION_LIMIT = 160;

  public String label() {
    return name + " " + version;
  }

  // Error namespaces this connector defines, e.g. DB for DB:CONNECTIVITY; MULE is not one of them.
  public Set<String> errorNamespaces() {
    Set<String> out = new LinkedHashSet<>();
    for (String error : errors) {
      String namespace = error.substring(0, Math.max(0, error.indexOf(':')));
      if (!namespace.isEmpty() && !namespace.equals("MULE")) {
        out.add(namespace);
      }
    }
    return out;
  }

  static ConnectorModel parse(JsonNode root) {
    JsonNode model = root.has("extensionModel") ? root.get("extensionModel") : root;
    JsonNode dsl = model.path("xmlDsl");
    String prefix = dsl.path("prefix").asText();
    Map<String, Component> components = new LinkedHashMap<>();
    for (JsonNode config : model.path("configurations")) {
      String element = suffixed(config.path("name").asText(), "config");
      add(components, component(Kind.CONFIG, element, config, null, prefix));
      for (JsonNode provider : config.path("connectionProviders")) {
        add(components, component(Kind.CONNECTION, suffixed(provider.path("name").asText(), "connection"),
            provider, element, prefix));
      }
      addAll(components, config.path("operations"), Kind.OPERATION, element, prefix);
      addAll(components, config.path("messageSources"), Kind.SOURCE, element, prefix);
    }
    for (JsonNode provider : model.path("connectionProviders")) {
      add(components, component(Kind.CONNECTION, suffixed(provider.path("name").asText(), "connection"),
          provider, null, prefix));
    }
    addAll(components, model.path("operations"), Kind.OPERATION, null, prefix);
    addAll(components, model.path("messageSources"), Kind.SOURCE, null, prefix);
    addAll(components, model.path("constructs"), Kind.CONSTRUCT, null, prefix);
    Set<String> errors = new LinkedHashSet<>();
    for (JsonNode error : model.path("errors")) {
      errors.add(error.path("error").asText());
    }
    return new ConnectorModel(model.path("name").asText(), model.path("version").asText(), prefix,
        dsl.path("namespace").asText(), components, errors);
  }

  private static void addAll(Map<String, Component> components, JsonNode nodes, Kind kind, String config,
      String prefix) {
    for (JsonNode node : nodes) {
      add(components, component(kind, hyphenize(node.path("name").asText()), node, config, prefix));
    }
  }

  private static void add(Map<String, Component> components, Component component) {
    components.putIfAbsent(component.element(), component);
  }

  private static Component component(Kind kind, String element, JsonNode node, String config, String prefix) {
    Map<String, Parameter> parameters = new LinkedHashMap<>();
    // Sources also take the parameters of their success and error callbacks, such as an HTTP listener's
    // response or a file listener's post-processing action.
    for (JsonNode owner : List.of(node, node.path("successCallback"), node.path("errorCallback"))) {
      for (JsonNode group : owner.path("parameterGroupModels")) {
        String groupElement = group.path("showInDsl").asBoolean()
            ? hyphenize(group.path("name").asText().replace(" ", "")) : null;
        for (JsonNode parameter : group.path("parameters")) {
          parameters.putIfAbsent(groupElement + ":" + parameter.path("name").asText(),
              parameter(parameter, groupElement, prefix));
        }
      }
    }
    List<String> errors = new ArrayList<>();
    node.path("errors").forEach(error -> errors.add(error.asText()));
    return new Component(kind, element, firstSentence(node.path("description").asText()), config,
        new ArrayList<>(parameters.values()), errors);
  }

  // Mirrors how Mule writes extension parameters in XML: simple values are attributes, content and
  // complex values are child elements, and a group shown in the DSL wraps its parameters in one element.
  private static Parameter parameter(JsonNode node, String groupElement, String prefix) {
    String name = node.path("name").asText();
    JsonNode type = node.path("type");
    boolean infrastructure = node.path("modelProperties")
        .has("org.mule.runtime.extension.api.property.InfrastructureParameterModelProperty");
    String role = node.path("role").asText();
    boolean content = role.equals("CONTENT") || role.equals("PRIMARY_CONTENT")
        || node.path("layoutModel").path("text").asBoolean();
    boolean simple = SIMPLE_TYPES.contains(type.path("type").asText()) || type.path("annotations").has("enum");
    boolean references = node.path("dslConfiguration").path("allowsReferences").asBoolean();
    String child = "child element <" + prefix + ":" + hyphenize(name) + ">";
    String form;
    boolean attribute;
    if (name.equals("config-ref")) {
      form = "attribute";
      attribute = true;
    } else if (groupElement != null) {
      form = "inside child element <" + prefix + ":" + groupElement + ">";
      attribute = false;
    } else if (infrastructure) {
      form = "standard Mule element";
      attribute = false;
    } else if (content) {
      form = child;
      attribute = false;
    } else if (simple) {
      form = "attribute";
      attribute = true;
    } else if (references) {
      form = "attribute naming a global element, or " + child;
      attribute = true;
    } else {
      form = child;
      attribute = false;
    }
    List<String> values = new ArrayList<>();
    type.path("annotations").path("enum").path("values").forEach(value -> values.add(value.asText()));
    JsonNode defaultValue = node.get("defaultValue");
    return new Parameter(name, form, attribute, infrastructure, node.path("required").asBoolean(),
        defaultValue == null || defaultValue.isNull() ? null : defaultValue.asText(),
        !node.path("expressionSupport").asText().equals("NOT_SUPPORTED"), values,
        firstSentence(node.path("description").asText()));
  }

  // Mule's XML element names: bulkInsert -> bulk-insert, payrollCAN -> payroll-can, AGENT-chat -> agent-chat;
  // underscores stay (Exchange-generated connectors use them).
  public static String hyphenize(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      char last = out.length() == 0 ? '-' : out.charAt(out.length() - 1);
      if (Character.isUpperCase(c) && i > 0 && last != '-' && last != '_') {
        char previous = name.charAt(i - 1);
        boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
        if (Character.isLowerCase(previous) || Character.isDigit(previous)
            || (Character.isUpperCase(previous) && nextLower)) {
          out.append('-');
        }
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString().toLowerCase(Locale.ROOT);
  }

  // Configs and connections end in -config and -connection unless the name already does: imap -> imap-config.
  private static String suffixed(String name, String suffix) {
    String element = hyphenize(name);
    return element.equals(suffix) || element.endsWith("-" + suffix) ? element : element + "-" + suffix;
  }

  private static String firstSentence(String text) {
    String clean = text == null ? "" : text.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    int end = clean.indexOf(". ");
    String sentence = end > 0 ? clean.substring(0, end + 1) : clean;
    return sentence.length() > DESCRIPTION_LIMIT ? sentence.substring(0, DESCRIPTION_LIMIT - 1) + "…" : sentence;
  }
}
