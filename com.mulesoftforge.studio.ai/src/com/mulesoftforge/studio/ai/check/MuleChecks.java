package com.mulesoftforge.studio.ai.check;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.mulesoftforge.studio.ai.connector.ConnectorCatalog;
import com.mulesoftforge.studio.ai.connector.ConnectorModel;
import com.mulesoftforge.studio.ai.connector.ConnectorModel.Component;
import com.mulesoftforge.studio.ai.connector.ConnectorModel.Kind;
import com.mulesoftforge.studio.ai.edit.ChangeSet;
import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;

// Static checks for the mistakes that otherwise only show up when the app fails to deploy.
// They read the project text only: no Studio, no Maven, no network.
public final class MuleChecks {
  private static final String MULE_NS = "http://www.mulesoft.org/schema/mule/";
  private static final String CORE_NS = MULE_NS + "core";
  private static final String DOC_NS = MULE_NS + "documentation";
  private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
  private static final List<String> CONFIG_DIRS = List.of("src/main/mule", "src/test/munit");
  private static final List<String> RESOURCE_DIRS = List.of("src/main/resources", "src/test/resources");
  private static final int LIST_LIMIT = 2000;
  private static final int REPORT_LIMIT = 15;

  // Core elements whose <error-handler> must be their last child.
  private static final Set<String> HANDLER_OWNERS = Set.of("flow", "sub-flow", "try");

  // Shipped with the runtime or MUnit: no application dependency provides these namespaces.
  private static final Set<String> RUNTIME_NAMESPACES = Set.of("core", "ee/core", "documentation", "tls", "batch",
      "api-gateway", "munit", "munit-tools");
  // Namespaces whose connector artifactId does not contain the namespace name.
  private static final Map<String, String> ARTIFACT_HINTS = Map.of("os", "objectstore",
      "secure-properties", "secure-configuration-property");
  private static final Set<String> REFERENCE_ATTRIBUTES = Set.of("config-ref", "tlsContext", "objectStore",
      "cachingStrategy-ref", "defaultErrorHandler-ref", "listenerConfig");
  // Words too generic to identify a connector when matching a namespace to a pom.xml artifactId.
  private static final Set<String> GENERIC_WORDS = Set.of("mule", "mule4", "api", "connector", "module", "plugin");
  private static final Set<String> MULE_ERRORS = Set.of("ANY", "REDELIVERY_EXHAUSTED", "TRANSFORMATION",
      "EXPRESSION", "VALIDATION", "FATAL", "SECURITY", "CLIENT_SECURITY", "SERVER_SECURITY", "NOT_PERMITTED", "ROUTING", "CONNECTIVITY",
      "RETRY_EXHAUSTED", "TIMEOUT", "COMPOSITE_ROUTING", "DUPLICATE_MESSAGE", "STREAM_MAXIMUM_SIZE_EXCEEDED",
      "UNKNOWN", "SOURCE", "SOURCE_RESPONSE", "SOURCE_RESPONSE_GENERATE", "SOURCE_RESPONSE_SEND",
      "SOURCE_ERROR_RESPONSE_GENERATE", "SOURCE_ERROR_RESPONSE_SEND", "CRITICAL", "OVERLOAD", "FLOW_BACK_PRESSURE",
      "FATAL_JVM_ERROR", "OUT_OF_MEMORY");
  // Usually supplied by the runtime or at deploy time rather than by a file in the project.
  private static final Set<String> RUNTIME_PROPERTIES = Set.of("env", "mule.env", "http.port", "https.port",
      "http.private.port", "https.private.port", "encryption.key", "secure.key");
  private static final List<String> RUNTIME_PROPERTY_PREFIXES = List.of("mule.", "app.", "anypoint.", "java.",
      "user.", "os.", "file.", "line.", "path.");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
  private static final Pattern YAML_KEY = Pattern.compile("^(?:\"([^\"]+)\"|'([^']+)'|([^\\s#'\"-][^:#]*?))\\s*:(?:\\s|$)");

  private MuleChecks() {}

  public static boolean watches(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return path.equals("pom.xml") || isConfig(path)
        || (under(path, RESOURCE_DIRS) && (lower.endsWith(".properties") || lower.endsWith(".yaml")
            || lower.endsWith(".yml")));
  }

