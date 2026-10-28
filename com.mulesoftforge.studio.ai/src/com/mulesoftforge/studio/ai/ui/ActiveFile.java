package com.mulesoftforge.studio.ai.ui;

import java.io.InputStream;
import java.nio.charset.Charset;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.mulesoftforge.studio.ai.llm.Attachment;

final class ActiveFile {
  private ActiveFile() {}

  // Must run on the UI thread. Returns null when no editor is open.
  static Attachment capture(IWorkbenchPage page) throws Exception {
    IEditorPart editor = page == null ? null : page.getActiveEditor();
    if (editor == null) {
      return null;
    }
    IEditorInput input = editor.getEditorInput();
    IFile file = input instanceof IFileEditorInput fileInput ? fileInput.getFile() : null;
    String text = unsavedText(editor);
    if (text == null && file != null) {
      text = read(file);
    }
    if (text == null) {
      return null;
    }
    return Attachment.openFile(project(file), path(file, input), text);
  }

  // Selected text in the editor, with its line numbers. Null when nothing is selected.
  static Attachment selection(IEditorPart editor) {
    ITextEditor textEditor = textEditor(editor);
    if (textEditor == null
        || !(textEditor.getSelectionProvider().getSelection() instanceof ITextSelection selection)
        || selection.getLength() == 0) {
      return null;
    }
    IEditorInput input = editor.getEditorInput();
    IFile file = input instanceof IFileEditorInput fileInput ? fileInput.getFile() : null;
    return Attachment.selection(project(file), path(file, input), selection.getStartLine() + 1,
        selection.getEndLine() + 1, selection.getText());
  }

  private static String project(IFile file) {
    return file == null ? null : file.getProject().getName();
  }

  private static String path(IFile file, IEditorInput input) {
    return file != null ? file.getProjectRelativePath().toString() : input.getName();
  }

  // Studio's Mule editor is multi-page (canvas plus XML tab); only a text page exposes a document.
  static ITextEditor textEditor(IEditorPart editor) {
    if (editor == null) {
      return null;
    }
    ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
    if (textEditor == null && editor instanceof MultiPageEditorPart multiPage
        && multiPage.getSelectedPage() instanceof ITextEditor selected) {
      textEditor = selected;
    }
    return textEditor == null || textEditor.getDocumentProvider() == null ? null : textEditor;
  }

  private static String unsavedText(IEditorPart editor) {
    ITextEditor textEditor = textEditor(editor);
    if (textEditor == null) {
      return null;
    }
    IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
    return document == null ? null : document.get();
  }

  private static String read(IFile file) throws Exception {
    try (InputStream in = file.getContents(true)) {
      return new String(in.readAllBytes(), Charset.forName(file.getCharset()));
    }
  }
}
