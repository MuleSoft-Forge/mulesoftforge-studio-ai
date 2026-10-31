package com.mulesoftforge.studio.ai.ui;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.debug.DebugField;
import com.mulesoftforge.studio.ai.debug.DebugSession;
import com.mulesoftforge.studio.ai.debug.DebugStop;
import com.mulesoftforge.studio.ai.debug.StudioObjects;

// Studio's Mule debugger, reached by reflection like the canvas menu, so a Studio release that changes it turns this
// off instead of breaking the plugin. It listens on the debugger's event bus and keeps the stop the debugger is at.
// Closing the Mule Debugger view clears every listener on that shared bus, so registration is renewed when a debug
// launch starts and whenever that view opens or closes.
public final class StudioDebugger implements DebugSession {
  private static final String BUNDLE = "org.mule.tooling.ui.contribution.debugger";
  private static final String VIEW = BUNDLE + ".view";
  private static final String CALLBACK = "com.mulesoft.mule.debugger.response.callback.IDebuggerResponseCallback";
  private static final Set<String> EVENTS = Set.of("MULE_MESSAGE_ARRIVED", "NEXT_STEP", "RESUMED", "DISCONNECTED");
  private static final StudioDebugger INSTANCE = new StudioDebugger();

  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final Map<Object, Object> handlers = new LinkedHashMap<>();
  private volatile DebugStop stop;
  private volatile Object messageId;
  private volatile Map<String, Object> handles = Map.of();
  private volatile boolean available;
  private boolean started;
  private boolean warned;
  private Bundle bundle;
  private Object bus;

  private StudioDebugger() {}

  public static StudioDebugger get() {
    return INSTANCE;
  }

  // Whether Studio's Mule debugger is installed and could be reached.
  public boolean available() {
    return available;
  }

