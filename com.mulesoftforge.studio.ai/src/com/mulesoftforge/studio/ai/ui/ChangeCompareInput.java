package com.mulesoftforge.studio.ai.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

import com.mulesoftforge.studio.ai.edit.FileChange;

// Read-only side-by-side diff: current file on the left, the AI proposal on the right.
final class ChangeCompareInput extends CompareEditorInput {
  private final FileChange change;

  ChangeCompareInput(FileChange change) {
    super(configuration(change));
    this.change = change;
    setTitle("AI proposal: " + change.path());
  }

  private static CompareConfiguration configuration(FileChange change) {
    CompareConfiguration config = new CompareConfiguration();
    config.setLeftLabel(change.isNew() ? "(new file)" : "Current: " + change.path());
    config.setRightLabel("Proposed: " + change.path());
    config.setLeftEditable(false);
    config.setRightEditable(false);
    return config;
  }

  @Override
  protected Object prepareInput(IProgressMonitor monitor) {
    String name = change.path().substring(change.path().lastIndexOf('/') + 1);
    String before = change.before() == null ? "" : change.before();
    return new DiffNode(null, Differencer.CHANGE, null, new Side(name, before), new Side(name, change.after()));
  }

  private record Side(String name, String text) implements ITypedElement, IEncodedStreamContentAccessor {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public Image getImage() {
      return null;
    }

    @Override
    public String getType() {
      int dot = name.lastIndexOf('.');
      return dot < 0 ? ITypedElement.TEXT_TYPE : name.substring(dot + 1);
    }

    @Override
    public InputStream getContents() {
      return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getCharset() {
      return StandardCharsets.UTF_8.name();
    }
  }
}
