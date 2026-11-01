package com.mulesoftforge.studio.ai.http;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.llm.SecretMask;

// The HTTP listeners in a project and the URLs they answer on when the app runs in Studio: each listener's path,
// and each APIkit flow's method and resource under the listener that hosts its router.
public final class Endpoints {
  // What Studio's run configuration sets when the project does not.
  static final String DEFAULT_PORT = "8081";
  private static final String HTTP = "http://www.mulesoft.org/schema/mule/http";
  private static final String APIKIT = "http://www.mulesoft.org/schema/mule/mule-apikit";
  private static final int LIST_LIMIT = 300;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
  private static final Pattern APIKIT_FLOW = Pattern.compile(
      "^(get|post|put|patch|delete|head|options):(\\\\[^:]*)(?::[^:]+)?:([^:]+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern YAML_KEY = Pattern.compile(
      "^(?:\"([^\"]+)\"|'([^']+)'|([^\\s#'\"-][^:#]*?))\\s*:(?:\\s+(.*))?$");

  public record Listener(String config, String scheme, String port, String basePath, String file) {}

  // method is ANY for a plain listener without allowedMethods.
  public record Endpoint(String method, String url, String flow, String note) {}

  public record Found(List<Listener> listeners, List<Endpoint> endpoints, List<String> notes) {}

  private Endpoints() {}

  public static Found find(ProjectFiles files) throws Exception {
    Map<String, String> properties = properties(files);
    List<String> notes = new ArrayList<>();
    Map<String, Listener> listeners = new LinkedHashMap<>();
    List<Element> listenerElements = new ArrayList<>();
    List<Element> flows = new ArrayList<>();
    Map<Element, String> fileOf = new LinkedHashMap<>();
    for (String path : files.list("src/main/mule", LIST_LIMIT)) {
      if (!path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
        continue;
      }
      String xml = files.read(path);
      Document document = xml == null ? null : parse(xml);
      if (document == null) {
        continue;
      }
      NodeList configs = document.getElementsByTagNameNS(HTTP, "listener-config");
      for (int i = 0; i < configs.getLength(); i++) {
        Element config = (Element) configs.item(i);
        Element connection = firstChild(config, HTTP, "listener-connection");
        String protocol = connection == null ? "" : resolve(connection.getAttribute("protocol"), properties, notes);
        String port = connection == null ? "" : resolve(connection.getAttribute("port"), properties, notes);
        listeners.put(config.getAttribute("name"), new Listener(config.getAttribute("name"),
            protocol.equalsIgnoreCase("HTTPS") ? "https" : "http", port.isBlank() ? DEFAULT_PORT : port,
            resolve(config.getAttribute("basePath"), properties, notes), path));
      }
      NodeList sources = document.getElementsByTagNameNS(HTTP, "listener");
      for (int i = 0; i < sources.getLength(); i++) {
        listenerElements.add((Element) sources.item(i));
        fileOf.put((Element) sources.item(i), path);
      }
      NodeList all = document.getDocumentElement().getChildNodes();
      for (int i = 0; i < all.getLength(); i++) {
        if (all.item(i) instanceof Element flow && "flow".equals(flow.getLocalName())) {
          flows.add(flow);
        }
      }
    }

    List<Endpoint> endpoints = new ArrayList<>();
    // APIkit config name to the URL prefix of the listener whose flow hosts its router.
    Map<String, String> routers = new LinkedHashMap<>();
    for (Element source : listenerElements) {
      Listener listener = listeners.get(source.getAttribute("config-ref"));
      Element flow = enclosingFlow(source);
      String flowName = flow == null ? "" : flow.getAttribute("name");
      if (listener == null) {
        notes.add("The listener in " + (flowName.isEmpty() ? fileOf.get(source) : "flow " + flowName)
            + " uses config " + source.getAttribute("config-ref") + ", which is not in src/main/mule.");
        continue;
      }
      String path = join(listener.basePath(), resolve(source.getAttribute("path"), properties, notes));
      String url = listener.scheme() + "://localhost:" + listener.port() + path;
      String methods = resolve(source.getAttribute("allowedMethods"), properties, notes);
      String router = flow == null ? null : routerConfig(flow);
      String note = router == null ? "" : "APIkit router " + router;
      if (router != null) {
        routers.put(router, listener.scheme() + "://localhost:" + listener.port() + trimWildcard(path));
      }
      if (methods.isBlank()) {
        endpoints.add(new Endpoint("ANY", url, flowName, note));
      } else {
        for (String method : methods.split(",")) {
          endpoints.add(new Endpoint(method.strip().toUpperCase(Locale.ROOT), url, flowName, note));
        }
      }
    }
    for (Element flow : flows) {
      Matcher m = APIKIT_FLOW.matcher(flow.getAttribute("name"));
      if (!m.matches()) {
        continue;
      }
      String prefix = routers.get(m.group(3));
      if (prefix == null) {
        continue;
      }
      String resource = m.group(2).replace('\\', '/').replaceAll("\\(([^)]+)\\)", "{$1}");
      endpoints.add(new Endpoint(m.group(1).toUpperCase(Locale.ROOT), prefix + resource, flow.getAttribute("name"),
          "APIkit"));
    }
    return new Found(List.copyOf(listeners.values()), endpoints, notes.stream().distinct().toList());
  }

