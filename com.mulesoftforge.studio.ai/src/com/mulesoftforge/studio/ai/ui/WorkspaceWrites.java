package com.mulesoftforge.studio.ai.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.MultiRule;

// Writing accepted files into a project. Nothing here needs the workbench, so check-workspace runs this class in a
// real workspace.
final class WorkspaceWrites {
  private WorkspaceWrites() {}

  // One job for Accept and Accept all, writing every file under one rule. contents maps project paths to the new
  // text; done gets null, or what failed.
  static Job job(IProject project, Map<String, String> contents, Consumer<Exception> done) {
    ISchedulingRule rule = rule(project, contents.keySet());
    Job job = new Job("Apply MuleSoftForge AI changes") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        try {
          project.getWorkspace().run((IWorkspaceRunnable) progress -> {
            for (Map.Entry<String, String> file : contents.entrySet()) {
              write(project, file.getKey(), file.getValue());
            }
          }, rule, IResource.NONE, monitor);
          done.accept(null);
          return Status.OK_STATUS;
        } catch (CoreException | RuntimeException e) {
          done.accept(e);
          return e instanceof CoreException core ? core.getStatus() : Status.error("Could not apply changes", e);
        }
      }
    };
    job.setRule(rule);
    return job;
  }

  // Creating a file or folder locks the folder it goes in, so a new file needs the rule for creating the first
  // missing folder on its path, or the file itself when its folder exists. An edit locks only its file.
  static ISchedulingRule rule(IProject project, Collection<String> paths) {
    IResourceRuleFactory rules = project.getWorkspace().getRuleFactory();
    ISchedulingRule combined = null;
    for (String path : paths) {
      IFile file = project.getFile(path);
      combined = MultiRule.combine(combined,
          file.exists() ? rules.modifyRule(file) : rules.createRule(firstMissing(file)));
    }
    return combined;
  }

  // Edits keep local history, so Replace With > Local History undoes them.
  static void write(IProject project, String path, String content) throws CoreException {
    IFile file = project.getFile(path);
    if (file.exists()) {
      file.setContents(new ByteArrayInputStream(content.getBytes(Charset.forName(file.getCharset()))),
          IResource.KEEP_HISTORY, null);
    } else {
      createFolders(file.getParent());
      file.create(new ByteArrayInputStream(content.getBytes(Charset.forName(project.getDefaultCharset()))),
          IResource.NONE, null);
    }
  }

  private static IResource firstMissing(IFile file) {
    IResource first = file;
    while (first.getParent() instanceof IFolder folder && !folder.exists()) {
      first = folder;
    }
    return first;
  }

  private static void createFolders(IContainer container) throws CoreException {
    if (container instanceof IFolder folder && !folder.exists()) {
      createFolders(folder.getParent());
      folder.create(IResource.NONE, true, null);
    }
  }
}
