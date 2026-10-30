package com.mulesoftforge.studio.ai.ui;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
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
  static final String FIX_CONSOLE_PROMPT = "Explain the error in this console output and fix its cause.";
  private static final int MAX_CONSOLES_LISTED = 6;

  private final Composite root;
  private final IWorkbenchPage page;
  private final Listener listener;
  private final List<Attachment> attached = new ArrayList<>();
  private final Composite attachedRow;
  private final Label attachedLabel;
  private final Text input;
  private final Combo modePicker;
  private final Combo modelPicker;
  private final ColorButton send;
  private final Button includeFile;
  private boolean busy;

  Composer(Composite root, IWorkbenchPage page, Listener listener) {
    this.root = root;
    this.page = page;
    this.listener = listener;

    // A grey panel behind the message box and its controls; text boxes and pickers keep their own white.
    Composite panel = new Composite(root, SWT.NONE);
    GridLayout panelLayout = new GridLayout(1, false);
    panelLayout.marginWidth = 6;
    panelLayout.marginHeight = 6;
    panel.setLayout(panelLayout);
    panel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    panel.setBackground(TranscriptStyles.panel(root));
    panel.setBackgroundMode(SWT.INHERIT_DEFAULT);

    attachedRow = new Composite(panel, SWT.NONE);
    GridLayout attachedLayout = new GridLayout(2, false);
    attachedLayout.marginHeight = 0;
    attachedRow.setLayout(attachedLayout);
    attachedRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    attachedLabel = new Label(attachedRow, SWT.WRAP);
    attachedLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Link removeAttached = new Link(attachedRow, SWT.NONE);
    removeAttached.setText("<a>Remove</a>");
    removeAttached.setToolTipText("Do not send these with the next message");
    removeAttached.addListener(SWT.Selection, e -> clearAttached());

    input = new Text(panel, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
    GridData inputData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
    inputData.heightHint = input.getLineHeight() * 4;
    input.setLayoutData(inputData);
    input.setMessage("Ask about this Mule project, or ask for a change. Enter sends, Shift+Enter adds a line,"
        + " the mic dictates.");
    input.addListener(SWT.KeyDown, e -> {
      boolean enter = e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR;
      if (enter && (e.stateMask & SWT.SHIFT) == 0) {
        e.doit = false;
        if (!busy) {
          listener.send();
        }
      }
    });

    Composite actions = new Composite(panel, SWT.NONE);
    GridLayout actionsLayout = new GridLayout(8, false);
    actionsLayout.verticalSpacing = 2;
    actions.setLayout(actionsLayout);
    actions.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    modePicker = new Combo(actions, SWT.DROP_DOWN | SWT.READ_ONLY);
    for (ChatMode mode : ChatMode.values()) {
      modePicker.add(mode.label);
    }
    modePicker.select(AiSettings.chatMode().ordinal());
    modePicker.addListener(SWT.Selection, e -> saveMode());
    StringBuilder modeHelp = new StringBuilder();
    for (ChatMode mode : ChatMode.values()) {
      modeHelp.append(mode.label).append(": ").append(mode.description).append('\n');
    }
    modePicker.setToolTipText(modeHelp.toString().trim());

    modelPicker = new Combo(actions, SWT.DROP_DOWN);
    GridData pickerData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    pickerData.widthHint = 200;
    modelPicker.setLayoutData(pickerData);
    modelPicker.addListener(SWT.Selection, e -> chooseModel());
    modelPicker.addListener(SWT.DefaultSelection, e -> chooseModel());
    modelPicker.addListener(SWT.FocusOut, e -> chooseModel());

    Button addContext = new Button(actions, SWT.PUSH);
    addContext.setText("+ Context");
    addContext.setToolTipText("Attach files, folders, the editor selection, the project's file list, Studio's problems,"
        + " Console output, or a clipboard image. Pasting an image into the message box attaches it too.");
    addContext.addListener(SWT.Selection, e -> showContextMenu(addContext));
    includeFile = new Button(actions, SWT.CHECK);
    includeFile.setText("Include open file");
    includeFile.setSelection(true);
    new Label(actions, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button clear = new Button(actions, SWT.PUSH);
    clear.setText("New chat");
    clear.addListener(SWT.Selection, e -> {
      if (!busy) {
        listener.newChat();
      }
    });
    Button mic = new Button(actions, SWT.PUSH);
    mic.setImage(Activator.image("icons/mic16.png"));
    mic.setToolTipText(Dictation.hint());
    mic.getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        e.result = "Dictate";
      }
    });
    mic.addListener(SWT.Selection, e -> dictate());
    send = new ColorButton(actions);
    send.setText("Send");
    send.setTone(ColorButton.Tone.GO);
    send.addListener(SWT.Selection, e -> {
      if (busy) {
        listener.cancel();
      } else {
        listener.send();
      }
    });

    showAttached();
    refreshModels();
  }

  String prompt() {
    return input.getText().trim();
  }

  void setPrompt(String text) {
    input.setText(text);
  }

  void focus() {
    input.setFocus();
  }

  ChatMode mode() {
    return ChatMode.values()[modePicker.getSelectionIndex()];
  }

  void setMode(ChatMode mode) {
    modePicker.select(mode.ordinal());
    saveMode();
  }

  boolean includeOpenFile() {
    return includeFile.getSelection();
  }

  List<Attachment> attached() {
    return List.copyOf(attached);
  }

  // Replaces an attachment for the same thing so attaching twice does not send it twice. Attaching problems, or
  // console output showing a failure, to an empty message suggests fixing it.
  void attach(Attachments.Result result) {
    for (Attachment attachment : result.attached()) {
      attached.removeIf(a -> sameTarget(a, attachment));
      attached.add(attachment);
    }
    if (!result.problems().isEmpty()) {
      listener.note("Note", String.join("\n", result.problems()));
    }
    Attachment failure = result.attached().stream().filter(Attachment::reportsFailure).findFirst().orElse(null);
    if (failure != null && prompt().isEmpty()) {
      input.setText(failure.kind() == Attachment.Kind.CONSOLE ? FIX_CONSOLE_PROMPT : FIX_PROBLEMS_PROMPT);
      input.setSelection(input.getText().length());
      if (mode() == ChatMode.ASK) {
        setMode(ChatMode.AGENT);
        listener.note("Note", "Switched to Agent mode so the AI can propose fixes. Pick Ask to only get an"
            + " explanation.");
      }
    }
    showAttached();
    input.setFocus();
  }

  private static boolean sameTarget(Attachment a, Attachment b) {
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
    Control focus = display.getFocusControl();
    if (focus instanceof Text text) {
      text.paste();
    } else if (focus instanceof Combo combo) {
      combo.paste();
    } else if (focus instanceof StyledText styled) {
      styled.paste();
    } else {
      input.paste();
    }
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
    send.setEnabled(true);
    send.setText(busy ? "Cancel" : "Send");
    send.setTone(busy ? ColorButton.Tone.STOP : ColorButton.Tone.GO);
    modelPicker.setEnabled(!busy);
    modePicker.setEnabled(!busy);
  }

  void setCancelling() {
    send.setEnabled(false);
    send.setText("Cancelling…");
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
    if (modelPicker.isDisposed()) {
      return;
    }
    Provider provider = AiSettings.provider();
    String current = AiSettings.model(provider);
    List<String> models = ModelLists.forPicker(provider, AiSettings.fetchedModels(provider), current);
    modelPicker.setItems(models.toArray(String[]::new));
    modelPicker.setText(current);
    showPrices(provider, current, models, refetchPrices);
  }

  // Focus first, then start dictation once the text box is the focused control, so it types there.
  private void dictate() {
    input.setFocus();
    input.setSelection(input.getText().length());
    input.getDisplay().asyncExec(() -> {
      if (input.isDisposed()) {
        return;
      }
      String problem = Dictation.start(input);
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

  private void chooseModel() {
    Provider provider = AiSettings.provider();
    String current = AiSettings.model(provider);
    String chosen = modelPicker.getText().trim();
    if (chosen.isEmpty()) {
      modelPicker.setText(current);
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
      Display.getDefault().asyncExec(() -> {
        if (modelPicker.isDisposed() || !current.equals(modelPicker.getText().trim())) {
          return;
        }
        modelPicker.setToolTipText(table.toString());
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
    boolean any = !attached.isEmpty();
    if (any) {
      List<String> labels = attached.stream().map(Attachment::label).toList();
      attachedLabel.setText("Attached: " + String.join(", ", labels));
      attachedLabel.setToolTipText("Sent with your next message, then cleared:\n" + String.join("\n", labels));
    }
    ((GridData) attachedRow.getLayoutData()).exclude = !any;
    attachedRow.setVisible(any);
    root.layout(true, true);
  }

  private void showContextMenu(Control anchor) {
    Menu menu = new Menu(anchor);
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
    Rectangle bounds = anchor.getBounds();
    menu.setLocation(anchor.getParent().toDisplay(bounds.x, bounds.y + bounds.height));
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
