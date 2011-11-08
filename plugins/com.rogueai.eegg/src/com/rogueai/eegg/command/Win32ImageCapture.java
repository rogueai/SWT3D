package com.rogueai.eegg.command;

import java.lang.reflect.Method;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.internal.win32.RECT;
import org.eclipse.swt.ole.win32.OleFrame;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;

public class Win32ImageCapture extends ImageCapture {

	static Method GetParent;

	static Method SendMessage;

	static {
		try {
			Class<?> osClass = Class.forName("org.eclipse.swt.internal.win32.OS");
			GetParent = osClass.getDeclaredMethod("GetParent", int.class);
			SendMessage = osClass.getDeclaredMethod("SendMessage", int.class, int.class, int.class, int.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected Image getImage(Control control, int maxWidth, int maxHeight, boolean includeChildren) {
		Image image = getImage(control, maxWidth, maxHeight);
		// We need to be able to handle right-to-left coordinates too. In that
		// case the bounds rectangle will be reversed from what we
		// think. We think the origin is upper-left, but the origin is really
		// upper-right. To get out of this thinking we will
		// instead convert all bounds to display bounds so that they will all be
		// left-to-right.
		if (image != null) {
			// Get the images of all of the children
			if (includeChildren && control instanceof Composite) {
				Display display = control.getDisplay();
				Rectangle parentBounds = control.getParent() == null ? control.getBounds() : display.map(control.getParent(), null,
						control.getBounds());
				// Need to clip the bounds to the size of the image so we get
				// just what we need.
				Rectangle imgBounds = image.getBounds();
				parentBounds.width = imgBounds.width;
				parentBounds.height = imgBounds.height;
				int parentRight = parentBounds.width + parentBounds.x;
				int parentBottom = parentBounds.height + parentBounds.y;
				Control[] children = ((Composite) control).getChildren();
				GC imageGC = new GC(image);
				try {
					int i = children.length;
					while (--i >= 0) {
						Control child = children[i];
						// If the child is not visible then don't try and get
						// its image
						// An example of where this would cause a problem is
						// TabFolder where all the controls
						// for each page are children of the TabFolder, but only
						// the visible one is being shown on the active page
						if (!child.isVisible())
							continue;
						Rectangle childBounds = display.map(control, null, child.getBounds());
						if (!parentBounds.intersects(childBounds))
							continue; // Child is completely outside parent.
						Image childImage = getImage(child, parentRight - childBounds.x, parentBottom - childBounds.y, true);
						if (childImage != null) {
							try {
								// Paint the child image on top of our one
								// Since the child bounds and parent bounds are
								// both in display coors, the difference between
								// the two is the offset of the child from the
								// parent.
								imageGC.drawImage(childImage, childBounds.x - parentBounds.x, childBounds.y - parentBounds.y);
							} finally {
								childImage.dispose();
							}
						}
					}
				} finally {
					imageGC.dispose();
				}
			}
		}
		return image;
	}

	/**
	 * Return the image of the argument. This includes the client and non-client
	 * area, but does not include any child controls. To get child control use
	 * {@link ImageCapture#getImage(Control, int, int, boolean)}.
	 * 
	 * @param control
	 * @param maxWidth
	 * @param maxHeight
	 * @return image or <code>null</code> if not valid for some reason. (Like
	 *         not yet sized).
	 */
	protected Image getImage(Control control, int maxWidth, int maxHeight) {

		Rectangle rect = control.getBounds();
		if (rect.width <= 0 || rect.height <= 0)
			return null;

		Image image = new Image(control.getDisplay(), Math.min(rect.width, maxWidth), Math.min(rect.height, maxHeight));
		int WM_PRINT = 0x0317;
		// int WM_PRINTCLIENT = 0x0318;
		// int PRF_CHECKVISIBLE = 0x00000001;
		int PRF_NONCLIENT = 0x00000002;
		int PRF_CLIENT = 0x00000004;
		int PRF_ERASEBKGND = 0x00000008;
		int PRF_CHILDREN = 0x00000010;
		// int PRF_OWNED = 0x00000020;
		int print_bits = PRF_NONCLIENT | PRF_CLIENT | PRF_ERASEBKGND;
		// This method does not print immediate children because the z-order
		// doesn't work correctly and needs to be
		// dealt with separately, however Table's TableColumn widgets are
		// children so must be handled differently
		boolean allowChildren = control instanceof Table || control instanceof Browser || control instanceof OleFrame || control instanceof CCombo;
		try {
			allowChildren |= control instanceof Spinner;
		} catch (NoClassDefFoundError e) {
		} // might not be on 3.1 of SWT
		if (allowChildren) {
			print_bits |= PRF_CHILDREN;
		}
		GC gc = new GC(image);

		// Need to handle cases where the GC font isn't automatically set by the
		// control's image (e.g. CLabel)
		// see bug 98830 (https://bugs.eclipse.org/bugs/show_bug.cgi?id=98830)
		Font f = control.getFont();
		if (f != null)
			gc.setFont(f);

		int hwnd = control.handle;
		try {
			if (control instanceof Tree) {
				int hwndParent = (Integer) GetParent.invoke(null, hwnd);
				if (hwndParent != control.getParent().handle) {
					hwnd = hwndParent;
					print_bits |= PRF_CHILDREN;
				}
			}
			SendMessage.invoke(null, hwnd, WM_PRINT, gc.handle, print_bits);
		} catch (Exception e) {
			e.printStackTrace();
		}

		gc.dispose();
		return image;
	}

	void printWidget(Control control, int /* long */hdc, GC gc) {
		int hwnd = control.handle;
		/*
		 * Bug in Windows. For some reason, PrintWindow() returns success but
		 * does nothing when it is called on a printer. The fix is to just go
		 * directly to WM_PRINT in this case.
		 */
		boolean success = false;
		if (!(OS.GetDeviceCaps(gc.handle, OS.TECHNOLOGY) == OS.DT_RASPRINTER)) {
			/*
			 * Bug in Windows. When PrintWindow() will only draw that portion of
			 * a control that is not obscured by the shell. The fix is
			 * temporarily reparent the window to the desktop, call
			 * PrintWindow() then reparent the window back.
			 */
			int /* long */hwndParent = OS.GetParent(hwnd);
			int /* long */hwndShell = hwndParent;
			while (OS.GetParent(hwndShell) != 0) {
				if (OS.GetWindow(hwndShell, OS.GW_OWNER) != 0)
					break;
				hwndShell = OS.GetParent(hwndShell);
			}
			RECT rect1 = new RECT();
			OS.GetWindowRect(hwnd, rect1);
			boolean fixPrintWindow = !OS.IsWindowVisible(hwnd);
			if (!fixPrintWindow) {
				RECT rect2 = new RECT();
				OS.GetWindowRect(hwndShell, rect2);
				OS.IntersectRect(rect2, rect1, rect2);
				fixPrintWindow = !OS.EqualRect(rect2, rect1);
			}
			/*
			 * Bug in Windows. PrintWindow() does not print portions of the
			 * receiver that are clipped out using SetWindowRgn() in a parent.
			 * The fix is temporarily reparent the window to the desktop, call
			 * PrintWindow() then reparent the window back.
			 */
			if (!fixPrintWindow) {
				int /* long */rgn = OS.CreateRectRgn(0, 0, 0, 0);
				int /* long */parent = OS.GetParent(hwnd);
				while (parent != hwndShell && !fixPrintWindow) {
					if (OS.GetWindowRgn(parent, rgn) != 0) {
						fixPrintWindow = true;
					}
					parent = OS.GetParent(parent);
				}
				OS.DeleteObject(rgn);
			}
			int bits1 = OS.GetWindowLong(hwnd, OS.GWL_STYLE);
			int bits2 = OS.GetWindowLong(hwnd, OS.GWL_EXSTYLE);
			int /* long */hwndInsertAfter = OS.GetWindow(hwnd, OS.GW_HWNDPREV);
			/*
			 * Bug in Windows. For some reason, when GetWindow () with
			 * GW_HWNDPREV is used to query the previous window in the z-order
			 * with the first child, Windows returns the first child instead of
			 * NULL. The fix is to detect this case and move the control to the
			 * top.
			 */
			if (hwndInsertAfter == 0 || hwndInsertAfter == hwnd) {
				hwndInsertAfter = OS.HWND_TOP;
			}
			if (fixPrintWindow) {
				int x = OS.GetSystemMetrics(OS.SM_XVIRTUALSCREEN);
				int y = OS.GetSystemMetrics(OS.SM_YVIRTUALSCREEN);
				int width = OS.GetSystemMetrics(OS.SM_CXVIRTUALSCREEN);
				int height = OS.GetSystemMetrics(OS.SM_CYVIRTUALSCREEN);
				int flags = OS.SWP_NOSIZE | OS.SWP_NOZORDER | OS.SWP_NOACTIVATE | OS.SWP_DRAWFRAME;
				if ((bits1 & OS.WS_VISIBLE) != 0) {
					OS.DefWindowProc(hwnd, OS.WM_SETREDRAW, 0, 0);
				}
				SetWindowPos(hwnd, 0, x + width, y + height, 0, 0, flags);
				if (!OS.IsWinCE && OS.WIN32_VERSION >= OS.VERSION(6, 0)) {
					OS.SetWindowLong(hwnd, OS.GWL_STYLE, (bits1 & ~OS.WS_CHILD) | OS.WS_POPUP);
					OS.SetWindowLong(hwnd, OS.GWL_EXSTYLE, bits2 | OS.WS_EX_TOOLWINDOW);
				}
				// Shell shell = getShell();
				Shell shell = control.getShell();
				// Control savedFocus = shell.savedFocus;
				OS.SetParent(hwnd, 0);
				// shell.setSavedFocus(savedFocus);
				if ((bits1 & OS.WS_VISIBLE) != 0) {
					OS.DefWindowProc(hwnd, OS.WM_SETREDRAW, 1, 0);
				}
			}
			if ((bits1 & OS.WS_VISIBLE) == 0) {
				OS.ShowWindow(hwnd, OS.SW_SHOW);
			}
			success = OS.PrintWindow(hwnd, hdc, 0);
			if ((bits1 & OS.WS_VISIBLE) == 0) {
				OS.ShowWindow(hwnd, OS.SW_HIDE);
			}
			if (fixPrintWindow) {
				if ((bits1 & OS.WS_VISIBLE) != 0) {
					OS.DefWindowProc(hwnd, OS.WM_SETREDRAW, 0, 0);
				}
				if (!OS.IsWinCE && OS.WIN32_VERSION >= OS.VERSION(6, 0)) {
					OS.SetWindowLong(hwnd, OS.GWL_STYLE, bits1);
					OS.SetWindowLong(hwnd, OS.GWL_EXSTYLE, bits2);
				}
				OS.SetParent(hwnd, hwndParent);
				OS.MapWindowPoints(0, hwndParent, rect1, 2);
				int flags = OS.SWP_NOSIZE | OS.SWP_NOACTIVATE | OS.SWP_DRAWFRAME;
				SetWindowPos(hwnd, hwndInsertAfter, rect1.left, rect1.top, rect1.right - rect1.left, rect1.bottom - rect1.top, flags);
				if ((bits1 & OS.WS_VISIBLE) != 0) {
					OS.DefWindowProc(hwnd, OS.WM_SETREDRAW, 1, 0);
				}
			}
		}
	}

	boolean SetWindowPos(int /* long */hWnd, int /* long */hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags) {
		if (OS.IsWinCE) {
			/*
			 * Feature in Windows. On Windows CE, SetWindowPos() always causes a
			 * WM_SIZE message, even when the new size is the same as the old
			 * size. The fix is to detect that the size has not changed and set
			 * SWP_NOSIZE.
			 */
			if ((uFlags & OS.SWP_NOSIZE) == 0) {
				RECT lpRect = new RECT();
				OS.GetWindowRect(hWnd, lpRect);
				if (cy == lpRect.bottom - lpRect.top && cx == lpRect.right - lpRect.left) {
					/*
					 * Feature in Windows. On Windows CE, SetWindowPos() when
					 * called with SWP_DRAWFRAME always causes a WM_SIZE
					 * message, even when SWP_NOSIZE is set and when the new
					 * size is the same as the old size. The fix is to clear
					 * SWP_DRAWFRAME when the size is the same.
					 */
					uFlags &= ~OS.SWP_DRAWFRAME;
					uFlags |= OS.SWP_NOSIZE;
				}
			}
		}
		return OS.SetWindowPos(hWnd, hWndInsertAfter, X, Y, cx, cy, uFlags);
	}

}
