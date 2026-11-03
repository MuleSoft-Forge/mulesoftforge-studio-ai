package com.mulesoftforge.studio.ai.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.launch.AppLauncher;

// Studio's Mule launches through the public Eclipse launch API: the project's own "Mule Applications" launch
// configuration when it has one, otherwise Studio's Run As / Debug As > Mule Application shortcut, which creates it.
final class StudioLauncher implements AppLauncher {
  static final String TYPE = "org.mule.tooling.runtime.muleServerLaunchType";
  private static final String SHORTCUT = "org.mule.tooling.runtime.muleLaunchShortcut";
  private static final String SHORTCUTS = "org.eclipse.debug.ui.launchShortcuts";

  private final Display display;

  StudioLauncher(Display display) {
    this.display = display;
  }

  @Override
  public Running running(String project) {
    Running other = null;
    for (ILaunch launch : manager().getLaunches()) {
      ILaunchConfiguration config = launch.getLaunchConfiguration();
      if (launch.isTerminated() || !isMule(config)) {
        continue;
      }
      Running running = new Running(config.getName(), launch.getLaunchMode());
      if (config.getName().equals(project)) {
        return running;
      }
      other = running;
    }
    return other;
  }

  @Override
  public Deployment deployment(String app) {
    return RuntimeEvents.INSTANCE.deployment(app);
  }

  @Override
  public void start(String project, String mode) throws Exception {
    IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
    if (!target.isOpen()) {
      throw new IllegalStateException("the project " + project + " is not open in Studio.");
    }
    RuntimeEvents.INSTANCE.starting(project);
    ILaunchConfiguration existing = configuration(project);
    ILaunchShortcut shortcut = existing == null ? shortcut() : null;
    AtomicReference<Exception> failure = new AtomicReference<>();
    display.syncExec(() -> {
      try {
        if (existing != null) {
          DebugUITools.launch(existing, mode);
        } else {
          shortcut.launch(new StructuredSelection(target), mode);
        }
      } catch (RuntimeException e) {
        failure.set(e);
      }
    });
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  @Override
  public boolean stop() throws Exception {
    boolean stopped = false;
    for (ILaunch launch : manager().getLaunches()) {
      if (!launch.isTerminated() && isMule(launch.getLaunchConfiguration())) {
        launch.terminate();
        stopped = true;
      }
    }
    return stopped;
  }

  private static ILaunchConfiguration configuration(String project) throws CoreException {
    ILaunchConfigurationType type = manager().getLaunchConfigurationType(TYPE);
    if (type == null) {
      throw new IllegalStateException("this Studio has no Mule Applications launch type.");
    }
    for (ILaunchConfiguration config : manager().getLaunchConfigurations(type)) {
      if (config.getName().equals(project)) {
        return config;
      }
    }
    return null;
  }

  private static ILaunchShortcut shortcut() throws CoreException {
    for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(SHORTCUTS)) {
      if (SHORTCUT.equals(element.getAttribute("id"))) {
        return (ILaunchShortcut) element.createExecutableExtension("class");
      }
    }
    throw new IllegalStateException("Studio's Run As > Mule Application shortcut is not installed.");
  }

  private static boolean isMule(ILaunchConfiguration config) {
    try {
      return config != null && TYPE.equals(config.getType().getIdentifier());
    } catch (CoreException e) {
      return false;
    }
  }

  private static ILaunchManager manager() {
    return DebugPlugin.getDefault().getLaunchManager();
  }

  // The app states Studio's runtime bundle broadcasts from the Mule agent (ON_MULE_RUNTIME_EVENT), reached by
  // reflection like the debugger, so a Studio that changes them leaves run_app reading the console instead. Studio
  // calls onAppDeployed when the app is only created; onAppStarted is when its listeners take requests.
  private static final class RuntimeEvents {
    static final RuntimeEvents INSTANCE = new RuntimeEvents();
    private static final String BUNDLE = "org.mule.tooling.runtime";

    private final Map<String, Deployment> states = new ConcurrentHashMap<>();
    private boolean registered;
    private boolean warned;

    synchronized void starting(String app) {
      register();
      states.remove(app);
    }

    Deployment deployment(String app) {
      return states.get(app);
    }

    private synchronized void register() {
      if (registered) {
        return;
      }
      registered = true;
      try {
        Bundle bundle = Platform.getBundle(BUNDLE);
        if (bundle == null) {
          return;
        }
        Class<?> plugin = bundle.loadClass(BUNDLE + ".MuleRuntimePlugin");
        Object bus = plugin.getMethod("getEventBus").invoke(plugin.getMethod("getDefault").invoke(null));
        Object type = bundle.loadClass(BUNDLE + ".events.MuleRuntimeEventTypes").getField("ON_MULE_RUNTIME_EVENT")
            .get(null);
        Class<?> listener = bundle.loadClass(BUNDLE + ".events.IMuleRuntimeEventListener");
        Object handler = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener},
            (proxy, method, args) -> switch (method.getName()) {
              case "toString" -> "MuleSoftForge Studio AI deployment listener";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> {
                changed(method.getName(), args);
                yield null;
              }
            });
        for (Method method : bus.getClass().getMethods()) {
          if (method.getName().equals("registerListener") && method.getParameterCount() == 2) {
            method.invoke(bus, type, handler);
            return;
          }
        }
        throw new NoSuchMethodException("registerListener on " + bus.getClass().getName());
      } catch (Throwable e) {
        if (!warned) {
          warned = true;
          Activator.logError("MuleSoftForge Studio AI cannot follow Studio's app deployments; run_app reads the"
              + " console instead", e);
        }
      }
    }

    // Each call is (serverId, appName, domainName), except the server ones, which have only the server.
    private void changed(String event, Object[] args) {
      if (event.equals("onServerShutdown")) {
        states.clear();
        return;
      }
      if (args == null || args.length < 2 || !(args[1] instanceof String app)) {
        return;
      }
      switch (event) {
        case "onAppStarted" -> states.put(app, Deployment.STARTED);
        case "onAppDeploymentFailed" -> states.put(app, Deployment.FAILED);
        case "onAppStopped", "onAppUndeployed" -> states.remove(app);
        default -> {}
      }
    }
  }
}
