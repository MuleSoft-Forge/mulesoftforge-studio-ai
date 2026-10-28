package com.mulesoftforge.studio.ai.ui;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

import com.mulesoftforge.studio.ai.Activator;

// Starts the operating system's dictation in a text box. Studio never records audio itself: its macOS app has no
// microphone permission, so the system's own Dictation, which has it, listens and types into the box.
final class Dictation {
  private Dictation() {}

  static String hint() {
    return switch (Platform.getOS()) {
      case Platform.OS_MACOSX -> "Speak instead of typing (macOS Dictation). Click Done or press Fn to stop.";
      case Platform.OS_WIN32 -> "Speak instead of typing (Windows voice typing, Windows+H).";
      default -> "Speak instead of typing, using your desktop's dictation.";
    };
  }

  // Null when dictation started; otherwise what to tell the user.
  static String start(Text input) {
    input.setFocus();
    try {
      return switch (Platform.getOS()) {
        case Platform.OS_MACOSX -> startMac() ? null
            : "macOS did not start Dictation. Turn it on in System Settings > Keyboard > Dictation, then click the"
                + " mic again.";
        case Platform.OS_WIN32 -> {
          Windows.voiceTyping();
          yield null;
        }
        default -> "Dictation is not built in here. Use your desktop's dictation tool; it types into the message box.";
      };
    } catch (Exception | LinkageError e) {
      Activator.logError("Could not start dictation", e);
      return "Could not start dictation: " + e + ". Use the system shortcut instead"
          + (Platform.OS_WIN32.equals(Platform.getOS()) ? " (Windows+H)." : " (press Fn twice, or the mic key).");
    }
  }

  // Sends AppKit's startDictation: to the focused text view, as Edit > Start Dictation does in other Mac apps.
  // SWT's Cocoa classes are internal and exist only in the macOS build, so they are reached by reflection.
  private static boolean startMac() throws ReflectiveOperationException {
    ClassLoader loader = Display.class.getClassLoader();
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, loader);
    Class<?> application = Class.forName("org.eclipse.swt.internal.cocoa.NSApplication", true, loader);
    Class<?> id = Class.forName("org.eclipse.swt.internal.cocoa.id", true, loader);
    long selector = (long) os.getMethod("sel_registerName", String.class).invoke(null, "startDictation:");
    Object shared = application.getMethod("sharedApplication").invoke(null);
    Method sendAction = application.getMethod("sendAction", long.class, id, id);
    return (boolean) sendAction.invoke(shared, selector, null, null);
  }

  // SWT cannot post the Windows key, so AWT's Robot presses Windows+H. Kept apart so AWT loads only on Windows.
  private static final class Windows {
    static void voiceTyping() throws Exception {
      Robot robot = new Robot();
      robot.keyPress(KeyEvent.VK_WINDOWS);
      robot.keyPress(KeyEvent.VK_H);
      robot.keyRelease(KeyEvent.VK_H);
      robot.keyRelease(KeyEvent.VK_WINDOWS);
    }
  }
}
