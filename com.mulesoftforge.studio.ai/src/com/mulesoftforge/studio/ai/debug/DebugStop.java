package com.mulesoftforge.studio.ai.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Where Studio's Mule debugger stopped and what the event held there. processorPath is Studio's location of the
// processor, e.g. get:\employees:api-config/processors/1 or main/errorHandler/7/processors/0. fields are the
// top-level entries Studio shows: payload, attributes, vars, error (on an error), rootId, correlationId, and one
// named after the processor with its settings as the runtime evaluated them.
public record DebugStop(String app, String correlationId, String processorPath, String processorType,
    List<DebugField> processorFields, List<DebugField> fields) {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> EVENT_FIELDS = Set.of("payload", "attributes", "vars", "error", "rootId",
      "correlationId");

  public DebugStop {
    processorFields = processorFields == null ? List.of() : List.copyOf(processorFields);
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public DebugField field(String name) {
    for (DebugField field : fields) {
      if (field.name().equals(name)) {
        return field;
      }
    }
    return null;
  }

  // The processor's settings as the runtime evaluated them, the entry that is not part of the event itself.
  public DebugField processorNode() {
    for (DebugField field : fields) {
      if (!EVENT_FIELDS.contains(field.name())) {
        return field;
      }
    }
    return null;
  }

  public DebugField error() {
    return field("error");
  }

  public String docName() {
    return setting("doc:name");
  }

  public String docId() {
    return setting("doc:id");
  }

  // The flow or error handler owner: the part of the path before the first processor segment.
  public String flowName() {
    if (processorPath == null) {
      return null;
    }
    int end = processorPath.length();
    for (String marker : new String[] {"/processors/", "/errorHandler/", "/route/"}) {
      int at = processorPath.indexOf(marker);
      if (at > 0 && at < end) {
        end = at;
      }
    }
    return processorPath.substring(0, end);
  }

  private String setting(String name) {
    for (DebugField field : processorFields) {
      if (field.name().equals(name)) {
        return field.value();
      }
    }
    return null;
  }

  // The format the development probe and the harness fixtures use (see harness/fixtures/debug).
  public static DebugStop fromJson(String json) throws Exception {
    JsonNode root = JSON.readTree(json);
    JsonNode processor = root.path("processor");
    return new DebugStop(text(root, "appName"), text(root.path("messageId"), "correlationId"),
        text(processor, "path"), text(processor, "identifier"), fields(processor.path("fields")),
        fields(root.path("definitions")));
  }

  public static DebugField fieldFromJson(JsonNode node) {
    return new DebugField(text(node, "name"), text(node, "className"), text(node, "value"), text(node, "dwAccessor"),
        (node.path("modifiers").asInt(0) & 8) != 0, node.path("hasUnloadedChildren").asBoolean(false),
        node.path("error").asBoolean(false), fields(node.path("children")));
  }

  private static List<DebugField> fields(JsonNode array) {
    List<DebugField> out = new ArrayList<>();
    if (array.isArray()) {
      for (JsonNode node : array) {
        if (node.has("name")) {
          out.add(fieldFromJson(node));
        }
      }
    }
    return out;
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }
}