  private static boolean isConfig(String path) {
    return path.toLowerCase(Locale.ROOT).endsWith(".xml") && under(path, CONFIG_DIRS);
  }

  private static boolean under(String path, List<String> dirs) {
    return dirs.stream().anyMatch(dir -> path.startsWith(dir + "/"));
  }

  // The checked files as they would be with every proposal accepted; null changes means as on disk.
  public static Map<String, String> snapshot(ProjectFiles files, ChangeSet changes) throws Exception {
    Map<String, String> out = new TreeMap<>();
    List<String> paths = new ArrayList<>(List.of("pom.xml"));
    for (String dir : Stream.concat(CONFIG_DIRS.stream(), RESOURCE_DIRS.stream()).toList()) {
      paths.addAll(files.list(dir, LIST_LIMIT));
    }
    for (String path : paths) {
      String content = watches(path) ? files.read(path) : null;
      if (content != null) {
        out.put(path, content);
      }
    }
    if (changes != null) {
      for (FileChange change : changes.all()) {
        if (watches(change.path()) && change.after() != null) {
          out.put(change.path(), change.after());
        }
      }
    }
    return out;
  }

  // Problems in after that were not in before, so a proposal is only held to what it changed.
  public static List<Problem> introduced(List<Problem> before, List<Problem> after) {
    Set<String> known = new HashSet<>();
    before.forEach(problem -> known.add(problem.key()));
    return after.stream().filter(problem -> !known.contains(problem.key())).toList();
  }

  public static String describe(List<Problem> problems) {
    StringBuilder out = new StringBuilder();
    problems.stream().limit(REPORT_LIMIT).forEach(problem -> out.append("- ").append(problem).append('\n'));
    if (problems.size() > REPORT_LIMIT) {
      out.append("- and ").append(problems.size() - REPORT_LIMIT).append(" more\n");
    }
    return out.toString();
  }

  public static List<Problem> check(Map<String, String> project) {
    return check(project, null);
  }

  // connectors: Studio's connector models, or null to skip the checks that need them.
  public static List<Problem> check(Map<String, String> project, ConnectorCatalog connectors) {
    Map<String, Problem> problems = new LinkedHashMap<>();
    List<Problem> found = new ArrayList<>();
    Pom pom = project.containsKey("pom.xml") ? Pom.parse(project.get("pom.xml"), found) : null;
    Map<String, ConnectorModel> byNamespace = new HashMap<>();
    Map<String, ConnectorModel> byErrorNamespace = new HashMap<>();
    if (connectors != null && pom != null) {
      for (ConnectorCatalog.Entry entry : connectors.forPom(project.get("pom.xml"))) {
        ConnectorModel model = entry.model();
        if (model != null) {
          byNamespace.putIfAbsent(model.namespace(), model);
          model.errorNamespaces().forEach(namespace -> byErrorNamespace.putIfAbsent(namespace, model));
        }
      }
    }

    Set<String> properties = new HashSet<>();
    Set<String> resources = new HashSet<>();
    List<ConfigFile> configs = new ArrayList<>();
    for (Map.Entry<String, String> file : project.entrySet()) {
      String path = file.getKey();
      if (isConfig(path)) {
        ConfigFile config = ConfigFile.parse(path, file.getValue(), found);
        if (config != null && "mule".equals(config.root)) {
          configs.add(config);
        }
      } else if (under(path, RESOURCE_DIRS)) {
        resources.add(path.substring(path.indexOf("/resources/") + "/resources/".length()));
        collectKeys(path, file.getValue(), properties);
      }
    }

    Set<String> globals = new HashSet<>();
    Set<String> flows = new HashSet<>();
    boolean imports = pom != null && pom.domain;
    for (ConfigFile config : configs) {
      globals.addAll(config.globals);
      flows.addAll(config.flows);
      properties.addAll(config.globalProperties);
      imports |= config.imports;
    }

    for (ConfigFile config : configs) {
      checkNamespaces(config, pom, found);
      checkErrorTypes(config, byErrorNamespace, found);
      checkComponents(config, byNamespace, found);
      found.addAll(config.structure);
      checkPlaceholders(config, properties, found);
      for (Use file : config.propertyFiles) {
        if (literal(file.value) && !resources.contains(file.value)) {
          found.add(new Problem(config.path, file.line, file.what + " file=\"" + file.value
              + "\" is not in src/main/resources."));
        }
      }
      // Global elements can live in an imported file or a shared domain that this project cannot see.
      if (!imports) {
        checkReferences(config, globals, flows, found);
      }
    }
    found.forEach(problem -> problems.putIfAbsent(problem.key(), problem));
    return new ArrayList<>(problems.values());
  }

