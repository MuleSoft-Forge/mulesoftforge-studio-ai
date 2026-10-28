package com.mulesoftforge.studio.ai;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.mulesoftforge.studio.ai.llm.Network;
import com.mulesoftforge.studio.ai.settings.AiSettings;

public class Activator extends AbstractUIPlugin {
  public static final String PLUGIN_ID = "com.mulesoftforge.studio.ai";

  private static Activator plugin;

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    plugin = this;
    Network.configure(AiSettings.trust());
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    plugin = null;
    super.stop(context);
  }

  public static Activator getDefault() {
    return plugin;
  }

  // A plugin icon such as "icons/mic16.png"; the @2x file is picked up on high-resolution screens.
  public static Image image(String path) {
    ImageRegistry registry = plugin.getImageRegistry();
    Image image = registry.get(path);
    if (image == null) {
      registry.put(path, imageDescriptorFromPlugin(PLUGIN_ID, path));
      image = registry.get(path);
    }
    return image;
  }

  public static void logError(String message, Throwable error) {
    ILog.of(Activator.class).log(Status.error(message, error));
  }
}
