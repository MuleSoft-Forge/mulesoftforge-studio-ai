package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.console.TextConsole;

import com.mulesoftforge.studio.ai.http.AppConsole;
import com.mulesoftforge.studio.ai.llm.Attachment;

// Reads Studio's Console view: Maven build output, running Mule apps, and other text consoles.
final class Consoles {
  private Consoles() {}

  private static final String TERMINATED = "<terminated>";
  // Launch consoles are titled "[<terminated> ]<configuration> [<launch type>] <process>"; Studio's Mule launch
  // type is "Mule Applications".
  private static final Pattern MULE_LAUNCH = Pattern.compile("(?i)\\s\\[mule[^\\]]*\\]");

  static final AppConsole MULE_APP = Consoles::muleApp;

  // The console of a Mule app run from Studio: project's own launch first, a running one before a terminated one,
  // then the newest. Null when no Mule app has been run.
  static AppConsole.Text muleApp(String project) {
    TextConsole best = null;
    int bestScore = -1;
    for (IConsole console : ConsolePlugin.getDefault().getConsoleManager().getConsoles()) {
      if (!(console instanceof TextConsole text)) {
        continue;
      }
      String name = text.getName();
      Matcher launch = MULE_LAUNCH.matcher(name);
      if (!launch.find()) {
        continue;
      }
      boolean terminated = name.startsWith(TERMINATED);
      String configuration = name.substring(terminated ? TERMINATED.length() : 0, launch.start()).strip();
      int score = (project != null && configuration.equals(project) ? 2 : 0) + (terminated ? 0 : 1);
      if (score >= bestScore) {
        best = text;
        bestScore = score;
      }
    }
    return best == null ? null : new AppConsole.Text(best.getName(), best.getDocument().get());
  }

  // Text consoles, the one showing in the Console view first.
  static List<TextConsole> all(IWorkbenchPage page) {
    List<TextConsole> consoles = new ArrayList<>();
    TextConsole shown = shown(page);
    if (shown != null) {
      consoles.add(shown);
    }
    for (IConsole console : ConsolePlugin.getDefault().getConsoleManager().getConsoles()) {
      if (console instanceof TextConsole text && text != shown) {
        consoles.add(text);
      }
    }
    return consoles;
  }

  static boolean isEmpty(TextConsole console) {
    return console.getDocument().getLength() == 0;
  }

  static Attachments.Result attach(TextConsole console) {
    String text = console.getDocument().get();
    if (text.isBlank()) {
      return new Attachments.Result(List.of(), List.of("The console " + console.getName() + " is empty."));
    }
    return new Attachments.Result(List.of(Attachment.console(console.getName(), text, false)), List.of());
  }

  // From the Console view: the selected text, or the whole console when nothing is selected.
  static Attachments.Result attach(IConsoleView view, ISelection selection) {
    if (!(view.getConsole() instanceof TextConsole console)) {
      return new Attachments.Result(List.of(), List.of("This console has no text to attach."));
    }
    if (selection instanceof ITextSelection text && text.getLength() > 0) {
      return new Attachments.Result(List.of(Attachment.console(console.getName(), text.getText(), true)), List.of());
    }
    return attach(console);
  }

  private static TextConsole shown(IWorkbenchPage page) {
    if (page != null && page.findView(IConsoleConstants.ID_CONSOLE_VIEW) instanceof IConsoleView view
        && view.getConsole() instanceof TextConsole text) {
      return text;
    }
    return null;
  }
}
