package com.mulesoftforge.studio.ai.ui;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.console.TextConsole;
import org.eclipse.ui.dialogs.ElementTreeSelectionDialog;
import org.eclipse.ui.model.BaseWorkbenchContentProvider;
import org.eclipse.ui.model.WorkbenchLabelProvider;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.edit.ProjectPaths;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.ChatMode;
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.ModelLists;
import com.mulesoftforge.studio.ai.llm.AutoRouter;
import com.mulesoftforge.studio.ai.llm.Pricing;
import com.mulesoftforge.studio.ai.settings.AiSettings;
import com.mulesoftforge.studio.ai.settings.Provider;

// The bottom of the chat view: attachments, the message box, mode and model pickers, and Send.
final class Composer {
  interface Listener {
    void send();

    void cancel();

    void newChat();

    // A message for the transcript, e.g. an attachment problem.
    void note(String speaker, String text);

    // The chosen model and its list price, and every picker model's price; price and prices are null while
    // they are being looked up.
    void modelShown(String model, String price, String priceTable, StatusPanel.PriceList prices);
  }

  static final String FIX_PROBLEMS_PROMPT = "Fix these problems.";
  static final String WHY_FAILED_PROMPT = "Why did this fail?";
  static final String EXPLAIN_EVENT_PROMPT = "Explain what is happening at this processor.";
  static final String FIX_CONSOLE_PROMPT = "Explain the error in this console output and fix its cause.";
  static final String DIAGNOSE_CONSOLE_PROMPT = "Find the cause of the error in this console output.";
  static final String PLACEHOLDER = "Ask about this Mule project, or ask for a change. Enter sends, Shift+Enter"
      + " adds a line, the mic dictates.";
  static final String CONTEXT_HELP = "Attach files, folders, the editor selection, the project's file list,"
      + " Studio's problems, Console output, or a clipboard image. Pasting an image into the message box attaches"
      + " it too.";
  private static final int MAX_CONSOLES_LISTED = 6;

  private final Composite root;
  private final IWorkbenchPage page;
  private final Listener listener;
  private final List<Attachment> attached = new ArrayList<>();
  private final ComposerUi ui;
  private List<String> pickerModels = List.of();
  private Map<String, String> shortPrices = Map.of();
  private ChatMode mode = AiSettings.chatMode();
  private boolean busy;

  // html: show the message box as a page in Eclipse's browser, falling back to SWT controls if it cannot start.
  Composer(Composite root, IWorkbenchPage page, boolean html, Listener listener) {
    this.root = root;
    this.page = page;
    this.listener = listener;
    ui = createUi(root, html, new ComposerUi.Events() {
      @Override
      public void sendPressed() {
        if (busy) {
          listener.cancel();
        } else {
          listener.send();
        }
      }

      @Override
      public void enterPressed() {
        if (!busy) {
          listener.send();
        }
      }

      @Override
      public void newChat() {
        if (!busy) {
          listener.newChat();
        }
      }

      @Override
      public void contextMenu(Point where) {
        showContextMenu(where);
      }

      @Override
      public void modeChosen(int index) {
        if (index >= 0 && index < ChatMode.values().length) {
          mode = ChatMode.values()[index];
          saveMode();
        }
      }

      @Override
      public void modelChosen(String model) {
        chooseModel(model);
      }

      @Override
      public void modelMenu(Point where) {
        showModelMenu(where);
      }

      @Override
      public void includeFileChanged(boolean include) {
        // Read when sending.
      }

      @Override
      public void dictate() {
        Composer.this.dictate();
      }

      @Override
      public void removeAttached(int index) {
        if (index < 0) {
          clearAttached();
        } else if (index < attached.size()) {
          attached.remove(index);
          showAttached();
        }
      }
    });

    List<String> labels = new ArrayList<>();
    StringBuilder modeHelp = new StringBuilder();
    for (ChatMode each : ChatMode.values()) {
      labels.add(each.label);
      modeHelp.append(each.label).append(": ").append(each.description).append('\n');
    }
    ui.setModes(labels, mode.ordinal(), modeHelp.toString().trim());
    showAttached();
    refreshModels();
  }

  private static ComposerUi createUi(Composite root, boolean html, ComposerUi.Events events) {
    if (html) {
      try {
        return new HtmlComposerUi(root, events);
      } catch (Throwable e) {
        Activator.logError("Rich message box unavailable; using the plain one", e);
      }
    }
    return new SwtComposerUi(root, events);
  }