  private static void checkNamespaces(ConfigFile config, Pom pom, List<Problem> found) {
    Map<String, String> locations = new LinkedHashMap<>();
    String[] parts = config.schemaLocation == null ? new String[0] : config.schemaLocation.trim().split("\\s+");
    for (int i = 0; i + 1 < parts.length; i += 2) {
      locations.put(parts[i], parts[i + 1]);
    }
    if (locations.containsKey(DOC_NS)) {
      found.add(new Problem(config.path, config.rootLine, "xsi:schemaLocation lists the doc namespace (" + DOC_NS
          + "). It has no schema; remove that pair."));
    }
    for (String uri : config.usedUris) {
      if (!uri.startsWith(MULE_NS) || uri.equals(DOC_NS)) {
        continue;
      }
      String name = uri.substring(MULE_NS.length()).replaceAll("/+$", "");
      List<String> schemas = schemaLocations(name);
      String location = locations.get(uri);
      if (location == null) {
        found.add(new Problem(config.path, config.rootLine, "namespace " + uri + " is used but missing from"
            + " xsi:schemaLocation. Add the pair: " + uri + " " + (schemas.isEmpty() ? "<schema URL>" : schemas.get(0))));
      } else if (!schemas.isEmpty() && !schemas.contains(location.replaceFirst("^https:", "http:"))) {
        found.add(new Problem(config.path, config.rootLine, "xsi:schemaLocation for " + uri + " is " + location
            + " but should be " + schemas.get(0)));
      }
      if (pom != null && !RUNTIME_NAMESPACES.contains(name) && !name.contains("/") && !pom.provides(name)) {
        found.add(new Problem(config.path, config.rootLine, "namespace \"" + name + "\" is used but pom.xml has no"
            + " dependency that looks like it provides it (no artifactId mentioning \""
            + String.join("\" or \"", words(name)) + "\")."
            + " Add the connector or module dependency with classifier mule-plugin; ask the user for the version"
            + " if you do not know it. If it comes from a parent POM or has another name, this can be ignored."));
      }
    }
  }

  // Accepted schema URLs, preferred first: .../mule/<name>/current/mule-<name>.xsd, with the core and EE core
  // files as exceptions. Names that already start with "mule-" are published both ways (mule-apikit.xsd, and
  // mule-mule-<name>.xsd for Exchange-generated connectors). Empty when there is no convention to check.
  private static List<String> schemaLocations(String name) {
    if (name.equals("core")) {
      return List.of(CORE_NS + "/current/mule.xsd");
    }
    if (name.equals("ee/core")) {
      return List.of(MULE_NS + "ee/core/current/mule-ee.xsd");
    }
    if (name.contains("/")) {
      return List.of();
    }
    String folder = MULE_NS + name + "/current/";
    if (!name.startsWith("mule-")) {
      return List.of(folder + "mule-" + name + ".xsd");
    }
    String plain = folder + name + ".xsd";
    String prefixed = folder + "mule-" + name + ".xsd";
    return name.equals("mule-apikit") ? List.of(plain, prefixed) : List.of(prefixed, plain);
  }

  // The distinctive words of a namespace, e.g. "open-router-api" -> [open, router]; short names stay whole.
  private static List<String> words(String namespace) {
    String hint = ARTIFACT_HINTS.get(namespace);
    if (hint != null) {
      return List.of(hint);
    }
    List<String> words = Stream.of(namespace.split("-"))
        .filter(word -> word.length() >= 3 && !GENERIC_WORDS.contains(word))
        .toList();
    return words.isEmpty() ? List.of(namespace) : words;
  }

