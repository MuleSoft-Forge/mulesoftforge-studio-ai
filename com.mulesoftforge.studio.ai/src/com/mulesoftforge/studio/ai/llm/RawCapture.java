package com.mulesoftforge.studio.ai.llm;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

// The model calls of one sent message. Providers on DiagnosingHttpClient record the exact HTTP exchange (method,
// URL, status, bodies); Azure OpenAI and Bedrock use their own SDK clients, so only LangChain4j's request and
// response are known for them. Headers are never recorded: they carry API keys and gateway secrets.
public final class RawCapture {
  public record Http(String method, String url, Integer status, String request, String response) {}

  // request and response are LangChain4j's view, as JSON; http is null when the wire exchange is not visible.
  public record Call(int turn, int step, String at, String provider, String model, Double seconds, Http http,
      String request, String response, String error) {

    public String heading() {
      StringBuilder out = new StringBuilder("Step " + step);
      if (http != null) {
        out.append(" · ").append(http.method()).append(' ').append(http.url());
        out.append(" · ").append(http.status() == null ? "no response" : "HTTP " + http.status());
      } else {
        out.append(" · ").append(provider).append(" (HTTP not visible for this provider)");
      }
      if (model != null) {
        out.append(" · ").append(model);
      }
      if (seconds != null) {
        out.append(" · ").append(String.format(Locale.ROOT, "%.1f s", seconds));
      }
      if (error != null) {
        out.append(" · failed");
      }
      return out.toString();
    }

    public String requestText() {
      return pretty(http != null ? http.request() : request);
    }

    public String responseText() {
      String body = http != null && http.response() != null ? http.response() : response;
      String text = body == null ? "" : pretty(body);
      return error == null ? text : (text.isEmpty() ? "" : text + "\n\n") + error;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final Pattern BASE64 = Pattern.compile("(data:[\\w/+.-]+;base64,)?[A-Za-z0-9+/=\\r\\n]+");
  private static final int LONG_DATA = 2_000;

  private final int turn;
  private final String provider;
  private final Consumer<Call> onCall;
  private final List<Call> calls = new ArrayList<>();
  private Pending pending;

  private static final class Pending {
    final int step;
    final String at;
    final long started;
    final String model;
    final String request;
    Http http;

    Pending(int step, String model, String request) {
      this.step = step;
      this.at = Instant.now().toString();
      this.started = System.nanoTime();
      this.model = model;
      this.request = request;
    }
  }

  // onCall runs on the model's thread as each call finishes, for example to append it to the raw log.
  public RawCapture(int turn, String provider, Consumer<Call> onCall) {
    this.turn = turn;
    this.provider = provider;
    this.onCall = onCall;
  }

  public synchronized List<Call> calls() {
    return List.copyOf(calls);
  }

  public ChatModelListener listener() {
    return new ChatModelListener() {
      @Override
      public void onRequest(ChatModelRequestContext context) {
        started(context.chatRequest());
      }

      @Override
      public void onResponse(ChatModelResponseContext context) {
        ChatResponse response = context.chatResponse();
        finished(response.modelName(), json(response(response)), null);
      }

      @Override
      public void onError(ChatModelErrorContext context) {
        Throwable error = context.error();
        finished(null, null, error.getClass().getSimpleName() + ": " + error.getMessage());
      }
    };
  }

  private synchronized void started(ChatRequest request) {
    pending = new Pending(calls.size() + 1, request.modelName(), json(request(request)));
  }

  // From DiagnosingHttpClient, on the same thread, between the listener's request and response.
  synchronized void http(String method, String url, Integer status, String request, String response) {
    if (pending != null) {
      pending.http = new Http(method, withoutKey(url), status, request, response);
    }
  }

  private void finished(String model, String response, String error) {
    Call call;
    synchronized (this) {
      if (pending == null) {
        return;
      }
      call = new Call(turn, pending.step, pending.at, provider, model != null ? model : pending.model,
          (System.nanoTime() - pending.started) / 1e9, pending.http, pending.request, response, error);
      calls.add(call);
      pending = null;
    }
    onCall.accept(call);
  }

  // Some gateways accept the key as a query parameter.
  private static String withoutKey(String url) {
    return url == null ? null : url.replaceAll("(?i)([?&](?:key|api[-_]?key|access_token)=)[^&]*", "$1«hidden»");
  }

  // Pretty JSON for reading, with long inline image or file data shortened. Not JSON: returned as sent.
  static String pretty(String body) {
    if (body == null || body.isBlank()) {
      return body == null ? "" : body;
    }
    try {
      JsonNode tree = JSON.readTree(body);
      if (tree == null || !(tree.isContainerNode())) {
        return body;
      }
      return JSON.writeValueAsString(shortenData(tree));
    } catch (IOException e) {
      return body;
    }
  }

  private static JsonNode shortenData(JsonNode node) {
    if (node instanceof ObjectNode object) {
      Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        field.setValue(shortenData(field.getValue()));
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, shortenData(array.get(i)));
      }
    } else if (node.isTextual()) {
      String text = node.asText();
      if (text.length() > LONG_DATA && BASE64.matcher(text).matches()) {
        return TextNode.valueOf("«" + text.length() + " characters of encoded data, kept in full in the raw log»");
      }
    }
    return node;
  }

  private static ObjectNode request(ChatRequest request) {
    ObjectNode out = JSON.createObjectNode();
    out.put("model", request.modelName());
    putIfSet(out, "temperature", request.temperature());
    putIfSet(out, "maxOutputTokens", request.maxOutputTokens());
    if (request.toolChoice() != null) {
      out.put("toolChoice", request.toolChoice().name());
    }
    out.set("messages", tree(ChatMessageSerializer.messagesToJson(request.messages())));
    List<ToolSpecification> tools = request.toolSpecifications();
    if (tools != null && !tools.isEmpty()) {
      ArrayNode specs = out.putArray("tools");
      for (ToolSpecification tool : tools) {
        specs.add(tree(tool.toJson()));
      }
    }
    return out;
  }

  private static ObjectNode response(ChatResponse response) {
    ObjectNode out = JSON.createObjectNode();
    out.put("id", response.id());
    out.put("model", response.modelName());
    if (response.finishReason() != null) {
      out.put("finishReason", response.finishReason().name());
    }
    TokenUsage usage = response.tokenUsage();
    if (usage != null) {
      ObjectNode tokens = out.putObject("tokenUsage");
      putIfSet(tokens, "input", usage.inputTokenCount());
      putIfSet(tokens, "output", usage.outputTokenCount());
      putIfSet(tokens, "total", usage.totalTokenCount());
    }
    out.set("message", tree(ChatMessageSerializer.messageToJson(response.aiMessage())));
    return out;
  }

  private static void putIfSet(ObjectNode node, String name, Number value) {
    if (value instanceof Integer i) {
      node.put(name, i);
    } else if (value instanceof Double d) {
      node.put(name, d);
    } else if (value != null) {
      node.put(name, value.toString());
    }
  }

  private static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (IOException e) {
      return TextNode.valueOf(json);
    }
  }

  private static String json(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (IOException e) {
      return node.toString();
    }
  }
}
