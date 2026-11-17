package com.mulesoftforge.studio.ai.settings;

import java.util.Arrays;

// The request format an OpenAI-compatible connection sends. Reasoning models such as GPT-5.6 and GPT-6 refuse
// function tools on Chat Completions unless reasoning is off, and take them with reasoning only on Responses.
public enum WireApi {
  CHAT_COMPLETIONS("Chat Completions (POST /chat/completions)"),
  RESPONSES("Responses (POST /responses)");

  public final String label;

  WireApi(String label) {
    this.label = label;
  }

  public static WireApi fromName(String name) {
    return Arrays.stream(values()).filter(w -> w.name().equals(name)).findFirst().orElse(CHAT_COMPLETIONS);
  }
}
