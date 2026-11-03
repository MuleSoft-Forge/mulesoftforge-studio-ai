package com.mulesoftforge.studio.ai.check;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

// Extra static checks for MUnit suites under src/test/munit. MuleChecks already covers namespaces, flow-ref targets
// and placeholders; these catch suite shape, mock targets and the pom.xml MUnit setup.
public final class MunitChecks {
  private static final String MUNIT_DIR = "src/test/munit/";
  private static final String MULE_DIR = "src/main/mule/";
  private static final String MUNIT_NS = "http://www.mulesoft.org/schema/mule/munit";
  private static final String TOOLS_NS = "http://www.mulesoft.org/schema/mule/munit-tools";
  // Elements whose content goes in value="…"; text inside them does not parse in munit-tools.
  private static final Set<String> VALUE_ELEMENTS = Set.of("payload", "attributes", "variable");
  private static final Pattern DOC_ID = Pattern.compile("\\bdoc:id\\s*=\\s*\"([^\"]+)\"");
  private static final Pattern DOC_NAME = Pattern.compile("\\bdoc:name\\s*=\\s*\"([^\"]+)\"");
  // Opening tags in src/main/mule, as written: "http:request", "flow-ref".
  private static final Pattern TAG = Pattern.compile("<([A-Za-z][\\w.-]*(?::[A-Za-z][\\w.-]*)?)[\\s/>]");
  private static final Pattern READ_URL = Pattern.compile("readUrl\\s*\\(\\s*'classpath://([^']+)'\\s*\\)");
  // attributeName / whereValue in either order; the quoted whereValue is then unwrapped (#['id'] → id).
  private static final Pattern DOC_ID_ATTR = Pattern.compile(
      "attributeName\\s*=\\s*(?:\"doc:id\"|'doc:id'|#\\['doc:id'\\]|#\\[\"doc:id\"\\])[^>]*whereValue\\s*=\\s*"
          + "(?:\"([^\"]+)\"|'([^']+)')"
          + "|whereValue\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')[^>]*"
          + "attributeName\\s*=\\s*(?:\"doc:id\"|'doc:id'|#\\['doc:id'\\]|#\\[\"doc:id\"\\])",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern BAD_MOCK = Pattern.compile(
      "<munit-tools:(?:mock-when|verify-call)\\b[^>]*\\bprocessor\\s*=\\s*\"((?:mule:)?logger|(?:ee:)?transform)\"",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern WITH_ATTRIBUTE = Pattern.compile("<munit-tools:with-attribute\\b([^>]*)/?>",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern DW_STRING = Pattern.compile("^#\\[(['\"])(.*)\\1]$", Pattern.DOTALL);

  private MunitChecks() {}

  // Adds suite problems to found. No-op when the project has no files under src/test/munit.
  public static void check(Map<String, String> project, List<Problem> found) {
    Set<String> suites = new LinkedHashSet<>();
    Set<String> docIds = new HashSet<>();
    Set<String> docNames = new HashSet<>();
    Set<String> tags = new HashSet<>();
    Set<String> resources = new HashSet<>();
    for (Map.Entry<String, String> file : project.entrySet()) {
      String path = file.getKey().replace('\\', '/');
      if (path.startsWith(MUNIT_DIR) && path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
        suites.add(path);
      }
      if (path.startsWith(MULE_DIR) && path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
        Matcher m = DOC_ID.matcher(file.getValue());
        while (m.find()) {
          docIds.add(m.group(1));
        }
        Matcher name = DOC_NAME.matcher(file.getValue());
        while (name.find()) {
          docNames.add(name.group(1));
        }
        Matcher tag = TAG.matcher(file.getValue());
        while (tag.find()) {
          tags.add(tag.group(1));
        }
      }
      if (path.contains("/resources/") && (path.startsWith("src/test/resources/")
          || path.startsWith("src/main/resources/"))) {
        resources.add(path.substring(path.indexOf("/resources/") + "/resources/".length()));
      }
    }
    if (suites.isEmpty()) {
      return;
    }
    for (String path : suites) {
      checkSuite(path, project.get(path), new MainApp(docIds, docNames, tags), resources, found);
    }
    String pom = project.get("pom.xml");
    if (pom == null) {
      found.add(new Problem("pom.xml", 0, "this project has MUnit suites but no pom.xml to declare munit-runner,"
          + " munit-tools and munit-maven-plugin."));
      return;
    }
    if (!pom.contains("<artifactId>munit-runner</artifactId>")) {
      found.add(new Problem("pom.xml", 0, "MUnit suites need a test-scoped munit-runner dependency"
          + " (com.mulesoft.munit:munit-runner, classifier mule-plugin)."));
    }
    if (!pom.contains("<artifactId>munit-tools</artifactId>")) {
      found.add(new Problem("pom.xml", 0, "MUnit suites need a test-scoped munit-tools dependency"
          + " (com.mulesoft.munit:munit-tools, classifier mule-plugin)."));
    }
    if (!pom.contains("<artifactId>munit-maven-plugin</artifactId>")) {
      found.add(new Problem("pom.xml", 0, "MUnit suites need the munit-maven-plugin"
          + " (com.mulesoft.munit.tools:munit-maven-plugin) with goal test."));
    }
  }

  private static void checkSuite(String path, String xml, MainApp app, Set<String> resources,
      List<Problem> found) {
    if (xml == null) {
      return;
    }
    // MUnit's own schemas, when Studio has generated them, find every shape error; the shape checks below are
    // the fallback for the common ones.
    boolean validated = MunitSchemas.validate(path, xml, found);
    List<Problem> shapeProblems = new ArrayList<>();
    SuiteShape shape = SuiteShape.parse(path, xml, shapeProblems);
    if (shape == null) {
      return;
    }
    if (!validated) {
      found.addAll(shapeProblems);
    }
    for (Mock mock : shape.mocks) {
      checkTarget(path, mock, app, found);
    }
    Set<String> docIds = app.docIds();
    if (!shape.hasConfig) {
      found.add(new Problem(path, 1, "an MUnit suite needs a <munit:config> element."));
    }
    Set<String> names = new HashSet<>();
    for (Test test : shape.tests) {
      if (test.name == null || test.name.isBlank()) {
        found.add(new Problem(path, test.line, "a <munit:test> needs a name attribute."));
      } else if (!names.add(test.name)) {
        found.add(new Problem(path, test.line, "munit:test name=\"" + test.name
            + "\" is used more than once in this suite."));
      }
      if (test.description == null || test.description.isBlank()) {
        found.add(new Problem(path, test.line, "munit:test"
            + (test.name == null || test.name.isBlank() ? "" : " name=\"" + test.name + "\"")
            + " needs a description attribute."));
      }
    }
    Matcher bad = BAD_MOCK.matcher(xml);
    while (bad.find()) {
      found.add(new Problem(path, lineOf(xml, bad.start()), "processor=\"" + bad.group(1)
          + "\" cannot be mocked or verified; Logger and Transform Message are not mockable."));
    }
    Matcher attrs = WITH_ATTRIBUTE.matcher(xml);
    while (attrs.find()) {
      Matcher id = DOC_ID_ATTR.matcher(attrs.group(1));
      if (!id.find()) {
        continue;
      }
      String value = literalId(first(id, 1, 4));
      if (value == null || value.isBlank()) {
        continue;
      }
      if (!docIds.contains(value)) {
        found.add(new Problem(path, lineOf(xml, attrs.start()), "doc:id \"" + value
            + "\" does not match any element in src/main/mule."));
      }
    }
    Matcher urls = READ_URL.matcher(xml);
    while (urls.find()) {
      String resource = urls.group(1);
      if (!resources.contains(resource)) {
        found.add(new Problem(path, lineOf(xml, urls.start()), "readUrl classpath://" + resource
            + " is not under src/test/resources or src/main/resources."));
      }
    }
  }

  // A mock or verify that matches nothing in the app is silently ignored by MUnit, so the real processor runs.
  private static void checkTarget(String path, Mock mock, MainApp app, List<Problem> found) {
    if (mock.processor() != null && !mock.processor().isBlank() && !mock.processor().contains("#[")) {
      String tag = mock.processor().startsWith("mule:") ? mock.processor().substring(5) : mock.processor();
      if (!app.tags().contains(tag)) {
        found.add(new Problem(path, mock.line(), mock.element() + " processor=\"" + mock.processor()
            + "\" matches no <" + tag + "> in src/main/mule; use the element name as written there, e.g."
            + " http:request or flow-ref."));
      }
    }
    for (Match match : mock.matches()) {
      String value = literalId(match.value());
      if (value == null || value.isBlank() || !match.attribute().equals("doc:name")) {
        continue;
      }
      if (!app.docNames().contains(value)) {
        found.add(new Problem(path, match.line(), "doc:name \"" + value + "\" does not match any element in"
            + " src/main/mule; match on the element's doc:id (or config-ref) instead."));
      }
    }
  }

  private record MainApp(Set<String> docIds, Set<String> docNames, Set<String> tags) {}

  private static String first(Matcher m, int from, int to) {
    for (int i = from; i <= to; i++) {
      String value = m.group(i);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  // #['uuid'] / #["uuid"] → uuid; other expressions (still containing #[ or ${) are skipped.
  private static String literalId(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.strip();
    Matcher dw = DW_STRING.matcher(value);
    if (dw.matches()) {
      value = dw.group(2);
    }
    if (value.contains("#[") || value.contains("${")) {
      return null;
    }
    return value;
  }

  private static int lineOf(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private record Test(String name, String description, int line) {}

  private record Match(String attribute, String value, int line) {}

  private record Mock(String element, String processor, int line, List<Match> matches) {}

  private record Open(String uri, String name, int line, StringBuilder text) {}

  // Element shapes from Studio's MUnit editor definitions (editors/munit-tools.xml and munit.xml), which the
  // runtime's schema enforces: a suite breaking them does not parse, so no test runs.
  private static final class SuiteShape extends DefaultHandler {
    boolean hasConfig;
    final List<Test> tests = new ArrayList<>();
    final List<Mock> mocks = new ArrayList<>();
    private final String path;
    private final List<Problem> found;
    private final Deque<Open> open = new ArrayDeque<>();
    private Mock mock;
    private Locator locator;

    private SuiteShape(String path, List<Problem> found) {
      this.path = path;
      this.found = found;
    }

    static SuiteShape parse(String path, String xml, List<Problem> found) {
      SuiteShape shape = new SuiteShape(path, found);
      try {
        SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newSAXParser().parse(new InputSource(new StringReader(xml)), shape);
        return shape;
      } catch (Exception e) {
        // Well-formedness is reported by MuleChecks' ConfigFile parse for the same file.
        return null;
      }
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      int line = locator == null ? 0 : locator.getLineNumber();
      if (MUNIT_NS.equals(uri) && "config".equals(localName)) {
        hasConfig = true;
      }
      if (MUNIT_NS.equals(uri) && "test".equals(localName)) {
        tests.add(new Test(attributes.getValue("", "name"), attributes.getValue("", "description"), line));
      }
      open.push(new Open(uri, localName, line, new StringBuilder()));
      if (!TOOLS_NS.equals(uri)) {
        return;
      }
      String element = "munit-tools:" + localName;
      switch (localName) {
        case "mock-when", "verify-call", "spy" -> {
          String processor = attributes.getValue("", "processor");
          if (processor == null || processor.isBlank()) {
            problem(line, element + " needs processor=\"…\" naming the element it targets, e.g."
                + " processor=\"http:request\" or processor=\"flow-ref\".");
          }
          mock = new Mock(element, processor == null ? null : processor.strip(), line, new ArrayList<>());
          mocks.add(mock);
        }
        case "with-attributes" -> {
          String name = attributes.getValue("", "attributeName");
          String value = attributes.getValue("", "whereValue");
          if (name != null && value != null && mock != null) {
            mock.matches().add(new Match(name.strip(), value.strip(), line));
          }
          for (int i = 0; i < attributes.getLength(); i++) {
            if (attributes.getURI(i).isEmpty()) {
              problem(line, "munit-tools:with-attributes takes no attributes (found " + attributes.getLocalName(i)
                  + "); put each match in a child <munit-tools:with-attribute attributeName=\"…\""
                  + " whereValue=\"…\"/>.");
              break;
            }
          }
        }
        case "with-attribute" -> {
          String name = attributes.getValue("", "attributeName");
          String value = attributes.getValue("", "whereValue");
          if (name == null || value == null) {
            problem(line, "munit-tools:with-attribute needs both attributeName and whereValue.");
          } else if (mock != null) {
            mock.matches().add(new Match(name.strip(), value.strip(), line));
          }
        }
        case "payload", "attributes" -> requireValue(element, attributes, line);
        case "variable" -> {
          requireValue(element, attributes, line);
          if (attributes.getValue("", "key") == null) {
            problem(line, element + " needs key=\"…\".");
          }
        }
        case "assert-that" -> {
          if (attributes.getValue("", "expression") == null || attributes.getValue("", "is") == null) {
            problem(line, "munit-tools:assert-that needs expression=\"#[…]\" and is=\"#[MunitTools::…]\".");
          }
        }
        default -> {}
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (!open.isEmpty()) {
        open.peek().text().append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      Open element = open.pop();
      boolean valued = (TOOLS_NS.equals(uri) || MUNIT_NS.equals(uri))
          && VALUE_ELEMENTS.contains(localName);
      if (valued && !element.text().toString().isBlank()) {
        problem(element.line(), (TOOLS_NS.equals(uri) ? "munit-tools:" : "munit:") + localName
            + " cannot have text content; put it in the value attribute: <" + qName + " value=\"#[…]\"/>.");
      }
      if (TOOLS_NS.equals(uri) && ("mock-when".equals(localName) || "verify-call".equals(localName)
          || "spy".equals(localName))) {
        mock = null;
      }
    }

    private void requireValue(String element, Attributes attributes, int line) {
      if (attributes.getValue("", "value") == null) {
        problem(line, element + " needs value=\"…\" (for example value=\"#[readUrl('classpath://data.json')]\""
            + " mediaType=\"application/json\").");
      }
    }

    private void problem(int line, String message) {
      found.add(new Problem(path, line, message));
    }
  }
}
