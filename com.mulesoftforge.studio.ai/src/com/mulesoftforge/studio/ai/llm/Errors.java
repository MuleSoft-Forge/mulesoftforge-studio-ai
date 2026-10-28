package com.mulesoftforge.studio.ai.llm;

import java.security.cert.CertPathBuilderException;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;

import dev.langchain4j.exception.HttpException;

public final class Errors {
  private static final String CERTIFICATE_HINT = "The server's HTTPS certificate is not trusted.";

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
