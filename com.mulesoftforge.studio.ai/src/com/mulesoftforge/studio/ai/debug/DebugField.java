package com.mulesoftforge.studio.ai.debug;

import java.util.List;

// One node of the event tree Studio's Mule debugger shows: payload, attributes, a variable, a field inside one, or a
// processor setting. Accessor is the DataWeave expression that reaches it (attributes.headers), null for processor
// settings. Unloaded means Studio has more children it did not send yet.
public record DebugField(String name, String type, String value, String accessor, boolean isStatic, boolean unloaded,
    boolean error, List<DebugField> children) {

  public DebugField {
    children = children == null ? List.of() : List.copyOf(children);
  }

  public DebugField child(String childName) {
    for (DebugField child : children) {
      if (child.name().equals(childName)) {
        return child;
      }
    }
    return null;
  }

  // The child's value, or null when there is no such child.
  public String childValue(String childName) {
    DebugField child = child(childName);
    return child == null ? null : child.value();
  }
}
