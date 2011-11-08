package com.rogueai.eegg.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;
import org.lwjgl.LWJGLException;

import com.ardor3d.framework.Canvas;
import com.ardor3d.framework.CanvasRenderer;
import com.ardor3d.framework.FrameHandler;
import com.ardor3d.framework.Scene;
import com.ardor3d.framework.Updater;
import com.ardor3d.framework.lwjgl.LwjglCanvasCallback;
import com.ardor3d.framework.lwjgl.LwjglCanvasRenderer;
import com.ardor3d.framework.swt.SwtCanvas;
import com.ardor3d.image.util.AWTImageLoader;
import com.ardor3d.input.ControllerWrapper;
import com.ardor3d.input.Key;
import com.ardor3d.input.PhysicalLayer;
import com.ardor3d.input.logical.DummyControllerWrapper;
import com.ardor3d.input.logical.InputTrigger;
import com.ardor3d.input.logical.KeyHeldCondition;
import com.ardor3d.input.logical.LogicalLayer;
import com.ardor3d.input.logical.TriggerAction;
import com.ardor3d.input.logical.TwoInputStates;
import com.ardor3d.input.swt.SwtFocusWrapper;
import com.ardor3d.input.swt.SwtKeyboardWrapper;
import com.ardor3d.input.swt.SwtMouseWrapper;
import com.ardor3d.intersection.PickResults;
import com.ardor3d.light.PointLight;
import com.ardor3d.math.ColorRGBA;
import com.ardor3d.math.Matrix3;
import com.ardor3d.math.Ray3;
import com.ardor3d.math.Vector3;
import com.ardor3d.renderer.Camera;
import com.ardor3d.renderer.Camera.ProjectionMode;
import com.ardor3d.renderer.ContextManager;
import com.ardor3d.renderer.Renderer;
import com.ardor3d.renderer.state.CullState;
import com.ardor3d.renderer.state.CullState.Face;
import com.ardor3d.renderer.state.LightState;
import com.ardor3d.renderer.state.ZBufferState;
import com.ardor3d.scenegraph.Mesh;
import com.ardor3d.scenegraph.Node;
import com.ardor3d.scenegraph.shape.Box;
import com.ardor3d.ui.text.BasicText;
import com.ardor3d.util.ContextGarbageCollector;
import com.ardor3d.util.GameTaskQueue;
import com.ardor3d.util.GameTaskQueueManager;
import com.ardor3d.util.ReadOnlyTimer;
import com.ardor3d.util.Timer;

public class EEggView extends ViewPart {

	class MyScene implements Scene {

		private final Node root;

		public MyScene() {
			root = new Node("root");
		}

		public Node getRoot() {
			return root;
		}

		public boolean renderUnto(final Renderer renderer) {
			// Execute renderQueue item
			GameTaskQueueManager.getManager(ContextManager.getCurrentContext()).getQueue(GameTaskQueue.RENDER).execute(renderer);
			ContextGarbageCollector.doRuntimeCleanup(renderer);

			renderer.draw(root);
			return true;
		}

		public PickResults doPick(final Ray3 pickRay) {
			return null;
		}

	}

	class MyGame implements Updater {

		private MyScene scene;

		private static final int MOVE_SPEED = 4;
		private static final double TURN_SPEED = 0.5;
		private final Matrix3 INCR = new Matrix3();
		private final static float CUBE_ROTATE_SPEED = 1;
		private final Vector3 rotationAxis = new Vector3(1, 1, 0);
		private double angle = 0;
		private Mesh box;
		private final Matrix3 rotation = new Matrix3();

		private LogicalLayer logicalLayer;

		private BasicText fpsLabel;

		public MyGame(MyScene scene, LogicalLayer logicalLayer) {
			this.scene = scene;
			this.logicalLayer = logicalLayer;
		}

