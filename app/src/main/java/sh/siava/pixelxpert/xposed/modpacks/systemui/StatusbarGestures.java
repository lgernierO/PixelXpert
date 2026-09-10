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
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

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
	/** True once the status-bar window root has delivered this touch sequence. */
	private boolean mStatusBarEventSeen = false;
	/** Cached status bar height in px, used to filter shade-window taps down to the bar. */
	private int mStatusBarHeight = -1;
	/** The status bar view itself, kept to measure the real strip height. */
	private View mStatusBarView = null;
	private long mLastScrollTopTime = 0L;
	private View mStatusBarWindowView = null;
	@SuppressLint("StaticFieldLeak")
	private static StatusbarGestures instance;
	private Object ShadeInteractorSceneContainerImpl;
	private Object mKeyguardInteractor;

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
		// Verified against the device's CANARY SystemUI dex: the status bar now
		// lives inside the shade window. NotificationShadeWindowView (which
		// overrides dispatchTouchEvent) is the only funnel every status-bar
		// touch passes through, and it forwards touches to PhoneStatusBarView
		// itself. StatusBarWindowView exists but sits OUTSIDE the touch path,
		// so hooking it never fires.
		ReflectedClass PhoneStatusBarViewClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.PhoneStatusBarView");
		ReflectedClass StatusBarWindowViewClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.window.StatusBarWindowView");

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

		// Same capture as ScreenGestures: used to suppress tap-to-top on the
		// lockscreen / bouncer, where scrolling a foreground app makes no sense.
		ReflectedClass.ofIfPossible("com.android.systemui.keyguard.domain.interactor.KeyguardInteractor")
				.afterConstruction()
				.run(param -> mKeyguardInteractor = param.thisObject);

		mGestureDetector = new GestureDetector(mContext, getPullDownLPListener());

		// Tap-to-top detector: mirrors the proven double-tap-to-sleep setup in
		// ScreenGestures - same event extraction, separate detector instance so
		// this stream stays untouched by the other status-bar gestures.
		mSingleTapDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
				if (StatusbarTapScrollTop) {
					scrollForegroundAppToTop(e.getX());
				}
				return false;
			}
		});

		// PRIMARY source, verified on-device: this CANARY build renders the
		// status bar through the scene container, whose window root view is
		// SceneWindowRootView. The proven double-tap-to-sleep gesture hooks
		// exactly this method, so it is the only touch funnel guaranteed to
		// fire. The window root spans the whole screen, so taps are filtered
		// down to the status-bar strip by Y, and suppressed while the
		// keyguard/bouncer shows or any shade surface is expanded.
		ReflectedClass.ofIfPossible("com.android.systemui.scene.ui.view.SceneWindowRootView")
				.before("dispatchTouchEvent")
				.run(param -> {
					if (!StatusbarTapScrollTop) return;
					if (!isTapToTopAllowed()) return;

					MotionEvent event = (MotionEvent) param.args[0];
					if (event.getY() > getStatusBarHeight()) return;

					if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
						mStatusBarEventSeen = false; // new gesture sequence begins
					}
					mSingleTapDetector.onTouchEvent(event);
					mStatusBarEventSeen = true;
				});

		// SECONDARY source for builds where the bar still lives inside the
		// legacy shade window. Guarded by mStatusBarEventSeen so a touch that
		// already passed through the scene root above is not fed twice (a
		// duplicate UP would be read as a double-tap and suppress the tap).
		ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationShadeWindowView")
				.before("dispatchTouchEvent")
				.run(param -> {
					if (!StatusbarTapScrollTop) return;
					if (mStatusBarEventSeen) return; // scene root already fed this sequence

					MotionEvent event = (MotionEvent) param.args[0];
					if (event.getY() > getStatusBarHeight()) return;

					if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
						mStatusBarEventSeen = false; // new gesture sequence begins
					}
					mSingleTapDetector.onTouchEvent(event);
					mStatusBarEventSeen = true;
				});

		// StatusBarWindowView is kept as a secondary source for builds where the
		// bar window still exists outside the shade window. Its own class does
		// not declare dispatchTouchEvent (CANARY dex), so the ViewGroup hook is
		// what actually fires for it.
		StatusBarWindowViewClass
				.afterConstruction()
				.run(param -> mStatusBarWindowView = (View) param.thisObject);

		if (StatusBarWindowViewClass.getClazz() != null) {
			ReflectedClass.of(ViewGroup.class)
					.before("dispatchTouchEvent")
					.run(param -> {
						if (param.thisObject != mStatusBarWindowView) return;
						if (!StatusbarTapScrollTop) return;

						MotionEvent event = (MotionEvent) param.args[0];
						if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
							mStatusBarEventSeen = false;
						}
						mSingleTapDetector.onTouchEvent(event);
						mStatusBarEventSeen = true;
					});
		}

		// Capture the real status-bar height from the bar view itself, so the
		// shade-window Y filter tracks the actual strip on every device.
		PhoneStatusBarViewClass
				.afterConstruction()
				.run(param -> {
					mStatusBarView = (View) param.thisObject;
					mStatusBarHeight = -1; // re-resolve on first use
				});

		// Legacy path: when the shade-window funnel above is unavailable (older
		// builds), PhoneStatusBarView receives the events directly. The
		// mStatusBarEventSeen guard keeps one physical tap from being fed twice,
		// which would make the detector report a double-tap and suppress
		// onSingleTapConfirmed.
		PhoneStatusBarViewClass
				.after("dispatchTouchEvent")
				.run(param -> {
					MotionEvent event = (MotionEvent) param.args[0];

					if (StatusbarTapScrollTop && !mStatusBarEventSeen) {
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
	 * Status-bar strip height in px. The shade window spans the whole screen,
	 * so the tap-to-top detector only accepts events inside this strip.
	 * The real bar height is captured from PhoneStatusBarView once it is
	 * laid out; until then the framework status bar height resource is used.
	 */
	/**
	 * Tap-to-top only makes sense on an unlocked, collapsed shade - otherwise
	 * WMS would target the keyguard or the shade itself. Mirrors the gating
	 * ScreenGestures uses for its status-bar gestures.
	 */
	private boolean isTapToTopAllowed() {
		if (mShadeInteractorAnyExpanded()) return false;
		if (mKeyguardInteractor == null) return true;
		try {
			return callMethod(getObjectField(mKeyguardInteractor, "isKeyguardShowing"), "getValue").equals(false)
					&& !callMethod(getObjectField(mKeyguardInteractor, "primaryBouncerShowing"), "getValue").equals(true);
		} catch (Throwable ignored) {
			return true;
		}
	}

	private boolean mShadeInteractorAnyExpanded() {
		if (ShadeInteractorSceneContainerImpl == null) return false;
		try {
			return (boolean) callMethod(callMethod(ShadeInteractorSceneContainerImpl, "isAnyExpanded"), "getValue");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private int getStatusBarHeight() {
		if (mStatusBarHeight < 0) {
			if (mStatusBarView != null && mStatusBarView.getHeight() > 0) {
				mStatusBarHeight = mStatusBarView.getHeight();
				return mStatusBarHeight;
			}
			try {
				int resId = mContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
				if (resId != 0) {
					mStatusBarHeight = mContext.getResources().getDimensionPixelSize(resId);
				}
			} catch (Throwable ignored) {}
			if (mStatusBarHeight < 0) mStatusBarHeight = 84; // conservative default (~24dp)
		}
		return mStatusBarHeight;
	}

	/**
	 * Chinese-ROM style "tap the status bar to jump back to the top of the
	 * current app" (MIUI's double-tap counterpart, adapted to a single tap).
	 * <p>
	 * Android 17 ships the OEM scroll-to-top pipeline natively - verified in
	 * this device's framework.jar and services.jar:
	 * WindowManagerService.dispatchScrollToTop(displayId, taskId, x) locates
	 * the top-visible app window at x, brings its task forward ("scrollToTop")
	 * and calls IWindow.dispatchScrollToTop(x), which ViewRootImpl forwards to
	 * View.onScrollToTop - implemented by AbsListView, ScrollView and
	 * RecyclerView to smooth-scroll to position 0.
	 * <p>
	 * Reverse-engineered from this device's MIUI framework (HyperOS V816):
	 * MIUI never uses the AOSP View.onScrollToTop pipeline - its
	 * MiuiInputManagerService.scrollToTop() notifies per-window listeners in
	 * every app process, and those listeners reflectively scroll whatever
	 * scrollable view they find (RecyclerView, WebView, custom containers -
	 * views that have no onScrollToTop at all). That is why apps that scroll
	 * on MIUI stayed dead on the native Pixel path: androidx RecyclerView and
	 * WebView simply do not implement onScrollToTop.
	 * <p>
	 * Pixel adaptation: keep the native WMS dispatchScrollToTop first (it is
	 * cheap and handles framework widgets), then ALWAYS notify the app-side
	 * engine (ScrollTopEnabler, same process as the target app) which does
	 * the MIUI-style reflective scroll - it covers androidx/WebView/custom
	 * containers and no-ops gracefully when the native path already worked.
	 * The broadcast targets the module package so only injected processes
	 * receive it; the receiver enforces no permission (harmless: it only
	 * scrolls the top of a view tree).
	 */
	private void scrollForegroundAppToTop(float x) {
		long now = SystemClock.uptimeMillis();
		if (now - mLastScrollTopTime < SCROLL_TOP_DEBOUNCE_MS) return;
		mLastScrollTopTime = now;

		try {
			Class<?> WindowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal");
			Object windowManagerService = WindowManagerGlobalClass
					.getMethod("getWindowManagerService")
					.invoke(null);
			windowManagerService.getClass()
					.getMethod("dispatchScrollToTop", int.class, int.class, int.class)
					.invoke(windowManagerService, Display.DEFAULT_DISPLAY, -1, Math.round(x));
			log("ScrollTop: WMS dispatchScrollToTop dispatched, x=" + Math.round(x));
		} catch (Throwable t) {
			log("ScrollTop: WMS dispatchScrollToTop failed: " + t);
		}

		// App-side MIUI-style engine: reaches views the native path cannot
		// (androidx RecyclerView, WebView, custom scrollers). Sent regardless
		// of the outcome above. Mirrors MIUI's design: the request is
		// announced to every injected process and each receiver decides
		// whether its window holds input focus - SystemUI cannot know the
		// foreground package here, so no setPackage targeting.
		new Thread(() -> {
			try {
				mContext.sendBroadcast(new Intent(Constants.ACTION_SCROLL_TOP));
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