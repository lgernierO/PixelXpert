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
	// 同一物理手势只认首次接受 DOWN 的入口，UP/CANCEL 后释放窗口引用。
	private View mTapEventSource;
	private long mTapDownTime = -1L;
	private int mTapDisplayId = Display.DEFAULT_DISPLAY;
	private boolean mScrollTopWarningLogged;
	/** Cached status bar height in px, used to filter shade-window taps down to the bar. */
	private int mStatusBarHeight = -1;
	/** The status bar view itself, kept to measure the real strip height. */
	private View mStatusBarView = null;
	private long mLastScrollTopTime = -SCROLL_TOP_DEBOUNCE_MS;
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
		// 当前 APK 的 Scene/旧 Shade 根视图均声明 dispatchTouchEvent。
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
				// 确认单击有延迟，执行前重新检查锁屏/面板状态；WMS 需要屏幕坐标。
				if (StatusbarTapScrollTop && isTapToTopAllowed()) {
					scrollForegroundAppToTop(e.getRawX());
				}
				return false;
			}
		});

		// framework.jar 已核实该类及方法存在。必须检查 HookHandle，不能把
		// “类存在”当作“Hook 注册成功”，否则方法未命中时连备用入口都被关闭。
		boolean inputStageHooked = false;
		try {
			ReflectedClass EarlyPostImeStageClass = ReflectedClass.ofIfPossible("android.view.ViewRootImpl$EarlyPostImeInputStage");
			inputStageHooked = !EarlyPostImeStageClass
					.before("processPointerEvent")
					.run(param -> {
						try {
							if (param.args.length == 0 || param.args[0] == null) return;
							Object event = getObjectField(param.args[0], "mEvent");
							if (!(event instanceof MotionEvent)) return;
							Object viewRoot = getObjectField(param.thisObject, "this$0");
							Object rootView = callMethod(viewRoot, "getView");
							if (!(rootView instanceof View)) return;
							String rootName = rootView.getClass().getName();
							if (!(rootName.equals("com.android.systemui.scene.ui.view.SceneWindowRootView")
									|| rootName.equals("com.android.systemui.shade.NotificationShadeWindowView")
									|| rootName.equals("com.android.systemui.statusbar.window.StatusBarWindowView"))) return;
							onTapToTopTouch((View) rootView, (MotionEvent) event);
						} catch (Throwable t) {
							warnScrollTop("状态栏回顶：读取输入事件失败", t);
						}
					}).isEmpty();
		} catch (Throwable t) {
			warnScrollTop("状态栏回顶：主输入 Hook 注册失败，使用视图入口", t);
		}

		final boolean useViewTapFallback = !inputStageHooked;
		if (useViewTapFallback) {
			ReflectedClass.ofIfPossible("com.android.systemui.scene.ui.view.SceneWindowRootView")
					.before("dispatchTouchEvent")
					.run(this::onTapToTopViewEvent);
			ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationShadeWindowView")
					.before("dispatchTouchEvent")
					.run(this::onTapToTopViewEvent);

			// StatusBarWindowView 继承 ViewGroup 的分发方法；按类型识别当前窗口，
			// 不保存某次构造的单例，避免窗口重建后仍过滤旧对象。
			if (StatusBarWindowViewClass.getClazz() != null) {
				ReflectedClass.of(ViewGroup.class)
						.before("dispatchTouchEvent")
						.run(param -> {
							if (StatusBarWindowViewClass.getClazz().isInstance(param.thisObject)) {
								onTapToTopViewEvent(param);
							}
						});
			}
		}
		// Capture the real status-bar height from the bar view itself, so the
		// shade-window Y filter tracks the actual strip on every device.
		PhoneStatusBarViewClass
				.afterConstruction()
				.run(param -> {
					mStatusBarView = (View) param.thisObject;
					mStatusBarHeight = -1; // re-resolve on first use
				});

		// PhoneStatusBarView 仅作为备用来源；主入口有效时不再次喂给单击检测器。
		PhoneStatusBarViewClass
				.after("dispatchTouchEvent")
				.run(param -> {
					MotionEvent event = (MotionEvent) param.args[0];

					if (useViewTapFallback) {
						onTapToTopTouch((View) param.thisObject, event);
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

	private void onTapToTopViewEvent(HookHelper.RunParam param) {
		if (param.thisObject instanceof View && param.args.length > 0
				&& param.args[0] instanceof MotionEvent) {
			onTapToTopTouch((View) param.thisObject, (MotionEvent) param.args[0]);
		}
	}

	private void onTapToTopTouch(View source, MotionEvent event) {
		if (!StatusbarTapScrollTop) {
			if (mTapEventSource != null) cancelTapToTop(event);
			return;
		}
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			// Scene -> Shade -> Phone 的重复 DOWN 不能覆盖首次入口，也不能重复识别。
			if (event.getDownTime() == mTapDownTime) return;
			mTapDownTime = event.getDownTime();
			mTapEventSource = null;
			if (!isTapToTopAllowed() || event.getY() < 0 || event.getY() > getStatusBarHeight()) {
				cancelTapToTop(event);
				return;
			}
			mTapEventSource = source;
			Display display = source.getDisplay();
			mTapDisplayId = display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
		} else if (source != mTapEventSource || event.getDownTime() != mTapDownTime) {
			return;
		}

		// DOWN 通过检查后必须继续交付 MOVE/UP，即使下拉已让 shade 开始展开。
		// 只观察，不 setResult、不改原事件，原生下拉和双击仍走原分发链。
		mSingleTapDetector.onTouchEvent(event);
		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
			mTapEventSource = null;
		}
	}

	private void cancelTapToTop(MotionEvent event) {
		MotionEvent cancel = MotionEvent.obtain(event);
		cancel.setAction(MotionEvent.ACTION_CANCEL);
		mSingleTapDetector.onTouchEvent(cancel);
		cancel.recycle();
		mTapEventSource = null;
	}

	private void warnScrollTop(String message, Throwable error) {
		if (mScrollTopWarningLogged) return;
		mScrollTopWarningLogged = true;
		log(message, error);
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
		if (mShadeInteractorAnyExpanded() || mKeyguardInteractor == null) return false;
		try {
			Object keyguardShowing = callMethod(getObjectField(mKeyguardInteractor, "isKeyguardShowing"), "getValue");
			Object bouncerShowing = callMethod(getObjectField(mKeyguardInteractor, "primaryBouncerShowing"), "getValue");
			return Boolean.FALSE.equals(keyguardShowing) && Boolean.FALSE.equals(bouncerShowing);
		} catch (Throwable t) {
			// 状态未知时不向后台应用发送回顶，避免在锁屏/认证界面误触发。
			warnScrollTop("状态栏回顶：无法读取锁屏状态，已跳过手势", t);
			return false;
		}
	}

	private boolean mShadeInteractorAnyExpanded() {
		if (ShadeInteractorSceneContainerImpl == null) {
						return false;
		}
		// Primary signal: synchronous getters read the live scene transition
		// state on every call - no flow caching, cannot go stale.
		try {
			float shadeExp = (float) callMethod(ShadeInteractorSceneContainerImpl, "getShadeExpansion");
			float qsExp = (float) callMethod(ShadeInteractorSceneContainerImpl, "getQsExpansion");
			return shadeExp > 0f || qsExp > 0f;
		} catch (Throwable getterErr) {
		}
		// Fallback: derived StateFlow - known to freeze (e.g. after QS screen
		// recording starts) but kept for builds without the sync getters.
		try {
			boolean anyExpanded = (boolean) callMethod(callMethod(ShadeInteractorSceneContainerImpl, "isAnyExpanded"), "getValue");
			if (anyExpanded) {
			}
			return anyExpanded;
		} catch (Throwable t) {
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
	 * 提供的 pixel framework 中 IWindowManager/WMS 均为 (displayId, x, taskId)。
	 * WMS 用第二参命中/换算窗口横坐标，用第三参查询任务；-1 表示不指定任务。
	 * 不按参数个数猜语义，避免把点击横坐标误当作任务 ID。
	 */
	private void scrollForegroundAppToTop(float x) {
		long now = SystemClock.uptimeMillis();
		if (now - mLastScrollTopTime < SCROLL_TOP_DEBOUNCE_MS) return;
		mLastScrollTopTime = now;

		try {
			Class<?> windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal");
			Object service = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null);
			service.getClass().getMethod("dispatchScrollToTop", int.class, int.class, int.class)
					.invoke(service, mTapDisplayId, Math.round(x), -1);
		} catch (Throwable t) {
			warnScrollTop("状态栏回顶：WMS 分发失败，继续应用侧补偿", t);
		}

		// 保留原有应用侧补偿通知；接收端检查实际窗口焦点，避免多窗口一起滚动。
		// 该接口返回 void，不反馈窗口是否已滚动，因此两条路径仍保持独立。
		new Thread(() -> {
			try {
				mContext.sendBroadcast(new Intent(Constants.ACTION_SCROLL_TOP));
			} catch (Throwable t) {
				warnScrollTop("状态栏回顶：应用侧补偿广播失败", t);
			}
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