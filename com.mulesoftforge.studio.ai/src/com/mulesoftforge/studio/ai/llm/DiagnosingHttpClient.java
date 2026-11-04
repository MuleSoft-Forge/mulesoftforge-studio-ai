package com.mulesoftforge.studio.ai.llm;

import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * Sync chat calls go through {@link java.net.http.HttpClient#sendAsync} so Cancel can abort the
 * in-flight exchange. Failures keep status, body and headers. Streaming is delegated to
 * LangChain4j's JDK client (headers on stream errors are not enriched yet).
 */
final class DiagnosingHttpClient implements HttpClient {
  private final java.net.http.HttpClient jdk;
  private final Duration readTimeout;
  private final HttpClient streaming;
  private final RawCapture capture;
  private final AtomicReference<CompletableFuture<?>> inFlight;

  // capture, when not null, receives each sync exchange without headers.
  // inFlight, when not null, holds the current sendAsync future so Job.canceling() can abort it.
  DiagnosingHttpClient(java.net.http.HttpClient jdk, Duration readTimeout, HttpClient streaming,
      RawCapture capture, AtomicReference<CompletableFuture<?>> inFlight) {
    this.jdk = jdk;
    this.readTimeout = readTimeout == null ? Duration.ofSeconds(180) : readTimeout;
    this.streaming = streaming;
    this.capture = capture;
    this.inFlight = inFlight;
  }

  @Override
  public SuccessfulHttpResponse execute(HttpRequest request) {
    // Multipart / form uploads stay on the stock client; chat completions are JSON bodies.
    if (hasFormData(request)) {
      try {
        return streaming.execute(request);
      } catch (HttpException e) {
        throw new ProviderHttpException(request.method().name(), request.url(), e.statusCode(),
            Map.of(), e.getMessage());
      }
    }

    String original = OutputCap.forTarget(request.url(), request.body());
    String sent = request.url().endsWith("/chat/completions")
        ? ChatCompletions.withCacheBreakpoints(original) : original;
    HttpResponse<String> response = send(request, sent);
    int status = response.statusCode();
    String body = response.body() == null ? "" : response.body();
    if (OutputCap.rejects(status, body) && !Objects.equals(OutputCap.lowered(sent), sent)) {
      captured(request, sent, status, body);
      OutputCap.remember(request.url(), sent);
      original = OutputCap.lowered(original);
      sent = OutputCap.lowered(sent);
      response = send(request, sent);
      status = response.statusCode();
      body = response.body() == null ? "" : response.body();
    }
    // A gateway that does not accept cache markers gets the request as LangChain4j wrote it.
    if (status == 400 && !Objects.equals(sent, original)) {
      captured(request, sent, status, body);
      sent = original;
      response = send(request, sent);
      status = response.statusCode();
      body = response.body() == null ? "" : response.body();
    }
    // Raw keeps what the gateway sent; LangChain4j gets a single choice when a model returned several.
    captured(request, sent, status, body);
    Map<String, List<String>> headers = response.headers().map();
    if (status >= 400) {
      throw new ProviderHttpException(request.method().name(), request.url(), status, headers, body);
    }
    body = ChatCompletions.collapseChoices(body);
    ReportedCost.capture(body);
    return SuccessfulHttpResponse.builder()
        .statusCode(status)
        .headers(headers)
        .body(body)
        .build();
  }

  @Override
  public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
    return streaming.executeAsync(request);
  }

  @Override
  public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
    streaming.execute(request, parser, listener);
  }

  @Override
  public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
    return streaming.stream(request, parser);
  }

  private HttpResponse<String> send(HttpRequest request, String body) {
    java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
        .uri(URI.create(request.url()))
        .timeout(readTimeout)
        .method(request.method().name(), body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
      for (String value : header.getValue()) {
        builder.header(header.getKey(), value);
      }
    }

    CompletableFuture<HttpResponse<String>> future = jdk.sendAsync(builder.build(), BodyHandlers.ofString());
    if (inFlight != null) {
      inFlight.set(future);
    }
    try {
      return future.join();
    } catch (CancellationException e) {
      captured(request, body, null, null);
      throw e;
    } catch (CompletionException e) {
      captured(request, body, null, null);
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Interrupted calling " + request.method() + " " + request.url());
      }
      throw new TransportException(request.method().name(), request.url(),
          "Transport failure calling " + request.method() + " " + request.url() + ": " + Errors.describe(cause),
          cause);
    } finally {
      if (inFlight != null) {
        inFlight.compareAndSet(future, null);
      }
    }
  }

  private void captured(HttpRequest request, String sent, Integer status, String body) {
    if (capture != null) {
      capture.http(request.method().name(), request.url(), status, sent, body);
    }
  }

  private static boolean hasFormData(HttpRequest request) {
    return (request.formDataFields() != null && !request.formDataFields().isEmpty())
        || (request.formDataFiles() != null && !request.formDataFiles().isEmpty());
  }

}
