package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.render.HtmlPage;
import com.mulesoftforge.studio.ai.render.HtmlRenderer;
import com.mulesoftforge.studio.ai.render.Palette;

// Transcript in Eclipse's browser widget: WebKit on macOS and Linux, Edge (WebView2) on Windows.
// The page never navigates; links and buttons call back into Studio.
final class HtmlTranscript implements Transcript {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Browser browser;
  private final Actions actions;
  private final List<String> codeBlocks = new ArrayList<>();
  private final List<String> queued = new ArrayList<>();
  private boolean loaded;

  // Falls back to the plain transcript when the browser engine cannot start.
  static Transcript create(Composite parent, Actions actions, boolean rich) {
    if (rich) {
      try {
        return new HtmlTranscript(parent, actions);
      } catch (Throwable e) {
        Activator.logError("Rich chat view unavailable; using the plain one", e);
      }
    }
    return new PlainTranscript(parent, actions);
  }

  private HtmlTranscript(Composite parent, Actions actions) {
    this.actions = actions;
    // Edge explicitly on Windows: the default there can be Internet Explorer, which cannot run the page.
    boolean windows = SWT.getPlatform().startsWith("win");
    browser = new Browser(parent, windows ? SWT.EDGE : SWT.NONE);
    new BrowserFunction(browser, "msfClick") {
      @Override
      public Object function(Object[] arguments) {
        if (arguments.length == 1 && arguments[0] instanceof String data) {
          browser.getDisplay().asyncExec(() -> Clicks.dispatch(data, codeBlocks, actions));
        }
        return null;
      }
    };
    browser.addProgressListener(new ProgressAdapter() {
      @Override
      public void completed(ProgressEvent event) {
        loaded = true;
        queued.forEach(browser::execute);
        queued.clear();
      }
    });
    browser.addLocationListener(new LocationAdapter() {
      @Override
      public void changing(LocationEvent event) {
        if (loaded) {
          event.doit = false;
        }
      }
    });
    browser.setText(HtmlPage.page(theme(parent), StudioImages.starterIcons()));
  }

  private static HtmlPage.Theme theme(Composite parent) {
    FontData font = JFaceResources.getDefaultFont().getFontData()[0];
    FontData mono = JFaceResources.getTextFont().getFontData()[0];
    Palette palette = ThemeStyles.palette();
    return new HtmlPage.Theme(palette, ThemeStyles.background(), ThemeStyles.foreground(), font.getName(),
        font.getHeight(), mono.getName(), mono.getHeight());
  }

  @Override
  public void applyTheme(Palette palette, Palette.Rgb background, Palette.Rgb foreground) {
    FontData font = JFaceResources.getDefaultFont().getFontData()[0];
    FontData mono = JFaceResources.getTextFont().getFontData()[0];
    HtmlPage.Theme next = new HtmlPage.Theme(palette, background, foreground, font.getName(), font.getHeight(),
        mono.getName(), mono.getHeight());
    run("msfTheme(" + js(HtmlPage.themeVars(next)) + ")");
  }

  @Override
  public Control control() {
    return browser;
  }

  @Override
  public void clear() {
    codeBlocks.clear();
    run("msfClear()");
  }

  @Override
  public void message(String speaker, String text, String context) {
    run("msfMessage(" + js(kind(speaker)) + "," + js(Transcript.label(speaker)) + "," + js(HtmlRenderer.plain(text)) + ","
        + js(context) + ")");
  }

  @Override
  public void markdown(String speaker, String markdown) {
    HtmlRenderer.Html html = HtmlRenderer.markdown(markdown, codeBlocks.size());
    codeBlocks.addAll(html.codeBlocks());
    run("msfMessage(" + js(kind(speaker)) + "," + js(Transcript.label(speaker)) + "," + js(html.html()) + ",null)");
  }

  @Override
  public void step(String text) {
    run("msfStep(" + js(text) + ")");
  }

  @Override
  public void thought(String summary, String text) {
    run("msfThought(" + js(summary) + "," + js(text == null ? "" : text) + ")");
  }

  @Override
  public void live(String status, String preview) {
    run("msfLive(" + (status == null ? "null" : js(status)) + "," + js(preview == null ? "" : preview) + ")");
  }

  @Override
  public void action(String label, String id) {
    run("msfAction(" + js(label) + "," + js(id) + ")");
  }

  @Override
  public void offer(String id, List<String> items, boolean suggested) {
    run("msfOffer(" + js(id) + "," + jsValue(items) + "," + js(HtmlPage.OFFER_BUTTON) + "," + suggested + ")");
  }

  @Override
  public void ask(String id, String question, List<String> choices) {
    run("msfAsk(" + js(id) + "," + js(question) + "," + jsValue(choices) + ")");
  }

  @Override
  public void todos(List<com.mulesoftforge.studio.ai.edit.TodoItem> items) {
    List<java.util.Map<String, String>> payload = new ArrayList<>();
    for (com.mulesoftforge.studio.ai.edit.TodoItem item : items) {
      payload.add(java.util.Map.of(
          "id", item.id(),
          "content", item.content(),
          "status", item.status().wire()));
    }
    run("msfTodos(" + jsValue(payload) + ")");
  }

  @Override
  public void raw(String title, List<RawCall> calls, String open) {
    run("msfRaw(" + js(title) + "," + jsValue(calls) + "," + js(open) + ")");
  }

  @Override
  public void showRaw(boolean show) {
    run("msfShowRaw(" + show + ")");
  }

  private static String kind(String speaker) {
    return switch (speaker) {
      case "You" -> "you";
      case "AI" -> "ai";
      case "Error" -> "error";
      default -> "note";
    };
  }

  private void run(String script) {
    if (loaded) {
      browser.execute(script);
    } else {
      queued.add(script);
    }
  }

  // A JavaScript string literal; JSON string syntax is valid JavaScript.
  private static String js(String value) {
    return value == null ? "null" : jsValue(value);
  }

  private static String jsValue(Object value) {
    try {
      return JSON.writeValueAsString(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
