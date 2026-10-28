package com.mulesoftforge.studio.ai.connector;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.connector.ConnectorModel.Component;
import com.mulesoftforge.studio.ai.connector.ConnectorModel.Kind;
import com.mulesoftforge.studio.ai.connector.ConnectorModel.Parameter;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;

// The connector models Studio generates when it resolves a project's dependencies, one JSON file per
// connector version under <workspace>/.mule/plugins-tmp/repository/<runtime>/<group path>/<artifact>/<version>/.
// The folder is Studio's own cache, not a documented API: when a model is missing, say so rather than guess.
public final class ConnectorCatalog {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
  // Parsing a model takes a moment (some are several MB), and they do not change once written.
  private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

  private record Cached(long modified, ConnectorModel model) {}

  public record Dependency(String groupId, String artifactId, String version) {
    public String coordinates() {
      return groupId + ":" + artifactId + ":" + version;
    }
  }

  // model is null when Studio has not generated one for this version yet.
  public record Entry(Dependency dependency, ConnectorModel model) {}

  private final Path root;

  private ConnectorCatalog(Path root) {
    this.root = root;
  }

  // Null when the project's workspace has no connector models at all.
  public static ConnectorCatalog of(ProjectFiles files) {
    Path root = files.connectorModels();
    return root != null && Files.isDirectory(root) ? new ConnectorCatalog(root) : null;
  }

  public static ConnectorCatalog at(Path root) {
    return Files.isDirectory(root) ? new ConnectorCatalog(root) : null;
  }

  // The mule-plugin dependencies of pom.xml with their models. Empty when the pom cannot be read.
  public List<Entry> forPom(String pomXml) {
    List<Entry> out = new ArrayList<>();
    if (pomXml == null) {
      return out;
    }
    for (Dependency dependency : muleDependencies(pomXml)) {
      out.add(new Entry(dependency, load(dependency)));
    }
    return out;
  }

  private ConnectorModel load(Dependency dependency) {
    Path file = locate(dependency);
    if (file == null) {
      return null;
    }
    try {
      long modified = Files.getLastModifiedTime(file).toMillis();
      Cached cached = CACHE.get(file);
      if (cached == null || cached.modified() != modified) {
        cached = new Cached(modified, ConnectorModel.parse(JSON.readTree(file.toFile())));
        CACHE.put(file, cached);
      }
      return cached.model();
    } catch (Exception e) {
      return null;
    }
  }

  private Path locate(Dependency dependency) {
    String relative = dependency.groupId().replace('.', '/') + "/" + dependency.artifactId() + "/"
        + dependency.version() + "/" + dependency.artifactId() + "-" + dependency.version() + "-mule-plugin.json";
    try (Stream<Path> runtimes = Files.list(root)) {
      return runtimes.filter(Files::isDirectory)
          .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
          .map(runtime -> runtime.resolve(relative))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElse(null);
    } catch (Exception e) {
      return null;
    }
  }

