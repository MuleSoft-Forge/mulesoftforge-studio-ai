package com.mulesoftforge.studio.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

// A push button with a coloured fill. Native SWT buttons ignore background colours on macOS, so this one draws
// itself. It sends SWT.Selection like a Button, on click or on Space/Enter when focused.
final class ColorButton extends Canvas {
  enum Tone {
    GO(46, 125, 50),
    STOP(198, 40, 40);

    final int red;
    final int green;
    final int blue;

    Tone(int red, int green, int blue) {
      this.red = red;
      this.green = green;
      this.blue = blue;
    }
  }

  private static final Color TEXT = new Color(255, 255, 255);
  private static final Color DISABLED = new Color(150, 150, 150);
  private static final int PAD_X = 16;
  private static final int PAD_Y = 6;

  private String text = "";
  private Tone tone = Tone.GO;
  private boolean hover;
  private boolean pressed;

  ColorButton(Composite parent) {
    super(parent, SWT.DOUBLE_BUFFERED);
    setFont(TranscriptStyles.bold());
    addListener(SWT.Paint, e -> paint(e.gc));
    addListener(SWT.MouseEnter, e -> setHover(true));
    addListener(SWT.MouseExit, e -> {
      pressed = false;
      setHover(false);
    });
    addListener(SWT.MouseDown, e -> {
      if (e.button == 1 && isEnabled()) {
        pressed = true;
        redraw();
      }
    });
    addListener(SWT.MouseUp, e -> {
      boolean click = pressed && e.button == 1 && getClientArea().contains(e.x, e.y);
      pressed = false;
      redraw();
      if (click) {
        select();
      }
    });
    addListener(SWT.KeyDown, e -> {
      if (e.character == ' ' || e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
        select();
      }
    });
    addListener(SWT.Traverse, e -> {
      if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
        e.doit = true;
      }
    });
    addListener(SWT.FocusIn, e -> redraw());
    addListener(SWT.FocusOut, e -> redraw());
    getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        e.result = text;
      }
    });
    getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
      @Override
      public void getRole(AccessibleControlEvent e) {
        e.detail = ACC.ROLE_PUSHBUTTON;
      }
    });
    setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
  }

  void setText(String text) {
    this.text = text;
    redraw();
  }

  void setTone(Tone tone) {
    this.tone = tone;
    redraw();
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    redraw();
  }

  @Override
  public Point computeSize(int wHint, int hHint, boolean changed) {
    GC gc = new GC(this);
    try {
      gc.setFont(getFont());
      // Wide enough for the longest label it shows, so the layout does not jump between Send and Cancelling….
      Point longest = gc.textExtent("Cancelling…");
      Point current = gc.textExtent(text);
      int width = Math.max(longest.x, current.x) + 2 * PAD_X;
      int height = current.y + 2 * PAD_Y;
      return new Point(wHint == SWT.DEFAULT ? width : wHint, hHint == SWT.DEFAULT ? height : hHint);
    } finally {
      gc.dispose();
    }
  }

  private void setHover(boolean hover) {
    this.hover = hover;
    redraw();
  }

  private void select() {
    if (isEnabled()) {
      notifyListeners(SWT.Selection, new Event());
    }
  }

  private void paint(GC gc) {
    Rectangle area = getClientArea();
    gc.setAntialias(SWT.ON);
    gc.setBackground(getParent().getBackground());
    gc.fillRectangle(area);
    gc.setBackground(isEnabled() ? shade() : DISABLED);
    gc.fillRoundRectangle(0, 0, area.width - 1, area.height - 1, 10, 10);
    if (isFocusControl()) {
      gc.setForeground(TEXT);
      gc.setLineStyle(SWT.LINE_DOT);
      gc.drawRoundRectangle(2, 2, area.width - 5, area.height - 5, 8, 8);
    }
    gc.setForeground(TEXT);
    gc.setFont(getFont());
    Point extent = gc.textExtent(text);
    gc.drawText(text, (area.width - extent.x) / 2, (area.height - extent.y) / 2, true);
  }

  private Color shade() {
    double factor = pressed ? 0.8 : hover ? 1.12 : 1.0;
    return new Color(clamp(tone.red * factor), clamp(tone.green * factor), clamp(tone.blue * factor));
  }

  private static int clamp(double value) {
    return (int) Math.max(0, Math.min(255, Math.round(value)));
  }
}
