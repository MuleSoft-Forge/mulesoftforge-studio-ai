package com.mulesoftforge.studio.ai.check;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

// Validates connector and module elements against the schemas Studio generates for the project's dependencies
// (<artifact>-<version>-mule-plugin.xsd next to each JSON model), on stand-ins for the core and TLS schemas. This
// catches what the JSON models do not describe: the children of configs and their nested groups, such as a
// <oauth2-provider:refresh-token-strategy> placed in the wrong element. Core elements are not checked, and errors
// are only reported when the element at fault belongs to a validated namespace.
final class ModuleSchemas {
  private static final String MULE_NS = "http://www.mulesoft.org/schema/mule/";
  private static final String CORE_NS = MULE_NS + "core";
  private static final String TLS_NS = MULE_NS + "tls";
  private static final String CORE_STUB = "mule-core-stub.xsd";
  private static final String TLS_STUB = "mule-tls-stub.xsd";
  private static final String FULL_CHECKING = "http://apache.org/xml/features/validation/schema-full-checking";
  private static final int MAX_PROBLEMS = 12;
  private static final Pattern ELEMENT = Pattern.compile("element '([^']+)'");
  private static final Pattern EXPANDED = Pattern.compile("\\{\"([^\"]+)\":[^}]+}");
  private static final Pattern EXPANDED_NAME = Pattern.compile("\"([^\"]+)\":([\\w.-]+)");
  private static final Pattern EXPECTED = Pattern.compile(" One of '([^']*)' is expected\\.");
  private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();
  private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

  private ModuleSchemas() {}

  // schemas: namespace URI to schema file, for the namespaces to check. imports: a schema for a namespace that
  // one of those imports (by its last path segment, "sockets"), or null. prefixes: namespace URI to the prefix
  // this file uses. Files that fail to load (an unusual import) are skipped, not reported.
  static List<Problem> validate(String path, String xml, Map<String, String> prefixes, Map<String, Path> schemas,
      Function<String, Path> imports) {
    if (schemas.isEmpty()) {
      return List.of();
    }
    Schema schema = schema(new TreeMap<>(schemas), imports);
    if (schema == null) {
      return List.of();
    }
    Map<String, String> byPrefix = new TreeMap<>();
    prefixes.forEach((uri, prefix) -> byPrefix.putIfAbsent(prefix, uri));
    Set<String> checked = schemas.keySet();
    Set<String> seen = new LinkedHashSet<>();
    List<Problem> problems = new ArrayList<>();
    Validator validator = schema.newValidator();
    validator.setErrorHandler(new ErrorHandler() {
      @Override
      public void warning(SAXParseException e) {}

      @Override
      public void error(SAXParseException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        Matcher m = ELEMENT.matcher(message);
        if (!m.find()) {
          return;
        }
        // The element at fault is written prefix:name, or {"uri":name} when the content model expected another.
        String element = m.group(1);
        Matcher expanded = EXPANDED.matcher(element);
        String uri = expanded.matches() ? expanded.group(1)
            : element.contains(":") ? byPrefix.get(element.substring(0, element.indexOf(':'))) : byPrefix.get("");
        if (uri == null || !checked.contains(uri) || !seen.add(e.getLineNumber() + message)) {
          return;
        }
        problems.add(new Problem(path, e.getLineNumber(), "schema: " + readable(message, prefixes)));
      }

      @Override
      public void fatalError(SAXParseException e) throws SAXException {
        throw e;
      }
    });
    try {
      validator.validate(new StreamSource(new StringReader(xml)));
    } catch (SAXException | IOException e) {
      // Not well-formed: MuleChecks reports that for the same file.
    }
    List<Problem> out = new ArrayList<>(problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS)));
    if (problems.size() > MAX_PROBLEMS) {
      out.add(new Problem(path, 0, "schema: " + (problems.size() - MAX_PROBLEMS) + " more errors like the ones"
          + " above."));
    }
    return out;
  }

  // Without the cvc- code, with "uri":name written as prefix:name in this file's prefixes, and without an
  // expected list that names only the schema's abstract placeholders (abstract-refresh-token-strategy).
  private static String readable(String message, Map<String, String> prefixes) {
    Matcher m = EXPANDED_NAME.matcher(message.replaceFirst("^cvc-[\\w.-]+:\\s*", ""));
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String prefix = prefixes.get(m.group(1));
      String name = prefix == null ? m.group() : prefix.isEmpty() ? m.group(2) : prefix + ":" + m.group(2);
      m.appendReplacement(out, Matcher.quoteReplacement(name));
    }
    m.appendTail(out);
    String text = out.toString().replaceAll("'\\{([^}']*)}'", "'$1'");
    Matcher expected = EXPECTED.matcher(text);
    if (expected.find() && Stream.of(expected.group(1).split(",\\s*")).allMatch(n -> n.contains("abstract"))) {
      text = text.substring(0, expected.start()) + " Look up what this element allows with connector_reference.";
    }
    return text;
  }

  private static Schema schema(Map<String, Path> schemas, Function<String, Path> imports) {
    StringBuilder key = new StringBuilder();
    schemas.forEach((uri, file) -> key.append(file).append('|').append(modified(file)).append(';'));
    Schema cached = CACHE.get(key.toString());
    if (cached != null || FAILED.contains(key.toString())) {
      return cached;
    }
    Schema schema = null;
    try (InputStream core = ModuleSchemas.class.getResourceAsStream(CORE_STUB);
        InputStream tls = ModuleSchemas.class.getResourceAsStream(TLS_STUB)) {
      if (core != null && tls != null) {
        byte[] coreBytes = core.readAllBytes();
        byte[] tlsBytes = tls.readAllBytes();
        Map<String, Path> known = new ConcurrentHashMap<>(schemas);
        SchemaFactory factory = SchemaFactory.newDefaultInstance();
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        // Generated schemas break Unique Particle Attribution (HTTP's sockets properties); the runtime accepts them.
        factory.setFeature(FULL_CHECKING, false);
        factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
          if (CORE_NS.equals(namespace)) {
            return new MunitSchemas.Input(coreBytes, systemId);
          }
          if (TLS_NS.equals(namespace)) {
            return new MunitSchemas.Input(tlsBytes, systemId);
          }
          if (namespace == null || !namespace.startsWith(MULE_NS)) {
            return null;
          }
          Path file = known.computeIfAbsent(namespace, uri -> {
            Path found = imports == null ? null : imports.apply(uri.substring(uri.lastIndexOf('/') + 1));
            return found == null ? Path.of("") : found;
          });
          byte[] bytes = read(file);
          return bytes == null ? null : new MunitSchemas.Input(bytes, file.toUri().toString());
        });
        List<Source> sources = new ArrayList<>();
        sources.add(new StreamSource(new ByteArrayInputStream(coreBytes), CORE_STUB));
        sources.add(new StreamSource(new ByteArrayInputStream(tlsBytes), TLS_STUB));
        schemas.values().forEach(file -> sources.add(new StreamSource(file.toFile())));
        schema = factory.newSchema(sources.toArray(Source[]::new));
      }
    } catch (SAXException | IOException | RuntimeException e) {
      schema = null;
    }
    if (schema == null) {
      FAILED.add(key.toString());
    } else {
      CACHE.put(key.toString(), schema);
    }
    return schema;
  }

  private static byte[] read(Path file) {
    try {
      return file.toString().isEmpty() || !Files.isRegularFile(file) ? null : Files.readAllBytes(file);
    } catch (IOException e) {
      return null;
    }
  }

  private static long modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }
}
