package com.mulesoftforge.studio.ai.ui;

import java.util.List;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.xtext.ui.editor.ISourceViewerAware;

import com.mulesoftforge.studio.ai.Activator;
import com.mulesoftforge.studio.ai.llm.Errors;

// An item in the context menu of Studio's DataWeave editors, contributed through Studio's
// org.mule.tooling.dfl.ui.weaveEmbeddedContextMenuAction extension point (see plugin.xml). Studio creates one per
// editor and passes in its viewer. "class:fix" in plugin.xml makes it the Fix item; otherwise it is Send.
public class DataWeaveAction extends Action implements ISourceViewerAware, IExecutableExtension {
  private boolean fix;
  private ISourceViewer viewer;

  public DataWeaveAction() {
    super("Send to MuleSoftForge AI");
    setImageDescriptor(AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, "icons/forge16.png"));
  }

  @Override
  public void setInitializationData(IConfigurationElement config, String propertyName, Object data) {
    fix = "fix".equals(data);
    if (fix) {
      setText("Fix with MuleSoftForge AI");
      setToolTipText("Send this script and its errors to MuleSoftForge AI, then review and apply the fix");
    } else {
      setToolTipText("Attach the selected DataWeave, or the whole script, to the MuleSoftForge AI chat");
    }
  }

  @Override
  public void setSourceViewer(ISourceViewer sourceViewer) {
    viewer = sourceViewer;
    DataWeaveScript.track(sourceViewer);
  }

  @Override
  public boolean isEnabled() {
    IDocument document = viewer == null ? null : viewer.getDocument();
    return document != null && !document.get().isBlank();
  }

  @Override
  public void run() {
    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IWorkbenchPage page = window == null ? null : window.getActivePage();
    if (page == null) {
      return;
    }
    try {
      DataWeaveScript script = DataWeaveScript.capture(viewer, page);
      if (script == null || script.text().isBlank()) {
        return;
      }
      ChatView view = (ChatView) page.showView(ChatView.ID);
      if (fix) {
        view.fixDataWeave(script);
      } else {
        view.attach(new Attachments.Result(List.of(script.attachment(true)), List.of()));
      }
    } catch (Exception e) {
      Activator.logError("MuleSoftForge AI could not use the DataWeave editor", e);
      MessageDialog.openError(window.getShell(), "MuleSoftForge AI",
          "Could not send the DataWeave script: " + Errors.describe(e));
    }
  }
}