  public static String describe(Found found) {
    if (found.listeners().isEmpty()) {
      return "No HTTP listener config in src/main/mule, so the app does not take HTTP requests.";
    }
    StringBuilder out = new StringBuilder("LISTENERS (when the app runs in Studio):\n");
    for (Listener listener : found.listeners()) {
      out.append("- ").append(listener.config()).append(": ").append(listener.scheme()).append("://localhost:")
          .append(listener.port()).append(listener.basePath().isBlank() ? "" : join(listener.basePath(), ""))
          .append(" (").append(listener.file()).append(")\n");
    }
    out.append(found.endpoints().isEmpty() ? "\nNo flow uses these listeners.\n" : "\nENDPOINTS:\n");
    for (Endpoint endpoint : found.endpoints()) {
      out.append("- ").append(endpoint.method()).append(' ').append(endpoint.url()).append("  (flow ")
          .append(endpoint.flow()).append(endpoint.note().isEmpty() ? "" : ", " + endpoint.note()).append(")\n");
    }
    if (!found.notes().isEmpty()) {
      out.append("\nNOTES:\n");
      found.notes().forEach(note -> out.append("- ").append(note).append('\n'));
    }
    return out.toString().strip();
  }

  private static Document parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      return builder.parse(new InputSource(new StringReader(xml)));
    } catch (Exception e) {
      return null;
    }
  }

  private static Element firstChild(Element parent, String namespace, String name) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && namespace.equals(element.getNamespaceURI())
          && name.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static Element enclosingFlow(Element element) {
    for (Node node = element.getParentNode(); node != null; node = node.getParentNode()) {
      if (node instanceof Element parent && "flow".equals(parent.getLocalName())) {
        return parent;
      }
    }
    return null;
  }

  private static String routerConfig(Element flow) {
    NodeList routers = flow.getElementsByTagNameNS(APIKIT, "router");
    return routers.getLength() == 0 ? null : ((Element) routers.item(0)).getAttribute("config-ref");
  }

  private static String join(String base, String path) {
    String joined = ("/" + (base == null ? "" : base) + "/" + (path == null ? "" : path)).replaceAll("/+", "/");
    return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
  }

  private static String trimWildcard(String path) {
    String trimmed = path.endsWith("/*") ? path.substring(0, path.length() - 2) : path;
    return trimmed.equals("/") ? "" : trimmed;
  }

  // ${key} from the project's properties; http.port falls back to what Studio uses.
  private static String resolve(String value, Map<String, String> properties, List<String> notes) {
    if (value == null || !value.contains("${")) {
      return value == null ? "" : value;
    }
    Matcher m = PLACEHOLDER.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String key = m.group(1).strip();
      String found = properties.get(key);
      if (found == null && (key.equals("http.port") || key.equals("https.port"))) {
        found = DEFAULT_PORT;
        notes.add("${" + key + "} is not set in the project; Studio runs the app with " + DEFAULT_PORT
            + " unless the run configuration says otherwise.");
      }
      if (found == null) {
        notes.add("${" + key + "} is not set in src/main/resources; it may come from the run configuration.");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(found == null ? m.group() : found));
    }
    m.appendTail(out);
    return out.toString();
  }

  // Values from .properties and .yaml files under src/main/resources; the first file to set a key wins.
  static Map<String, String> properties(ProjectFiles files) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    for (String path : files.list("src/main/resources", LIST_LIMIT)) {
      String lower = path.toLowerCase(Locale.ROOT);
      boolean props = lower.endsWith(".properties");
      if ((!props && !lower.endsWith(".yaml") && !lower.endsWith(".yml")) || SecretMask.isBlocked(path)) {
        continue;
      }
      String text = files.read(path);
      if (text == null) {
        continue;
      }
      Map<String, String> values = props ? propertiesFile(text) : yaml(text);
      values.forEach(out::putIfAbsent);
    }
    return out;
  }

  private static Map<String, String> propertiesFile(String text) {
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(text));
    } catch (Exception e) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    properties.stringPropertyNames().forEach(name -> out.put(name, properties.getProperty(name)));
    return out;
  }

  // Enough YAML for Mule config files: nested maps flatten to dotted keys.
  static Map<String, String> yaml(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    List<Integer> indents = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (String line : text.split("\\R")) {
      String stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("---")) {
        continue;
      }
      Matcher m = YAML_KEY.matcher(stripped);
      if (!m.matches()) {
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
      String value = m.group(4) == null ? "" : m.group(4).replaceAll("\\s+#.*$", "").strip();
      if (!value.isEmpty()) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("'") && value.endsWith("'"))) {
          value = value.substring(1, value.length() - 1);
        }
        out.put(String.join(".", names), value);
      }
    }
    return out;
  }
}
