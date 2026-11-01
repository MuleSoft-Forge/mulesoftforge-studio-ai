package com.mulesoftforge.studio.ai.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A documentation page as plain text: the article without navigation, scripts or markup, and the links in it.
public final class PageText {
  private static final int MAX_LINKS = 40;
  private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
  private static final Pattern ARTICLE = Pattern.compile("(?is)<article\\b[^>]*>(.*)</article>");
  private static final Pattern MAIN = Pattern.compile("(?is)<main\\b[^>]*>(.*)</main>");
  private static final Pattern PRE = Pattern.compile("(?is)<pre\\b[^>]*>(.*?)</pre>");
  private static final Pattern CODE_TOKEN = Pattern.compile("\u0000(\\d+)\u0000");
  private static final Pattern DROPPED = Pattern.compile(
      "(?is)<(script|style|noscript|svg|nav|header|footer|aside|form|template)\\b[^>]*>.*?</\\1>");
  private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
  private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>");
  private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>");
  private static final Pattern ITEM = Pattern.compile("(?i)<li\\b[^>]*>");
  private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>|</(p|div|li|tr|pre|table|ul|ol|dl|dt|dd|"
      + "blockquote|section)>|<(p|pre|table|tr)\\b[^>]*>");
  private static final Pattern CELL = Pattern.compile("(?i)</t[dh]>");
  private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
  private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

  public record Page(String title, String text, List<String> links) {}

  private PageText() {}

  // Links are kept when allowed accepts them, resolved against base.
  public static Page of(String html, URI base, Predicate<URI> allowed) {
    Matcher title = TITLE.matcher(html);
    String pageTitle = title.find() ? clean(title.group(1)) : "";
    String body = COMMENT.matcher(html).replaceAll("");
    Matcher article = ARTICLE.matcher(body);
    Matcher main = MAIN.matcher(body);
    if (article.find()) {
      body = article.group(1);
    } else if (main.find()) {
      body = main.group(1);
    }
    body = DROPPED.matcher(body).replaceAll("");

    Set<String> links = new LinkedHashSet<>();
    Matcher link = LINK.matcher(body);
    while (link.find() && links.size() < MAX_LINKS) {
      try {
        URI target = base.resolve(decode(link.group(1).strip()));
        String text = clean(link.group(2));
        if (allowed.test(target) && !text.isEmpty()) {
          links.add(text + ": " + withoutFragment(target));
        }
      } catch (IllegalArgumentException ignored) {
        // Not a URL we can follow.
      }
    }

    // Code keeps its indentation, so it is set aside before the rest is reflowed.
    List<String> code = new ArrayList<>();
    String text = PRE.matcher(body).replaceAll(m -> {
      code.add(decode(TAG.matcher(m.group(1)).replaceAll("")).strip());
      return "\n\u0000" + (code.size() - 1) + "\u0000\n";
    });
    text = HEADING.matcher(text).replaceAll(m -> "\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " "
        + Matcher.quoteReplacement(m.group(2)) + "\n");
    text = ITEM.matcher(text).replaceAll("\n- ");
    text = CELL.matcher(text).replaceAll(" | ");
    text = BREAK.matcher(text).replaceAll("\n");
    text = TAG.matcher(text).replaceAll("");
    text = decode(text);
    List<String> lines = new ArrayList<>();
    boolean blank = false;
    for (String line : text.split("\\R")) {
      String stripped = line.replace('\u00A0', ' ').strip();
      if (stripped.isEmpty()) {
        blank = !lines.isEmpty();
        continue;
      }
      if (blank) {
        lines.add("");
        blank = false;
      }
      lines.add(stripped);
    }
    // Toolbars and breadcrumbs that come before the page's own title.
    int heading = lines.indexOf(lines.stream().filter(line -> line.startsWith("# ")).findFirst().orElse(null));
    if (heading > 0) {
      lines.subList(0, heading).clear();
    }
    String joined = CODE_TOKEN.matcher(String.join("\n", lines)).replaceAll(m ->
        Matcher.quoteReplacement("```\n" + code.get(Integer.parseInt(m.group(1))) + "\n```"));
    return new Page(pageTitle, joined, List.copyOf(links));
  }

  private static String clean(String html) {
    return decode(TAG.matcher(html).replaceAll("")).replace('\u00A0', ' ').replaceAll("\\s+", " ").strip();
  }

  private static String withoutFragment(URI uri) {
    String text = uri.toString();
    int hash = text.indexOf('#');
    return hash < 0 ? text : text.substring(0, hash);
  }

  static String decode(String text) {
    Matcher m = ENTITY.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String value;
      try {
        value = name.startsWith("#x") || name.startsWith("#X") ? Character.toString(Integer.parseInt(name.substring(2), 16))
            : name.startsWith("#") ? Character.toString(Integer.parseInt(name.substring(1)))
            : switch (name.toLowerCase(Locale.ROOT)) {
              case "amp" -> "&";
              case "lt" -> "<";
              case "gt" -> ">";
              case "quot" -> "\"";
              case "apos" -> "'";
              case "nbsp" -> "\u00A0";
              case "ndash" -> "–";
              case "mdash" -> "—";
              case "hellip" -> "…";
              case "rsquo", "lsquo" -> "'";
              case "rdquo", "ldquo" -> "\"";
              case "copy" -> "©";
              default -> m.group();
            };
      } catch (IllegalArgumentException e) {
        value = m.group();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
