package com.mulesoftforge.studio.ai.check;

import java.io.StringReader;
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
  private static final Pattern DOC_ID = Pattern.compile("\\bdoc:id\\s*=\\s*\"([^\"]+)\"");
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
      checkSuite(path, project.get(path), docIds, resources, found);
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

  private static void checkSuite(String path, String xml, Set<String> docIds, Set<String> resources,
      List<Problem> found) {
    if (xml == null) {
      return;
    }
    SuiteShape shape = SuiteShape.parse(path, xml, found);
    if (shape == null) {
      return;
    }
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

  private static final class SuiteShape extends DefaultHandler {
    boolean hasConfig;
    final List<Test> tests = new java.util.ArrayList<>();
    private Locator locator;

    static SuiteShape parse(String path, String xml, List<Problem> found) {
      SuiteShape shape = new SuiteShape();
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
    }
  }
}
