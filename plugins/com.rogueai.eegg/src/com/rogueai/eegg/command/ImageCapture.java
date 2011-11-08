package com.rogueai.eegg.command;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

public abstract class ImageCapture {

	protected abstract Image getImage(Control control, int maxWidth, int maxHeight, boolean includeChildren);

	public ImageData captureImage(final Control control) {
		return captureImage(control, true);
	}

	public ImageData captureImage(final Control control, boolean includeChildren) {
		try {
			Point size = control.getSize();
			if (size.x == 0 || size.y == 0) {
				return null;
			}
			Image image = getImage(control, size.x, size.y, includeChildren);
			if (image != null) {
				ImageData imageData = image.getImageData();
				image.dispose();
				return imageData;
			}
		} catch (Throwable e) {
			if (e instanceof Error)
				throw (Error) e;
		}
		return null;
	}
}
