package com.mulesoftforge.studio.ai.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoftforge.studio.ai.llm.Network;

// One MCP server over Streamable HTTP (MCP 2025-06-18): JSON-RPC requests POSTed to one URL, answered with JSON or
// an event stream. Keeps the Mcp-Session-Id the server hands out and starts a new session when it expires (404).
// Follows 307 and 308 redirects to the same host only, so the Authorization header never goes elsewhere.
public final class McpClient {
  public static final String PROTOCOL = "2025-06-18";
  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_PAGES = 20;
  private static final ObjectMapper JSON = new ObjectMapper();

  public record Reply(int status, String contentType, String sessionId, String location, String body) {}

  public interface Transport {
    Reply post(URI uri, Map<String, String> headers, String body, Duration timeout)
        throws IOException, InterruptedException;
  }

  // A failure the user and the model can act on; the message never holds a header value.
  public static final class McpException extends Exception {
    private static final long serialVersionUID = 1L;

    public McpException(String message) {
      super(message);
    }
  }

  public static final Transport LIVE = (uri, headers, body, timeout) -> {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
        .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    var response = Network.client().send(request.build(), BodyHandlers.ofString());
    var h = response.headers();
    return new Reply(response.statusCode(), h.firstValue("Content-Type").orElse(""),
        h.firstValue("Mcp-Session-Id").orElse(null), h.firstValue("Location").orElse(null), response.body());
  };

  private final URI url;
  private final Supplier<Map<String, String>> headers;
  private final Transport transport;
  private final Duration timeout;
  private final AtomicLong ids = new AtomicLong();
  private String sessionId;
  private String instructions = "";
  private boolean initialized;

  // headers is asked on every request, so a token Studio refreshes is picked up.
  public McpClient(URI url, Supplier<Map<String, String>> headers, Transport transport, Duration timeout) {
    this.url = url;
    this.headers = headers;
    this.transport = transport;
    this.timeout = timeout;
  }

  public synchronized String instructions() throws McpException {
    ensureInitialized();
    return instructions;
  }

  public synchronized List<JsonNode> listTools() throws McpException {
    ensureInitialized();
    List<JsonNode> tools = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode params = JSON.createObjectNode();
      if (cursor != null) {
        params.put("cursor", cursor);
      }
      JsonNode result = request("tools/list", params, true);
      result.path("tools").forEach(tools::add);
      cursor = result.path("nextCursor").asText(null);
      if (cursor == null || cursor.isEmpty()) {
        break;
      }
    }
    return tools;
  }

  public synchronized JsonNode callTool(String name, JsonNode arguments) throws McpException {
    ensureInitialized();
    ObjectNode params = JSON.createObjectNode();
    params.put("name", name);
    params.set("arguments", arguments == null || !arguments.isObject() ? JSON.createObjectNode() : arguments);
    return request("tools/call", params, true);
  }

  private void ensureInitialized() throws McpException {
    if (initialized) {
      return;
    }
    sessionId = null;
    ObjectNode params = JSON.createObjectNode();
    params.put("protocolVersion", PROTOCOL);
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "MuleSoftForge Studio AI").put("version", "0.5");
    JsonNode result = request("initialize", params, false);
    instructions = result.path("instructions").asText("");
    initialized = true;
    notifyInitialized();
  }

  private void notifyInitialized() throws McpException {
    ObjectNode note = JSON.createObjectNode();
    note.put("jsonrpc", "2.0");
    note.put("method", "notifications/initialized");
    Reply reply = post(note.toString());
    if (reply.status() >= 400) {
      throw new McpException("the server refused notifications/initialized (HTTP " + reply.status() + ")");
    }
  }

  // retry: start a new session once when the server says this one has expired.
  private JsonNode request(String method, ObjectNode params, boolean retry) throws McpException {
    long id = ids.incrementAndGet();
    ObjectNode message = JSON.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("id", id);
    message.put("method", method);
    message.set("params", params);
    Reply reply = post(message.toString());
    if (reply.status() == 404 && sessionId != null && retry) {
      initialized = false;
      ensureInitialized();
      return request(method, params, false);
    }
    if (reply.status() == 401 || reply.status() == 403) {
      throw new McpException("the server refused the sign-in (HTTP " + reply.status() + ")");
    }
    if (reply.status() >= 400) {
      throw new McpException("HTTP " + reply.status() + " for " + method + oneLine(reply.body()));
    }
    JsonNode answer = find(reply, id);
    if (answer == null) {
      throw new McpException("no answer to " + method + " in the server's reply");
    }
    JsonNode error = answer.get("error");
    if (error != null && !error.isNull()) {
      throw new McpException(method + " failed: " + error.path("message").asText("unknown error")
          + (error.has("code") ? " (code " + error.path("code").asText() + ")" : ""));
    }
    if (reply.sessionId() != null && "initialize".equals(method)) {
      sessionId = reply.sessionId();
    }
    return answer.path("result");
  }

  private Reply post(String body) throws McpException {
    URI target = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      Map<String, String> sent = new LinkedHashMap<>(headers.get());
      sent.put("Content-Type", "application/json");
      sent.put("Accept", "application/json, text/event-stream");
      if (sessionId != null) {
        sent.put("Mcp-Session-Id", sessionId);
      }
      if (initialized) {
        sent.put("MCP-Protocol-Version", PROTOCOL);
      }
      Reply reply;
      try {
        reply = transport.post(target, sent, body, timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new McpException("interrupted");
      } catch (IOException e) {
        throw new McpException("could not reach " + url.getHost() + ": " + (e.getMessage() == null
            ? e.getClass().getSimpleName() : e.getMessage()));
      }
      if ((reply.status() == 307 || reply.status() == 308) && reply.location() != null) {
        URI next = target.resolve(reply.location());
        if (!sameOrigin(url, next)) {
          throw new McpException("the server redirects to another host (" + next.getHost() + "), which is not"
              + " followed");
        }
        target = next;
        continue;
      }
      return reply;
    }
    throw new McpException("too many redirects");
  }

  // The JSON-RPC response with this id, from a JSON body or from the events of a stream.
  static JsonNode find(Reply reply, long id) {
    String body = reply.body() == null ? "" : reply.body();
    if (reply.contentType() != null && reply.contentType().toLowerCase().startsWith("text/event-stream")) {
      StringBuilder data = new StringBuilder();
      for (String line : (body + "\n\n").split("\r?\n", -1)) {
        if (line.isEmpty()) {
          JsonNode match = matching(data.toString(), id);
          if (match != null) {
            return match;
          }
          data.setLength(0);
        } else if (line.startsWith("data:")) {
          data.append(data.length() == 0 ? "" : "\n").append(line.substring(5).stripLeading());
        }
      }
      return null;
    }
    return matching(body, id);
  }

  private static JsonNode matching(String text, long id) {
    if (text.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(text);
      if (node.isArray()) {
        for (JsonNode item : node) {
          if (item.path("id").asLong(-1) == id) {
            return item;
          }
        }
        return null;
      }
      return node.path("id").asLong(-1) == id ? node : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
        && a.getPort() == b.getPort();
  }

  private static String oneLine(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String flat = body.replaceAll("\\s+", " ").strip();
    return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
  }
}
