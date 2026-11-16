package com.mulesoftforge.studio.ai.settings;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

// A flat store of string settings. A missing key reads as "".
public interface KeyValues {
  String get(String key);

  Map<String, String> all();

  // A null value removes the key. Returns the keys whose value changed.
  Set<String> update(Map<String, String> changes) throws IOException;
}
