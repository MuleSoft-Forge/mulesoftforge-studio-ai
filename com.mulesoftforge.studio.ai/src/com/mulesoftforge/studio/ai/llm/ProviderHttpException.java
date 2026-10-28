package com.mulesoftforge.studio.ai.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import dev.langchain4j.exception.HttpException;

/**
 * HTTP failure from a model provider. Extends LangChain4j's {@link HttpException} so status-based
 * mapping (401 → AuthenticationException, etc.) still works, while keeping the exact response body
 * and headers for support.
 */
public final class ProviderHttpException extends HttpException {
  private final String method;
  private final String url;
  private final Map<String, List<String>> responseHeaders;
  private final String responseBody;

  public ProviderHttpException(String method, String url, int statusCode,
      Map<String, List<String>> responseHeaders, String responseBody) {
    super(statusCode, format(method, url, statusCode, responseHeaders, responseBody));
    this.method = method;
    this.url = url;
    this.responseHeaders = copyHeaders(responseHeaders);
    this.responseBody = responseBody == null ? "" : responseBody;
  }

  public String method() {
    return method;
  }

  public String url() {
    return url;
  }

  public Map<String, List<String>> responseHeaders() {
    return responseHeaders;
  }

  public String responseBody() {
    return responseBody;
  }

  static String format(String method, String url, int statusCode,
      Map<String, List<String>> responseHeaders, String responseBody) {
    StringBuilder out = new StringBuilder();
    out.append("HTTP ").append(statusCode);
    if (method != null && !method.isBlank()) {
      out.append(' ').append(method.trim().toUpperCase(Locale.ROOT));
    }
    if (url != null && !url.isBlank()) {
      out.append(' ').append(url.trim());
    }
    out.append('\n');
    out.append("Response headers:\n");
    Map<String, List<String>> headers = copyHeaders(responseHeaders);
    if (headers.isEmpty()) {
      out.append("  (none)\n");
    } else {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        for (String value : entry.getValue()) {
          out.append("  ").append(entry.getKey()).append(": ").append(value).append('\n');
        }
      }
    }
    out.append("Response body:\n");
    String body = responseBody == null || responseBody.isBlank() ? "(empty)" : responseBody.trim();
    out.append(body);
    return out.toString();
  }

  // Drop Set-Cookie; keep everything else including x-request-id / retry-after / www-authenticate.
  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
    Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (source == null) {
      return Map.copyOf(new LinkedHashMap<>());
    }
    for (Map.Entry<String, List<String>> entry : source.entrySet()) {
      if (entry.getKey() == null || "set-cookie".equalsIgnoreCase(entry.getKey())) {
        continue;
      }
      List<String> values = new ArrayList<>();
      if (entry.getValue() != null) {
        for (String value : entry.getValue()) {
          if (value != null) {
            values.add(value);
          }
        }
      }
      if (!values.isEmpty()) {
        copy.put(entry.getKey(), List.copyOf(values));
      }
    }
    return Map.copyOf(new LinkedHashMap<>(copy));
  }
}
