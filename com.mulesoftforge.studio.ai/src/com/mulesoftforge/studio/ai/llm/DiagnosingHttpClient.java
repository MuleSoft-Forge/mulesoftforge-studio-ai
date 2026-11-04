package com.mulesoftforge.studio.ai.llm;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * Chat calls, sync and streamed, go through {@link java.net.http.HttpClient#sendAsync} so Cancel can abort the
 * in-flight exchange. Failures keep status, body and headers. Only {@link #stream} is delegated to LangChain4j's
 * JDK client.
 */
final class DiagnosingHttpClient implements HttpClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  // A stream is not limited in total, only in how long the model may stay silent: before the response headers,
  // and between pieces after them (including the first, which a thinking model can be slow to send).
  static volatile Duration STREAM_START = Duration.ofSeconds(120);
  static volatile Duration STREAM_IDLE = Duration.ofSeconds(120);

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
    while (OutputCap.rejects(status, body) && !Objects.equals(OutputCap.lowered(sent), sent)) {
      captured(request, sent, status, body);
      original = OutputCap.lowered(original);
      sent = OutputCap.lowered(sent);
      OutputCap.remember(request.url(), sent);
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

  // Streams on the calling thread, so LangChain4j's handler runs there too: the call blocks until the reply is
  // complete, ReportedCost sees the usage chunk, and Cancel closes the stream. A server that answers with plain JSON
  // instead of a stream gets its reply passed on as one chunk.
  @Override
  public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
    boolean completions = request.url().endsWith("/chat/completions");
    String original = OutputCap.forTarget(request.url(),
        completions ? ChatStream.withUsage(request.body()) : request.body());
    String sent = completions ? ChatCompletions.withCacheBreakpoints(original) : original;
    HttpResponse<InputStream> response;
    try {
      response = send(request, sent, BodyHandlers.ofInputStream(), STREAM_START);
      String error = errorBody(response);
      while (OutputCap.rejects(response.statusCode(), error) && !Objects.equals(OutputCap.lowered(sent), sent)) {
        captured(request, sent, response.statusCode(), error);
        original = OutputCap.lowered(original);
        sent = OutputCap.lowered(sent);
        OutputCap.remember(request.url(), sent);
        response = send(request, sent, BodyHandlers.ofInputStream(), STREAM_START);
        error = errorBody(response);
      }
      if (response.statusCode() == 400 && !Objects.equals(sent, original)) {
        captured(request, sent, response.statusCode(), error);
        sent = original;
        response = send(request, sent, BodyHandlers.ofInputStream(), STREAM_START);
        error = errorBody(response);
      }
      if (error != null) {
        captured(request, sent, response.statusCode(), error);
        listener.onError(new ProviderHttpException(request.method().name(), request.url(), response.statusCode(),
            response.headers().map(), error));
        return;
      }
    } catch (RuntimeException e) {
      listener.onError(e);
      return;
    }
    stream(request, sent, response, parser, listener, completions);
  }

  private void stream(HttpRequest request, String sent, HttpResponse<InputStream> response,
      ServerSentEventParser parser, ServerSentEventListener listener, boolean completions) {
    WatchedStream body = new WatchedStream(response.body(), STREAM_IDLE);
    CompletableFuture<Void> handle = new CompletableFuture<>();
    handle.whenComplete((ignored, error) -> {
      if (error instanceof CancellationException) {
        body.cancel();
      }
    });
    if (inFlight != null) {
      inFlight.set(handle);
    }
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ChatStream chunks = new ChatStream();
    try {
      listener.onOpen(SuccessfulHttpResponse.builder().statusCode(response.statusCode())
          .headers(response.headers().map()).build());
      String type = response.headers().firstValue("Content-Type").orElse("");
      if (completions && !type.contains("event-stream")) {
        String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        ReportedCost.capture(text);
        for (String data : ChatStream.fromCompletion(text)) {
          listener.onEvent(new ServerSentEvent(null, data));
        }
      } else {
        parser.parse(body, new ServerSentEventListener() {
          @Override
          public void onEvent(ServerSentEvent event) {
            if (failure.get() != null) {
              return;
            }
            String data = event.data();
            ProviderHttpException error = completions ? streamError(request, data) : null;
            if (error != null) {
              failure.set(error);
              body.cancel();
              return;
            }
            ReportedCost.capture(data);
            for (String chunk : completions ? chunks.rewrite(data) : List.of(data)) {
              listener.onEvent(new ServerSentEvent(event.event(), chunk));
            }
          }

          @Override
          public void onError(Throwable error) {
            failure.compareAndSet(null, error);
          }
        });
      }
    } catch (Exception e) {
      failure.compareAndSet(null, e);
    } finally {
      body.finish();
      if (inFlight != null) {
        inFlight.compareAndSet(handle, null);
      }
      captured(request, sent, response.statusCode(), body.text());
    }
    if (body.cancelled() && !(failure.get() instanceof ProviderHttpException)) {
      listener.onError(new CancellationException("Cancelled calling " + request.method() + " " + request.url()));
    } else if (body.timedOut()) {
      listener.onError(new TransportException(request.method().name(), request.url(), "The model sent nothing for "
          + STREAM_IDLE.toSeconds() + " s while answering " + request.method() + " " + request.url() + ".",
          new HttpTimeoutException("idle stream")));
    } else if (failure.get() != null) {
      Throwable error = failure.get();
      listener.onError(error instanceof RuntimeException || error instanceof Error ? error
          : new TransportException(request.method().name(), request.url(), "The stream from " + request.url()
              + " broke: " + Errors.describe(error), error));
    } else {
      listener.onClose();
    }
  }

  // An error sent inside a 200 stream, as OpenRouter does when the upstream provider fails mid-answer.
  private static ProviderHttpException streamError(HttpRequest request, String data) {
    if (data == null || !data.contains("\"error\"") || data.contains("\"choices\"")) {
      return null;
    }
    try {
      JsonNode error = JSON.readTree(data).path("error");
      if (error.isMissingNode() || error.isNull()) {
        return null;
      }
      int code = error.path("code").asInt(0);
      return new ProviderHttpException(request.method().name(), request.url(), code >= 400 && code < 600 ? code : 502,
          Map.of(), data);
    } catch (Exception e) {
      return null;
    }
  }

  // The whole body when the status is an error, else null.
  private static String errorBody(HttpResponse<InputStream> response) {
    if (response.statusCode() < 400) {
      return null;
    }
    try (InputStream in = response.body()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  @Override
  public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
    return streaming.stream(request, parser);
  }

  private HttpResponse<String> send(HttpRequest request, String body) {
    return send(request, body, BodyHandlers.ofString(), readTimeout);
  }

  // timeout bounds the wait for the response headers.
  private <T> HttpResponse<T> send(HttpRequest request, String body, BodyHandler<T> handler, Duration timeout) {
    java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
        .uri(URI.create(request.url()))
        .timeout(timeout)
        .method(request.method().name(), body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
      for (String value : header.getValue()) {
        builder.header(header.getKey(), value);
      }
    }

    CompletableFuture<HttpResponse<T>> future = jdk.sendAsync(builder.build(), handler);
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
