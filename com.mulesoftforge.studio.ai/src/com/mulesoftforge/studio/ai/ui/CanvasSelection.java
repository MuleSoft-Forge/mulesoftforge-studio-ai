package com.mulesoftforge.studio.ai.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;

import com.mulesoftforge.studio.ai.llm.Attachment;

// Elements selected on the canvas (Message Flow page) of Studio's Mule editor. The selection holds Studio's edit
// parts; each has getEntity().getElement().asXML(). Those are Studio internals, so they are called by reflection:
// when a Studio release changes them, nothing is attached instead of the plugin failing to load.
final class CanvasSelection {
  private CanvasSelection() {}

  // Empty when the selection has no canvas elements.
  static Attachments.Result attach(ISelection selection, IEditorPart editor) {
    List<String> elements = new ArrayList<>();
    if (selection instanceof IStructuredSelection structured) {
      for (Object part : structured.toList()) {
        String xml = xmlOf(part);
        if (xml != null) {
          elements.add(xml);
        }
      }
    }
    if (elements.isEmpty()) {
      return new Attachments.Result(List.of(), List.of());
    }
    IFile file = editor == null ? null : Adapters.adapt(editor.getEditorInput(), IFile.class);
    String project = file == null ? null : file.getProject().getName();
    String path = file != null ? file.getProjectRelativePath().toString()
        : editor != null ? editor.getEditorInput().getName() : "the open Mule configuration";
    return new Attachments.Result(List.of(Attachment.processors(project, path, elements)), List.of());
  }

  private static String xmlOf(Object part) {
    Object element = call(call(part, "getEntity"), "getElement");
    return call(element, "asXML") instanceof String xml && !xml.isBlank() ? xml : null;
  }

  private static Object call(Object target, String method) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      m.trySetAccessible();
      return m.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