  // Connector elements placed directly in a flow, scope or router must be one of the connector's
  // operations, sources or scopes, and their attributes must be its parameters.
  private static void checkComponents(ConfigFile config, Map<String, ConnectorModel> models, List<Problem> found) {
    for (ElementUse use : config.elements) {
      ConnectorModel model = models.get(use.uri);
      if (model == null || use.depth <= 2 || !use.parentUri.equals(CORE_NS)) {
        continue;
      }
      String element = model.prefix() + ":" + use.localName;
      Component component = model.components().get(use.localName);
      if (component == null || component.kind() == Kind.CONFIG || component.kind() == Kind.CONNECTION) {
        found.add(new Problem(config.path, use.line, "<" + element + "> is not an operation, source or scope of "
            + model.label() + ". Look up its components with connector_reference \"" + model.prefix() + "\"."));
        continue;
      }
      Set<String> parameters = new HashSet<>();
      component.parameters().forEach(parameter -> parameters.add(parameter.name()));
      for (String attribute : use.attributes) {
        if (!parameters.contains(attribute)) {
          found.add(new Problem(config.path, use.line, "<" + element + "> has no parameter \"" + attribute + "\" in "
              + model.label() + ". Look up its parameters with connector_reference \"" + element + "\"."));
        }
      }
    }
  }

  private static void checkErrorTypes(ConfigFile config, Map<String, ConnectorModel> byErrorNamespace,
      List<Problem> found) {
    Set<String> connectorNamespaces = new HashSet<>();
    config.prefixes.forEach((uri, prefix) -> {
      String name = uri.startsWith(MULE_NS) ? uri.substring(MULE_NS.length()) : "";
      if (!prefix.isEmpty() && !name.isEmpty() && !RUNTIME_NAMESPACES.contains(name)) {
        connectorNamespaces.add(prefix.toUpperCase(Locale.ROOT));
      }
    });
    for (Use use : config.errorTypes) {
      boolean defines = use.what.equals("raise-error") || use.what.endsWith("targetType");
      for (String raw : use.value.split(",")) {
        String type = raw.trim();
        if (type.isEmpty() || !literal(type)) {
          continue;
        }
        int colon = type.indexOf(':');
        String namespace = colon < 0 ? "MULE" : type.substring(0, colon).trim();
        String id = colon < 0 ? type : type.substring(colon + 1).trim();
        if (namespace.equals("MULE") && !MULE_ERRORS.contains(id)) {
          found.add(new Problem(config.path, use.line, use.what + " \"" + type + "\": MULE:" + id
              + " is not a Mule core error type. Use a real one (for example MULE:ANY, MULE:EXPRESSION,"
              + " MULE:CONNECTIVITY, MULE:TIMEOUT, MULE:VALIDATION), a documented connector type, or your own"
              + " namespace such as APP:" + id + "."));
        } else if (defines && connectorNamespaces.contains(namespace)) {
          found.add(new Problem(config.path, use.line, use.what + " \"" + type + "\": " + namespace
              + " belongs to a connector or module, so custom errors cannot use it. Use your own namespace,"
              + " such as APP:" + id + "."));
        } else if (!defines && byErrorNamespace.containsKey(namespace)
            && !byErrorNamespace.get(namespace).errors().contains(namespace + ":" + id)) {
          ConnectorModel model = byErrorNamespace.get(namespace);
          List<String> real = model.errors().stream().filter(error -> error.startsWith(namespace + ":")).toList();
          found.add(new Problem(config.path, use.line, use.what + " \"" + type + "\": " + model.label()
              + " has no error type " + namespace + ":" + id + ". Its error types are: " + String.join(", ", real)
              + "."));
        }
      }
    }
  }

  private static void checkPlaceholders(ConfigFile config, Set<String> properties, List<Problem> found) {
    for (Use use : config.placeholders) {
      String name = use.value.trim();
      if (name.startsWith("secure::")) {
        name = name.substring("secure::".length());
      }
      String key = name;
      if (properties.contains(key) || RUNTIME_PROPERTIES.contains(key)
          || RUNTIME_PROPERTY_PREFIXES.stream().anyMatch(prefix -> key.startsWith(prefix))) {
        continue;
      }
      found.add(new Problem(config.path, use.line, "${" + use.value.trim() + "} is not defined in any .properties"
          + " or .yaml file under src/main/resources, or by a global-property. Define it there, unless it is set"
          + " at deploy time."));
    }
  }

