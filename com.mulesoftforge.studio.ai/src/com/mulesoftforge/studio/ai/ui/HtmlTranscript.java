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
    browser.setText(HtmlPage.page(theme(parent)));
  }

  private static HtmlPage.Theme theme(Composite parent) {
    Palette.Rgb background = TranscriptStyles.rgb(parent.getBackground().getRGB());
    FontData font = JFaceResources.getDefaultFont().getFontData()[0];
    FontData mono = JFaceResources.getTextFont().getFontData()[0];
    return new HtmlPage.Theme(Palette.forBackground(background), background,
        TranscriptStyles.rgb(parent.getForeground().getRGB()), font.getName(), font.getHeight(), mono.getName(),
        mono.getHeight());
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
    run("msfMessage(" + js(kind(speaker)) + "," + js(speaker) + "," + js(HtmlRenderer.plain(text)) + ","
        + js(context) + ")");
  }

  @Override
  public void markdown(String speaker, String markdown) {
    HtmlRenderer.Html html = HtmlRenderer.markdown(markdown, codeBlocks.size());
    codeBlocks.addAll(html.codeBlocks());
    run("msfMessage(" + js(kind(speaker)) + "," + js(speaker) + "," + js(html.html()) + ",null)");
  }

  @Override
  public void step(String text) {
    run("msfStep(" + js(text) + ")");
  }

  @Override
  public void action(String label, String id) {
    run("msfAction(" + js(label) + "," + js(id) + ")");
  }

  @Override
  public void offer(String id, List<String> items) {
    try {
      run("msfOffer(" + js(id) + "," + JSON.writeValueAsString(items).replace("\u2028", "\\u2028")
          .replace("\u2029", "\\u2029") + "," + js(HtmlPage.OFFER_BUTTON) + ")");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
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
    try {
      return value == null ? "null" : JSON.writeValueAsString(value).replace("\u2028", "\\u2028")
          .replace("\u2029", "\\u2029");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
