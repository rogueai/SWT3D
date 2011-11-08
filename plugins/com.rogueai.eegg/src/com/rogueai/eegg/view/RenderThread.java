package com.rogueai.eegg.view;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.widgets.Display;

import com.ardor3d.framework.FrameHandler;
import com.ardor3d.framework.swt.SwtCanvas;

/**
 * Thread for rendering to a {@link SwtCanvas}
 * 
 */
public class RenderThread extends Thread {

	private static final Logger logger = Logger.getLogger(RenderThread.class.getName());

	private Runnable renderRunnable;

	private Display display;

	private FrameHandler framework;

	public RenderThread(String name, Display display, FrameHandler framework) {
		super(name);
		this.display = display;
		this.framework = framework;
	}

	@Override
	public void run() {
		logger.log(Level.INFO, "RenderThread started in single thread mode.");
		renderRunnable = new Runnable() {

			public void run() {
				framework.updateFrame();
			}

		};
		while (!Thread.interrupted()) {
			if (!display.isDisposed()) {
				display.syncExec(renderRunnable);
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

}