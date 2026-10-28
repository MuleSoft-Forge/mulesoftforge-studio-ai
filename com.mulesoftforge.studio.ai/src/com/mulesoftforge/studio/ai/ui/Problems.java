package com.mulesoftforge.studio.ai.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com.mulesoftforge.studio.ai.edit.ProjectPaths;
import com.mulesoftforge.studio.ai.llm.Attachment;
import com.mulesoftforge.studio.ai.llm.Attachment.Problem;

// Reads Studio's problem markers (what the Problems view lists) into attachments.
final class Problems {
  private Problems() {}

  // Problems on a file, or on everything inside a folder or project. Build output is left out.
  static List<IMarker> on(IResource resource) {
    try {
      return List.of(resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE));
    } catch (CoreException e) {
      return List.of();
    }
  }

  static int count(IResource resource) {
    return (int) on(resource).stream().filter(Problems::kept).count();
  }

  static Attachments.Result attach(IResource scope) {
    List<IMarker> markers = on(scope);
    if (markers.stream().noneMatch(Problems::kept)) {
      return new Attachments.Result(List.of(), List.of("Studio reports no problems in " + scope.getName() + "."));
    }
    String path = scope instanceof IProject ? "" : scope.getProjectRelativePath().toString();
    EclipseProject project = EclipseProject.of(scope.getProject());
    return new Attachments.Result(List.of(build(project == null ? null : project.name(), path, markers)), List.of());
  }

  // Markers picked in the Problems view, one attachment per project.
  static Attachments.Result attach(List<IMarker> markers) {
    Map<IProject, List<IMarker>> byProject = new LinkedHashMap<>();
    for (IMarker marker : markers) {
      byProject.computeIfAbsent(marker.getResource().getProject(), p -> new ArrayList<>()).add(marker);
    }
    List<Attachment> attached = new ArrayList<>();
    for (Map.Entry<IProject, List<IMarker>> entry : byProject.entrySet()) {
      IProject project = entry.getKey();
      String name = project == null ? null : project.getName();
      attached.add(build(name, project == null ? "workspace" : "", entry.getValue()));
    }
    return new Attachments.Result(attached, List.of());
  }

  private static Attachment build(String project, String scope, List<IMarker> markers) {
    List<Problem> problems = new ArrayList<>();
    for (IMarker marker : markers) {
      if (kept(marker)) {
        problems.add(new Problem(severity(marker), path(marker), marker.getAttribute(IMarker.LINE_NUMBER, 0),
            marker.getAttribute(IMarker.MESSAGE, "(no message)")));
      }
    }
    return Attachment.problems(project, scope, problems);
  }

  private static boolean kept(IMarker marker) {
    return marker.exists() && !ProjectPaths.isSkipped(path(marker));
  }

  private static String path(IMarker marker) {
    IResource resource = marker.getResource();
    return resource instanceof IProject ? "" : resource.getProjectRelativePath().toString();
  }

  private static Problem.Severity severity(IMarker marker) {
    return switch (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
      case IMarker.SEVERITY_ERROR -> Problem.Severity.ERROR;
      case IMarker.SEVERITY_WARNING -> Problem.Severity.WARNING;
      default -> Problem.Severity.INFO;
    };
  }
}
