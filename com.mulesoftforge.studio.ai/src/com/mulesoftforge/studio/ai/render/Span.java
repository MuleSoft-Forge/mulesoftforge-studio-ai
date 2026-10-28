package com.mulesoftforge.studio.ai.render;

// A styled region of rendered text. data carries a link URL or an action id.
public record Span(int start, int length, Kind kind, String data) {
  public enum Kind {
    BOLD,
    ITALIC,
    HEADING,
    INLINE_CODE,
    CODE_BLOCK,
    TABLE,
    LINK,
    ACTION,
    QUOTE,
    // Code tokens
    TAG,
    ATTR_NAME,
    ATTR_VALUE,
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
    KEY,
    EXPRESSION,
    DIRECTIVE
  }

  public Span(int start, int length, Kind kind) {
    this(start, length, kind, null);
  }

  public boolean isCodeToken() {
    return kind.ordinal() >= Kind.TAG.ordinal();
  }

  Span shift(int offset) {
    return new Span(start + offset, length, kind, data);
  }
}