		@Override
		public void init() {
			box = new Box("box", new Vector3(0, 0, 0), new Vector3(1, 1, 0.1));

			final ZBufferState buf = new ZBufferState();
			buf.setEnabled(true);
			buf.setFunction(ZBufferState.TestFunction.LessThanOrEqualTo);
			Node root = scene.getRoot();
			root.setRenderState(buf);

			final PointLight light = new PointLight();
			light.setDiffuse(new ColorRGBA());
			light.setAmbient(new ColorRGBA());
			light.setLocation(new Vector3(4, 4, 4));
			light.setEnabled(true);

			// Attach the light to a lightState and the lightState to rootNode
			final LightState lightState = new LightState();
			lightState.setEnabled(true);
			lightState.attach(light);
			root.setRenderState(lightState);

			root.attachChild(box);

			Node textNode = new Node("textNode");
			CullState cs = new CullState();
			cs.setCullFace(Face.Back);
			textNode.setRenderState(cs);

			fpsLabel = BasicText.createDefaultTextLabel("fps", "", 24);

			root.attachChild(textNode);
			textNode.attachChild(fpsLabel);

			registerInputTriggers();
		}

		private void registerInputTriggers() {
			logicalLayer.registerTrigger(new InputTrigger(new KeyHeldCondition(Key.W), new TriggerAction() {
				public void perform(final Canvas source, final TwoInputStates inputStates, final double tpf) {
					moveForward(source, tpf);
				}
			}));
			logicalLayer.registerTrigger(new InputTrigger(new KeyHeldCondition(Key.S), new TriggerAction() {
				public void perform(final Canvas source, final TwoInputStates inputStates, final double tpf) {
					moveBack(source, tpf);
				}
			}));
			logicalLayer.registerTrigger(new InputTrigger(new KeyHeldCondition(Key.A), new TriggerAction() {
				public void perform(final Canvas source, final TwoInputStates inputStates, final double tpf) {
					turnLeft(source, tpf);
				}
			}));
			logicalLayer.registerTrigger(new InputTrigger(new KeyHeldCondition(Key.D), new TriggerAction() {
				public void perform(final Canvas source, final TwoInputStates inputStates, final double tpf) {
					turnRight(source, tpf);
				}
			}));
		}

		private void turn(final Canvas canvas, final double speed) {
			final Camera camera = canvas.getCanvasRenderer().getCamera();

			final Vector3 temp = Vector3.fetchTempInstance();
			INCR.fromAngleNormalAxis(speed, camera.getUp());

			INCR.applyPost(camera.getLeft(), temp);
			camera.setLeft(temp);

			INCR.applyPost(camera.getDirection(), temp);
			camera.setDirection(temp);

			INCR.applyPost(camera.getUp(), temp);
			camera.setUp(temp);
			Vector3.releaseTempInstance(temp);

			camera.normalize();
		}

		private void turnRight(final Canvas canvas, final double tpf) {
			turn(canvas, -TURN_SPEED * tpf);
		}

		private void turnLeft(final Canvas canvas, final double tpf) {
			turn(canvas, TURN_SPEED * tpf);
		}

		private void moveForward(final Canvas canvas, final double tpf) {
			final Camera camera = canvas.getCanvasRenderer().getCamera();
			final Vector3 loc = Vector3.fetchTempInstance().set(camera.getLocation());
			final Vector3 dir = Vector3.fetchTempInstance();
			if (camera.getProjectionMode() == ProjectionMode.Perspective) {
				dir.set(camera.getDirection());
			} else {
				// move up if in parallel mode
				dir.set(camera.getUp());
			}
			dir.multiplyLocal(MOVE_SPEED * tpf);
			loc.addLocal(dir);
			camera.setLocation(loc);
			Vector3.releaseTempInstance(loc);
			Vector3.releaseTempInstance(dir);
		}

		private void moveBack(final Canvas canvas, final double tpf) {
			final Camera camera = canvas.getCanvasRenderer().getCamera();
			final Vector3 loc = Vector3.fetchTempInstance().set(camera.getLocation());
			final Vector3 dir = Vector3.fetchTempInstance();
			if (camera.getProjectionMode() == ProjectionMode.Perspective) {
				dir.set(camera.getDirection());
			} else {
				// move up if in parallel mode
				dir.set(camera.getUp());
			}
			dir.multiplyLocal(-MOVE_SPEED * tpf);
			loc.addLocal(dir);
			camera.setLocation(loc);
			Vector3.releaseTempInstance(loc);
			Vector3.releaseTempInstance(dir);
		}

