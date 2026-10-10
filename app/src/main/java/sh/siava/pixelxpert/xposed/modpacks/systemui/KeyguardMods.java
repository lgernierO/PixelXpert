package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.graphics.Color.TRANSPARENT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.invokeOriginalMethod;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.getBooleanField;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.modpacks.systemui.BatteryDataProvider.isCharging;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf;
import static sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod;
import static sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField;
import static sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils.getColorAttrDefaultColor;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.StringFormatter;
import sh.siava.pixelxpert.xposed.utils.LockscreenCustomText;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyguardMods extends XposedModPack {
	//region keyguard charging data
	public static final String EXTRA_MAX_CHARGING_CURRENT = "max_charging_current";
	public static final String EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage";
	public static final String EXTRA_TEMPERATURE = "temperature";

	private static final Object WALLPAPER_DIM_AMOUNT_DIMMED = 0.6F; //DefaultDeviceEffectsApplier
	private static final ThreadLocal<Boolean> isRenderingShortcut = ThreadLocal.withInitial(() -> false);

	private float max_charging_current = 0;
	private float max_charging_voltage = 0;
	private float temperature = 0;

	private static boolean ShowChargingInfo = false;
	//endregion

	private static boolean customCarrierTextEnabled = false;
	private static String customCarrierText = "";
	private static Object carrierTextController;
	private static volatile boolean carrierBatteryAnimationPending;
	private static volatile boolean carrierBatteryAnimationActive;

	final StringFormatter carrierStringFormatter = new StringFormatter();
	final StringFormatter clockStringFormatter = new StringFormatter();
	private final LockscreenCustomText middleText;
	private static String KGMiddleCustomText = "";
	private Object mColorExtractor;
	private boolean mDozing = false;
	private boolean mSupportsDarkText = false;

	private static boolean DisableUnlockHintAnimation = false;

	//region keyguardDimmer
	public static float KeyGuardDimAmount = -1f;
	private static boolean TemperatureUnitF = false;
	//endregion

	//region keyguard bottom area shortcuts and transparency
	private static final String BOTTOM_START = "bottom_start";
	private static final String BOTTOM_END = "bottom_end";
	private static final int INVALID_POINTER_ID = -1;
	private static boolean transparentBGcolor = false;
	private static volatile boolean requireShortcutInwardSwipe = false;
	private static volatile int shortcutInwardSwipeDistanceDp = 48;
	private boolean shortcutTouchActive;
	private int shortcutPointerId = INVALID_POINTER_ID;
	private float shortcutDownRawX;
	private PendingShortcutLaunch pendingShortcutLaunch;
	//endregion

	//region hide user avatar
	private boolean HideLockScreenUserAvatar = false;
	private static boolean ForceAODwCharging = false;
	//endregion

	private static boolean AnimateFlashlight = false;
	private static boolean DisableStrongAuthTimeout = false;

	public KeyguardMods(Context context) {
		super(context);
		middleText = new LockscreenCustomText(this::setMiddleColor);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		DisableUnlockHintAnimation = Xprefs.getBoolean("DisableUnlockHintAnimation", false);

		KGMiddleCustomText = Xprefs.getString("KGMiddleCustomText", "");

		customCarrierTextEnabled = Xprefs.getBoolean("carrierTextMod", false);
		customCarrierText = Xprefs.getString("carrierTextValue", "");

		ShowChargingInfo = Xprefs.getBoolean("ShowChargingInfo", false);
		TemperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false);

		HideLockScreenUserAvatar = Xprefs.getBoolean("HideLockScreenUserAvatar", false);

		ForceAODwCharging = Xprefs.getBoolean("ForceAODwCharging", false);

		KeyGuardDimAmount = Xprefs.getSliderFloat( "KeyGuardDimAmount", -1f) / 100f;

		transparentBGcolor = Xprefs.getBoolean("KeyguardBottomButtonsTransparent", false);
		requireShortcutInwardSwipe = Xprefs.getBoolean("KeyguardShortcutInwardSwipe", false);
		shortcutInwardSwipeDistanceDp = Xprefs.getSliderInt("KeyguardShortcutInwardSwipeDistance", 48);
		if (!requireShortcutInwardSwipe) {
			clearShortcutInwardSwipeState();
		}

		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false);
		DisableStrongAuthTimeout = Xprefs.getBoolean("DisableStrongAuthTimeout", false);

		if (Key.length > 0) {
			switch (Key[0]) {
				case "KGMiddleCustomText":
					updateMiddleTexts();
					break;
				case "carrierTextValue":
				case "carrierTextMod":
					setCarrierText();
					if (!customCarrierTextEnabled) {
						try {
							callMethod(
									getObjectField(carrierTextController, "carrierTextManager"),
									"updateCarrierText");
						} catch (Throwable ignored) {
						} //probably not initiated yet
					}
					break;
			}
		}
	}

	@SuppressLint({"DiscouragedApi", "DefaultLocale"})
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass CarrierTextControllerClass = ReflectedClass.of("com.android.keyguard.CarrierTextController");
		ReflectedClass KeyguardIndicationControllerClass = ReflectedClass.of("com.android.systemui.statusbar.KeyguardIndicationController");
		ReflectedClass ScrimControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimController");
		ReflectedClass ScrimStateEnum = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimState");
		ReflectedClass KeyguardStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.KeyguardStatusBarView");
		ReflectedClass CentralSurfacesImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
		ReflectedClass NotificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController"); //used to launch camera
		ReflectedClass AmbientDisplayConfigurationClass = ReflectedClass.of("android.hardware.display.AmbientDisplayConfiguration");
		ReflectedClass DefaultShortcutsSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection");
		ReflectedClass KeyguardIndicationControllerGoogleClass = ReflectedClass.of("com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle");
		ReflectedClass ShortcutElementProviderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.composable.elements.ShortcutElementProvider");
		ReflectedClass NotificationShadeWindowViewClass = ReflectedClass.of("com.android.systemui.shade.NotificationShadeWindowView");
		ReflectedClass BackgroundKtClass = ReflectedClass.of("androidx.compose.foundation.BackgroundKt");
		ReflectedClass IconKtClass = ReflectedClass.of("androidx.compose.material3.IconKt");
		ReflectedClass KeyguardQuickAffordanceViewBinderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder");
		ReflectedClass KeyguardQuickAffordanceViewClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.view.KeyguardQuickAffordanceView");
		ReflectedClass SceneWindowRootViewClass = ReflectedClass.ofIfPossible("com.android.systemui.scene.ui.view.SceneWindowRootView");
		ReflectedClass SystemStatusAnimationSchedulerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerImpl");
		ReflectedClass StatusBarSystemEventDefaultAnimatorClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.fragment.StatusBarSystemEventDefaultAnimator");
		ReflectedClass CarrierTranslationCallbackClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.KeyguardStatusBarViewController$$ExternalSyntheticLambda11");

		NotificationShadeWindowViewClass
				.after("onAttachedToWindow")
				.run(param -> {
					ViewGroup thisView = param.getThisObject();
					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(thisView.findViewById(idOf("start_button")));
					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(thisView.findViewById(idOf("end_button")));
				});

		// Compose-based shortcuts transparency
		ShortcutElementProviderClass
				.before("KeyguardShortcut")
				.run(param -> isRenderingShortcut.set(true));

		ShortcutElementProviderClass
				.after("KeyguardShortcut")
				.run(param -> isRenderingShortcut.set(false));

		BackgroundKtClass
				.before(Pattern.compile("background.*|m.*background.*"))
				.run(param -> {
					//noinspection DataFlowIssue
					if (transparentBGcolor && isRenderingShortcut.get() && param.args.length == 3 && param.args[1] instanceof Long) {
						param.args[1] = 0L; // Color.Transparent
					}
				});

		IconKtClass
				.before(Pattern.compile("Icon.*|m.*Icon.*"))
				.run(param -> {
					//noinspection DataFlowIssue
					if (transparentBGcolor && isRenderingShortcut.get() && param.args.length >= 4 && param.args[3] instanceof Long) {
						int wallpaperTextColorAccent = getColorAttrDefaultColor(
								mContext,
								mContext.getResources().getIdentifier("wallpaperTextColorAccent", "attr", mContext.getPackageName()));
						param.args[3] = ((long) wallpaperTextColorAccent) << 32;
					}
				});

		KeyguardQuickAffordanceViewBinderClass
				.after("bind").run(param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getArg(0)));

		KeyguardQuickAffordanceViewClass
				.afterConstruction()
				.run(param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getThisObject()));

		KeyguardQuickAffordanceViewClass
				.after("setBackground")
				.run(param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getThisObject()));

		hookShortcutInwardSwipe(
				ShortcutElementProviderClass,
				NotificationShadeWindowViewClass,
				SceneWindowRootViewClass);

		ReflectedClass.of(CameraManager.class)
				.before("setTorchMode")
				.run(param -> {
					SystemUtils.setFlash((Boolean) param.args[1], AnimateFlashlight);
					param.setResult(null);
				});

		DefaultShortcutsSectionClass
				.after("addViews")
				.run(param -> {
					Resources res = mContext.getResources();

					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
							callMethod(param.args[0], "findViewById", res.getIdentifier(
									"end_button",
									"id",
									mContext.getPackageName())));

					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
							callMethod(param.args[0], "findViewById", res.getIdentifier(
									"start_button",
									"id",
									mContext.getPackageName())));
				});

		middleText.install();
		updateMiddleTexts();

		AmbientDisplayConfigurationClass
				.after("alwaysOnEnabled")
				.run(param -> {
					if(ForceAODwCharging) {
						param.setResult((boolean) param.getResult() || isCharging());
					}
				});

		NotificationPanelViewControllerClass
				.before("startUnlockHintAnimation")
				.run(param -> {
					if(DisableUnlockHintAnimation) param.setResult(null);
				});

		//needed to extract wallpaper colors and capabilities. This is a SysUIColorExtractor
		CentralSurfacesImplClass
				.afterConstruction()
				.run(param -> mColorExtractor = getObjectField(param.thisObject, "mColorExtractor"));

		//region hide user avatar
		KeyguardStatusBarViewClass
				.after("updateVisibilities")
				.run(param -> {
					View mMultiUserAvatar = getObjectField(param.thisObject, "mMultiUserAvatar");
					boolean mIsUserSwitcherEnabled = getBooleanField(param.thisObject, "mIsUserSwitcherEnabled");
					mMultiUserAvatar.setVisibility(!HideLockScreenUserAvatar && mIsUserSwitcherEnabled
							? VISIBLE
							: GONE);
				});
		//endregion

		//region keyguard battery info


		KeyguardIndicationControllerGoogleClass
				.after("computePowerIndication")
				.run(param -> {
					if (ShowChargingInfo) {
						String result = param.getResult();

						Float shownTemperature = (TemperatureUnitF)
								? (temperature * 1.8f) + 32f
								: temperature;

						param.setResult(
								String.format(
										"%s\n%.1fW (%.1fV, %.1fA) • %.0fº%s"
										, result
										, max_charging_current * max_charging_voltage
										, max_charging_voltage
										, max_charging_current
										, shownTemperature
										, TemperatureUnitF
												? "F"
												: "C"));
					}
				});


		BatteryDataProvider.registerStatusCallback((batteryStatus, batteryStatusIntent) -> {
			max_charging_current = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000000f;
			max_charging_voltage = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000000f;
			temperature = batteryStatusIntent.getIntExtra(EXTRA_TEMPERATURE, 0) / 10f;
		});
		//endregion

		//region keyguardDimmer
		ScrimControllerClass
				.before(Pattern.compile("scheduleUpdate.*"))
				.run(param -> {
					if (KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1) return;

					setObjectField(param.thisObject, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount);
					Object[] constants = ScrimStateEnum.getClazz().getEnumConstants();
					for (Object constant : constants) {
						setObjectField(constant, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount);
					}
				});

		ReflectedClass.of(WallpaperManager.class)
				.after("getWallpaperDimAmount")
				.run(param -> {
					if ((KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1)
							|| param.getResult().equals(WALLPAPER_DIM_AMOUNT_DIMMED))
						return;

					//ref ColorUtils.compositeAlpha - Since KEYGUARD_SCRIM_ALPHA = .2f, we need to range the result between -70 and 255 to get the correct value when composed with 20% - we use 60 to cover float inaccuracies and never see a negative final result
					param.setResult((325 * KeyGuardDimAmount - 60) / 255);
				});
		//endregion

		carrierStringFormatter.registerCallback(this::setCarrierText);

		clockStringFormatter.registerCallback(this::updateMiddleTexts);

		KeyguardStatusBarViewClass
				.after("onFinishInflate")
				.run(param -> applyCarrierTextLayout(param.thisObject));

		KeyguardStatusBarViewClass
				.after("updateFirstStartSideItemMargin")
				.run(param -> applyCarrierTextLayout(param.thisObject));

		KeyguardStatusBarViewClass
				.after("updateWindowInsets")
				.run(param -> applyCarrierTextLayout(param.thisObject));

		SystemStatusAnimationSchedulerClass
				.after("onStatusEvent")
				.run(param -> updatePendingBatteryAnimation(param.thisObject));

		StatusBarSystemEventDefaultAnimatorClass
				.before("onSystemEventAnimationBegin")
				.run(param -> beginCarrierBatteryAnimation());

		StatusBarSystemEventDefaultAnimatorClass
				.after("onSystemEventAnimationFinish")
				.run(param -> finishCarrierBatteryAnimation(param.getResult()));

		CarrierTranslationCallbackClass
				.after("invoke")
				.run(this::compensateCarrierBatteryTranslation);

		CarrierTextControllerClass
				.after("onViewAttached")
				.run(param -> {
					carrierTextController = param.thisObject;
					Object carrierTextCallback = getObjectField(carrierTextController, "carrierTextCallback");
					setCarrierText();
					ReflectedClass.of(carrierTextCallback.getClass())
							.before("updateCarrierInfo")
							.run(param1 -> {
								if (customCarrierTextEnabled)
									param1.setResult(null);
							});
				});

		//a way to know when the device goes to AOD/dozing
		KeyguardIndicationControllerClass
				.after("updateDeviceEntryIndication")
				.run(param -> {
					if (mDozing != (boolean) getObjectField(param.thisObject, "mDozing")) {
						mDozing = !mDozing;
						setMiddleColor();
					}
				});

		//remove 72-hour strong auth timeout requirement
		//0x10 = STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, proven by services.jar:
		//StrongAuthTimeoutAlarmListener.onAlarm() -> const/16 #16 -> requireStrongAuth(flags)
		//LockPatternUtils$StrongAuthTracker is a CLASS (not interface) on this build, and no
		//SystemUI class overrides getStrongAuthForUser, so hooking it covers every subclass
		//(KeyguardUpdateMonitor$StrongAuthTracker and the Android17 Kotlin
		// com.android.systemui.keyguard.data.repository.StrongAuthTracker, whose
		// currentUserAuthFlags StateFlow value comes from this same method).
		ReflectedClass StrongAuthTrackerClass = ReflectedClass.of("com.android.internal.widget.LockPatternUtils$StrongAuthTracker");
		StrongAuthTrackerClass
				.after("getStrongAuthForUser")
				.run(param -> {
					if (DisableStrongAuthTimeout) {
						param.setResult(((int) param.getResult()) & ~0x10);
					}
				});

		//CANARY SystemUI also has 5 call sites that read the flag through LockPatternUtils
		//itself (CredentialInteractorImpl.verifyCredential, AuthenticationRepositoryImpl,
		//GlobalActionsDialogLite, ControlsComponent, KeyguardQuickAffordancesCombinedViewModel).
		//Those bypass the tracker whenever no tracker was registered (the AOSP impl falls back
		//to Settings.Secure user_requires_strong_auth), so clear the bit there too. Idempotent
		//when LockPatternUtils delegates to the tracker above.
		ReflectedClass.of("com.android.internal.widget.LockPatternUtils")
				.after("getStrongAuthForUser")
				.run(param -> {
					if (DisableStrongAuthTimeout) {
						param.setResult(((int) param.getResult()) & ~0x10);
					}
				});
	}

	private void hookShortcutInwardSwipe(
			ReflectedClass shortcutElementProviderClass,
			ReflectedClass notificationShadeWindowViewClass,
			ReflectedClass sceneWindowRootViewClass) {
		shortcutElementProviderClass
				.before("triggerQuickAffordance")
				.run(this::deferBottomShortcutLaunch);

		notificationShadeWindowViewClass
				.before("dispatchTouchEvent")
				.run(this::trackShortcutTouch);
		sceneWindowRootViewClass
				.before("dispatchTouchEvent")
				.run(this::trackShortcutTouch);
	}

	private void deferBottomShortcutLaunch(HookHelper.RunParam param) {
		String slotId = getLongPressBottomShortcutSlot(param.args.length > 0 ? param.args[0] : null);
		if (!requireShortcutInwardSwipe
				|| !shortcutTouchActive
				|| param.args.length != 2
				|| !(param.method instanceof Method originalMethod)
				|| slotId == null) {
			return;
		}

		pendingShortcutLaunch = new PendingShortcutLaunch(
				param.thisObject,
				originalMethod,
				(Object[]) param.args.clone(),
				shortcutPointerId,
				shortcutDownRawX,
				shouldSwipeTowardRight(slotId));
		param.setResult(null);
	}

	private void trackShortcutTouch(HookHelper.RunParam param) {
		if (!requireShortcutInwardSwipe) {
			clearShortcutInwardSwipeState();
			return;
		}
		if (param.args.length == 0 || !(param.args[0] instanceof MotionEvent motionEvent)) {
			return;
		}

		switch (motionEvent.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				pendingShortcutLaunch = null;
				shortcutTouchActive = motionEvent.getPointerCount() == 1;
				shortcutPointerId = shortcutTouchActive
						? motionEvent.getPointerId(0)
						: INVALID_POINTER_ID;
				shortcutDownRawX = shortcutTouchActive ? motionEvent.getRawX() : 0f;
				break;
			case MotionEvent.ACTION_MOVE:
				confirmPendingShortcutLaunch(motionEvent);
				break;
			case MotionEvent.ACTION_UP:
				confirmPendingShortcutLaunch(motionEvent);
				clearShortcutInwardSwipeState();
				break;
			case MotionEvent.ACTION_CANCEL:
			case MotionEvent.ACTION_POINTER_DOWN:
			case MotionEvent.ACTION_POINTER_UP:
				clearShortcutInwardSwipeState();
				break;
		}
	}

	private void confirmPendingShortcutLaunch(MotionEvent motionEvent) {
		PendingShortcutLaunch launch = pendingShortcutLaunch;
		if (launch == null
				|| !shortcutTouchActive
				|| motionEvent.getPointerCount() != 1
				|| launch.pointerId != shortcutPointerId) {
			return;
		}

		float horizontalMovement = motionEvent.getRawX() - launch.downRawX;
		boolean movedOutward = launch.swipeTowardRight
				? horizontalMovement < 0
				: horizontalMovement > 0;
		if (movedOutward && Math.abs(horizontalMovement) > ViewConfiguration.get(mContext).getScaledTouchSlop()) {
			pendingShortcutLaunch = null;
			return;
		}

		float requiredDistancePx = shortcutInwardSwipeDistanceDp
				* mContext.getResources().getDisplayMetrics().density;
		boolean reachedInwardDistance = launch.swipeTowardRight
				? horizontalMovement >= requiredDistancePx
				: -horizontalMovement >= requiredDistancePx;
		if (!reachedInwardDistance) {
			return;
		}

		pendingShortcutLaunch = null;
		try {
			invokeOriginalMethod(launch.method, launch.receiver, launch.args);
		} catch (Throwable ignored) {
		}
	}

	private String getLongPressBottomShortcutSlot(Object viewModel) {
		try {
			if (viewModel == null || !getBooleanField(viewModel, "useLongPress")) {
				return null;
			}
			String slotId = getObjectField(viewModel, "slotId");
			return BOTTOM_START.equals(slotId) || BOTTOM_END.equals(slotId) ? slotId : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private boolean shouldSwipeTowardRight(String slotId) {
		boolean isRtl = mContext.getResources().getConfiguration().getLayoutDirection()
				== View.LAYOUT_DIRECTION_RTL;
		return BOTTOM_START.equals(slotId) != isRtl;
	}

	private void clearShortcutInwardSwipeState() {
		pendingShortcutLaunch = null;
		shortcutTouchActive = false;
		shortcutPointerId = INVALID_POINTER_ID;
		shortcutDownRawX = 0f;
	}

	private static final class PendingShortcutLaunch {
		final Object receiver;
		final Method method;
		final Object[] args;
		final int pointerId;
		final float downRawX;
		final boolean swipeTowardRight;

		PendingShortcutLaunch(
				Object receiver,
				Method method,
				Object[] args,
				int pointerId,
				float downRawX,
				boolean swipeTowardRight) {
			this.receiver = receiver;
			this.method = method;
			this.args = args;
			this.pointerId = pointerId;
			this.downRawX = downRawX;
			this.swipeTowardRight = swipeTowardRight;
		}
	}

	private void setMiddleColor() {
		try {
			if (mColorExtractor != null) {
				Object colors = callMethod(mColorExtractor, "getColors", WallpaperManager.FLAG_LOCK);
				mSupportsDarkText = callMethod(colors, "supportsDarkText");
			}
		} catch (Throwable ignored) {}
		middleText.setColor((mDozing || !mSupportsDarkText) ? Color.WHITE : Color.BLACK);
	}

	private void setCarrierText() {
		try {
			TextView mView = getObjectField(carrierTextController, "mView");
			mView.post(() -> {
				try {
					callMethod(mView.getParent(), "updateFirstStartSideItemMargin");
					applyCarrierTextLayout(mView.getParent());
				} catch (Throwable ignored) {}
				if (customCarrierTextEnabled) {
					mView.setText(carrierStringFormatter.formatString(customCarrierText));
				}
			});
		} catch (Throwable ignored) {} //probably not initiated yet
	}

	private void updatePendingBatteryAnimation(Object scheduler) {
		try {
			Object scheduledEvent = callMethod(getObjectField(scheduler, "scheduledEvent"), "getValue");
			carrierBatteryAnimationPending = scheduledEvent != null
					&& scheduledEvent.getClass().getName().equals("com.android.systemui.statusbar.events.BatteryEvent");
		} catch (Throwable ignored) {
			carrierBatteryAnimationPending = false;
		}
	}

	private void beginCarrierBatteryAnimation() {
		if (!carrierBatteryAnimationPending || !customCarrierTextEnabled) return;

		carrierBatteryAnimationPending = false;
		carrierBatteryAnimationActive = true;
		clearCarrierAnimationTranslation();
	}

	private void finishCarrierBatteryAnimation(Object animator) {
		if (!carrierBatteryAnimationActive) return;

		try {
			ClassLoader classLoader = animator.getClass().getClassLoader();
			Class<?> listenerClass = Class.forName(
					"androidx.core.animation.Animator$AnimatorListener", false, classLoader);
			Object listener = java.lang.reflect.Proxy.newProxyInstance(
					classLoader,
					new Class<?>[]{listenerClass},
					(proxy, method, args) -> {
						if (method.getName().startsWith("onAnimationEnd")
								|| method.getName().equals("onAnimationCancel")) {
							endCarrierBatteryAnimation();
						} else if (method.getName().equals("hashCode")) {
							return System.identityHashCode(proxy);
						} else if (method.getName().equals("equals")) {
							return proxy == args[0];
						} else if (method.getName().equals("toString")) {
							return "PixelXpertCarrierAnimationListener";
						}
						return null;
					});
			callMethod(animator, "addListener", listener);
		} catch (Throwable ignored) {
			postCarrierAnimationFallback();
		}
	}

	private void postCarrierAnimationFallback() {
		try {
			TextView carrierLabel = getObjectField(carrierTextController, "mView");
			carrierLabel.postDelayed(this::endCarrierBatteryAnimation, 1200);
		} catch (Throwable ignored) {
			endCarrierBatteryAnimation();
		}
	}

	private void endCarrierBatteryAnimation() {
		if (!carrierBatteryAnimationActive) return;

		carrierBatteryAnimationActive = false;
		clearCarrierAnimationTranslation();
	}

	private void compensateCarrierBatteryTranslation(HookHelper.RunParam param) {
		try {
			if (!carrierBatteryAnimationActive || !customCarrierTextEnabled
					|| getIntField(param.thisObject, "$r8$classId") != 1
					|| param.args.length != 1 || !(param.args[0] instanceof Float parentTranslationX)) {
				return;
			}
			TextView carrierLabel = getObjectField(carrierTextController, "mView");
			carrierLabel.setTranslationX(-parentTranslationX);
		} catch (Throwable ignored) {
			clearCarrierAnimationTranslation();
		}
	}

	private void clearCarrierAnimationTranslation() {
		try {
			if (carrierTextController != null) {
				TextView carrierLabel = getObjectField(carrierTextController, "mView");
				carrierLabel.setTranslationX(0f);
			}
		} catch (Throwable ignored) {}
	}

	private void applyCurrentCarrierTextLayout() {
		try {
			TextView carrierLabel = getObjectField(carrierTextController, "mView");
			applyCarrierTextLayout(carrierLabel.getParent());
		} catch (Throwable ignored) {}
	}

	private void applyCarrierTextLayout(Object keyguardStatusBarView) {
		try {
			TextView carrierLabel = getObjectField(keyguardStatusBarView, "mCarrierLabel");
			if (!(carrierLabel.getLayoutParams() instanceof RelativeLayout.LayoutParams layoutParams)) {
				return;
			}

			int agentIconPlaceholderId = carrierLabel.getResources().getIdentifier(
					"keyguard_agent_icon_placeholder", "id", mContext.getPackageName());
			if (!customCarrierTextEnabled) {
				carrierLabel.setTranslationX(0f);
				layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_START);
				if (agentIconPlaceholderId != 0) {
					layoutParams.addRule(RelativeLayout.END_OF, agentIconPlaceholderId);
				}
				carrierLabel.setLayoutParams(layoutParams);
				return;
			}

			if (agentIconPlaceholderId != 0) {
				layoutParams.removeRule(RelativeLayout.END_OF);
			}
			layoutParams.addRule(RelativeLayout.ALIGN_PARENT_START);

			ViewGroup statusBarView = (ViewGroup) keyguardStatusBarView;
			int symmetricMargin = Math.max(0, statusBarView.getPaddingEnd()
					+ getIntField(keyguardStatusBarView, "mStatusBarPaddingEnd")
					- statusBarView.getPaddingStart());
			layoutParams.setMarginStart(symmetricMargin);
			carrierLabel.setLayoutParams(layoutParams);
		} catch (Throwable ignored) {}
	}

	private void updateMiddleTexts() {
		middleText.setText(KGMiddleCustomText.isEmpty() ? "" : clockStringFormatter.formatString(KGMiddleCustomText));
	}

	public static class ControlledLaunchableImageViewBackgroundDrawable extends Drawable
	{
		Context mContext;
		Drawable mDrawable;
		WeakReference<ImageView> parentImageViewReference;

		public static void captureDrawable(ImageView imageView)
		{
			if (imageView == null) return;
			try {
				Drawable background = imageView.getBackground();
				if (background == null || background instanceof ControlledLaunchableImageViewBackgroundDrawable) return;

				background = new ControlledLaunchableImageViewBackgroundDrawable(background.getCurrent().mutate(), imageView);
				imageView.setBackground(background);

			} catch (Throwable ignored) {}
		}
		@NonNull
		@Override
		public Drawable mutate()
		{
			return new ControlledLaunchableImageViewBackgroundDrawable(mDrawable.mutate(), parentImageViewReference.get());
		}
		public ControlledLaunchableImageViewBackgroundDrawable(Drawable drawable, ImageView parentView)
		{
			parentImageViewReference = new WeakReference<>(parentView);
			mContext = parentView.getContext();
			mDrawable = drawable;
		}
		@Override
		public void setTint(int tintColor)
		{
			if(!transparentBGcolor)
			{
				mDrawable.setTint(tintColor);
			}
			else
			{
				mDrawable.setTint(TRANSPARENT);
			}
		}

		@Override
		public void setTintList(ColorStateList tintList) {
			if(transparentBGcolor)
			{
				mDrawable.setTint(TRANSPARENT);

				ImageView parentView = parentImageViewReference.get();
				if (parentView != null && parentView.getDrawable() != null) {
					@SuppressLint("DiscouragedApi") int wallpaperTextColorAccent = getColorAttrDefaultColor(
							mContext,
							mContext.getResources().getIdentifier("wallpaperTextColorAccent", "attr", mContext.getPackageName()));

					parentView.getDrawable().setTint(wallpaperTextColorAccent);
				}
			}
			else
			{
				mDrawable.setTintList(tintList);
			}
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			mDrawable.draw(canvas);
		}

		@Override
		public void jumpToCurrentState()
		{
			mDrawable.jumpToCurrentState();
		}

		@Override
		public void setAlpha(int alpha) {
			mDrawable.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			if(!transparentBGcolor)
			{
				mDrawable.setColorFilter(colorFilter);
			}
		}

		@Override
		public int getOpacity() {
			//noinspection deprecation
			return mDrawable.getOpacity();
		}

		@Override
		public boolean getPadding(@NonNull Rect padding)
		{
			return mDrawable.getPadding(padding);
		}

		@Override
		public int getMinimumHeight()
		{
			return mDrawable.getMinimumHeight();
		}

		@Override
		public int getMinimumWidth()
		{
			return mDrawable.getMinimumWidth();
		}

		@Override
		public boolean isStateful()
		{
			return mDrawable.isStateful();
		}

		@Override
		public boolean setVisible(boolean visible, boolean restart)
		{
			return mDrawable.setVisible(visible, restart);
		}

		@Override
		public void getOutline(@NonNull Outline outline)
		{
			mDrawable.getOutline(outline);
		}

		@Override
		public boolean isProjected()
		{
			return mDrawable.isProjected();
		}

		@Override
		public void setBounds(@NonNull Rect bounds)
		{
			mDrawable.setBounds(bounds);
		}

		@Override
		public void setBounds(int l, int t, int r, int b)
		{
			mDrawable.setBounds(l,t,r,b);
		}

		@NonNull
		@Override
		public Drawable getCurrent()
		{
			return mDrawable.getCurrent();
		}

		@Override
		public boolean setState(@NonNull int[] stateSet)
		{
			return mDrawable.setState(stateSet);
		}

		@Override
		public int getIntrinsicWidth()
		{
			return mDrawable.getIntrinsicWidth();
		}

		@Override
		public int getIntrinsicHeight()
		{
			return mDrawable.getIntrinsicHeight();
		}
	}
}