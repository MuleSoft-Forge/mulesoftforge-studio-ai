package com.mulesoftforge.studio.ai.ui;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.progress.IProgressConstants;

import com.mulesoftforge.studio.ai.edit.FileChange;
import com.mulesoftforge.studio.ai.edit.ProjectFiles;
import com.mulesoftforge.studio.ai.edit.ProjectPaths;

final class EclipseProject implements ProjectFiles {
  private static final String[] EXPLORERS = {
      "org.eclipse.ui.navigator.ProjectExplorer", "org.eclipse.jdt.ui.PackageExplorer"};

  private final IProject project;

  private EclipseProject(IProject project) {
    this.project = project;
  }

  // Must run on the UI thread. The active editor's project wins, then the explorer selection.
  static EclipseProject find(IWorkbenchPage page) {
    if (page == null) {
      return null;
    }
    IEditorPart editor = page.getActiveEditor();
    if (editor != null && editor.getEditorInput() instanceof IFileEditorInput input) {
      return of(input.getFile().getProject());
    }
    for (String id : EXPLORERS) {
      ISelection selection = page.getSelection(id);
      if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
        IResource resource = Adapters.adapt(structured.getFirstElement(), IResource.class);
        if (resource != null) {
          return of(resource.getProject());
        }
      }
    }
    return null;
  }

  static EclipseProject of(IProject project) {
    return project != null && project.isOpen() ? new EclipseProject(project) : null;
  }

  IProject resource() {
    return project;
  }

  @Override
  public String name() {
    return project.getName();
  }

  @Override
  public String read(String path) throws Exception {
    IFile file = project.getFile(path);
    if (!file.exists()) {
      return null;
    }
    try (InputStream in = file.getContents(true)) {
      return new String(in.readAllBytes(), Charset.forName(file.getCharset()));
    }
  }

  // Studio writes them under the workspace root, which is not always where the project lives.
  @Override
  public Path connectorModels() {
    return project.getWorkspace().getRoot().getLocation().toFile().toPath()
        .resolve(".mule").resolve("plugins-tmp").resolve("repository");
  }

  @Override
  public List<String> list(String directory, int limit) throws Exception {
    IContainer start = directory.isEmpty() ? project : project.getFolder(directory);
    if (!start.exists()) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    start.accept((IResourceProxy proxy) -> {
      if (proxy.isDerived() || proxy.getName().startsWith(".") && proxy.getType() == IResource.FOLDER) {
        return false;
      }
      IResource resource = proxy.requestResource();
      String path = resource.getProjectRelativePath().toString();
      if (!path.isEmpty() && ProjectPaths.isSkipped(path)) {
        return false;
      }
      if (proxy.getType() == IResource.FILE) {
        paths.add(path);
      }
      return true;
    }, IResource.NONE);
    Collections.sort(paths);
    return paths.size() > limit ? paths.subList(0, limit) : paths;
  }

  // Checks that can only run on the UI thread. Returns a problem message, or null when the write may proceed.
  String preflight(FileChange change, IWorkbenchPage page) throws Exception {
    IFile file = project.getFile(change.path());
    if (isDirtyInEditor(file, page)) {
      return change.path() + " has unsaved edits. Save or revert it, then ask again.";
    }
    if (!Objects.equals(read(change.path()), change.before())) {
      return change.path() + " changed since it was proposed. Ask again.";
    }
    return null;
  }

  // Writes in a user job; edits keep local history, so Replace With > Local History undoes them.
  void applyAsync(List<FileChange> changes, IWorkbenchPage page, ApplyCallback callback) {
    Map<String, String> contents = new LinkedHashMap<>();
    for (FileChange change : changes) {
      contents.put(change.path(), change.after());
    }
    Job job = WorkspaceWrites.job(project, contents, callback::done);
    job.setUser(true);
    // The chat already says what was not written, next to Accept.
    job.setProperty(IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY, Boolean.TRUE);
    job.schedule();
  }

  interface ApplyCallback {
    void done(Exception error);
  }

  private static boolean isDirtyInEditor(IFile file, IWorkbenchPage page) throws PartInitException {
    if (page == null) {
      return false;
    }
    for (IEditorReference reference : page.getEditorReferences()) {
      IEditorInput input = reference.getEditorInput();
      if (reference.isDirty() && input instanceof IFileEditorInput fileInput && file.equals(fileInput.getFile())) {
        return true;
      }
    }
    return false;
  }
}
