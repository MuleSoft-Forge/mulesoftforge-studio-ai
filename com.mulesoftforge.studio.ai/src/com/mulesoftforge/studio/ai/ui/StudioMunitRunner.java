package com.mulesoftforge.studio.ai.ui;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.TextConsole;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.launch.MunitRunner;
import com.mulesoftforge.studio.ai.launch.MunitTools;

// Studio's MUnit runner, reached by reflection like the debugger, so a Studio without MUnit or with a changed one
// only loses run_munit. Runs go through MunitStaticRunner, as the MUnit editor's Run and Debug buttons do; results
// come from the RunStatus that MUnit broadcasts to its MUnit view (ON_MUNIT_SUITE_STATUS_CHANGED).
final class StudioMunitRunner implements MunitRunner {
  static final String TYPE = "org.eclipse.jdt.munit.launchconfig";
  private static final String BUNDLE = "org.mule.tooling.ui.contribution.munit";

  private final Display display;
  private volatile ILaunch ours;

  StudioMunitRunner(Display display) {
    this.display = display;
  }

  @Override
  public void start(String project, List<String> suites, List<String> tests, String mode) throws Exception {
    IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
    if (!target.isOpen()) {
      throw new IllegalStateException("the project " + project + " is not open in Studio.");
    }
    Bundle bundle = Platform.getBundle(BUNDLE);
    if (bundle == null) {
      throw new IllegalStateException("this Studio has no MUnit plugin.");
    }
    Method run = bundle.loadClass(BUNDLE + ".runner.MunitStaticRunner").getMethod("runOrDebugMunitTest",
        IProject.class, Set.class, Set.class, String.class, String.class);
    Results.INSTANCE.starting(project);
    Set<ILaunch> before = Set.of(launches());
    ours = null;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    display.syncExec(() -> {
      try {
        // No perspective: the run stays in the user's current one.
        run.invoke(null, target, new LinkedHashSet<>(suites), new LinkedHashSet<>(tests), mode, null);
      } catch (InvocationTargetException e) {
        failure.set(e.getCause());
      } catch (ReflectiveOperationException | RuntimeException e) {
        failure.set(e);
      }
    });
    if (failure.get() instanceof Exception e) {
      throw e;
    }
    if (failure.get() != null) {
      throw new IllegalStateException(failure.get());
    }
    for (ILaunch launch : launches()) {
      if (!before.contains(launch) && isMunit(launch.getLaunchConfiguration())) {
        ours = launch;
      }
    }
  }

  @Override
  public Run run() {
    return Results.INSTANCE.run();
  }

  // The launch started here while Studio still lists it and it has not ended; before any start, any MUnit launch.
  // MUnit's launch first packages the app in a job, with no process yet, and Studio removes it if that fails.
  @Override
  public boolean running() {
    ILaunch mine = ours;
    List<ILaunch> all = List.of(launches());
    if (mine != null) {
      return all.contains(mine) && !mine.isTerminated();
    }
    return all.stream().anyMatch(launch -> !launch.isTerminated() && isMunit(launch.getLaunchConfiguration()));
  }

  @Override
  public String console() {
    ILaunch last = ours;
    if (last == null) {
      for (ILaunch launch : launches()) {
        if (isMunit(launch.getLaunchConfiguration())) {
          last = launch;
        }
      }
    }
    if (last == null) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    for (IProcess process : last.getProcesses()) {
      IConsole console = DebugUITools.getConsole(process);
      if (console instanceof TextConsole textConsole) {
        text.append(textConsole.getDocument().get());
      }
    }
    return text.toString();
  }

  private static ILaunch[] launches() {
    return DebugPlugin.getDefault().getLaunchManager().getLaunches();
  }

  private static boolean isMunit(ILaunchConfiguration config) {
    try {
      return config != null && TYPE.equals(config.getType().getIdentifier());
    } catch (CoreException e) {
      return false;
    }
  }

  // The last RunStatus MUnit broadcast after a run started here. RunStatus keeps suites and tests behind package
  // methods, read by reflection; MUnit updates them while it runs, so a read that collides is retried next poll.
  private static final class Results {
    static final Results INSTANCE = new Results();