  String prompt() {
    return ui.text().trim();
  }

  void setPrompt(String text) {
    ui.setText(text);
  }

  void focus() {
    ui.focus();
  }

  ChatMode mode() {
    return mode;
  }

  void setMode(ChatMode next) {
    mode = next;
    ui.selectMode(next.ordinal());
    saveMode();
  }

  boolean includeOpenFile() {
    return ui.includeFile();
  }

  List<Attachment> attached() {
    return List.copyOf(attached);
  }

  // Replaces an attachment for the same thing so attaching twice does not send it twice. Attaching problems to an
  // empty message suggests fixing them; console output showing a failure suggests diagnosing it.
  void attach(Attachments.Result result) {
    for (Attachment attachment : result.attached()) {
      attached.removeIf(a -> sameTarget(a, attachment));
      attached.add(attachment);
    }
    if (!result.problems().isEmpty()) {
      listener.note("Note", String.join("\n", result.problems()));
    }
    // An explanation comes first while debugging, so the mode stays as it is.
    Attachment event = result.attached().stream().filter(a -> a.kind() == Attachment.Kind.DEBUG_EVENT).findFirst()
        .orElse(null);
    if (event != null) {
      if (prompt().isEmpty()) {
        ui.setText(event.reportsFailure() ? WHY_FAILED_PROMPT : EXPLAIN_EVENT_PROMPT);
      }
      showAttached();
      ui.focus();
      return;
    }
    Attachment failure = result.attached().stream().filter(Attachment::reportsFailure).findFirst().orElse(null);
    if (failure != null && prompt().isEmpty()) {
      // A failing console needs its cause found first; Problems usually say what to fix.
      boolean console = failure.kind() == Attachment.Kind.CONSOLE;
      if (console && (mode() == ChatMode.ASK || mode() == ChatMode.AGENT)) {
        setMode(ChatMode.DIAGNOSE);
        listener.note("Note", "Switched to Diagnose mode: it reproduces the error and finds its cause, then offers"
            + " fixes for Agent mode to make. Pick Agent to go straight to a fix.");
      } else if (!console && mode() == ChatMode.ASK) {
        setMode(ChatMode.AGENT);
        listener.note("Note", "Switched to Agent mode so the AI can propose fixes. Pick Ask to only get an"
            + " explanation.");
      }
      ui.setText(!console ? FIX_PROBLEMS_PROMPT
          : mode() == ChatMode.DIAGNOSE ? DIAGNOSE_CONSOLE_PROMPT : FIX_CONSOLE_PROMPT);
    }
    showAttached();
    ui.focus();
  }

  private static boolean sameTarget(Attachment a, Attachment b) {
    if (a.kind() == Attachment.Kind.DEBUG_EVENT && b.kind() == Attachment.Kind.DEBUG_EVENT) {
      return true;
    }
    if (!Objects.equals(a.project(), b.project())) {
      return false;
    }
    if (a.kind() == Attachment.Kind.PROBLEMS || b.kind() == Attachment.Kind.PROBLEMS
        || a.kind() == Attachment.Kind.CONSOLE || b.kind() == Attachment.Kind.CONSOLE
        || a.kind() == Attachment.Kind.IMAGE || b.kind() == Attachment.Kind.IMAGE) {
      return a.kind() == b.kind() && a.path().equals(b.path());
    }
    return a.label().equals(b.label());
  }

  // Cmd/Ctrl+V anywhere in the chat view. An image on the clipboard is attached; text pastes as usual.
  void paste() {
    Display display = root.getDisplay();
    if (ClipboardImage.available(display)) {
      attachClipboardImage();
      return;
    }
    ui.paste();
  }

  private void attachClipboardImage() {
    long images = attached.stream().filter(a -> a.kind() == Attachment.Kind.IMAGE).count();
    if (images >= Attachment.MAX_IMAGES) {
      listener.note("Note", "At most " + Attachment.MAX_IMAGES + " images can go with one message.");
      return;
    }
    Attachment image;
    try {
      image = ClipboardImage.read(root.getDisplay(), "pasted image " + (images + 1));
    } catch (Exception e) {
      Activator.logError("Could not read the clipboard image", e);
      listener.note("Error", "Could not read the image on the clipboard: " + Errors.describe(e));
      return;
    }
    if (image == null) {
      listener.note("Note", "The clipboard has no image. Copy a screenshot or picture first.");
      return;
    }
    attach(new Attachments.Result(List.of(image), List.of()));
  }

