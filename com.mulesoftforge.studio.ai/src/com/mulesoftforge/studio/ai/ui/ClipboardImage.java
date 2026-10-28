package com.mulesoftforge.studio.ai.ui;

import java.io.ByteArrayOutputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;

import com.mulesoftforge.studio.ai.llm.Attachment;

// An image on the system clipboard, e.g. a screenshot, as an attachment the model can look at.
final class ClipboardImage {
  // Providers downscale anything larger, and charge by size, so there is no point sending more.
  private static final int MAX_SIDE = 1568;
  // Anthropic refuses images over 5 MB once base64-encoded; PNG screenshots that big are sent as JPEG.
  private static final int MAX_PNG_BYTES = 3_500_000;

  private ClipboardImage() {}

  static boolean available(Display display) {
    Clipboard clipboard = new Clipboard(display);
    try {
      for (TransferData type : clipboard.getAvailableTypes()) {
        if (ImageTransfer.getInstance().isSupportedType(type)) {
          return true;
        }
      }
      return false;
    } finally {
      clipboard.dispose();
    }
  }

  // Null when the clipboard holds no image.
  static Attachment read(Display display, String name) {
    ImageData data;
    Clipboard clipboard = new Clipboard(display);
    try {
      data = (ImageData) clipboard.getContents(ImageTransfer.getInstance());
    } finally {
      clipboard.dispose();
    }
    if (data == null) {
      return null;
    }
    ImageData scaled = scaled(display, data);
    byte[] png = encode(scaled, SWT.IMAGE_PNG);
    if (png.length <= MAX_PNG_BYTES) {
      return Attachment.image(name, scaled.width, scaled.height, "image/png", png);
    }
    return Attachment.image(name, scaled.width, scaled.height, "image/jpeg", encode(scaled, SWT.IMAGE_JPEG));
  }

  private static ImageData scaled(Display display, ImageData data) {
    int longest = Math.max(data.width, data.height);
    if (longest <= MAX_SIDE) {
      return data;
    }
    int width = Math.max(1, data.width * MAX_SIDE / longest);
    int height = Math.max(1, data.height * MAX_SIDE / longest);
    Image source = new Image(display, data);
    Image target = new Image(display, width, height);
    GC gc = new GC(target);
    try {
      gc.setAntialias(SWT.ON);
      gc.setInterpolation(SWT.HIGH);
      gc.drawImage(source, 0, 0, data.width, data.height, 0, 0, width, height);
      return target.getImageData();
    } finally {
      gc.dispose();
      source.dispose();
      target.dispose();
    }
  }

  private static byte[] encode(ImageData data, int format) {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    loader.save(out, format);
    return out.toByteArray();
  }
}
