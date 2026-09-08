package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class StatusbarGestures extends XposedModPack {
	private static final int PULLDOWN_SIDE_RIGHT = 1;
	@SuppressWarnings("unused")
	private static final int PULLDOWN_SIDE_LEFT = 2;
	private static final int STATUSBAR_MODE_SHADE = 0;
	private static final int STATUSBAR_MODE_KEYGUARD = 1;
	/**
	 * @noinspection unused
	 */
	private static final int STATUSBAR_MODE_SHADE_LOCKED = 2;

	private static int pullDownSide = PULLDOWN_SIDE_RIGHT;
	private static boolean oneFingerPulldownEnabled = false;
	private boolean oneFingerPullupEnabled = false;
	private static float statusbarPortion = 0.25f; // now set to 25% of the screen. it can be anything between 0 to 100%
	private Object NotificationPanelViewController;
	GestureDetector mGestureDetector;
	private boolean StatusbarLongpressAppSwitch = false;
	private static boolean StatusbarTapScrollTop = false;
	private MotionEvent mDownEvent;
	private GestureDetector mSingleTapDetector;
	private long mLastScrollTopTime = 0L;
	@SuppressLint("StaticFieldLeak")
	private static StatusbarGestures instance;
	private Object ShadeInteractorSceneContainerImpl;

	public StatusbarGestures(Context context) {
		super(context);
		instance = this;
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		oneFingerPulldownEnabled = Xprefs.getBoolean("QSPullodwnEnabled", false);
		oneFingerPullupEnabled = oneFingerPulldownEnabled && Xprefs.getBoolean("oneFingerPullupEnabled", false);
		statusbarPortion = Xprefs.getSliderInt("QSPulldownPercent", 25) / 100f;
		pullDownSide = Integer.parseInt(Xprefs.getString("QSPulldownSide", "1"));

		StatusbarLongpressAppSwitch = Xprefs.getBoolean("StatusbarLongpressAppSwitch", false);
		StatusbarTapScrollTop = Xprefs.getBoolean("StatusbarTapScrollTop", false);
	}

	public static void collapseQSPanel()
	{
		if(instance != null)
		{
			instance.collapseQS();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass NotificationPanelViewControllerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationPanelViewController"); //Pre 17QPR1
		ReflectedClass PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView");

		//17QPR1
		ReflectedClass ShadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl");
		ReflectedClass ShadeInteractorImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorImpl");
		ReflectedClass ShadeSurfaceImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ShadeSurfaceImpl");

		ShadeSurfaceImplClass
				.before("onStatusBarLongPress")
				.run(this::onStatusBarLongPress);

		ShadeInteractorSceneContainerImplClass
				.afterConstruction()
				.run(param -> ShadeInteractorSceneContainerImpl = param.thisObject);

		// CANARY routes status-bar shade commands through ShadeInteractorImpl.  The
		// SceneContainer implementation remains the active BaseShadeInteractor,
		// but relying on only that concrete class misses the stable command facade.
		ShadeInteractorImplClass
				.afterConstruction()
				.run(param -> ShadeInteractorSceneContainerImpl = param.thisObject);

		mGestureDetector = new GestureDetector(mContext, getPullDownLPListener());

		// Tap-to-top detector: mirrors the proven double-tap-to-sleep setup in
		// ScreenGestures - same hook point (PhoneStatusBarView.onTouchEvent),
		// same "before" timing, same event extraction.  A separate detector
		// instance keeps this stream untouched for the other features.
		mSingleTapDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
				if (StatusbarTapScrollTop) {
					scrollForegroundAppToTop();
				}
				return false;
			}
		});

		PhoneStatusBarViewClass
				.after("onTouchEvent")
				.run(param -> {
					MotionEvent event =
							param.args[0] instanceof MotionEvent
									? (MotionEvent) param.args[0]
									: (MotionEvent) param.args[1];

					if (StatusbarTapScrollTop) {
						mSingleTapDetector.onTouchEvent(event);
					}

					if (!oneFingerPulldownEnabled) return;

					mGestureDetector.onTouchEvent(event);
				});

		GestureDetector pullUpDetector = new GestureDetector(mContext, getPullUpListener());

		final long[] lastPullupTouchTime = {0};

		NotificationPanelViewControllerClass //Pre 17QPR1
				.before("onStatusBarLongPress")
				.run(this::onStatusBarLongPress);

		NotificationPanelViewControllerClass //Pre 17QPR1
				.afterConstruction()
				.run(param -> {
					NotificationPanelViewController = param.thisObject;
					Object mTouchHandler = getObjectField(param.thisObject, "mTouchHandler");
					ReflectedClass.of(mTouchHandler.getClass())
							.before("onTouchEvent")
							.run(param2 -> {
								MotionEvent motionEvent = (MotionEvent) param2.args[0];

								if (oneFingerPullupEnabled
										&& STATUSBAR_MODE_KEYGUARD != getBarState(NotificationPanelViewController)) {
									if(SystemClock.uptimeMillis() - lastPullupTouchTime[0] > 1000)
									{
										motionEvent.setAction(MotionEvent.ACTION_DOWN);
										lastPullupTouchTime[0] = SystemClock.uptimeMillis();
										mDownEvent = MotionEvent.obtain(motionEvent);
										return;
									}
									else if (MotionEvent.ACTION_UP == motionEvent.getAction()) {
										lastPullupTouchTime[0] = 0;
									}
									pullUpDetector.onTouchEvent(motionEvent);
								}
							});
				});
	}

	private void onStatusBarLongPress(HookHelper.RunParam param) {
		if (StatusbarLongpressAppSwitch) {
			sendAppSwitchBroadcast();
			param.setResult(null);
		}
	}

	/** Debounce window guarding against duplicated touch delivery. */
	private static final long SCROLL_TOP_DEBOUNCE_MS = 400L;

	/**
	 * Chinese-ROM style "tap the status bar to jump back to the top of the
	 * current app".
	 * <p>
	 * SystemUI cannot reliably inject input events here: InputManager's
	 * injectInputEvent is blocked/greylisted for reflection on modern builds.
	 * This is what OEM ROMs solve inside services.jar instead - so the
	 * confirmed tap is forwarded to the system_server-side PixelXpert hooks
	 * (AndroidModPack) which inject the key from inside the input pipeline.
	 */
	private void scrollForegroundAppToTop() {
		long now = SystemClock.uptimeMillis();
		if (now - mLastScrollTopTime < SCROLL_TOP_DEBOUNCE_MS) return;
		mLastScrollTopTime = now;

		new Thread(() -> {
			try {
				mContext.sendBroadcast(new Intent(Constants.ACTION_SCROLL_TOP)
						.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND));
			} catch (Throwable ignored) {}
		}).start();
	}

	//speedfactor & heightfactor are based on display height
	private boolean isValidFling(MotionEvent e1, MotionEvent e2, float velocityY, float speedFactor, float heightFactor) {
		//noinspection DataFlowIssue
		Rect displayBounds = SystemUtils.WindowManager().getCurrentWindowMetrics().getBounds();
		try {
			return ((e2.getY() - e1.getY()) / heightFactor) > displayBounds.height() //enough travel in right direction
					&& isTouchInRegion(e1, displayBounds.width()) //start point in hot zone
					&& (velocityY / speedFactor > displayBounds.height()); //enough speed in right direction
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isTouchInRegion(MotionEvent motionEvent, float width) {
		float x = motionEvent.getX();
		float region = width * statusbarPortion;

		return (pullDownSide == PULLDOWN_SIDE_RIGHT)
				? width - region < x
				: x < region;
	}

	private void sendAppSwitchBroadcast() {
		new Thread(() -> mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())).start();
	}

	private GestureDetector.OnGestureListener getPullDownLPListener() {
		return new GestureListener() {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (isStatusbarClosed()
						&& isValidFling(e1, e2, velocityY, .15f, 0.01f)) {
					if (expandQuickSettingsShade()) {
						return true;
					}
				}
				return false;
			}
		};
	}

	@SuppressWarnings("ConstantValue")
	private boolean isStatusbarClosed()
	{
		return (NotificationPanelViewController != null && STATUSBAR_MODE_SHADE == getBarState(NotificationPanelViewController))
				|| ShadeInteractorSceneContainerImpl != null; //touch only gets here if statusbar is closed
	}

	private int getBarState(Object controller) {
		try { return (int) callMethod(controller, "getBarState"); }
		catch (Throwable ignored) { return (int) getObjectField(controller, "mBarState"); }
	}

	private GestureDetector.OnGestureListener getPullUpListener() {
		return new GestureListener() {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (isValidFling(mDownEvent, e2, velocityY, -.15f, -.06f)) {
					collapseQS();
					return true;
				}
				return false;
			}
		};
	}

	/**
	 * The Compose/scene-container SystemUI uses this interface for the same
	 * command that legacy builds exposed as NotificationPanelViewController
	 * {@code expandToQs()}.  CANARY no longer has that legacy method.
	 */
	private boolean expandQuickSettingsShade() {
		if (ShadeInteractorSceneContainerImpl != null) {
			try {
				callMethod(ShadeInteractorSceneContainerImpl, "expandQuickSettingsShade", "PixelXpert.oneFingerPulldown", null);
				return true;
			} catch (Throwable ignored) {}
		}

		// Kept exclusively as a pre-scene-container fallback.
		try {
			callMethod(NotificationPanelViewController, "expandToQs");
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void collapseQS() {
		if (ShadeInteractorSceneContainerImpl != null) {
			try {
				callMethod(ShadeInteractorSceneContainerImpl, "collapseEitherShade", "PixelXpert.oneFingerPullup", null);
				return;
			} catch (Throwable ignored) {}
		}
		try {
			callMethod(NotificationPanelViewController, "collapse", true, 1f);
		} catch (Throwable ignored) {
			try { callMethod(NotificationPanelViewController, "collapse", 1f, true); }
			catch (Throwable ignoredToo) {}
		}
	}

	private static class GestureListener implements GestureDetector.OnGestureListener {
		@Override
		public boolean onDown(@NonNull MotionEvent e) {
			return false;
		}

		@Override
		public void onShowPress(@NonNull MotionEvent e) {
		}

		@Override
		public boolean onSingleTapUp(@NonNull MotionEvent e) {
			return false;
		}

		@Override
		public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
			return false;
		}

		@Override
		public void onLongPress(@NonNull MotionEvent e) {}

		@Override
		public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
			return false;
		}
	}
}