package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;

import com.mulesoftforge.studio.ai.Activator;

// Helpers for posting work back to the UI thread without throwing into the Error Log.
final class Ui {
  private Ui() {}

  static void async(Display display, Widget widget, Runnable work) {
    if (display == null || display.isDisposed()) {
      return;
    }
    display.asyncExec(() -> {
      if (widget != null && widget.isDisposed()) {
        return;
      }
      SafeRunner.run(new ISafeRunnable() {
        @Override
        public void run() {
          work.run();
        }

        @Override
        public void handleException(Throwable exception) {
          Activator.logError("UI update failed", exception);
        }
      });
    });
  }

  static void async(Widget widget, Runnable work) {
    if (widget == null || widget.isDisposed()) {
      return;
    }
    async(widget.getDisplay(), widget, work);
  }
}
