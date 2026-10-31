package com.mulesoftforge.studio.ai.llm;

/**
 * Network failure before an HTTP status is available (DNS, connect, TLS, interrupt).
 * Distinct from {@link ProviderHttpException}, which carries status and body.
 */
public final class TransportException extends RuntimeException {
  private final String method;
  private final String url;

  public TransportException(String method, String url, String message, Throwable cause) {
    super(message, cause);
    this.method = method;
    this.url = url;
  }

  public String method() {
    return method;
  }

  public String url() {
    return url;
  }
}