  void clearAttached() {
    attached.clear();
    showAttached();
  }

  void setBusy(boolean busy) {
    this.busy = busy;
    ui.setBusy(busy);
  }

  void setCancelling() {
    ui.setCancelling();
  }

  void applyTheme() {
    if (!ui.control().isDisposed()) {
      ui.applyTheme();
    }
  }

  // The curated models for the current provider, those Refresh models last fetched, and whatever is chosen,
  // which may be a typed id.
  void refreshModels() {
    refreshModels(false);
  }

  // Fetches the price list again, then shows the models with the new prices.
  void refreshPrices() {
    refreshModels(true);
  }

  private void refreshModels(boolean refetchPrices) {
    if (ui.control().isDisposed()) {
      return;
    }
    Provider provider = AiSettings.provider();
    String current = AiSettings.model(provider);
    List<String> models = ModelLists.forPicker(provider, AiSettings.fetchedModels(provider), current);
    pickerModels = models;
    ui.setModels(models, current);
    showPrices(provider, current, models, refetchPrices);
  }

  // Focus first, then start dictation once the message box is focused, so it types there.
  private void dictate() {
    ui.focus();
    Control control = ui.control();
    control.getDisplay().asyncExec(() -> {
      if (control.isDisposed()) {
        return;
      }
      String problem = Dictation.start();
      if (problem != null) {
        listener.note("Note", problem);
      }
    });
  }

  private void saveMode() {
    try {
      AiSettings.saveChatMode(mode());
    } catch (Exception e) {
      Activator.logError("Could not save the chat mode", e);
    }
  }

  private void chooseModel(String typed) {
    Provider provider = AiSettings.provider();
    String current = AiSettings.model(provider);
    String chosen = typed == null ? "" : typed.trim();
    if (chosen.isEmpty()) {
      ui.setModelText(current);
      return;
    }
    if (chosen.equals(current)) {
      return;
    }
    try {
      AiSettings.saveModel(provider, chosen);
    } catch (Exception e) {
      listener.note("Error", "Could not save the model choice: " + Errors.describe(e));
      return;
    }
    refreshModels();
  }

  // The price list is fetched over the network the first time, so prices are looked up in a job.
  private void showPrices(Provider provider, String current, List<String> models, boolean refetch) {
    listener.modelShown(current, null, null, null);
    Job job = Job.create("MuleSoftForge AI model prices", monitor -> {
      if (refetch) {
        Pricing.refresh();
      }
      String price = AutoRouter.isAuto(current) ? autoPrice(provider) : Pricing.priceText(provider, current);
      StringBuilder table = new StringBuilder("List prices per 1M tokens (input · output):");
      List<String> found = new ArrayList<>();
      for (String model : models) {
        boolean auto = AutoRouter.isAuto(model);
        table.append("\n").append(model).append("   ").append(auto ? autoPrice(provider) : Pricing.priceText(provider, model));
        found.add(auto ? "fast or strong model, per message" : Pricing.shortPriceText(provider, model));
      }
      table.append("\n\nType any other model id and press Enter.");
      StatusPanel.PriceList prices = priceList(provider, current, models, found);
      Control control = ui.control();
      Ui.async(control.getDisplay(), control, () -> {
        if (!current.equals(ui.modelText().trim())) {
          return;
        }
        ui.setModelHelp(table.toString());
        Map<String, String> byModel = new HashMap<>();
        for (int i = 0; i < models.size(); i++) {
          if (found.get(i) != null) {
            byModel.put(models.get(i), found.get(i));
          }
        }
        shortPrices = byModel;
        listener.modelShown(current, price, table.toString(), prices);
      });
      return Status.OK_STATUS;
    });
    job.setSystem(true);
    job.schedule();
  }

  private static String autoPrice(Provider provider) {
    AutoRouter.Models auto = AiSettings.autoModels(provider);
    return "Picks per message: fast " + auto.fast() + " (" + Pricing.priceText(provider, auto.fast()) + "), strong "
        + auto.strong() + " (" + Pricing.priceText(provider, auto.strong()) + ")";
  }

