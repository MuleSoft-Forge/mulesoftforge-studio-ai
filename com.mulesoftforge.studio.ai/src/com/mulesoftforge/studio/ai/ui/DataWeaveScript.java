package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;

import com.mulesoftforge.studio.ai.llm.Attachment;

// The script in one of Studio's DataWeave editors (Transform Message and others), captured when the user picks a
// MuleSoftForge AI item from its context menu. Offsets are in the viewer's document; the editor may show only a
// region of it, so text and lines are taken from that region. Runs on the UI thread.
final class DataWeaveScript {
  static final String PROBLEMS_SCOPE = "the DataWeave script";

  // Every DataWeave viewer Studio has built. Transform Message disposes its viewer when another editor becomes
  // active and builds a new one when the Mule editor is back, so apply looks here for the new one.
  private static final Set<ISourceViewer> VIEWERS = Collections.newSetFromMap(new WeakHashMap<>());

  private final ISourceViewer viewer;
  private final IWorkbenchPage page;
  private final IEditorInput input;
  private final String text;
  private final String project;
  private final String where;
  private final Integer firstLine;
  private final Integer lastLine;
  private final String selected;

  private DataWeaveScript(ISourceViewer viewer, IWorkbenchPage page, IEditorInput input, String text,
      String project, String where, Integer firstLine, Integer lastLine, String selected) {
    this.viewer = viewer;
    this.page = page;
    this.input = input;
    this.text = text;
    this.project = project;
    this.where = where;
    this.firstLine = firstLine;
    this.lastLine = lastLine;
    this.selected = selected;
  }

  // Null when the editor has no document.
  static DataWeaveScript capture(ISourceViewer viewer, IWorkbenchPage page) throws BadLocationException {
    IDocument document = viewer == null ? null : viewer.getDocument();
    if (document == null) {
      return null;
    }
    IRegion region = region(viewer, document);
    String text = document.get(region.getOffset(), region.getLength());
    Integer first = null;
    Integer last = null;
    String selected = null;
    if (viewer.getSelectionProvider().getSelection() instanceof ITextSelection selection && selection.getLength() > 0
        && selection.getOffset() >= region.getOffset()
        && selection.getOffset() + selection.getLength() <= region.getOffset() + region.getLength()) {
      int top = document.getLineOfOffset(region.getOffset());
      first = selection.getStartLine() - top + 1;
      last = selection.getEndLine() - top + 1;
      selected = selection.getText();
    }
    IEditorPart editor = page == null ? null : page.getActiveEditor();
    IEditorInput input = editor == null ? null : editor.getEditorInput();
    IFile file = input == null ? null : Adapters.adapt(input, IFile.class);
    String project = file == null ? null : file.getProject().getName();
    String where = file == null ? "the open Mule configuration" : file.getProjectRelativePath().toString();
    return new DataWeaveScript(viewer, page, input, text, project, where, first, last, selected);
  }

  // Called with each viewer Studio passes to DataWeaveAction, which it does as it builds the editor.
  static void track(ISourceViewer viewer) {
    if (viewer != null) {
      VIEWERS.add(viewer);
    }
  }

  String text() {
    return text;
  }

  String project() {
    return project;
  }

  String where() {
    return where;
  }

  // "lines 4–9" when text was selected, otherwise null.
  String selectedLines() {
    return firstLine == null ? null : attachment(true).lines();
  }

  // With selectionOnly, the selected text when there is a selection; otherwise the whole script.
  Attachment attachment(boolean selectionOnly) {
    if (selectionOnly && selected != null) {
      return Attachment.dataWeave(project, where, firstLine, lastLine, selected);
    }
    return Attachment.dataWeave(project, where, null, null, text);
  }

  // Errors and warnings the editor shows on the script, with lines counted from the top of the script.
  List<Attachment.Problem> problems() throws BadLocationException {
    IAnnotationModel model = viewer.getAnnotationModel();
    IDocument document = viewer.getDocument();
    List<Attachment.Problem> problems = new ArrayList<>();
    if (model == null || document == null) {
      return problems;
    }
    IRegion region = region(viewer, document);
    int top = document.getLineOfOffset(region.getOffset());
    Set<String> seen = new LinkedHashSet<>();
    for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
      Annotation annotation = it.next();
      Attachment.Problem.Severity severity = severity(annotation.getType());
      Position position = model.getPosition(annotation);
      String message = annotation.getText();
      if (severity == null || annotation.isMarkedDeleted() || position == null || position.isDeleted()
          || message == null || message.isBlank() || position.getOffset() < region.getOffset()
          || position.getOffset() > region.getOffset() + region.getLength()) {
        continue;
      }
      int line = document.getLineOfOffset(position.getOffset()) - top + 1;
      if (seen.add(severity + ":" + line + ":" + message)) {
        problems.add(new Attachment.Problem(severity, "script", line, message.strip()));
      }
    }
    return problems;
  }

  // Replaces the script with fixed. Returns why it could not, or null when it was applied.
  String apply(String fixed) throws BadLocationException {
    ISourceViewer target = viewer;
    if (!live(viewer)) {
      IEditorPart active = page == null ? null : page.getActiveEditor();
      if (input == null || active == null || !input.equals(active.getEditorInput())) {
        return "The DataWeave editor this fix was for has been closed. Go back to " + where + ", select the"
            + " component so its script shows in Transform Message, then click Apply again.";
      }
      List<ISourceViewer> showing = new ArrayList<>();
      for (ISourceViewer candidate : List.copyOf(VIEWERS)) {
        if (!live(candidate)) {
          VIEWERS.remove(candidate);
        } else if (candidate != viewer && script(candidate).equals(text)) {
          showing.add(candidate);
        }
      }
      if (showing.size() > 1) {
        return "More than one open DataWeave editor shows this script, so the fix was not applied. Choose Fix with"
            + " MuleSoftForge AI again in the one to change.";
      }
      if (showing.isEmpty()) {
        return "No open DataWeave editor shows the script this fix was for. Select the component so its script shows"
            + " in Transform Message, then click Apply again. If the script has changed, choose Fix with"
            + " MuleSoftForge AI again.";
      }
      target = showing.get(0);
    }
    if (!script(target).equals(text)) {
      return "The script has changed since it was sent, so the fix was not applied. Choose Fix with MuleSoftForge AI"
          + " again on the current script.";
    }
    IDocument document = target.getDocument();
    IRegion region = region(target, document);
    document.replace(region.getOffset(), region.getLength(), fixed);
    target.setSelectedRange(region.getOffset(), 0);
    target.revealRange(region.getOffset(), 0);
    target.getTextWidget().setFocus();
    return null;
  }

  private static boolean live(ISourceViewer viewer) {
    StyledText widget = viewer.getTextWidget();
    return widget != null && !widget.isDisposed() && viewer.getDocument() != null;
  }

  private static String script(ISourceViewer viewer) throws BadLocationException {
    IDocument document = viewer.getDocument();
    IRegion region = region(viewer, document);
    return document.get(region.getOffset(), region.getLength());
  }

  private static IRegion region(ISourceViewer viewer, IDocument document) {
    IRegion visible = viewer.getVisibleRegion();
    return visible == null ? new Region(0, document.getLength()) : visible;
  }

  private static Attachment.Problem.Severity severity(String type) {
    String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
    if (lower.endsWith("error")) {
      return Attachment.Problem.Severity.ERROR;
    }
    if (lower.endsWith("warning")) {
      return Attachment.Problem.Severity.WARNING;
    }
    return null;
  }
}
