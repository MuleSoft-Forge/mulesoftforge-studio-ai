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

// Read-only side-by-side diff: current text on the left, the AI proposal on the right.
final class ChangeCompareInput extends CompareEditorInput {
  private final String name;
  private final String before;
  private final String after;

  private ChangeCompareInput(String title, String leftLabel, String rightLabel, String name, String before,
      String after) {
    super(configuration(leftLabel, rightLabel));
    this.name = name;
    this.before = before;
    this.after = after;
    setTitle(title);
  }

  static ChangeCompareInput of(FileChange change) {
    return new ChangeCompareInput("AI proposal: " + change.path(),
        change.isNew() ? "(new file)" : "Current: " + change.path(), "Proposed: " + change.path(),
        change.path().substring(change.path().lastIndexOf('/') + 1),
        change.before() == null ? "" : change.before(), change.after());
  }

  // A fix for the script in a DataWeave editor, which belongs to the Mule file named by where.
  static ChangeCompareInput dataWeave(String where, String before, String after) {
    return new ChangeCompareInput("AI fix: DataWeave in " + where, "Current DataWeave in " + where,
        "Proposed fix", "script.dwl", before, after);
  }

  private static CompareConfiguration configuration(String leftLabel, String rightLabel) {
    CompareConfiguration config = new CompareConfiguration();
    config.setLeftLabel(leftLabel);
    config.setRightLabel(rightLabel);
    config.setLeftEditable(false);
    config.setRightEditable(false);
    return config;
  }

  @Override
  protected Object prepareInput(IProgressMonitor monitor) {
    return new DiffNode(null, Differencer.CHANGE, null, new Side(name, before), new Side(name, after));
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
