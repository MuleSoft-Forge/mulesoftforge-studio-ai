package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.resources.ResourcesPlugin;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.debug.DebugSnapshot;
import com.mulesoftforge.studio.ai.debug.DebugStop;
import com.mulesoftforge.studio.ai.debug.ProcessorSource;
import com.mulesoftforge.studio.ai.llm.Attachment;

// The live event as an attachment. The debugged app's project, found by its name in the workspace, supplies the
// processor's XML; reads files, so call it off the UI thread.
final class DebugEvents {
  private DebugEvents() {}

  static Attachment attachment(DebugStop stop) {
    EclipseProject project = project(stop.app());
    ProcessorSource.Located source = null;
    try {
      source = ProcessorSource.find(project, stop);
    } catch (Exception e) {
      Activator.logFailure("Could not find the stopped processor's XML", e);
    }
    DebugSnapshot.Snapshot snapshot = DebugSnapshot.render(stop, null, source);
    return Attachment.debugEvent(project == null ? null : project.name(), snapshot.title(), snapshot.failed(),
        snapshot.text(), snapshot.truncated());
  }

  private static EclipseProject project(String app) {
    if (app == null || app.isBlank()) {
      return null;
    }
    try {
      return EclipseProject.of(ResourcesPlugin.getWorkspace().getRoot().getProject(app));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
