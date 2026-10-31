import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

// The child JVM of check-workspace: Equinox from Anypoint Studio's plugins with the resources plugin and the check
// bundle, printing what WorkspaceCheck returns. Arguments: work folder, check bundle, then the bundles to install.
public final class WorkspaceLauncher {
  private WorkspaceLauncher() {}

  public static void main(String[] args) {
    int status = 0;
    try {
      launch(new File(args[0]), new File(args[1]), List.of(args).subList(2, args.length));
    } catch (Throwable e) {
      e.printStackTrace(System.out);
      status = 1;
    }
    System.exit(status);
  }

  private static void launch(File work, File checkBundle, List<String> jars) throws Exception {
    Map<String, String> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, new File(work, "configuration").getAbsolutePath());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    config.put("osgi.instance.area", new File(work, "workspace").getAbsolutePath());
    Framework framework = ServiceLoader.load(FrameworkFactory.class).iterator().next().newFramework(config);
    framework.start();
    try {
      BundleContext context = framework.getBundleContext();
      List<Bundle> bundles = new ArrayList<>();
      for (String jar : jars) {
        bundles.add(context.installBundle("reference:" + new File(jar).toURI()));
      }
      Bundle check = context.installBundle("reference:" + checkBundle.toURI());
      bundles.add(check);
      for (Bundle bundle : bundles) {
        if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) == null) {
          try {
            bundle.start();
          } catch (BundleException e) {
            System.out.println(bundle.getSymbolicName() + " did not start: " + e.getMessage());
          }
        }
      }
      @SuppressWarnings("unchecked")
      List<String> results = (List<String>) check.loadClass("com.mulesoftforge.studio.ai.ui.WorkspaceCheck")
          .getMethod("run").invoke(null);
      results.forEach(System.out::println);
    } finally {
      framework.stop();
      framework.waitForStop(20_000);
    }
  }
}
