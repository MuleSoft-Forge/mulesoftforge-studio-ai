package com.mulesoftforge.studio.ai.check;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

// Studio's metadata file. Studio reports a broken one only as unrelated DataWeave errors in the Transform it
// describes, so the mistakes are found here instead.
final class TypesChecks {
  static final String PATH = "src/main/resources/application-types.xml";
  private static final String TYPES_NS = "http://www.mulesoft.org/schema/mule/types";
  private static final Pattern DOC_ID = Pattern.compile("\\bdoc:id\\s*=\\s*[\"']([^\"']+)[\"']");
  // A record key such as content-type: needs quotes in a weave shape, or the whole shape fails to parse.
  private static final Pattern UNQUOTED_DASH_KEY = Pattern.compile(
      "(?m)(?:^|[{|,])\\s*([A-Za-z_]\\w*(?:-\\w+)+)\\s*\\??:");

  private TypesChecks() {}

  static void check(Map<String, String> project, Set<String> resources, List<Problem> found) {
    String xml = project.get(PATH);
    if (xml == null || xml.isBlank()) {
      return;
    }
    Document doc;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      doc = builder.parse(new InputSource(new StringReader(xml)));
    } catch (SAXParseException e) {
      found.add(new Problem(PATH, e.getLineNumber(), "not well-formed XML: " + e.getMessage()));
      return;
    } catch (Exception e) {
      found.add(new Problem(PATH, 0, "could not be parsed: " + e.getMessage()));
      return;
    }

    Set<String> docIds = new HashSet<>();
    project.forEach((path, content) -> {
      if (path.startsWith("src/main/mule/") && content != null) {
        Matcher m = DOC_ID.matcher(content);
        while (m.find()) {
          docIds.add(m.group(1));
        }
      }
    });

    Set<String> names = new HashSet<>();
    NodeList types = doc.getElementsByTagNameNS(TYPES_NS, "type");
    for (int i = 0; i < types.getLength(); i++) {
      names.add(((Element) types.item(i)).getAttribute("name"));
    }
    NodeList shapes = doc.getElementsByTagNameNS(TYPES_NS, "shape");
    for (int i = 0; i < shapes.getLength(); i++) {
      Element shape = (Element) shapes.item(i);
      String type = ((Element) shape.getParentNode()).getAttribute("name");
      for (String attribute : List.of("example", "location")) {
        String file = shape.getAttribute(attribute);
        if (!file.isEmpty() && !resources.contains(file)) {
          found.add(new Problem(PATH, lineOf(xml, attribute + "=\"" + file), "type \"" + type + "\" has "
              + attribute + "=\"" + file + "\", which is not under src/main/resources."));
        }
      }
      if ("weave".equals(shape.getAttribute("format"))) {
        Matcher key = UNQUOTED_DASH_KEY.matcher(shape.getTextContent());
        while (key.find()) {
          found.add(new Problem(PATH, lineOf(xml, key.group(1)), "type \"" + type + "\": the key " + key.group(1)
              + " needs quotes (\"" + key.group(1) + "\": …). Unquoted, the shape does not parse and Studio"
              + " reports errors in every Transform that uses it."));
        }
      }
    }

    NodeList enrichments = doc.getElementsByTagNameNS(TYPES_NS, "enrichment");
    for (int i = 0; i < enrichments.getLength(); i++) {
      String select = ((Element) enrichments.item(i)).getAttribute("select");
      String id = select.startsWith("#") ? select.substring(1) : select;
      if (!id.isEmpty() && !docIds.contains(id)) {
        found.add(new Problem(PATH, lineOf(xml, "select=\"" + select), "enrichment select=\"" + select
            + "\" matches no doc:id in src/main/mule. It must be # plus the doc:id of the processor it describes."));
      }
    }
    for (String part : List.of("payload", "attributes", "variable")) {
      NodeList uses = doc.getElementsByTagNameNS(TYPES_NS, part);
      for (int i = 0; i < uses.getLength(); i++) {
        String type = ((Element) uses.item(i)).getAttribute("type");
        if (!type.isEmpty() && !names.contains(type)) {
          found.add(new Problem(PATH, lineOf(xml, "type=\"" + type + "\""), "<types:" + part + " type=\"" + type
              + "\"> names no <types:type> in the catalog."));
        }
      }
    }
  }

  private static int lineOf(String text, String needle) {
    int at = text.indexOf(needle);
    return at < 0 ? 0 : (int) text.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
  }
}
