package com.mulesoftforge.studio.ai.ui;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
import com.mulesoftforge.studio.ai.llm.Errors;
import com.mulesoftforge.studio.ai.llm.NewsFeed;

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
  private final Label compact;
  private final Label pricesNote;
  private final Label pricesHelp;
  private final ScrolledComposite pricesScroll;
  private final Composite pricesRows;
  private final Composite pricesHeading;
  private final Label pricesToggle;
  private boolean pricesShown;
  private final Consumer<String> openUrl;
  private final Label newsToggle;
  private final ScrolledComposite newsScroll;
  private final Composite newsRows;
  private boolean newsShown;
  private boolean newsLoaded;
  private Color muted;

  StatusPanel(Composite parent, Runnable refreshPrices, Runnable compactChat, Consumer<String> openUrl) {
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
    compact = action(panel, "Compact", "Summarise the older turns before your next message, so the chat stays within"
        + " the model's context window. It happens by itself at " + Math.round(ChatSession.COMPACT_AT * 100) + "%.",
        compactChat);
    showContext(0, 0, false);
    gap();

    // The full price list is reference material, so it stays folded away until asked for.
    pricesToggle = disclosure("List prices per 1M tokens for every model in the picker",
        () -> setPricesShown(!pricesShown));

    pricesHeading = new Composite(panel, SWT.NONE);
    GridLayout headingLayout = new GridLayout(3, false);
    headingLayout.marginWidth = 0;
    headingLayout.marginHeight = 0;
    headingLayout.horizontalSpacing = 4;
    pricesHeading.setLayout(headingLayout);
    pricesHeading.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    Label pricesTitle = new Label(pricesHeading, SWT.NONE);
    pricesTitle.setText("Per 1M tokens");
    pricesTitle.setForeground(muted);
    pricesTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    pricesHelp = new Label(pricesHeading, SWT.NONE);
    pricesHelp.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_LCL_LINKTO_HELP));
    pricesHelp.setToolTipText("Where these rates come from, and how they relate to Cost above.");
    action(pricesHeading, "Refresh", "Fetch the price list again", refreshPrices);
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

    // MuleSoftForge's What's New feed, also folded away; it is fetched only when first opened.
    this.openUrl = openUrl;
    newsToggle = disclosure("New connectors, tools and Studio AI releases from " + NewsFeed.URL,
        () -> setNewsShown(!newsShown));
    newsScroll = new ScrolledComposite(panel, SWT.V_SCROLL);
    newsScroll.setExpandHorizontal(true);
    newsScroll.setExpandVertical(true);
    GridData newsData = new GridData(SWT.FILL, SWT.FILL, true, true);
    newsData.widthHint = 120;
    newsData.heightHint = 160;
    newsScroll.setLayoutData(newsData);
    newsRows = new Composite(newsScroll, SWT.NONE);
    GridLayout newsLayout = new GridLayout(1, false);
    newsLayout.marginWidth = 0;
    newsLayout.marginHeight = 0;
    newsLayout.verticalSpacing = 2;
    newsRows.setLayout(newsLayout);
    newsScroll.setContent(newsRows);
    newsScroll.addListener(SWT.Resize, e -> fit(newsScroll, newsRows));

    matchPanelBackground();
    showPrices(null);
    setPricesShown(false);
    setNewsShown(false);
  }

  private void setNewsShown(boolean shown) {
    newsShown = shown;
    newsToggle.setText((shown ? "▾  " : "▸  ") + "News");
    newsScroll.setVisible(shown);
    ((GridData) newsScroll.getLayoutData()).exclude = !shown;
    if (shown && !newsLoaded) {
      newsLoaded = true;
      loadNews();
    }
    if (shown) {
      fit(newsScroll, newsRows);
    }
    relayout();
  }

  private void loadNews() {
    showNews(null, null);
    Job.create("Reading MuleSoftForge news", monitor -> {
      List<NewsFeed.Item> items = null;
      String error = null;
      try {
        items = NewsFeed.fetch(NewsFeed.URL);
      } catch (Exception e) {
        error = Errors.describe(e);
      }
      List<NewsFeed.Item> shown = items;
      String failed = error;
      Ui.async(panel, () -> showNews(shown, failed));
      return Status.OK_STATUS;
    }).schedule();
  }

  // items is null while loading, or when error says why it could not be read.
  private void showNews(List<NewsFeed.Item> items, String error) {
    if (panel.isDisposed()) {
      return;
    }
    for (Control row : newsRows.getChildren()) {
      row.dispose();
    }
    if (items == null) {
      Label note = newsLabel(error == null ? "Reading news…" : "Could not read the news: " + error);
      note.setForeground(muted);
      if (error != null) {
        newsLink("Try again", null, () -> loadNews());
      }
    } else {
      if (items.isEmpty()) {
        newsLabel("No news yet.").setForeground(muted);
      }
      for (NewsFeed.Item item : items) {
        if (item.link() == null) {
          newsLabel(item.title()).setFont(TranscriptStyles.bold());
        } else {
          newsLink(item.title(), item.link(), () -> openUrl.accept(item.link()));
        }
        if (item.date() != null || !item.description().isEmpty()) {
          Label about = newsLabel((item.date() == null ? "" : item.date() + " · ") + item.description());
          about.setForeground(muted);
        }
        new Label(newsRows, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
      }
      newsLink("All news on mulesoftforge.com", NewsFeed.PAGE, () -> openUrl.accept(NewsFeed.PAGE));
    }
    fit(newsScroll, newsRows);
    relayout();
  }

  private Label newsLabel(String text) {
    Label label = new Label(newsRows, SWT.WRAP);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 100;
    label.setLayoutData(data);
    label.setText(text.replace("&", "&&"));
    return label;
  }

  private void newsLink(String text, String tooltip, Runnable click) {
    Link link = new Link(newsRows, SWT.WRAP);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.widthHint = 100;
    link.setLayoutData(data);
    link.setText("<a>" + text.replace("&", "&&").replace("<", "&lt;") + "</a>");
    link.setToolTipText(tooltip);
    link.addListener(SWT.Selection, e -> click.run());
  }

  private void setPricesShown(boolean shown) {
    pricesShown = shown;
    pricesToggle.setText((shown ? "▾  " : "▸  ") + "Prices");
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
    if (tokens == 0 || window == 0) {
      context.setText("Nothing sent yet");
    } else {
      long percent = Math.round(100.0 * tokens / window);
      context.setText(ContextWindow.tokens(tokens) + " / " + ContextWindow.tokens(window) + " (" + percent + "%)");
    }
    context.setToolTipText(tokens == 0 || window == 0
        ? "Tokens the model read and wrote in its last call. The window is the model's published limit; 128k when unknown."
        : ContextWindow.describe(tokens, window) + ". Used includes the system prompt, tools, history and the"
            + " latest message. Reserved is room kept for the model's reply (~"
            + ContextWindow.tokens(ContextWindow.RESERVED_OUTPUT) + ").");
    compact.setEnabled(canCompact);
    compact.setForeground(canCompact ? muted : ThemeStyles.swt(ThemeStyles.BUTTON_DISABLED));
    compact.setCursor(canCompact ? compact.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
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
    for (Composite part : new Composite[] {pricesScroll, pricesRows, newsScroll, newsRows}) {
      part.setBackground(background);
    }
    pricesRows.setBackgroundMode(SWT.INHERIT_DEFAULT);
    newsRows.setBackgroundMode(SWT.INHERIT_DEFAULT);
  }

  private void heading(String text) {
    Label label = new Label(panel, SWT.NONE);
    label.setText(text);
    label.setFont(TranscriptStyles.bold());
  }

  // A bold section header like Model and Cost, whose arrow folds the part below it.
  private Label disclosure(String tooltip, Runnable click) {
    Label header = clickable(panel, tooltip, click, null);
    header.setFont(TranscriptStyles.bold());
    return header;
  }

  private Label action(Composite parent, String text, String tooltip, Runnable click) {
    Label action = clickable(parent, tooltip, click, muted);
    action.setText(text);
    return action;
  }

  // Text that works as a button: a hand cursor and a hover colour rather than a link's underline.
  private static Label clickable(Composite parent, String tooltip, Runnable click, Color rest) {
    Label label = new Label(parent, SWT.NONE);
    label.setToolTipText(tooltip);
    label.setForeground(rest);
    label.setCursor(label.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
    label.addListener(SWT.MouseEnter, e -> {
      if (label.isEnabled()) {
        label.setForeground(ThemeStyles.swt(ThemeStyles.LINK));
      }
    });
    label.addListener(SWT.MouseExit, e -> {
      if (label.isEnabled()) {
        label.setForeground(rest);
      }
    });
    label.addListener(SWT.MouseUp, e -> {
      if (e.button == 1 && label.isEnabled() && e.x >= 0 && e.y >= 0 && e.x < label.getSize().x
          && e.y < label.getSize().y) {
        click.run();
      }
    });
    return label;
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
    fit(pricesScroll, pricesRows);
  }

  private static void fit(ScrolledComposite scroll, Composite rows) {
    int width = scroll.getClientArea().width;
    scroll.setMinSize(rows.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT));
    rows.layout(true, true);
  }

  private void relayout() {
    panel.layout(true, true);
  }
}