		@Override
		public void update(ReadOnlyTimer timer) {
			final double timePerFrame = timer.getTimePerFrame();

			fpsLabel.setText(Math.round(timer.getFrameRate()) + " fps");

			logicalLayer.checkTriggers(timePerFrame);

			angle += timePerFrame * CUBE_ROTATE_SPEED;
			rotation.fromAngleAxis(angle, rotationAxis);
			box.setRotation(rotation);
			scene.getRoot().updateGeometricState(timePerFrame, true);
		}

		public Mesh getBox() {
			return box;
		}

	}

	public static final String ID = "com.rogueai.eegg.view.EEggView";

	private MyGame game;

	private RenderThread renderThread;

	public EEggView() {

	}

	@Override
	public void createPartControl(Composite parent) {
		System.setProperty("ardor3d.useMultipleContexts", "true");

		AWTImageLoader.registerLoader();

		LogicalLayer logicalLayer = new LogicalLayer();

		Timer timer = new Timer();
		final FrameHandler framework = new FrameHandler(timer);
		MyScene scene = new MyScene();
		game = new MyGame(scene, logicalLayer);
		framework.addUpdater(game);

		final GLData data = new GLData();
		data.depthSize = 8;
		data.doubleBuffer = true;
		final SwtCanvas canvas = new SwtCanvas(parent, SWT.BORDER, data);
		canvas.setLayout(new FillLayout());
		LwjglCanvasRenderer renderer = new LwjglCanvasRenderer(scene);
		renderer.setCanvasCallback(new LwjglCanvasCallback() {
			@Override
			public void makeCurrent() throws LWJGLException {
				canvas.setCurrent();
			}

			@Override
			public void releaseContext() throws LWJGLException {
			}
		});
		canvas.setCanvasRenderer(renderer);
		canvas.addControlListener(createCanvasControlListener(canvas, renderer));

		SwtKeyboardWrapper keyboardWrapper = new SwtKeyboardWrapper(canvas);
		SwtMouseWrapper mouseWrapper = new SwtMouseWrapper(canvas);
		SwtFocusWrapper focusWrapper = new SwtFocusWrapper(canvas);
		ControllerWrapper controllerWrapper = new DummyControllerWrapper();
		PhysicalLayer physicalLayer = new PhysicalLayer(keyboardWrapper, mouseWrapper, controllerWrapper, focusWrapper);
		logicalLayer.registerInput(canvas, physicalLayer);

		framework.addCanvas(canvas);

		framework.init();

		final Display display = canvas.getDisplay();
		renderThread = new RenderThread("Render Thread", display, framework);
		renderThread.start();
	}

	@Override
	public void dispose() {
		renderThread.stop();
		super.dispose();
	}

	static ControlListener createCanvasControlListener(final SwtCanvas canvas, final CanvasRenderer renderer) {
		final ControlListener retVal = new ControlListener() {
			public void controlMoved(final ControlEvent e) {
			}

			public void controlResized(final ControlEvent event) {
				final Rectangle size = canvas.getClientArea();
				if ((size.width == 0) && (size.height == 0)) {
					return;
				}
				final float aspect = (float) size.width / (float) size.height;
				final Camera camera = renderer.getCamera();
				if (camera != null) {
					final float fovY = 45; // XXX no camera.getFov()
					double near = camera.getFrustumNear();
					near = 0.5;
					final double far = camera.getFrustumFar();
					camera.setFrustumPerspective(fovY, aspect, near, far);
					camera.resize(size.width, size.height);
				}
			}
		};
		return retVal;
	}

	@Override
	public void setFocus() {

	}

	public Mesh getBox() {
		return game.getBox();
	}

}
