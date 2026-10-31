package com.mulesoftforge.studio.ai.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.MultiRule;

// Runs inside Equinox with Studio's resources plugin (see WorkspaceLauncher): Accept's writes in a real workspace,
// where Eclipse enforces scheduling rules. Returns one PASS or FAIL line per check.
public final class WorkspaceCheck {
  private static final String RESOURCES = "src/main/resources";

  private final List<String> results = new ArrayList<>();

  public static List<String> run() throws Exception {
    WorkspaceCheck check = new WorkspaceCheck();
    check.checks(workspace());
    return check.results;
  }

  private void checks(IWorkspace workspace) throws Exception {
    IProject project = workspace.getRoot().getProject("employees-app");
    project.create(null);
    project.open(null);
    for (String folder : List.of("src", "src/main", RESOURCES)) {
      project.getFolder(folder).create(IResource.NONE, true, null);
    }
    IFile existing = project.getFile(RESOURCES + "/log4j2.xml");
    existing.create(new ByteArrayInputStream("<Configuration/>".getBytes(StandardCharsets.UTF_8)), IResource.NONE,
        null);

    ISchedulingRule edit = WorkspaceWrites.rule(project, List.of(RESOURCES + "/log4j2.xml"));
    check("an edit locks only its file", edit.contains(existing) && !edit.contains(project.getFolder(RESOURCES)), "");
    ISchedulingRule newFile = WorkspaceWrites.rule(project, List.of(RESOURCES + "/application-types.xml"));
    check("a new file locks its folder, not the project", newFile.contains(project.getFolder(RESOURCES))
        && !newFile.contains(project), "");
    ISchedulingRule newFolders = WorkspaceWrites.rule(project, List.of("src/test/munit/employees-test-suite.xml"));
    check("a new file in new folders locks the folder they go in", newFolders.contains(project.getFolder("src"))
        && !newFolders.contains(project), "");

    Map<String, String> contents = new LinkedHashMap<>();
    contents.put(RESOURCES + "/examples/employees-request-attributes.json", "{\"headers\": {}}");
    contents.put(RESOURCES + "/application-types.xml", "<types/>");
    contents.put(RESOURCES + "/log4j2.xml", "<Configuration status=\"WARN\"/>");
    contents.put("src/test/munit/employees-test-suite.xml", "<mule/>");
    List<Exception> reported = new ArrayList<>();
    Job job = WorkspaceWrites.job(project, contents, reported::add);
    job.schedule();
    job.join();
    boolean written = true;
    for (Map.Entry<String, String> file : contents.entrySet()) {
      written &= file.getValue().equals(read(project.getFile(file.getKey())));
    }
    check("Accept all writes new files, new folders and an edit in one job", job.getResult().isOK()
        && reported.size() == 1 && reported.get(0) == null && written, job.getResult().getMessage());
    check("the edit keeps local history", existing.getHistory(null).length == 1, "");

    List<Exception> failure = new ArrayList<>();
    Job blocked = WorkspaceWrites.job(project, Map.of(RESOURCES + "/log4j2.xml/inner.txt", "x"), failure::add);
    blocked.schedule();
    blocked.join();
    check("a write that fails reaches the chat and the job status", !blocked.getResult().isOK()
        && failure.size() == 1 && failure.get(0) instanceof CoreException, blocked.getResult().getMessage());

    // The rule 0.5.6 used, only the files themselves: the failure users saw. Shows this check catches it.
    IFile more = project.getFile(RESOURCES + "/more/example.json");
    ISchedulingRule filesOnly = MultiRule.combine(new ISchedulingRule[] {more});
    Job old = Job.create("Apply with the 0.5.6 rule", monitor -> {
      try {
        workspace.run((IWorkspaceRunnable) progress -> WorkspaceWrites.write(project,
            more.getProjectRelativePath().toString(), "{}"), filesOnly, IResource.NONE, monitor);
        return Status.OK_STATUS;
      } catch (CoreException e) {
        return e.getStatus();
      } catch (RuntimeException e) {
        return Status.error(String.valueOf(e.getMessage()), e);
      }
    });
    old.setRule(filesOnly);
    old.schedule();
    old.join();
    IStatus oldResult = old.getResult();
    check("locking only the files fails for a new file, as in 0.5.6", !oldResult.isOK()
        && oldResult.getMessage().contains("does not match outer scope rule") && !more.exists(), oldResult.getMessage());
  }

  private void check(String name, boolean ok, String detail) {
    results.add((ok ? "PASS" : "FAIL") + "  " + name + (ok || detail == null || detail.isEmpty() ? "" : ": " + detail));
  }

  private static String read(IFile file) throws Exception {
    if (!file.exists()) {
      return null;
    }
    try (InputStream in = file.getContents(true)) {
      return new String(in.readAllBytes(), file.getCharset());
    }
  }

  // The resources plugin opens the workspace once the instance location is known, which can be just after it starts.
  private static IWorkspace workspace() throws InterruptedException {
    IllegalStateException last = null;
    for (int i = 0; i < 200; i++) {
      try {
        return ResourcesPlugin.getWorkspace();
      } catch (IllegalStateException e) {
        last = e;
        Thread.sleep(50);
      }
    }
    throw last;
  }
}
