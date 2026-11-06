package com.mulesoftforge.studio.ai.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.render.HtmlPage;

// Studio's own flow, MUnit and Mule icons, read at run time from the org.mule.tooling.images bundle so they match the
// installed Studio and are never copied into this plugin. Outside Studio, or when a path moves, callers get the
// fallback (our icon, or no icon on a starter card). The website card uses our own round logo from this plugin.
final class StudioImages {
  static final String BUNDLE = "org.mule.tooling.images";
  static final String FLOW = "images/palette/flow.png";
  static final String MULE = "images/obj/mule.png";
  static final String MUNIT = "images/obj/munit.png";
  static final String MUNIT_TEST = "images/palette/munit-test.png";
  static final String ERROR_HANDLER = "images/palette/error-handler.png";
  static final String WEBSITE = "icons/forge-round24.png";
  private static final int MAX_BYTES = 64 * 1024;

  private StudioImages() {}

  // For menus and buttons; the @2x file is picked up on high-resolution screens.
  static ImageDescriptor descriptor(String path, String fallbackPluginIcon) {
    if (find(BUNDLE, path) != null) {
      ImageDescriptor studio = AbstractUIPlugin.imageDescriptorFromPlugin(BUNDLE, path);
      if (studio != null) {
        return studio;
      }
    }
    return AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, fallbackPluginIcon);
  }

  static HtmlPage.Icons starterIcons() {
    return new HtmlPage.Icons(dataUri(BUNDLE, FLOW), dataUri(BUNDLE, MULE), dataUri(BUNDLE, MUNIT_TEST),
        dataUri(BUNDLE, ERROR_HANDLER), dataUri(Activator.PLUGIN_ID, WEBSITE));
  }

  // The @2x file when there is one, so the 24 px card icon stays sharp; null when the bundle lacks it.
  private static String dataUri(String bundleId, String path) {
    URL url = find(bundleId, path.replace(".png", "@2x.png"));
    if (url == null) {
      url = find(bundleId, path);
    }
    if (url == null) {
      return null;
    }
    try (InputStream in = url.openStream()) {
      byte[] png = in.readNBytes(MAX_BYTES + 1);
      return png.length > MAX_BYTES ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    } catch (IOException e) {
      return null;
    }
  }

  private static URL find(String bundleId, String path) {
    Bundle bundle = Platform.getBundle(bundleId);
    return bundle == null ? null : FileLocator.find(bundle, new Path(path));
  }
}