  private static void checkReferences(ConfigFile config, Set<String> globals, Set<String> flows,
      List<Problem> found) {
    for (Use use : config.references) {
      if (use.what.equals("flow-ref")) {
        if (!flows.contains(use.value)) {
          found.add(new Problem(config.path, use.line, "flow-ref name=\"" + use.value
              + "\" does not match any flow or sub-flow."));
        }
      } else if (!globals.contains(use.value)) {
        found.add(new Problem(config.path, use.line, use.what + "=\"" + use.value
            + "\" does not match the name of any global element."));
      }
    }
  }

  private static boolean literal(String value) {
    return !value.contains("#[") && !value.contains("${");
  }

  private static void collectKeys(String path, String text, Set<String> out) {
    if (path.toLowerCase(Locale.ROOT).endsWith(".properties")) {
      Properties properties = new Properties();
      try {
        properties.load(new StringReader(text));
      } catch (Exception e) {
        return;
      }
      out.addAll(properties.stringPropertyNames());
      return;
    }
    // Enough YAML for Mule config files: nested maps flatten to dotted keys.
    List<Integer> indents = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (String line : text.split("\\R")) {
      String stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("---")) {
        continue;
      }
      Matcher m = YAML_KEY.matcher(stripped);
      if (!m.find()) {
        continue;
      }
      String key = (m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3)).trim();
      int indent = line.length() - line.stripLeading().length();
      while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
        indents.remove(indents.size() - 1);
        names.remove(names.size() - 1);
      }
      indents.add(indent);
      names.add(key);
      out.add(String.join(".", names));
    }
  }

  private record Use(String what, String value, int line) {}

  // A connector or module element; attributes are its unqualified attribute names.
  private record ElementUse(String uri, String localName, String parentUri, int depth, int line,
      List<String> attributes) {}

  private record Pom(List<String> artifacts, boolean domain) {
    static Pom parse(String xml, List<Problem> found) {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        NodeList dependencies = builder.parse(new InputSource(new StringReader(xml)))
            .getElementsByTagName("dependency");
        List<String> artifacts = new ArrayList<>();
        boolean domain = false;
        for (int i = 0; i < dependencies.getLength(); i++) {
          Element dependency = (Element) dependencies.item(i);
          artifacts.add(child(dependency, "artifactId").toLowerCase(Locale.ROOT));
          domain |= child(dependency, "classifier").equals("mule-domain");
        }
        return new Pom(artifacts, domain);
      } catch (SAXParseException e) {
        found.add(new Problem("pom.xml", e.getLineNumber(), "not well-formed XML: " + e.getMessage()));
      } catch (Exception e) {
        found.add(new Problem("pom.xml", 0, "could not be read: " + e.getMessage()));
      }
      return null;
    }

    private static String child(Element parent, String name) {
      NodeList nodes = parent.getElementsByTagName(name);
      return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    // Lenient on purpose: Exchange connectors name their artifact after the asset, not the namespace.
    boolean provides(String namespace) {
      List<String> words = words(namespace);
      return artifacts.stream().anyMatch(artifact -> words.stream().anyMatch(artifact::contains));
    }
  }

  // What one Mule configuration file declares and references, collected in a single SAX pass.
  private static final class ConfigFile extends DefaultHandler {
    final String path;
    String root;
    int rootLine;
    String schemaLocation;
    boolean imports;
    final Map<String, String> prefixes = new LinkedHashMap<>();
    final Set<String> usedUris = new LinkedHashSet<>();
    final Set<String> globals = new HashSet<>();
    final Set<String> flows = new HashSet<>();
    final Set<String> globalProperties = new HashSet<>();
    final List<Use> references = new ArrayList<>();
    final List<Use> placeholders = new ArrayList<>();
    final List<Use> errorTypes = new ArrayList<>();
    final List<Use> propertyFiles = new ArrayList<>();
    final List<ElementUse> elements = new ArrayList<>();
    final List<Problem> structure = new ArrayList<>();
    private final Deque<String> openUris = new ArrayDeque<>();
    // For each open element: its name (local for core elements), and the line of its <error-handler> once seen.
    private final Deque<String> openNames = new ArrayDeque<>();
    private final Deque<int[]> handlerLines = new ArrayDeque<>();
    private Locator locator;
    private int depth;

    private ConfigFile(String path) {
      this.path = path;
    }

    static ConfigFile parse(String path, String content, List<Problem> found) {
      ConfigFile file = new ConfigFile(path);
      try {
        SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newSAXParser().parse(new InputSource(new StringReader(content)), file);
        return file;
      } catch (SAXParseException e) {
        found.add(new Problem(path, e.getLineNumber(), "not well-formed XML: " + e.getMessage()));
      } catch (Exception e) {
        found.add(new Problem(path, 0, "could not be parsed: " + e.getMessage()));
      }
      return null;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) {
      prefixes.putIfAbsent(uri, prefix);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      depth++;
      int line = locator == null ? 0 : locator.getLineNumber();
      if (depth == 1) {
        root = localName;
        rootLine = line;
        schemaLocation = attributes.getValue(XSI_NS, "schemaLocation");
      }
      if (!uri.isEmpty()) {
        usedUris.add(uri);
      }
      if (uri.startsWith(MULE_NS) && !uri.equals(CORE_NS) && !uri.equals(DOC_NS)) {
        List<String> unqualified = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
          if (attributes.getURI(i).isEmpty()) {
            unqualified.add(attributes.getLocalName(i));
          }
        }
        elements.add(new ElementUse(uri, localName, openUris.isEmpty() ? "" : openUris.peek(), depth, line,
            unqualified));
      }
      boolean core = uri.equals(CORE_NS);
      if (!handlerLines.isEmpty() && handlerLines.peek()[0] > 0) {
        structure.add(new Problem(path, line, "<" + qName + "> comes after the <error-handler> of <" + openNames.peek()
            + ">. The error handler must be the last element; move it to the end."));
      }
      if (core && localName.equals("error-handler") && HANDLER_OWNERS.contains(openNames.peek())) {
        handlerLines.peek()[0] = line;
        if (openNames.peek().equals("sub-flow")) {
          structure.add(new Problem(path, line, "a sub-flow cannot have an <error-handler>. Wrap its content in a"
              + " <try> scope, or handle the error in the flow that calls it."));
        }
      }
      openUris.push(uri);
      openNames.push(core ? localName : qName);
      handlerLines.push(new int[1]);
      String name = attributes.getValue("", "name");
      if (depth == 2) {
        if (name != null) {
          globals.add(name);
          if (core && (localName.equals("flow") || localName.equals("sub-flow"))) {
            flows.add(name);
          }
          if (core && localName.equals("global-property")) {
            globalProperties.add(name);
          }
        }
        imports |= core && localName.equals("import");
      }
      if (core && localName.equals("flow-ref") && name != null && literal(name)) {
        references.add(new Use("flow-ref", name, line));
      }
      String ref = attributes.getValue("", "ref");
      if (core && localName.equals("error-handler") && ref != null && literal(ref)) {
        references.add(new Use("error-handler ref", ref, line));
      }
      String type = attributes.getValue("", "type");
      if (core && type != null && (localName.equals("on-error-propagate") || localName.equals("on-error-continue")
          || localName.equals("raise-error"))) {
        errorTypes.add(new Use(localName, type, line));
      }
      if (core && localName.equals("error-mapping")) {
        for (String attribute : List.of("sourceType", "targetType")) {
          String mapped = attributes.getValue("", attribute);
          if (mapped != null) {
            errorTypes.add(new Use("error-mapping " + attribute, mapped, line));
          }
        }
      }
      String file = attributes.getValue("", "file");
      if (file != null && (core && localName.equals("configuration-properties")
          || uri.equals(MULE_NS + "secure-properties") && localName.equals("config"))) {
        propertyFiles.add(new Use(qName, file, line));
      }
      for (int i = 0; i < attributes.getLength(); i++) {
        if (!attributes.getURI(i).isEmpty()) {
          usedUris.add(attributes.getURI(i));
        }
        String attribute = attributes.getLocalName(i);
        String value = attributes.getValue(i);
        if (REFERENCE_ATTRIBUTES.contains(attribute) && literal(value)) {
          references.add(new Use(attribute, value, line));
        }
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
          placeholders.add(new Use(attribute, m.group(1), line));
        }
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      depth--;
      openUris.pop();
      openNames.pop();
      handlerLines.pop();
    }
  }
}