  // found holds each model's short price text, null where the price list has no entry for it.
  private static StatusPanel.PriceList priceList(Provider provider, String current, List<String> models,
      List<String> found) {
    if (provider == Provider.OLLAMA) {
      List<StatusPanel.PriceRow> rows = new ArrayList<>();
      for (String model : models) {
        rows.add(new StatusPanel.PriceRow(model, "free", model.equals(current)));
      }
      String note = "Source: local (Ollama), free.";
      String help = "Ollama runs on this machine, so there is no charge.\n\n"
          + "Cost above stays at $0. The table is only here so every provider shows the same section.";
      return new StatusPanel.PriceList(rows, note, help);
    }
    Pricing.ListStatus status = Pricing.status();
    boolean loaded = status.fetchedAt() != null;
    List<StatusPanel.PriceRow> rows = new ArrayList<>();
    for (int i = 0; i < models.size(); i++) {
      String text = found.get(i);
      if (text == null) {
        text = loaded ? "not in the price list" : "unavailable";
      }
      rows.add(new StatusPanel.PriceRow(models.get(i), text, models.get(i).equals(current)));
    }
    if (!loaded) {
      String reason = status.failure() == null ? "." : ": " + status.failure();
      String note = "Source: unavailable" + reason + " Retry in 10 minutes, or press Refresh.";
      String help = "The plugin loads public list prices from OpenRouter's model catalogue"
          + " (https://openrouter.ai/api/v1/models). That fetch failed" + reason
          + "\n\nCost above still uses token counts from each reply; amounts stay unknown until prices load,"
          + " unless the provider reported a billed cost.";
      return new StatusPanel.PriceList(rows, note, help);
    }
    String fetched = LocalTime.ofInstant(status.fetchedAt(), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"));
    String note = "Source: Public list prices (via OpenRouter), fetched " + fetched + ".";
    String help = "Public list prices (via OpenRouter), fetched " + fetched + ".\n\n"
        + "This table is catalogue rates per 1M tokens, not your invoice. "
        + "Cost above uses token counts from each model reply; dollars are the provider's billed amount "
        + "when the response includes one (OpenRouter does), otherwise an estimate from these same list prices "
        + "(shown with ~).";
    if (Pricing.listPriceIsGuide(provider)) {
      note += " Your endpoint sets its own prices, so treat these as a guide.";
      help = "Public list prices (via OpenRouter), fetched " + fetched + ". "
          + provider.label + " sets its own prices, so treat these as a guide.\n\n"
          + "This table is catalogue rates per 1M tokens, not what your endpoint bills. "
          + "Cost above uses token counts from each model reply; dollars are estimated from these list prices "
          + "(shown with ~) unless the response includes a billed amount.";
    }
    return new StatusPanel.PriceList(rows, note, help);
  }

  private void showAttached() {
    List<String> labels = attached.stream().map(Attachment::label).toList();
    ui.showAttached(labels, "Sent with your next message, then cleared:\n" + String.join("\n", labels));
  }

  // The picker's models with their list prices, then a way to type any other id.
  private void showModelMenu(Point where) {
    if (busy) {
      return;
    }
    Provider provider = AiSettings.provider();
    String current = AiSettings.model(provider);
    Menu menu = new Menu(ui.control());
    Menus.disposeOnHide(menu);
    for (String model : pickerModels) {
      MenuItem item = new MenuItem(menu, SWT.RADIO);
      String price = shortPrices.get(model);
      item.setText(model.replace("&", "&&") + (price == null ? "" : "\t" + price));
      item.setSelection(model.equals(current));
      item.addListener(SWT.Selection, e -> {
        if (item.getSelection()) {
          chooseModel(model);
        }
      });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem other = new MenuItem(menu, SWT.PUSH);
    other.setText("Other model id…");
    other.addListener(SWT.Selection, e -> {
      InputDialog dialog = new InputDialog(root.getShell(), "Model",
          "Type a " + provider.label + " model id, for example one from Refresh models in Preferences.", current,
          null);
      if (dialog.open() == Window.OK) {
        chooseModel(dialog.getValue());
      }
    });
    menu.setLocation(where);
    menu.setVisible(true);
  }

  private void showContextMenu(Point where) {
    Menu menu = new Menu(ui.control());
    Menus.disposeOnHide(menu);
    MenuItem files = new MenuItem(menu, SWT.PUSH);
    files.setText("Files or folders…");
    files.addListener(SWT.Selection, e -> pickResources());
    IEditorPart editor = page.getActiveEditor();
    MenuItem lines = new MenuItem(menu, SWT.PUSH);
    lines.setText("Selected lines in the editor");
    Attachment selection = editor == null ? null : ActiveFile.selection(editor);
    lines.setEnabled(selection != null);
    lines.addListener(SWT.Selection, e -> attach(new Attachments.Result(List.of(selection), List.of())));
    EclipseProject project = EclipseProject.find(page);
    MenuItem whole = new MenuItem(menu, SWT.PUSH);
    whole.setText(project == null ? "Project file list (no project open)" : "File list of " + project.name());
    whole.setEnabled(project != null);
    whole.addListener(SWT.Selection, e -> attach(Attachments.fromResources(List.of(project.resource()))));
    MenuItem clipboard = new MenuItem(menu, SWT.PUSH);
    boolean hasImage = ClipboardImage.available(root.getDisplay());
    clipboard.setText(hasImage ? "Clipboard image" : "Clipboard image (no image copied)");
    clipboard.setEnabled(hasImage);
    clipboard.addListener(SWT.Selection, e -> attachClipboardImage());

    new MenuItem(menu, SWT.SEPARATOR);
    IFile openFile = editor == null ? null : Adapters.adapt(editor.getEditorInput(), IFile.class);
    MenuItem fileProblems = new MenuItem(menu, SWT.PUSH);
    int inFile = openFile == null ? 0 : Problems.count(openFile);
    fileProblems.setText(openFile == null ? "Problems in the open file (no file open)"
        : "Problems in " + openFile.getName() + " (" + inFile + ")");
    fileProblems.setEnabled(inFile > 0);
    fileProblems.addListener(SWT.Selection, e -> attach(Problems.attach(openFile)));
    MenuItem projectProblems = new MenuItem(menu, SWT.PUSH);
    int inProject = project == null ? 0 : Problems.count(project.resource());
    projectProblems.setText(project == null ? "Problems in the project (no project open)"
        : "Problems in " + project.name() + " (" + inProject + ")");
    projectProblems.setEnabled(inProject > 0);
    projectProblems.addListener(SWT.Selection, e -> attach(Problems.attach(project.resource())));

    new MenuItem(menu, SWT.SEPARATOR);
    List<TextConsole> consoles = Consoles.all(page);
    if (consoles.isEmpty()) {
      MenuItem none = new MenuItem(menu, SWT.PUSH);
      none.setText("Console output (no console open)");
      none.setEnabled(false);
    }
    for (TextConsole console : consoles.subList(0, Math.min(consoles.size(), MAX_CONSOLES_LISTED))) {
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText("Console: " + console.getName().replace("&", "&&"));
      item.setEnabled(!Consoles.isEmpty(console));
      item.addListener(SWT.Selection, e -> attach(Consoles.attach(console)));
    }
    menu.setLocation(where);
    menu.setVisible(true);
  }

  private void pickResources() {
    ElementTreeSelectionDialog dialog = new ElementTreeSelectionDialog(root.getShell(),
        new WorkbenchLabelProvider(), new BaseWorkbenchContentProvider());
    dialog.setTitle("Attach files or folders");
    dialog.setMessage("Files are sent with their content. Folders are sent as a list of the files inside.\n"
        + "Ctrl/Cmd-click to pick several.");
    dialog.setInput(ResourcesPlugin.getWorkspace().getRoot());
    dialog.setAllowMultiple(true);
    dialog.addFilter(new ViewerFilter() {
      @Override
      public boolean select(Viewer viewer, Object parent, Object element) {
        if (element instanceof IProject project) {
          return project.isOpen();
        }
        return !(element instanceof IResource resource)
            || !ProjectPaths.isSkipped(resource.getProjectRelativePath().toString());
      }
    });
    EclipseProject project = EclipseProject.find(page);
    if (project != null) {
      dialog.setInitialSelection(project.resource());
    }
    if (dialog.open() != Window.OK || dialog.getResult() == null) {
      return;
    }
    List<IResource> picked = new ArrayList<>();
    for (Object element : dialog.getResult()) {
      if (element instanceof IResource resource) {
        picked.add(resource);
      }
    }
    attach(Attachments.fromResources(picked));
  }
}
