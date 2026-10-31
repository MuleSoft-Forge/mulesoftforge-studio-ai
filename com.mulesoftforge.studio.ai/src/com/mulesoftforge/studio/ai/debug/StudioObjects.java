package com.mulesoftforge.studio.ai.debug;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// Reads the objects Studio's Mule debugger hands its listeners (MuleMessageInfo, ObjectFieldDefinition,
// MessageProcessorInfo) by their public getter names, so this needs no compile-time dependency on Studio and can be
// tested with stand-ins that have the same getters. handles keeps each field's FieldPath object by DataWeave accessor,
// for asking Studio to load fields it has not sent yet.
public final class StudioObjects {
  private static final int MAX_DEPTH = 12;
  private static final int MAX_CHILDREN = 500;

  private StudioObjects() {}

  public static DebugStop stop(Object messageInfo, Map<String, Object> handles) {
    Object id = call(messageInfo, "getMuleMessageInfoId");
    Object processor = call(messageInfo, "getMessageProcessorInfo");
    return new DebugStop(string(call(messageInfo, "getAppName")), string(call(id, "getCorrelationId")),
        string(call(processor, "getPath")), string(call(processor, "getIdentifier")),
        fields(call(processor, "getFields"), 0, null), fields(call(messageInfo, "getDefinitions"), 0, handles));
  }

  public static DebugField field(Object definition, Map<String, Object> handles) {
    return field(definition, 0, handles);
  }

  private static DebugField field(Object definition, int depth, Map<String, Object> handles) {
    String accessor = string(call(definition, "getDwAccessor"));
    if (handles != null && accessor != null) {
      Object path = call(definition, "getPath");
      if (path != null) {
        handles.put(accessor, path);
      }
    }
    Object modifiers = call(definition, "getModifiers");
    boolean isStatic = modifiers instanceof Integer m && (m & 8) != 0;
    List<DebugField> children = depth >= MAX_DEPTH ? List.of()
        : fields(call(definition, "getInnerElements"), depth + 1, handles);
    return new DebugField(string(call(definition, "getName")), string(call(definition, "getClassName")),
        string(call(definition, "getValue")), accessor, isStatic,
        Boolean.TRUE.equals(call(definition, "isHasUnloadedChildren")), Boolean.TRUE.equals(call(definition, "isError")),
        children);
  }

  private static List<DebugField> fields(Object list, int depth, Map<String, Object> handles) {
    if (!(list instanceof Collection<?> items)) {
      return List.of();
    }
    List<DebugField> out = new ArrayList<>();
    for (Object item : items) {
      if (out.size() == MAX_CHILDREN) {
        break;
      }
      if (item != null) {
        out.add(field(item, depth, handles));
      }
    }
    return out;
  }

  public static Object call(Object target, String method) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      return m.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }
}