  static List<Dependency> muleDependencies(String pomXml) {
    List<Dependency> out = new ArrayList<>();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      Element project = builder.parse(new InputSource(new StringReader(pomXml))).getDocumentElement();
      Map<String, String> properties = new HashMap<>();
      Element props = child(project, "properties");
      if (props != null) {
        for (Node node = props.getFirstChild(); node != null; node = node.getNextSibling()) {
          if (node instanceof Element property) {
            properties.put(property.getTagName(), property.getTextContent().trim());
          }
        }
      }
      String projectVersion = text(child(project, "version"));
      properties.putIfAbsent("project.version", projectVersion);
      Element dependencies = child(project, "dependencies");
      if (dependencies == null) {
        return out;
      }
      for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element dependency && "mule-plugin".equals(text(child(dependency, "classifier")))) {
          out.add(new Dependency(resolve(text(child(dependency, "groupId")), properties),
              resolve(text(child(dependency, "artifactId")), properties),
              resolve(text(child(dependency, "version")), properties)));
        }
      }
    } catch (Exception e) {
      return out;
    }
    return out;
  }

  private static Element child(Element parent, String name) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && element.getTagName().equals(name)) {
        return element;
      }
    }
    return null;
  }

  private static String text(Element element) {
    return element == null ? "" : element.getTextContent().trim();
  }

  private static String resolve(String value, Map<String, String> properties) {
    Matcher m = PROPERTY.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(properties.getOrDefault(m.group(1), m.group())));
    }
    m.appendTail(out);
    return out.toString();
  }

  // What the connector_reference tool returns. query: "" for every connector, "db" for one connector,
  // "db:select" (or db:bulkInsert) for one component.
  public String reference(String pomXml, String query) {
    List<Entry> entries = forPom(pomXml);
    if (entries.isEmpty()) {
      return "pom.xml lists no dependencies with classifier mule-plugin.";
    }
    String q = query == null ? "" : query.trim();
    if (q.startsWith("<")) {
      q = q.substring(1);
    }
    if (q.isEmpty()) {
      return overview(entries);
    }
    int colon = q.indexOf(':');
    String connector = colon < 0 ? q : q.substring(0, colon);
    Entry entry = find(entries, connector);
    if (entry == null) {
      return "No connector \"" + connector + "\" in pom.xml.\n\n" + overview(entries);
    }
    if (entry.model() == null) {
      return missing(entry.dependency());
    }
    if (colon < 0 || colon == q.length() - 1) {
      return connector(entry.model());
    }
    String element = ConnectorModel.hyphenize(q.substring(colon + 1).replace(">", "").trim());
    Component component = entry.model().components().get(element);
    if (component == null) {
      return "No component " + entry.model().prefix() + ":" + element + " in " + entry.model().label() + ".\n\n"
          + connector(entry.model());
    }
    return component(entry.model(), component);
  }

  private static Entry find(List<Entry> entries, String connector) {
    String wanted = connector.toLowerCase(Locale.ROOT);
    for (Entry entry : entries) {
      if (entry.model() != null && entry.model().prefix().equalsIgnoreCase(wanted)) {
        return entry;
      }
    }
    for (Entry entry : entries) {
      if (entry.dependency().artifactId().toLowerCase(Locale.ROOT).contains(wanted)) {
        return entry;
      }
    }
    return null;
  }

  private static String missing(Dependency dependency) {
    return "Studio has not generated a model for " + dependency.coordinates() + " yet. It does that when it"
        + " resolves the project's dependencies: save pom.xml or refresh the project in Studio, then ask again."
        + " Until then, do not guess this connector's operations or parameters; say you could not check them.";
  }

  private static String overview(List<Entry> entries) {
    StringBuilder out = new StringBuilder("Connectors and modules in pom.xml, from the models Studio generated:\n");
    for (Entry entry : entries) {
      ConnectorModel model = entry.model();
      if (model == null) {
        out.append("- ").append(entry.dependency().coordinates()).append(": no model yet (Studio has not resolved"
            + " this version; save pom.xml or refresh the project in Studio)\n");
        continue;
      }
      out.append("- ").append(model.prefix()).append(": ").append(model.label()).append(" (")
          .append(entry.dependency().groupId()).append(':').append(entry.dependency().artifactId()).append(") — ");
      List<String> counts = new ArrayList<>();
      for (Kind kind : Kind.values()) {
        long n = model.components().values().stream().filter(c -> c.kind() == kind).count();
        if (n > 0 && kind != Kind.CONNECTION) {
          counts.add(n + " " + kind.label + (n == 1 ? "" : "s"));
        }
      }
      out.append(String.join(", ", counts)).append('\n');
    }
    return out.append("Ask for one connector (\"db\") or one component (\"db:select\") for details.").toString();
  }

  private static String connector(ConnectorModel model) {
    StringBuilder out = new StringBuilder();
    out.append(model.label()).append(", prefix ").append(model.prefix()).append(", namespace ")
        .append(model.namespace()).append('\n');
    for (Kind kind : Kind.values()) {
      List<Component> components = model.components().values().stream().filter(c -> c.kind() == kind).toList();
      if (components.isEmpty()) {
        continue;
      }
      out.append('\n').append(switch (kind) {
        case CONFIG -> "Configs";
        case CONNECTION -> "Connections (inside a config)";
        case OPERATION -> "Operations";
        case SOURCE -> "Sources (start a flow)";
        case CONSTRUCT -> "Scopes and routers";
      }).append(":\n");
      for (Component component : components) {
        out.append("- ").append(model.prefix()).append(':').append(component.element());
        if (kind == Kind.CONNECTION && component.config() != null) {
          out.append(" (in ").append(model.prefix()).append(':').append(component.config()).append(')');
        }
        if (!component.description().isEmpty()) {
          out.append(" — ").append(component.description());
        }
        out.append('\n');
      }
    }
    if (!model.errors().isEmpty()) {
      out.append("\nError types: ").append(String.join(", ", model.errors().stream()
          .filter(error -> !error.startsWith("MULE:")).toList())).append('\n');
    }
    return out.toString();
  }

  private static String component(ConnectorModel model, Component component) {
    StringBuilder out = new StringBuilder();
    out.append('<').append(model.prefix()).append(':').append(component.element()).append("> — ")
        .append(component.kind().label).append(" of ").append(model.label());
    if (component.config() != null && component.kind() != Kind.CONFIG) {
      out.append("; ").append(component.kind() == Kind.CONNECTION ? "goes inside " : "config-ref names a ")
          .append('<').append(model.prefix()).append(':').append(component.config()).append('>');
    }
    out.append('\n');
    if (!component.description().isEmpty()) {
      out.append(component.description()).append('\n');
    }
    List<Parameter> regular = component.parameters().stream().filter(p -> !p.infrastructure()).toList();
    if (!regular.isEmpty()) {
      out.append("\nParameters:\n");
      for (Parameter p : regular) {
        out.append("- ").append(p.name()).append(": ").append(p.form());
        if (p.required()) {
          out.append(", required");
        }
        if (p.defaultValue() != null && !p.defaultValue().isEmpty()) {
          out.append(", default ").append(p.defaultValue());
        }
        if (!p.values().isEmpty()) {
          out.append(", one of ").append(String.join(", ", p.values()));
        }
        if (!p.expressions()) {
          out.append(", no expressions");
        }
        if (!p.description().isEmpty()) {
          out.append(". ").append(p.description());
        }
        out.append('\n');
      }
    }
    List<String> infrastructure = component.parameters().stream().filter(Parameter::infrastructure)
        .map(Parameter::name).toList();
    if (!infrastructure.isEmpty()) {
      out.append("\nAlso supports the standard Mule elements for: ").append(String.join(", ", infrastructure))
          .append('\n');
    }
    if (!component.errors().isEmpty()) {
      out.append("\nError types it can raise: ").append(String.join(", ", component.errors())).append('\n');
    }
    return out.toString();
  }
}
