package com.mulesoftforge.studio.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;

// Popup menus must be disposed after they hide; otherwise each open leaks a native menu until the view closes.
final class Menus {
  private Menus() {}

  static void disposeOnHide(Menu menu) {
    // Selection arrives after Hide on some platforms, so dispose on the next event-loop turn.
    menu.addListener(SWT.Hide, e -> e.display.asyncExec(() -> {
      if (!menu.isDisposed()) {
        menu.dispose();
      }
    }));
  }
}