  // Runs when the debugger changes: stopped somewhere new, stepped off a processor, resumed or disconnected. Called
  // off the UI thread.
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  public synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    bundle = Platform.getBundle(BUNDLE);
    if (bundle == null || !register("start")) {
      return;
    }
    available = true;
    DebugPlugin.getDefault().getLaunchManager().addLaunchListener(new ILaunchListener() {
      @Override
      public void launchAdded(ILaunch launch) {
        register("debug launch");
      }

      @Override
      public void launchChanged(ILaunch launch) {}

      @Override
      public void launchRemoved(ILaunch launch) {}
    });
    if (PlatformUI.isWorkbenchRunning()) {
      PlatformUI.getWorkbench().getDisplay().asyncExec(this::watchDebuggerView);
    }
  }

  @Override
  public DebugStop current() {
    return stop;
  }

  @Override
  public Evaluation evaluate(String script, Duration timeout) throws Exception {
    Object id = stoppedId();
    long started = System.nanoTime();
    CompletableFuture<Evaluation> result = new CompletableFuture<>();
    Object callback = callback((method, args) -> {
      long ms = (System.nanoTime() - started) / 1_000_000;
      switch (method) {
        case "onScriptEvaluation" -> {
          Object field = StudioObjects.call(args[0], "getResult");
          result.complete(new Evaluation(true, text(StudioObjects.call(field, "getValue")),
              text(StudioObjects.call(args[0], "getResultMimeType")), null, ms));
        }
        case "onScriptEvaluationException" -> result.complete(new Evaluation(false, null, null, message(args[0]), ms));
        case "onError" -> result.complete(new Evaluation(false, null, null, String.valueOf(args[0]), ms));
        default -> {}
      }
    });
    method(client(), "executeScript", 3).invoke(client(), script, id, callback);
    return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public DebugField expand(String accessor, Duration timeout) throws Exception {
    Object id = stoppedId();
    Map<String, Object> current = handles;
    Object path = current.get(accessor);
    if (path == null) {
      return null;
    }
    CompletableFuture<DebugField> result = new CompletableFuture<>();
    Object callback = callback((method, args) -> {
      if (method.equals("onInnerFieldsLoaded")) {
        result.complete(StudioObjects.field(args[0], current));
      } else if (method.equals("onError")) {
        result.completeExceptionally(new IllegalStateException(String.valueOf(args[0])));
      }
    });
    method(client(), "loadInnerFields", 3).invoke(client(), path, id, callback);
    return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private Object stoppedId() {
    Object id = messageId;
    if (id == null) {
      throw new IllegalStateException("The debugger is not stopped at a processor now.");
    }
    return id;
  }

  // Unregistering first keeps one registration per event when the bus still has ours.
  private synchronized boolean register(String reason) {
    try {
      Object service = bundle.loadClass(BUNDLE + ".service.MuleDebuggerService").getMethod("getDefault").invoke(null);
      Object current = service.getClass().getMethod("getEventBus").invoke(service);
      Class<?> eventType = bundle.loadClass("org.mule.tooling.utils.eventbus.EventType");
      Class<?> handlerType = bundle.loadClass("org.mule.tooling.utils.eventbus.IEventHandler");
      Method registerListener = current.getClass().getMethod("registerListener", eventType, handlerType);
      Method unregister = current.getClass().getMethod("unRegisterListener", eventType, handlerType);
      if (handlers.isEmpty()) {
        for (Field field : bundle.loadClass(BUNDLE + ".controller.DebuggerEventTypes").getFields()) {
          if (Modifier.isStatic(field.getModifiers()) && EVENTS.contains(field.getName())
              && field.getGenericType() instanceof ParameterizedType generic
              && generic.getActualTypeArguments()[0] instanceof Class<?> type) {
            handlers.put(field.get(null), handler(type));
          }
        }
      }
      if (handlers.size() != EVENTS.size()) {
        throw new IllegalStateException("Studio's debugger events changed: found " + handlers.size());
      }
      for (Map.Entry<Object, Object> entry : handlers.entrySet()) {
        if (current == bus) {
          unregister.invoke(current, entry.getKey(), entry.getValue());
        }
        registerListener.invoke(current, entry.getKey(), entry.getValue());
      }
      bus = current;
      return true;
    } catch (Throwable e) {
      if (!warned) {
        warned = true;
        Activator.logError("MuleSoftForge AI cannot follow Studio's Mule debugger in this Studio (" + reason + ")", e);
      }
      return false;
    }
  }

  private Object handler(Class<?> type) {
    InvocationHandler invocation = (proxy, method, args) -> switch (method.getName()) {
      case "toString" -> "MuleSoftForge AI debugger listener";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "onMessageArrived" -> {
        arrived(args[0]);
        yield null;
      }
      case "onNextStep", "onDebuggerResume", "onDisconnected" -> {
        moved();
        yield null;
      }
      default -> null;
    };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, invocation);
  }

  private void arrived(Object messageInfo) {
    Map<String, Object> newHandles = new ConcurrentHashMap<>();
    DebugStop next = StudioObjects.stop(messageInfo, newHandles);
    handles = newHandles;
    messageId = StudioObjects.call(messageInfo, "getMuleMessageInfoId");
    stop = next;
    changed();
  }

  private void moved() {
    stop = null;
    messageId = null;
    handles = Map.of();
    changed();
  }

  private void changed() {
    for (Runnable listener : listeners) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        Activator.logError("MuleSoftForge AI debugger listener failed", e);
      }
    }
  }

  private Object client() throws Exception {
    Object service = bundle.loadClass(BUNDLE + ".service.MuleDebuggerService").getMethod("getDefault").invoke(null);
    Object client = service.getClass().getMethod("getDebuggerClient").invoke(service);
    if (client == null) {
      throw new IllegalStateException("Studio's debugger is not connected.");
    }
    return client;
  }

  private Object callback(BiConsumer<String, Object[]> onEvent) throws ClassNotFoundException {
    Class<?> type = bundle.loadClass(CALLBACK);
    InvocationHandler invocation = (proxy, method, args) -> switch (method.getName()) {
      case "toString" -> "MuleSoftForge AI debugger callback";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      default -> {
        onEvent.accept(method.getName(), args == null ? new Object[0] : args);
        yield null;
      }
    };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, invocation);
  }

  private static Method method(Object target, String name, int parameters) throws NoSuchMethodException {
    for (Method m : target.getClass().getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == parameters) {
        return m;
      }
    }
    throw new NoSuchMethodException(name + " on " + target.getClass().getName());
  }

  private static String message(Object error) {
    String message = error instanceof Throwable t ? t.getMessage() : null;
    if (message == null || message.isBlank()) {
      message = text(StudioObjects.call(StudioObjects.call(error, "getException"), "getValue"));
    }
    return message == null ? "The debugger could not evaluate the script." : message;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private void watchDebuggerView() {
    IPartListener2 parts = new IPartListener2() {
      @Override
      public void partOpened(IWorkbenchPartReference ref) {
        if (VIEW.equals(ref.getId())) {
          register("Mule Debugger view opened");
        }
      }

      @Override
      public void partClosed(IWorkbenchPartReference ref) {
        if (VIEW.equals(ref.getId())) {
          Display.getCurrent().timerExec(500, () -> register("Mule Debugger view closed"));
        }
      }
    };
    for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
      window.getPartService().addPartListener(parts);
    }
    PlatformUI.getWorkbench().addWindowListener(new IWindowListener() {
      @Override
      public void windowOpened(IWorkbenchWindow window) {
        window.getPartService().addPartListener(parts);
      }

      @Override
      public void windowActivated(IWorkbenchWindow window) {}

      @Override
      public void windowDeactivated(IWorkbenchWindow window) {}

      @Override
      public void windowClosed(IWorkbenchWindow window) {}
    });
  }
}
