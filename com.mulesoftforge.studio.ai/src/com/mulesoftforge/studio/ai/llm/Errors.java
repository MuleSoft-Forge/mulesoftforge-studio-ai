package com.mulesoftforge.studio.ai.llm;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import javax.net.ssl.SSLHandshakeException;

import com.mulesoftforge.studio.ai.settings.Provider;

import dev.langchain4j.exception.HttpException;

public final class Errors {
  private static final String CERTIFICATE_HINT = "The server's HTTPS certificate is not trusted.";

  public record UserError(String message, String details, Duration retryAfter) {
    public String transcript() {
      if (details == null || details.isBlank() || details.equals(message)) {
        return message;
      }
      return message + "\n\nDetails:\n" + details;
    }
  }

  private Errors() {}

  /**
   * Support-friendly description of a provider failure. Prefer the exact HTTP status, response
   * headers and body when we have them; otherwise fall back to the exception chain.
   */
  public static String describe(Throwable error) {
    ProviderHttpException provider = find(error, ProviderHttpException.class);
    if (provider != null) {
      return provider.getMessage();
    }

    HttpException http = find(error, HttpException.class);
    if (http != null) {
      return "HTTP " + http.statusCode() + "\nResponse body:\n"
          + (http.getMessage() == null || http.getMessage().isBlank() ? "(empty)" : http.getMessage().trim());
    }

    Set<String> parts = new LinkedHashSet<>();
    for (Throwable t = error; t != null && parts.size() < 4; t = t.getCause()) {
      String message = t.getMessage();
      parts.add(message == null || message.isBlank() ? t.getClass().getSimpleName() : message.trim());
    }
    String text = String.join(" | ", parts);
    if (isCertificateProblem(error) && !text.contains(CERTIFICATE_HINT)) {
      text += "\n\n" + CERTIFICATE_HINT + " On a corporate network (Zscaler, Netskope,"
          + " a TLS-inspecting proxy) ask IT for the root CA certificate as a PEM file and set it under"
          + " Preferences > MuleSoftForge AI > Network. Make sure \"Trust the operating system's certificates\""
          + " is ticked.";
    }
    return text;
  }

  // Short user-facing message with the raw dump kept for a Details section.
  public static UserError classify(Throwable error, Provider provider) {
    if (error instanceof CancellationException || find(error, CancellationException.class) != null
        || find(error, InterruptedException.class) != null) {
      return new UserError("Cancelled.", null, null);
    }
    String label = provider == null ? "The provider" : provider.label;
    HttpException http = find(error, ProviderHttpException.class);
    if (http == null) {
      http = find(error, HttpException.class);
    }
    int status = http == null ? 0 : http.statusCode();
    String details = describe(error);
    Duration retryAfter = retryAfter(error);
    if (status == 401 || status == 403) {
      return new UserError(label + " rejected the API key. Check Settings.", details, null);
    }
    if (status == 404) {
      return new UserError(label + " could not find that model. Check the model id in Settings.", details, null);
    }
    if (status == 429) {
      String wait = retryAfter == null ? "" : " Try again in about " + retryAfter.toSeconds() + " s.";
      return new UserError(label + " is rate limiting requests." + wait, details, retryAfter);
    }
    if (status == 408 || status >= 500) {
      return new UserError(label + " had a server error (HTTP " + status + ").", details, retryAfter);
    }
    if (find(error, HttpTimeoutException.class) != null || find(error, SocketTimeoutException.class) != null) {
      return new UserError("No answer within 180 s.", details, null);
    }
    if (find(error, UnknownHostException.class) != null || find(error, ConnectException.class) != null
        || find(error, SocketException.class) != null || find(error, TransportException.class) != null) {
      return new UserError("Cannot reach " + label + ". Check the network or proxy.", details, null);
    }
    if (isCertificateProblem(error)) {
      return new UserError(CERTIFICATE_HINT + " Set the corporate CA under Preferences > MuleSoftForge AI > Network.",
          details, null);
    }
    return new UserError(details, null, null);
  }

  // Compact line for the Error Log: status and request id only, never the full body dump.
  public static String logSummary(Throwable error) {
    ProviderHttpException provider = find(error, ProviderHttpException.class);
    if (provider != null) {
      String id = header(provider.responseHeaders(), "x-request-id");
      if (id == null) {
        id = header(provider.responseHeaders(), "request-id");
      }
      return "HTTP " + provider.statusCode()
          + (provider.method() == null ? "" : " " + provider.method())
          + (provider.url() == null ? "" : " " + provider.url())
          + (id == null ? "" : " request-id=" + id);
    }
    HttpException http = find(error, HttpException.class);
    if (http != null) {
      return "HTTP " + http.statusCode();
    }
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.trim();
  }

  public static boolean isExpectedFailure(Throwable error) {
    if (error instanceof CancellationException || find(error, CancellationException.class) != null) {
      return true;
    }
    if (find(error, HttpException.class) != null || find(error, TransportException.class) != null
        || find(error, HttpTimeoutException.class) != null || find(error, UnknownHostException.class) != null
        || find(error, ConnectException.class) != null || find(error, SocketException.class) != null
        || isCertificateProblem(error)) {
      return true;
    }
    return false;
  }

  private static Duration retryAfter(Throwable error) {
    ProviderHttpException provider = find(error, ProviderHttpException.class);
    if (provider == null) {
      return null;
    }
    String value = header(provider.responseHeaders(), "retry-after");
    if (value == null) {
      return null;
    }
    try {
      return Duration.ofSeconds(Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String header(Map<String, List<String>> headers, String name) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
          && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }

  private static boolean isCertificateProblem(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String message = t.getMessage() == null ? "" : t.getMessage();
      if (t instanceof SSLHandshakeException || t instanceof CertPathBuilderException
          || message.contains("PKIX path") || message.contains("unable to find valid certification path")) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Throwable> T find(Throwable error, Class<T> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
    }
    return null;
  }
}