    private volatile Object status;
    private volatile Object previous;
    private volatile String project;
    private volatile boolean armed;
    private boolean registered;
    private boolean warned;

    synchronized void starting(String app) {
      register();
      project = app;
      previous = status;
      status = null;
      armed = true;
    }

    Run run() {
      Object current = status;
      if (current == null) {
        return null;
      }
      try {
        return read(current);
      } catch (ReflectiveOperationException | RuntimeException e) {
        return null;
      }
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
        Object bus = bundle.loadClass(BUNDLE + ".MunitPlugin").getMethod("getEventBus").invoke(null);
        Object type = bundle.loadClass(BUNDLE + ".event.RunStatusEvent").getField("ON_MUNIT_SUITE_STATUS_CHANGED")
            .get(null);
        Class<?> listener = bundle.loadClass(BUNDLE + ".event.IMunitRunStatusChangedListener");
        Object handler = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener},
            (proxy, method, args) -> switch (method.getName()) {
              case "toString" -> "MuleSoftForge Studio AI MUnit listener";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> {
                if (method.getName().equals("changed") && args != null && args.length == 1) {
                  changed(args[0]);
                }
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
          Activator.logError("MuleSoftForge Studio AI cannot read MUnit results; run_munit reports only the console",
              e);
        }
      }
    }

    // A new launch makes MUnit end the previous run's status, which then arrives finished with its old counts; a
    // status is the new run's only once it has been seen unfinished.
    private synchronized void changed(Object runStatus) {
      if (!armed || runStatus == null || runStatus == previous || runStatus == status) {
        return;
      }
      try {
        if ((Boolean) call(runStatus, "isFinished")) {
          return;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Unreadable; taken as live.
      }
      try {
        Object target = runStatus.getClass().getMethod("getProject").invoke(runStatus);
        if (target instanceof IProject p && project != null && !p.getName().equals(project)) {
          return;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Without the project, the run is taken to be the one started here.
      }
      status = runStatus;
    }

    private static Run read(Object runStatus) throws ReflectiveOperationException {
      boolean finished = (Boolean) call(runStatus, "isFinished");
      int tests = (Integer) call(runStatus, "getNumberOfTests");
      List<String> errors = new ArrayList<>();
      if ((Boolean) call(runStatus, "hasContainerStartFailed")) {
        errors.add(String.valueOf(call(runStatus, "getCause")));
      }
      List<Test> results = new ArrayList<>();
      for (Object suite : (Collection<?>) call(runStatus, "getSuites")) {
        String path = suitePath((String) call(suite, "getPath"));
        if (Boolean.TRUE.equals(field(suite, "error"))) {
          errors.add(path + ": " + call(suite, "getCause"));
        }
        for (Object test : (Collection<?>) call(suite, "getTests")) {
          if (!(Boolean) call(test, "isFinished")) {
            continue;
          }
          Outcome outcome = (Boolean) call(test, "isIgnored") ? Outcome.IGNORED
              : (Boolean) call(test, "hasError") ? Outcome.ERROR
              : (Boolean) call(test, "hasFailed") ? Outcome.FAILED : Outcome.PASSED;
          results.add(new Test(path, (String) call(test, "getName"), outcome, (String) call(test, "getCause")));
        }
      }
      return new Run(finished, tests, results, errors.isEmpty() ? null : String.join("\n", errors));
    }

    private static String suitePath(String path) {
      if (path == null) {
        return "";
      }
      String normal = path.replace('\\', '/');
      int at = normal.indexOf(MunitTools.FOLDER);
      return at < 0 ? normal : normal.substring(at + MunitTools.FOLDER.length());
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
        try {
          Method method = type.getDeclaredMethod(name);
          method.setAccessible(true);
          return method.invoke(target);
        } catch (NoSuchMethodException e) {
          // Declared further up.
        }
      }
      throw new NoSuchMethodException(name + " on " + target.getClass().getName());
    }

    private static Object field(Object target, String name) {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
        try {
          Field field = type.getDeclaredField(name);
          field.setAccessible(true);
          return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
          // Declared further up, or not at all.
        }
      }
      return null;
    }
  }
}
