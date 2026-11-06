package com.mulesoftforge.studio.ai.feedback;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// 👍 under an answer opens LinkedIn's post composer with this text filled in; the user edits it and decides
// whether to post. Nothing about the chat or the project goes into it.
public final class LinkedInPost {
  public static final String SITE = "https://mulesoftforge.com/studio-ai/";
  public static final String TEXT = "I used MuleSoftForge Studio AI in Anypoint Studio and liked the result. "
      + SITE + " #MuleSoft #AnypointStudio";
  private static final String COMPOSER = "https://www.linkedin.com/feed/?shareActive=true&text=";

  private LinkedInPost() {}

  public static String url() {
    return COMPOSER + URLEncoder.encode(TEXT, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
