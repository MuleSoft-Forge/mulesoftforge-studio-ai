package com.mulesoftforge.studio.ai.llm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hides passwords, keys and tokens from the model: in properties and YAML files, Mule XML attributes, JSON and
// DataWeave string literals, URLs, JVM arguments, console output and the user's own message. Each value becomes
// «masked-N». Within a chat the same value always gets the same placeholder, and unmask puts the real values
// back into what the model writes, so its edits still apply to the real files.
public final class SecretMask {
  public record Masked(String text, int count) {}

  public static final Pattern PLACEHOLDER = Pattern.compile("«masked-\\d+»");

  // Matched against a lower-cased key or attribute name, with any namespace prefix removed.
  private static final Pattern SENSITIVE_NAME = Pattern.compile(
      "password|passwd|passphrase|pwd|(?:^|[._-])pass(?:$|[._-])|secret|token|credential|authorization|bearer"
          + "|api[._-]?key|access[._-]?key|private[._-]?key|secret[._-]?key|master[._-]?key|subscription[._-]?key"
          + "|client[._-]?id|sas[._-]?token|connection[._-]?string|[._-]key$");
  private static final Pattern CAMEL_KEY = Pattern.compile("[a-z0-9]Key$");
  // A token-like name ending in one of these names a setting about the secret, not the secret.
  private static final Set<String> SAFE_SUFFIXES = Set.of("url", "uri", "path", "endpoint", "host", "port", "type",
      "timeout", "ttl", "length", "size", "mode", "enabled", "file", "ref", "header", "name", "expiry",
      "expiration", "location", "policy", "strategy", "provider", "format", "algorithm", "count", "required");
  private static final Set<String> HARMLESS = Set.of("true", "false", "null", "~", "yes", "no", "none", "");

