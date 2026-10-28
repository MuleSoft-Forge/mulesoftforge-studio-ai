package com.mulesoftforge.studio.ai.ui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IExecutableExtensionFactory;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.Errors;

// "Send to MuleSoftForge AI" in the context menu of the Mule editor's canvas. Studio builds that menu itself and
// asks providers registered on its org.mule.tooling.messageflow.nodecontextmenu extension point (see plugin.xml)
// to add items, every time it opens. Providers implement Studio's IContextMenuProvider; this factory implements it
// with a proxy, so the plugin needs no compile-time or bundle dependency on Studio's internals.
public class CanvasMenu implements IExecutableExtensionFactory {
  private static final String MESSAGEFLOW_BUNDLE = "org.mule.tooling.messageflow";
  private static final String PROVIDER = "org.mule.tooling.messageflow.editor.IContextMenuProvider";
  private static final String GROUP = "org.eclipse.gef.group.rest";

  @Override
  public Object create() throws CoreException {
    Bundle bundle = Platform.getBundle(MESSAGEFLOW_BUNDLE);
    try {
      if (bundle == null) {
        throw new ClassNotFoundException(MESSAGEFLOW_BUNDLE + " is not installed");
      }
      Class<?> provider = bundle.loadClass(PROVIDER);
      return Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[] {provider}, new Handler());
    } catch (ClassNotFoundException e) {
      throw new CoreException(Status.error("MuleSoftForge AI cannot add to the canvas menu in this Studio", e));
    }
  }

  private static final class Handler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      return switch (method.getName()) {
        case "toString" -> "MuleSoftForge AI canvas menu";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "addActions" -> {
          if (args != null && args.length > 0 && args[0] instanceof IMenuManager menu) {
            addItem(menu);
          }
          yield null;
        }
        default -> null;
      };
    }
  }

  // Shown only when the canvas selection has elements; the selection is read when the menu opens.
  private static void addItem(IMenuManager menu) {
    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IWorkbenchPage page = window == null ? null : window.getActivePage();
    if (page == null) {
      return;
    }
    Attachments.Result selected = CanvasSelection.attach(window.getSelectionService().getSelection(),
        page.getActiveEditor());
    if (selected.attached().isEmpty()) {
      return;
    }
    Action send = new Action("Send to MuleSoftForge AI") {
      @Override
      public void run() {
        try {
          ((ChatView) page.showView(ChatView.ID)).attach(selected);
        } catch (Exception e) {
          Activator.logError("Could not open the MuleSoftForge AI view", e);
          MessageDialog.openError(window.getShell(), "MuleSoftForge AI",
              "Could not open the MuleSoftForge AI view: " + Errors.describe(e));
        }
      }
    };
    send.setImageDescriptor(AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, "icons/forge16.png"));
    send.setToolTipText("Attach the XML of the selected elements to the MuleSoftForge AI chat");
    if (menu.find(GROUP) != null) {
      menu.appendToGroup(GROUP, send);
    } else {
      menu.add(send);
    }
  }
}
