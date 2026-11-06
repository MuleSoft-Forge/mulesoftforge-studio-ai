package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

// MuleSoftForge's What's New RSS feed, shown folded under the prices. It is read only when the user opens it.
public final class NewsFeed {
  public static final String URL = "https://mulesoftforge.com/feed.xml";
  public static final String PAGE = "https://mulesoftforge.com/whats-new";
  public static final int MAX_ITEMS = 15;
  public static final int MAX_DESCRIPTION = 220;
  private static final int MAX_BYTES = 512 * 1024;
  private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  // link is null unless it is an https URL; date is "6 Nov 2026", or null.
  public record Item(String title, String description, String link, String date) {}

  private NewsFeed() {}

  public static List<Item> fetch(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/rss+xml, application/xml")
        .GET()
        .build();
    HttpResponse<String> response = Network.client().send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
    }
    if (response.body().length() > MAX_BYTES) {
      throw new IOException("the feed is larger than " + MAX_BYTES / 1024 + " KB");
    }
    return parse(response.body());
  }

  // Newest first, as the feed lists them. A DOCTYPE is refused, so no entity can expand or reach a file.
  public static List<Item> parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(null);
    Document document = builder.parse(new InputSource(new StringReader(xml)));
    NodeList nodes = document.getElementsByTagName("item");
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < nodes.getLength() && items.size() < MAX_ITEMS; i++) {
      Element item = (Element) nodes.item(i);
      String title = text(item, "title");
      if (title.isEmpty()) {
        continue;
      }
      items.add(new Item(title, shorten(text(item, "description")), https(text(item, "link")),
          date(text(item, "pubDate"))));
    }
    return items;
  }

  private static String text(Element parent, String tag) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && tag.equals(element.getTagName())) {
        return element.getTextContent().replaceAll("\\s+", " ").strip();
      }
    }
    return "";
  }

  private static String shorten(String text) {
    return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION - 1).strip() + "…";
  }

  private static String https(String link) {
    try {
      URI uri = URI.create(link);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri.toString() : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String date(String pubDate) {
    try {
      return pubDate.isEmpty() ? null
          : ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).format(SHOWN);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