  private static final Pattern PEM = Pattern.compile(
      "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
  private static final Pattern TOKENS = Pattern.compile(
      "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
          + "|\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"
          + "|\\bsk-(?:ant-|proj-|or-v1-)?[A-Za-z0-9_-]{20,}"
          + "|\\bgh[pousr]_[A-Za-z0-9]{30,}\\b|\\bgithub_pat_[A-Za-z0-9_]{40,}"
          + "|\\bxox[abprs]-[A-Za-z0-9-]{10,}"
          + "|\\bAIza[0-9A-Za-z_-]{35}\\b");
  // Requires a digit or base64 symbol so prose such as "Basic authentication" is left alone.
  private static final Pattern AUTH_SCHEME = Pattern.compile(
      "\\b(?:Bearer|Basic)\\s+((?=[A-Za-z0-9+/=._~-]*[0-9+/=])[A-Za-z0-9+/=._~-]{8,})");
  private static final Pattern URL_PASSWORD = Pattern.compile(
      "\\b[a-zA-Z][\\w+.-]*://[^\\s/:@\"'<>]+:([^\\s@/\"'<>]+)@");
  private static final Pattern URL_PARAM = Pattern.compile(
      "(?i)[;?&]\\s*(?:password|pwd|pass|secret|token|api_?key|access_?token|client_secret)\\s*=\\s*([^;&\\s\"'<>]+)");
  private static final Pattern JVM_ARG = Pattern.compile("-D([\\w.\\-]+)=(\"?)([^\\s\"]+)");
  private static final Pattern ATTR_DOUBLE = Pattern.compile("(?<![\\w.:-])([\\w.:-]+)\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern ATTR_SINGLE = Pattern.compile("(?<![\\w.:-])([\\w.:-]+)\\s*=\\s*'([^']*)'");
  // <http:header key="Authorization" value="…"/>, <http:query-param key="client_secret" value="…"/>.
  private static final Pattern KEY_VALUE_PAIR = Pattern.compile(
      "\\b(?:key|name)\\s*=\\s*\"([^\"]+)\"[^>]*?\\bvalue\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern COLON_DOUBLE = Pattern.compile("(?<![\\w.-])\"?([\\w.\\-]+)\"?\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern COLON_SINGLE = Pattern.compile("(?<![\\w.-])'?([\\w.\\-]+)'?\\s*:\\s*'([^']*)'");
  // A whole-line key = value or key: value, as in properties, YAML and .env files.
  private static final Pattern LINE = Pattern.compile(
      "(?m)^([ \\t]*(?:export[ \\t]+|-[ \\t]+)?)([\\w.\\-\\[\\]]+)([ \\t]*[=:][ \\t]*)(?![\"'])(\\S.*?)[ \\t]*$");
  // key=value inside free text, such as a log line or the user's message. Not key: value, which prose uses.
  private static final Pattern INLINE = Pattern.compile("\\b([\\w.\\-]+)\\s*=\\s*([^\\s\"',;&}<>]{4,})");
  private static final Pattern SCHEME_ONLY = Pattern.compile("(?i)(?:bearer|basic)\\s*");

  private static final Set<String> CONFIG_EXTENSIONS = Set.of("properties", "yaml", "yml", "env", "conf", "cfg",
      "ini", "txt");
  private static final Set<String> BLOCKED_EXTENSIONS = Set.of("pem", "key", "p12", "pfx", "jks", "jceks",
      "keystore", "ppk", "kdbx");
  private static final Set<String> BLOCKED_NAMES = Set.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".netrc",
      ".npmrc", ".pgpass", "settings-security.xml");

  private final Map<String, String> byValue = new HashMap<>();
  private final Map<String, String> byPlaceholder = new HashMap<>();
  private boolean enabled = true;
  private int total;

  public synchronized void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public synchronized boolean enabled() {
    return enabled;
  }

  // Values masked so far in this chat.
  public synchronized int total() {
    return total;
  }

  public synchronized void clear() {
    byValue.clear();
    byPlaceholder.clear();
    total = 0;
  }

  // Files that usually hold nothing but secrets: never read, attached or sent, even with masking off.
  public static boolean isBlocked(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    String name = path.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (name.equals(".env") || name.startsWith(".env.") || BLOCKED_NAMES.contains(name)
        || (name.startsWith("credentials") && name.endsWith(".json"))) {
      return true;
    }
    int dot = name.lastIndexOf('.');
    return dot > 0 && BLOCKED_EXTENSIONS.contains(name.substring(dot + 1));
  }

  public static String withheld(String path) {
    return "WITHHELD: " + path + " usually holds only secrets (keys, certificates or credentials), so its content"
        + " is never sent. Work from its name, or ask the user what you need to know about it.";
  }

  // Path is the project file the text comes from, or null for free text such as console output or a message.
  public synchronized Masked mask(String path, String text) {
    if (text == null || text.isEmpty()) {
      return new Masked(text, 0);
    }
    if (isBlocked(path)) {
      total++;
      return new Masked(withheld(path), 1);
    }
    if (!enabled) {
      return new Masked(text, 0);
    }
    int before = total;
    String out = text;
    out = replace(out, PEM, 0, 0);
    out = replace(out, TOKENS, 0, 0);
    out = replace(out, AUTH_SCHEME, 0, 1);
    out = replace(out, URL_PASSWORD, 0, 1);
    out = replace(out, URL_PARAM, 0, 1);
    out = replace(out, JVM_ARG, 1, 3);
    out = replace(out, KEY_VALUE_PAIR, 1, 2);
    out = replace(out, ATTR_DOUBLE, 1, 2);
    out = replace(out, ATTR_SINGLE, 1, 2);
    out = replace(out, COLON_DOUBLE, 1, 2);
    out = replace(out, COLON_SINGLE, 1, 2);
    if (path == null) {
      out = replace(out, INLINE, 1, 2);
    } else if (CONFIG_EXTENSIONS.contains(extension(path))) {
      out = replace(out, LINE, 2, 4);
    }
    return new Masked(out, total - before);
  }

  // For an attachment's content; folder listings and images carry no file text to mask.
  public Attachment protect(Attachment attachment) {
    String source = switch (attachment.kind()) {
      case FOLDER, IMAGE -> null;
      case CONSOLE, PROBLEMS -> "";
      case OPEN_FILE, FILE, SELECTION, DATAWEAVE, PROCESSORS -> attachment.path();
    };
    if (source == null) {
      return attachment;
    }
    Masked masked = mask(source.isEmpty() ? null : source, attachment.content());
    return masked.count() == 0 ? attachment : new Attachment(attachment.kind(), attachment.project(),
        attachment.path(), attachment.lines(), masked.text(), attachment.truncated());
  }

  // Puts the real values back into text the model wrote. Placeholders it made up are left in place.
  public synchronized String unmask(String text) {
    if (text == null || byPlaceholder.isEmpty()) {
      return text;
    }
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = byPlaceholder.get(m.group());
      m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
    }
    m.appendTail(out);
    return out.toString();
  }

  public static boolean hasPlaceholder(String text) {
    return text != null && PLACEHOLDER.matcher(text).find();
  }

  public static boolean isSensitiveName(String name) {
    String bare = name.substring(name.lastIndexOf(':') + 1);
    String lower = bare.toLowerCase(Locale.ROOT);
    for (String suffix : SAFE_SUFFIXES) {
      if (lower.endsWith(suffix) && !lower.equals(suffix)) {
        return false;
      }
    }
    return SENSITIVE_NAME.matcher(lower).find() || CAMEL_KEY.matcher(bare).find();
  }

  // nameGroup 0 means the pattern alone marks the value as secret.
  private String replace(String text, Pattern pattern, int nameGroup, int valueGroup) {
    Matcher m = pattern.matcher(text);
    StringBuilder out = new StringBuilder();
    boolean changed = false;
    while (m.find()) {
      String value = m.group(valueGroup);
      boolean secret = value != null && !keep(value) && (nameGroup == 0 || isSensitiveName(m.group(nameGroup)));
      if (!secret) {
        m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
        continue;
      }
      String whole = m.group();
      int from = m.start(valueGroup) - m.start();
      String replaced = whole.substring(0, from) + placeholder(value) + whole.substring(from + value.length());
      m.appendReplacement(out, Matcher.quoteReplacement(replaced));
      changed = true;
    }
    if (!changed) {
      return text;
    }
    m.appendTail(out);
    return out.toString();
  }

  // References and settings, not secrets: ${db.password}, ${secure::x}, p('x'), #[vars.token], ![encrypted].
  private static boolean keep(String value) {
    String v = value.strip();
    return HARMLESS.contains(v.toLowerCase(Locale.ROOT)) || SCHEME_ONLY.matcher(value).matches()
        || v.startsWith("![") || v.contains("${")
        || v.contains("#[") || v.contains("p(") || PLACEHOLDER.matcher(v).find() || v.chars().allMatch(c -> c == '*');
  }

  private String placeholder(String value) {
    String existing = byValue.get(value);
    total++;
    if (existing != null) {
      return existing;
    }
    String created = "«masked-" + (byValue.size() + 1) + "»";
    byValue.put(value, created);
    byPlaceholder.put(created, value);
    return created;
  }

  private static String extension(String path) {
    String name = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? (name.startsWith(".env") ? "env" : "") : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
