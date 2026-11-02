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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoftforge.studio.ai.render.HtmlPage;

// A small page in Eclipse's browser (WebKit on macOS and Linux, Edge on Windows) below the transcript. The page
// calls one browser function with (kind, value); its "height" calls resize the widget to fit, within limits.
// Scripts sent before the page has loaded wait until it has.
final class PageBrowser {
  static final String HEIGHT = "height";

  interface Handler {
    void handle(String kind, String value);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Composite root;
  private final Browser browser;
  private final GridData layout;
  private final int minHeight;
  private final int maxHeight;
  private final List<String> queued = new ArrayList<>();
  private boolean loaded;

  // Throws when the browser engine cannot start.
  PageBrowser(Composite root, String function, int minHeight, int maxHeight, Handler handler) {
    this.root = root;
    this.minHeight = minHeight;
    this.maxHeight = maxHeight;
    boolean windows = SWT.getPlatform().startsWith("win");
    browser = new Browser(root, windows ? SWT.EDGE : SWT.NONE);
    layout = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
    layout.heightHint = minHeight;
    browser.setLayoutData(layout);
    new BrowserFunction(browser, function) {
      @Override
      public Object function(Object[] arguments) {
        if (arguments.length == 2 && arguments[0] instanceof String kind && arguments[1] instanceof String value) {
          browser.getDisplay().asyncExec(() -> {
            if (browser.isDisposed()) {
              return;
            }
            if (HEIGHT.equals(kind)) {
              fitHeight(parse(value));
            } else {
              handler.handle(kind, value);
            }
          });
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
  }

  Browser browser() {
    return browser;
  }

  void load(String html) {
    browser.setText(html);
  }

  void run(String script) {
    if (browser.isDisposed()) {
      return;
    }
    if (loaded) {
      browser.execute(script);
    } else {
      queued.add(script);
    }
  }

  // Shows or hides the page and takes it out of the layout while hidden.
  void setShown(boolean shown) {
    layout.exclude = !shown;
    browser.setVisible(shown);
    root.layout(true, true);
  }

  // "x,y" in page pixels to a display point, or null.
  Point toDisplay(String value) {
    String[] xy = value.split(",");
    return xy.length == 2 ? browser.toDisplay(parse(xy[0]), parse(xy[1])) : null;
  }

  private void fitHeight(int height) {
    int clamped = Math.max(minHeight, Math.min(maxHeight, height));
    if (height > 0 && clamped != layout.heightHint) {
      layout.heightHint = clamped;
      root.layout(true, true);
    }
  }

  static int parse(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static HtmlPage.Theme theme() {
    FontData font = JFaceResources.getDefaultFont().getFontData()[0];
    FontData mono = JFaceResources.getTextFont().getFontData()[0];
    return new HtmlPage.Theme(ThemeStyles.palette(), ThemeStyles.background(), ThemeStyles.foreground(),
        font.getName(), font.getHeight(), mono.getName(), mono.getHeight());
  }

  // A JavaScript literal. JSON is valid JavaScript, except for two line separators that strings cannot hold raw.
  static String js(Object value) {
    try {
      return JSON.writeValueAsString(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
