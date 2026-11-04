package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.mulesoftforge.studio.ai.llm.ChatSession;
import com.mulesoftforge.studio.ai.llm.ContextWindow;

// The column beside the transcript: the model in use, its list price, what chats have cost, and the list
// price of every model in the picker.
final class StatusPanel {
  // price is what to show under the model id: a price, or why there is none.
  record PriceRow(String model, String price, boolean current) {}

  // note is the short Source line; help is the full hover text for the note and help icon.
  record PriceList(List<PriceRow> rows, String note, String help) {}

  private final Composite panel;
  private final Label model;
  private final Label price;
  private final Label chatCost;
  private final Label todayCost;
  private final Label monthCost;
  private final Label context;
  private final Link compact;
  private final Label pricesNote;
  private final Label pricesHelp;
  private final ScrolledComposite pricesScroll;
  private final Composite pricesRows;
  private final Composite pricesHeading;
  private final Link pricesToggle;
  private boolean pricesShown;
  private Color muted;

  StatusPanel(Composite parent, Runnable refreshPrices, Runnable compactChat) {
    panel = new Composite(parent, SWT.NONE);
    ThemeStyles.applyClass(panel, "MsfPanel");
    panel.setBackgroundMode(SWT.INHERIT_DEFAULT);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 8;
    layout.verticalSpacing = 3;
    panel.setLayout(layout);
    muted = ThemeStyles.swt(ThemeStyles.MUTED);

    heading("Model");
    model = value(false);
    price = value(true);
    gap();
    heading("Cost");
    chatCost = value(false);
    todayCost = value(false);
    monthCost = value(false);
    gap();
    heading("Context");
    context = value(false);
    compact = new Link(panel, SWT.NONE);
    compact.setText("<a>Compact</a>");
    compact.setToolTipText("Summarise the older turns before your next message, so the chat stays within the model's"
        + " context window. It happens by itself at " + Math.round(ChatSession.COMPACT_AT * 100) + "%.");
    compact.addListener(SWT.Selection, e -> compactChat.run());
    showContext(0, 0, false);
    gap();

    // The full price list is reference material, so it stays folded away until asked for.
    pricesToggle = new Link(panel, SWT.NONE);
    pricesToggle.setToolTipText("List prices per 1M tokens for every model in the picker");
    pricesToggle.addListener(SWT.Selection, e -> setPricesShown(!pricesShown));

    pricesHeading = new Composite(panel, SWT.NONE);
    GridLayout headingLayout = new GridLayout(3, false);
    headingLayout.marginWidth = 0;
    headingLayout.marginHeight = 0;
    headingLayout.horizontalSpacing = 4;
    pricesHeading.setLayout(headingLayout);
    pricesHeading.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    Label pricesTitle = new Label(pricesHeading, SWT.NONE);
    pricesTitle.setText("Prices per 1M tokens");
    pricesTitle.setFont(TranscriptStyles.bold());
    pricesTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    pricesHelp = new Label(pricesHeading, SWT.NONE);
    pricesHelp.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_LCL_LINKTO_HELP));
    pricesHelp.setToolTipText("Where these rates come from, and how they relate to Cost above.");
    Link refresh = new Link(pricesHeading, SWT.NONE);
    refresh.setText("<a>Refresh</a>");
    refresh.setToolTipText("Fetch the price list again");
    refresh.addListener(SWT.Selection, e -> refreshPrices.run());
    pricesNote = value(false);
    pricesNote.setForeground(muted);

    pricesScroll = new ScrolledComposite(panel, SWT.V_SCROLL);
    pricesScroll.setExpandHorizontal(true);
    pricesScroll.setExpandVertical(true);
    GridData scrollData = new GridData(SWT.FILL, SWT.FILL, true, true);
    scrollData.widthHint = 120;
    scrollData.heightHint = 120;
    pricesScroll.setLayoutData(scrollData);
    pricesRows = new Composite(pricesScroll, SWT.NONE);
    GridLayout rowsLayout = new GridLayout(1, false);
    rowsLayout.marginWidth = 0;
    rowsLayout.marginHeight = 0;
    rowsLayout.verticalSpacing = 1;
    pricesRows.setLayout(rowsLayout);
    pricesScroll.setContent(pricesRows);
    pricesScroll.addListener(SWT.Resize, e -> fitPrices());
    matchPanelBackground();
    showPrices(null);
    setPricesShown(false);
  }

  private void setPricesShown(boolean shown) {
    pricesShown = shown;
    pricesToggle.setText(shown ? "<a>Hide prices ▾</a>" : "<a>Show prices ▸</a>");
    for (Control part : new Control[] {pricesHeading, pricesNote, pricesScroll}) {
      part.setVisible(shown);
      ((GridData) part.getLayoutData()).exclude = !shown;
    }
    if (shown) {
      fitPrices();
    }
    relayout();
  }

  Control control() {
    return panel;
  }

  // price is null while it is being looked up.
  void showModel(String id, String priceText, String priceTable) {
    model.setText(id == null || id.isBlank() ? "(none chosen)" : id);
    model.setToolTipText(priceTable);
    price.setText(priceText == null ? "Checking price…" : priceText);
    price.setToolTipText(priceTable);
    relayout();
  }

  void showCost(String chat, String today, String month, String details) {
    chatCost.setText("This chat: " + chat);
    todayCost.setText("Today: " + today);
    monthCost.setText("This month: " + month);
    for (Label label : new Label[] {chatCost, todayCost, monthCost}) {
      label.setToolTipText(details);
    }
    relayout();
  }

  // tokens: the size of the chat's context after the last call; window: the model's limit, 0 before a model is known.
  void showContext(long tokens, int window, boolean canCompact) {
    context.setText(tokens == 0 || window == 0 ? "Nothing sent yet" : ContextWindow.describe(tokens, window));
    context.setToolTipText("Tokens the model read and wrote in its last call: the system prompt, tools, this chat's"
        + " history and the latest message. The window is the model's published limit; 128k when it is not known.");
    compact.setEnabled(canCompact);
    relayout();
  }

  // list is null while prices are being looked up.
  void showPrices(PriceList list) {
    for (Control row : pricesRows.getChildren()) {
      row.dispose();
    }
    if (list == null) {
      pricesNote.setText("Checking prices…");
      pricesNote.setToolTipText(null);
      pricesHelp.setToolTipText("Where these rates come from, and how they relate to Cost above.");
    } else {
      pricesNote.setText(list.note());
      pricesNote.setToolTipText(list.help());
      pricesHelp.setToolTipText(list.help());
      if (list.rows().isEmpty()) {
        rowLabel("No models for this provider.", false).setForeground(muted);
      }
      for (PriceRow row : list.rows()) {
        Label id = rowLabel(row.model(), row.current());
        id.setToolTipText(row.current() ? row.model() + " (in use)" : row.model());
        Label cost = rowLabel(row.price(), false);
        cost.setForeground(muted);
        ((GridData) cost.getLayoutData()).horizontalIndent = 8;
      }
    }
    fitPrices();
    relayout();
  }

  boolean isDisposed() {
    return panel.isDisposed();
  }

  void applyTheme() {
    if (panel.isDisposed()) {
      return;
    }
    ThemeStyles.applyClass(panel, "MsfPanel");
    muted = ThemeStyles.swt(ThemeStyles.MUTED);
    pricesNote.setForeground(muted);
    matchPanelBackground();
    panel.layout(true, true);
  }

  // A ScrolledComposite paints its own grey on macOS, which reads as a box inside the panel.
  private void matchPanelBackground() {
    Color background = ThemeStyles.swt(ThemeStyles.PANEL);
    pricesScroll.setBackground(background);
    pricesRows.setBackground(background);
    pricesRows.setBackgroundMode(SWT.INHERIT_DEFAULT);
  }

  private void heading(String text) {
    Label label = new Label(panel, SWT.NONE);
    label.setText(text);
    label.setFont(TranscriptStyles.bold());
  }

  private void gap() {
    Label gap = new Label(panel, SWT.NONE);
    gap.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
  }

  // Wrapping labels need a width hint, or GridLayout sizes them to one long line.
  private Label value(boolean italic) {
    Label label = new Label(panel, SWT.WRAP);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 120;
    label.setLayoutData(data);
    if (italic) {
      label.setFont(TranscriptStyles.italic());
    }
    return label;
  }

  private Label rowLabel(String text, boolean bold) {
    Label label = new Label(pricesRows, SWT.WRAP);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 100;
    label.setLayoutData(data);
    label.setText(text);
    if (bold) {
      label.setFont(TranscriptStyles.bold());
    }
    return label;
  }

  // The rows wrap to the visible width, so their height depends on it.
  private void fitPrices() {
    int width = pricesScroll.getClientArea().width;
    pricesScroll.setMinSize(pricesRows.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT));
    pricesRows.layout(true, true);
  }

  private void relayout() {
    panel.layout(true, true);
  }
}
