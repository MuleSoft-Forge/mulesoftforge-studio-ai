package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.views.markers.MarkerItem;

import com.mulesoftforge.studio.ai.edit.AttachmentReader;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.Errors;

// Builds attachments from workspace resources and editor selections. Runs on the UI thread.
final class Attachments {
  record Result(List<Attachment> attached, List<String> problems) {}

  private Attachments() {}

  static Result fromResources(List<IResource> resources) {
    List<Attachment> attached = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (IResource resource : resources) {
      EclipseProject project = EclipseProject.of(resource.getProject());
      if (project == null) {
        problems.add(resource.getName() + " is not in an open project.");
        continue;
      }
      String path = resource instanceof IProject ? "" : resource.getProjectRelativePath().toString();
      try {
        attached.add(AttachmentReader.read(project, path));
      } catch (IllegalArgumentException e) {
        problems.add(e.getMessage());
      } catch (Exception e) {
        problems.add("Could not read " + resource.getFullPath() + ": " + Errors.describe(e));
      }
    }
    return new Result(attached, problems);
  }

  // Selected text in an editor, files and folders selected in a tree such as Package Explorer, rows in
  // the Problems view, or processors selected on a Mule editor's canvas.
  static Result fromSelection(ISelection selection, IEditorPart editor) {
    if (selection instanceof ITextSelection && editor != null) {
      Attachment lines = ActiveFile.selection(editor);
      return lines == null ? new Result(List.of(), List.of()) : new Result(List.of(lines), List.of());
    }
    List<IResource> resources = new ArrayList<>();
    List<IMarker> markers = new ArrayList<>();
    boolean groupHeading = false;
    if (selection instanceof IStructuredSelection structured) {
      for (Object element : structured.toList()) {
        IMarker marker = element instanceof MarkerItem item ? item.getMarker() : Adapters.adapt(element, IMarker.class);
        IResource resource = Adapters.adapt(element, IResource.class);
        if (marker != null) {
          markers.add(marker);
        } else if (element instanceof MarkerItem) {
          groupHeading = true;
        } else if (resource != null) {
          resources.add(resource);
        }
      }
    }
    if (!markers.isEmpty()) {
      return Problems.attach(markers);
    }
    if (groupHeading) {
      return new Result(List.of(), List.of("Select the problems themselves (Cmd/Ctrl+A selects them all), not the"
          + " group heading, or use + Context > Problems."));
    }
    if (resources.isEmpty()) {
      return CanvasSelection.attach(selection, editor);
    }
    return fromResources(resources);
  }
}
