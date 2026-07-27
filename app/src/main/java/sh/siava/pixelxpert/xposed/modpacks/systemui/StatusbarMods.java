package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.dimenIdOf;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.resourceIdOf;
import static sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf;
import static sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.objenesis.ObjenesisHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import javax.security.auth.callback.Callback;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic;
import sh.siava.pixelxpert.xposed.utils.ShyLinearLayout;
import sh.siava.pixelxpert.xposed.utils.StringFormatter;
import sh.siava.pixelxpert.xposed.utils.StringFormatter.FormattedStringCallback;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.batteryStyles.BatteryBarView;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools;

/**
 * @noinspection v
 */
@SystemUIModPack
public class StatusbarMods extends XposedModPack {
	//region Clock
	public static final int POSITION_LEFT = 0;
	public static final int POSITION_CENTER = 1;
	public static final int POSITION_RIGHT = 2;
	public static final int POSITION_LEFT_EXTRA_LEVEL = 3;

	private static final int AM_PM_STYLE_SMALL = 1;
	private static final int AM_PM_STYLE_GONE = 2;
	private final int leftClockPadding, rightClockPadding;
	private static int clockPosition = POSITION_LEFT;
	private static int mAmPmStyle = AM_PM_STYLE_GONE;
	private static boolean mShowSeconds = false;
	private static String mStringFormatBefore = "", mStringFormatAfter = "";
	private static boolean mBeforeSmall = true, mAfterSmall = true;
	private Integer mBeforeClockColor = null, mAfterClockColor = null, clockColor = null;
	//endregion

	//region network traffic
	private static boolean networkOnSBEnabled = false;
	private static int networkTrafficPosition = POSITION_LEFT;
	private NetworkTraffic networkTrafficSB = null;
	//endregion

	//region battery bar
	private static boolean BBarEnabled;
	private static boolean BBarColorful;
	private static boolean BBOnlyWhileCharging;
	private static boolean BBOnBottom;
	private static boolean BBSetCentered;
	private static int BBOpacity = 100;
	private static int BBarHeight = 10;
	private static List<Float> batteryLevels = Arrays.asList(20f, 40f);
	private static int[] batteryColors = new int[]{Color.RED, Color.YELLOW};
	private static int chargingColor = Color.WHITE;
	private static int fastChargingColor = Color.WHITE;
	private static int powerSaveColor = Color.parseColor("#FFBF00");
	private static boolean indicateCharging = false;
	private static boolean indicateFastCharging = false;
	private static boolean indicatePowerSave = false;
	private static boolean BBarTransitColors = false;
	private static boolean BBAnimateCharging = false;
	//endregion

	//region privacy chip
	private static boolean HidePrivacyChip = false; //works
	//endregion

	//region general use
	private static final float PADDING_DEFAULT = -0.5f;
	private static final ArrayList<ClockVisibilityCallback> clockVisibilityCallbacks = new ArrayList<>();
	private Object mActivityStarter;
	private static boolean notificationAreaMultiRow = false;
	private static int NotificationAODIconLimit = 3;
	private static int NotificationIconLimit = 4;
	private Object AODNIC;
	private Object SBNIC;
	private ViewGroup mStatusbarStartSide = null;
	private View mCenteredIconArea = null;
	private LinearLayout mSystemIconArea = null;
	private static int currentClockColor = 0;
	private static final ArrayList<StatusbarTextColorCallback> mTextColorCallbacks = new ArrayList<>();
	//    private Object STB = null;

	/*
	 * CANARY renders the home clock from ClockViewModel/ClockKt and keeps the XML
	 * Clock only as a bounds/compatibility view. Do not move that compatibility
	 * view: StatusBarRoot hides it during every recomposition. A dedicated native
	 * overlay gives PixelXpert one stable rendering owner for location, date text,
	 * seconds and colour customisation.
	 */
	private TextView mClockView;
	private TextView mCanaryClockOverlay;
	private ViewGroup mNotificationIconContainer = null;
	LinearLayout mNotificationContainerContainer;
	private LinearLayout mLeftVerticalSplitContainer;
	private LinearLayout mLeftExtraRowContainer;
	private static float SBPaddingStart = 0, SBPaddingEnd = 0;
	private FrameLayout mPhoneStatusbarView;
	private boolean mModernStatusBar;
	/* True once the overlay has a parent in the current PhoneStatusBarView. It can
	 * be safely used while the root is still being attached, which is important
	 * because Compose may render its first frame before the controller callback. */
	private boolean mCanaryClockAttached;
	private static final ThreadLocal<Integer> statusBarClockCompositionDepth = ThreadLocal.withInitial(() -> 0);
	private final Runnable mCanaryClockTick = new Runnable() {
		@Override
		public void run() {
			refreshCanaryClockText();
			scheduleCanaryClockTick();
		}
	};

	//endregion

	//region vo_data
	private static final String VO_LTE_SLOT = "volte";
	private static final String VO_WIFI_SLOT = "vowifi";

	private static boolean VolteIconEnabled = false; //works
	private final Executor voDataExec = Runnable::run;

	private Object mStatusBarIconController;

	private ReflectedClass StatusBarIconClass;
	private ReflectedClass StatusBarIconHolderClass;
	private Object volteStatusbarIconHolder;
	private boolean telephonyCallbackRegistered = false;
	private boolean lastVolteAvailable = false;
	private final serverStateCallback voDataCallback = new serverStateCallback();
	//endregion

	private static boolean VowifiIconEnabled = false;
	private Object vowifiStatusbarIconHolder;
	private boolean lastVowifiAvailable = false;
	//endregion

	//region combined signal icons
	private boolean mWifiVisible = false;
	private static boolean CombineSignalIcons = false;
	private static boolean HideRoamingState = false;
	private Object mTunerService;
	public static final String ICON_HIDE_LIST = "icon_blacklist";
	//endregion
	//region app profile switch
	public static final String APP_SWITCH_SLOT = "app_switch";
	private Object mAppSwitchStatusbarIconHolder = null;

	private static boolean StatusbarAppSwitchIconEnabled = false; //works

	private final BroadcastReceiver mAppProfileSwitchReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (Constants.ACTION_PROFILE_SWITCH_AVAILABLE.equals(intent.getAction())) {
				boolean isAvailable = intent.getBooleanExtra("available", false);
				if (isAvailable
						&& StatusbarAppSwitchIconEnabled
						&& mStatusBarIconController != null) {
					callMethod(mStatusBarIconController, "setIcon", APP_SWITCH_SLOT, mAppSwitchStatusbarIconHolder);
				} else {
					removeSBIconSlot(APP_SWITCH_SLOT);
				}
			}
		}
	};
	//endregion

	@SuppressLint("DiscouragedApi")
	public StatusbarMods(Context context) {
		super(context);
		rightClockPadding = mContext.getResources().getDimensionPixelSize(dimenIdOf("status_bar_clock_starting_padding"));
		leftClockPadding = mContext.getResources().getDimensionPixelSize(dimenIdOf("status_bar_left_clock_end_padding"));
	}

	private void initSwitchIcon() {
		try {
			Icon appSwitchIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_app_switch);

			Object appSwitchStatusbarIcon = getStatusbarIconFor(appSwitchIcon, APP_SWITCH_SLOT);

			mAppSwitchStatusbarIconHolder = getStatusbarIconHolderFor(appSwitchStatusbarIcon);
		} catch (Throwable ignored) {
		}
	}

	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		StatusbarAppSwitchIconEnabled = Xprefs.getBoolean("StatusbarAppSwitchIconEnabled", false);

		HidePrivacyChip = Xprefs.getBoolean("HidePrivacyChip", false);

		HideRoamingState = Xprefs.getBoolean("HideRoamingState", false);

		CombineSignalIcons = Xprefs.getBoolean("combinedSignalEnabled", false);
		wifiVisibleChanged();

		if (Key.length > 0 && Key[0].equals("notificationAreaMultiRow")) { //WHY we check the old value? because if prefs is empty it will fill it up and count an unwanted change
			boolean newnotificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false);
			if (newnotificationAreaMultiRow != notificationAreaMultiRow) {
				SystemUtils.killSelf();
			}
		}
		notificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false);

		try {
			NotificationIconLimit = Integer.parseInt(Xprefs.getString("NotificationIconLimit", "").trim());
		} catch (Throwable ignored) {
			NotificationIconLimit = getIntegerResource("max_notif_static_icons", 4);
		}


		try {
			NotificationAODIconLimit = Integer.parseInt(Xprefs.getString("NotificationAODIconLimit", "").trim());
		} catch (Throwable ignored) {
			NotificationAODIconLimit = getIntegerResource("max_notif_icons_on_aod", 3);
		}

		// The CANARY binders are created independently. Preference updates can arrive
		// after the AOD binder is ready but before the status-bar binder exists.
		// Both target flows read their instance field at collection time; update the
		// already-created model only after confirming it is available.
		if (AODNIC != null) {
			setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit);
		}
		if (SBNIC != null) {
			setObjectField(SBNIC, "maxIcons", NotificationIconLimit);
		}

		List<Float> paddings = Xprefs.getSliderValues("statusbarPaddings", 0);

		if (paddings.size() > 1) {
			SBPaddingStart = paddings.get(0);
			SBPaddingEnd = 100f - paddings.get(1);
		}

		//region BatteryBar Settings
		BBarEnabled = Xprefs.getBoolean("BBarEnabled", false);
		BBarColorful = Xprefs.getBoolean("BBarColorful", false);
		BBOnlyWhileCharging = Xprefs.getBoolean("BBOnlyWhileCharging", false);
		BBOnBottom = Xprefs.getBoolean("BBOnBottom", false);
		BBSetCentered = Xprefs.getBoolean("BBSetCentered", false);
		BBOpacity = Xprefs.getSliderInt("BBOpacity", 100);
		BBarHeight = Xprefs.getSliderInt("BBarHeight", 50);
		BBarTransitColors = Xprefs.getBoolean("BBarTransitColors", false);
		BBAnimateCharging = Xprefs.getBoolean("BBAnimateCharging", false);

		batteryLevels = Xprefs.getSliderValues("batteryWarningRange", 0);

		batteryColors = new int[]{
				Xprefs.getInt("batteryCriticalColor", Color.RED),
				Xprefs.getInt("batteryWarningColor", Color.YELLOW)};


		indicateFastCharging = Xprefs.getBoolean("indicateFastCharging", false);
		indicatePowerSave = Xprefs.getBoolean("indicatePowerSave", false);
		indicateCharging = Xprefs.getBoolean("indicateCharging", true);

		chargingColor = Xprefs.getInt("batteryChargingColor", Color.GREEN);
		fastChargingColor = Xprefs.getInt("batteryFastChargingColor", Color.BLUE);
		powerSaveColor = Xprefs.getInt("batteryPowerSaveColor", Color.parseColor("#FFBF00"));

		if (BBarEnabled) {
			placeBatteryBar();
		}

		if (BatteryBarView.hasInstance()) {
			refreshBatteryBar(BatteryBarView.getInstance());
		}
		//endregion BatteryBar Settings


		//region network Traffic settings
		networkOnSBEnabled = Xprefs.getBoolean("networkOnSBEnabled", false);
		String networkTrafficModeStr = Xprefs.getString("networkTrafficMode", "0");
		int networkTrafficMode = Integer.parseInt(networkTrafficModeStr);

		boolean networkTrafficRXTop = Xprefs.getBoolean("networkTrafficRXTop", true);
		int networkTrafficDLColor = Xprefs.getInt("networkTrafficDLColor", Color.GREEN);
		int networkTrafficULColor = Xprefs.getInt("networkTrafficULColor", Color.RED);
		int networkTrafficOpacity = Xprefs.getSliderInt("networkTrafficOpacity", 100);
		int networkTrafficInterval = Xprefs.getSliderInt("networkTrafficInterval", 1);
		boolean networkTrafficColorful = Xprefs.getBoolean("networkTrafficColorful", false);
		boolean networkTrafficShowIcons = Xprefs.getBoolean("networkTrafficShowIcons", true);
		boolean networkTrafficShowInBits = Xprefs.getBoolean("networkTrafficShowInBits", false);

		if (networkOnSBEnabled) {
			networkTrafficPosition = Integer.parseInt(Xprefs.getString("networkTrafficPosition", String.valueOf(POSITION_RIGHT)));
			if (networkTrafficPosition == POSITION_LEFT_EXTRA_LEVEL) {
				Xprefs.edit().putString("networkTrafficPosition", String.valueOf(POSITION_LEFT)).apply();
				networkTrafficPosition = POSITION_LEFT;
			}

			String thresholdText = Xprefs.getString("networkTrafficThreshold", "10");

			int networkTrafficThreshold;
			try {
				networkTrafficThreshold = Math.round(Float.parseFloat(thresholdText));
			} catch (Exception ignored) {
				networkTrafficThreshold = 10;
			}
			NetworkTraffic.setConstants(networkTrafficInterval, networkTrafficThreshold, networkTrafficMode, networkTrafficRXTop, networkTrafficColorful, networkTrafficDLColor, networkTrafficULColor, networkTrafficOpacity, networkTrafficShowIcons, networkTrafficShowInBits);

		}
		if (networkOnSBEnabled) {
			networkTrafficSB = NetworkTraffic.getInstance(mContext, true);
			networkTrafficSB.update();
		}
		placeNTSB();

		//endregion network settings

		//region clock settings
		clockPosition = Integer.parseInt(Xprefs.getString("SBClockLoc", String.valueOf(POSITION_LEFT)));
		if (clockPosition == POSITION_LEFT_EXTRA_LEVEL) {
			Xprefs.edit().putString("SBClockLoc", String.valueOf(POSITION_LEFT)).apply();
			clockPosition = POSITION_LEFT;
		}

		mShowSeconds = Xprefs.getBoolean("SBCShowSeconds", false);
		mAmPmStyle = Integer.parseInt(Xprefs.getString("SBCAmPmStyle", String.valueOf(AM_PM_STYLE_GONE)));

		mStringFormatBefore = Xprefs.getString("DateFormatBeforeSBC", "");
		mStringFormatAfter = Xprefs.getString("DateFormatAfterSBC", "");
		mBeforeSmall = Xprefs.getBoolean("BeforeSBCSmall", true);
		mAfterSmall = Xprefs.getBoolean("AfterSBCSmall", true);

		if (Xprefs.getBoolean("SBCClockColorful", false)) {
			clockColor = Xprefs.getInt("SBCClockColor", Color.WHITE);
			mBeforeClockColor = Xprefs.getInt("SBCBeforeClockColor", Color.WHITE);
			mAfterClockColor = Xprefs.getInt("SBCAfterClockColor", Color.WHITE);
		} else {
			clockColor
					= mBeforeClockColor
					= mAfterClockColor
					= null;
		}

		if ((mStringFormatBefore + mStringFormatAfter).trim().isEmpty()) {
			int SBCDayOfWeekMode = Integer.parseInt(Xprefs.getString("SBCDayOfWeekMode", "0"));

			switch (SBCDayOfWeekMode) {
				case 0:
					mStringFormatAfter = mStringFormatBefore = "";
					break;
				case 1:
					mStringFormatBefore = "$GEEE ";
					mStringFormatAfter = "";
					mBeforeSmall = false;
					break;
				case 2:
					mStringFormatBefore = "$GEEE ";
					mStringFormatAfter = "";
					mBeforeSmall = true;
					break;
				case 3:
					mStringFormatBefore = "";
					mStringFormatAfter = " $GEEE";
					mAfterSmall = false;
					break;
				case 4:
					mStringFormatBefore = "";
					mStringFormatAfter = " $GEEE";
					mAfterSmall = true;
					break;
			}
		}

		try {
			refreshClockRenderer();
		} catch (Throwable ignored) {}
		//endregion clock settings

		//region vo_data
		VolteIconEnabled = Xprefs.getBoolean("VolteIconEnabled", false);
		VowifiIconEnabled = Xprefs.getBoolean("VowifiIconEnabled", false);
		//endregion

		if (Key.length > 0) {
			switch (Key[0]) {
				case "statusbarPaddings":
					updateStatusbarHeight();
					break;
				case "VolteIconEnabled":
				case "VowifiIconEnabled":
					if (VolteIconEnabled || VowifiIconEnabled) {
						initVoData();

						if (!VolteIconEnabled) removeSBIconSlot(VO_LTE_SLOT);
						if (!VowifiIconEnabled) removeSBIconSlot(VO_WIFI_SLOT);
					} else
						removeVoDataCallback();
					break;
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	private int getIntegerResource(String resourceName, int defaultValue) {
		try {
			return mContext.getResources().getInteger(resourceIdOf(resourceName, "integer"));
		} catch (Throwable ignored) {
			return defaultValue;
		}
	}

	private void updateClock() {
		try {
			if (mClockView == null) return;
			mClockView.post(() -> { //the builtin update method doesn't care about the format. Just the text sadly
				if (mClockView == null) return;
				callMethod(getObjectField(mClockView, "mCalendar"), "setTimeInMillis", System.currentTimeMillis());
				mClockView.setText((CharSequence) callMethod(mClockView, "getSmallTime"));
			});
		} catch (Throwable ignored) {
		}
	}

	@SuppressLint("DiscouragedApi")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		IntentFilter filter = new IntentFilter();
		filter.addAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE);
		mContext.registerReceiver(mAppProfileSwitchReceiver, filter, Context.RECEIVER_EXPORTED);

		//region needed classes
		ReflectedClass ClockClass = ReflectedClass.of("com.android.systemui.statusbar.policy.Clock");
		ReflectedClass StatusBarRootFactoryClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory");
		/*
		 * Verified against 系统界面_CANARY.APK:
		 * StatusBarRootKt$$ExternalSyntheticLambda0 invokes ClockViewModel/ClockKt
		 * and calls setVisibility(GONE) on the XML Clock during recomposition.
		 */
		ReflectedClass StatusBarRootComposableClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootKt$$ExternalSyntheticLambda0");
		ReflectedClass StatusBarClockComposableClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootKt$$ExternalSyntheticLambda7");
		ReflectedClass ClockComposableClass = ReflectedClass.ofIfPossible("com.android.systemui.clock.ui.composable.ClockKt");
		mModernStatusBar = StatusBarRootFactoryClass.getClazz() != null;
		ReflectedClass PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView");
		ReflectedClass NotificationIconContainerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.NotificationIconContainer");
		ReflectedClass TunerServiceImplClass = ReflectedClass.of("com.android.systemui.tuner.TunerServiceImpl");
		ReflectedClass ConnectivityCallbackHandlerClass = ReflectedClass.of("com.android.systemui.statusbar.connectivity.CallbackHandler");
		ReflectedClass NotificationIconContainerAlwaysOnDisplayViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel");
		ReflectedClass NotificationIconContainerStatusBarViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel");
		StatusBarIconClass = ReflectedClass.of("com.android.internal.statusbar.StatusBarIcon");
		StatusBarIconHolderClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarIconHolder");
		ReflectedClass PrivacyItemClass = ReflectedClass.of("com.android.systemui.privacy.PrivacyItem");
		ReflectedClass PhoneStatusBarViewControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarViewController");
		ReflectedClass KeyguardStateControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl");
		ReflectedClass StatusBarIconControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl");
		ReflectedClass ShadeHeaderControllerClass = ReflectedClass.of("com.android.systemui.shade.ShadeHeaderController");
		ReflectedClass ActivityStarterImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ActivityStarterImpl");
		//endregion


		KeyguardStateControllerImplClass
				.after("notifyKeyguardState")
				.run(param -> {
					Object mKeyguardUpdateMonitor = getObjectField(param.thisObject, "mKeyguardUpdateMonitor");
					boolean keyguardShowing = (boolean) getObjectField(mKeyguardUpdateMonitor, "mKeyguardShowing");
					for (ClockVisibilityCallback c : clockVisibilityCallbacks)
					{
						try {
							c.OnVisibilityChanged(!keyguardShowing);
						} catch (Throwable ignored) {}
					}
				});

		StatusBarIconControllerImplClass
				.afterConstruction()
				.run(param -> {
					mStatusBarIconController = param.thisObject;
					// The icon controller and status-bar view are constructed by separate
					// CANARY binders. Re-evaluate IMS state once the controller is ready;
					// a prior callback may have run while it was still null.
					if (telephonyCallbackRegistered) updateVoData(true);
				});


		if (NotificationIconContainerAlwaysOnDisplayViewModelClass.getClazz() != null) //Viewbinder implementation of the notification icon container
		{
			NotificationIconContainerAlwaysOnDisplayViewModelClass
					.afterConstruction()
					.run(param -> {
						/* maxIcons is final in Kotlin but is read with iget by the
						 * CANARY icon flow after construction. Xposed's field writer is
						 * therefore applied only to this verified instance field. */
						AODNIC = param.thisObject;
						setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit);
					});

			NotificationIconContainerStatusBarViewModelClass
					.afterConstruction()
					.run(param -> {
						SBNIC = param.thisObject;
						setObjectField(SBNIC, "maxIcons", NotificationIconLimit);
					});
		}

		initSwitchIcon();

		//forcing a refresh on statusbar once the charging chip goes away to avoid layout issues
		//only needed if chip is shown on lockscreen and device is unlocked quickly afterwards


		//region combined signal icons
		TunerServiceImplClass
				.afterConstruction()
				.run(param -> {
					mTunerService = param.thisObject;
					ReflectedClass.of(getObjectField(param.thisObject, "mObserver").getClass())
							.after("onChange")
							.run(param2 -> wifiVisibleChanged());
				});

		TunerServiceImplClass
				.after("addTunable")
				.run(param -> {
					if (param.args[1].getClass().equals(String[].class)
							&& Arrays.asList((String[]) param.args[1]).contains(ICON_HIDE_LIST)) {
						wifiVisibleChanged();
					} else if (ICON_HIDE_LIST.equals(param.args[1])) {
						wifiVisibleChanged();
					}
				});

		ConnectivityCallbackHandlerClass
				.after("setWifiIndicators")
				.run(param -> {
					boolean wifiVisible = getBooleanField(getObjectField(param.args[0], "statusIcon"), "visible");
					if (wifiVisible != mWifiVisible) {
						mWifiVisible = wifiVisible;
						if (CombineSignalIcons) {
							wifiVisibleChanged();
						}
					}
				});
		//endregion

		//region privacy chip
		PrivacyItemClass //A16 //qpr2b2 has removed the constructor, but it does do a distinct thing before using them. (Sunglass + roll emoji goes here)
				.before("hashCode")
				.run(param -> {
					if(HidePrivacyChip)
					{
						setObjectField(param.thisObject, "paused", true);
					}
				});
		//endregion

		//region SB Padding
		PhoneStatusBarViewClass
				.afterConstruction()
				.run(param -> {
					if (param.thisObject instanceof View) bindPhoneStatusbarView((View) param.thisObject);
				});

		PhoneStatusBarViewClass
				.after("updateStatusBarHeight")
				.run(param -> {
					@SuppressLint("DiscouragedApi")
					View sbContentsView = ((View) param.thisObject).findViewById(idOf("status_bar_contents"));

					if (sbContentsView == null || (SBPaddingStart == PADDING_DEFAULT && SBPaddingEnd == PADDING_DEFAULT))
						return;

					int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;

					int paddingStart = SBPaddingStart == PADDING_DEFAULT
							? sbContentsView.getPaddingStart()
							: Math.round(SBPaddingStart * screenWidth / 100f);

					int paddingEnd = SBPaddingEnd == PADDING_DEFAULT
							? sbContentsView.getPaddingEnd()
							: Math.round(SBPaddingEnd * screenWidth / 100f);

					sbContentsView.setPaddingRelative(paddingStart, sbContentsView.getPaddingTop(), paddingEnd, sbContentsView.getPaddingBottom());
				});
		//endregion

		//region multi row statusbar
		//bypassing the max icon limit during measurement
		NotificationIconContainerClass
				.before("onMeasure")
				.run(param -> { if (notificationAreaMultiRow) setObjectField(param.thisObject, "mIsStaticLayout", false); });

		NotificationIconContainerClass
				.after("onMeasure")
				.run(param -> { if (notificationAreaMultiRow) setObjectField(param.thisObject, "mIsStaticLayout", true); });

		//endregion

		//update statusbar
		PhoneStatusBarViewClass
				.after("onConfigurationChanged")
				.run(param -> new Timer().schedule(new TimerTask() {
					@Override
					public void run() {
						if (BatteryBarView.hasInstance()) {
							BatteryBarView.getInstance().post(() -> refreshBatteryBar(BatteryBarView.getInstance()));
						}
					}
				}, 2000));

		//stealing a working activity starter
		ActivityStarterImplClass
				.afterConstruction()
				.run(param -> {
					if(mActivityStarter == null)
						mActivityStarter = param.thisObject;
				});

		final ClickListener clickListener = new ClickListener();

		ShadeHeaderControllerClass
				.after("onInit")
				.run(param -> {
					View mView = (View) getObjectField(param.thisObject, "mView");
					if (mView == null) return;

					View clock = mView.findViewById(idOf("clock"));
					if (clock != null) {
						clock.setOnClickListener(clickListener);
						clock.setOnLongClickListener(clickListener);
					}

					View date = mView.findViewById(idOf("date"));
					if (date != null) {
						date.setOnClickListener(clickListener);
						date.setOnLongClickListener(clickListener);
					}
				});

		// CANARY clock renderer. The XML Clock remains in its stock location so
		// StatusBarRoot can use it for bounds; all visible custom content belongs
		// to the overlay attached to PhoneStatusBarView.
		PhoneStatusBarViewControllerClass
				.after("onViewAttached")
				.run(param -> {
					try {
						Object view = getObjectField(param.thisObject, "mView");
						if (view instanceof View) bindPhoneStatusbarView((View) view);
					} catch (Throwable ignored) {}
					if (mPhoneStatusbarView == null) return;

					updateClockColor();

					try {
						createCenterIconArea();
					} catch (Throwable ignored) {}

					/* The split container changes the HOME hierarchy. Do not build it
					 * when multi-row is disabled: the CANARY Compose host and stock
					 * notification layout must then stay untouched. */
					if (notificationAreaMultiRow) {
						try {
							makeLeftSplitArea();
							mPhoneStatusbarView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> setHeights());
							if (mNotificationIconContainer != null && mNotificationIconContainer.getChildCount() == 0) {
								mNotificationContainerContainer.setVisibility(GONE);
							}
							setHeights();
						} catch (Throwable ignored) {}
					}

					if (BBarEnabled) {
						placeBatteryBar();
					}

					if (VolteIconEnabled || VowifiIconEnabled) {
						initVoData();
					}

					if (networkOnSBEnabled) {
						networkTrafficSB = NetworkTraffic.getInstance(mContext, true);
						placeNTSB();
					}

					refreshClockRenderer();
				});

		/*
		 * StatusBarRoot's start-side lambda is the CANARY-specific composition
		 * boundary. Its f$2 field is the exact XML Clock captured by the target
		 * APK. Bind and place our overlay before Lambda7 invokes ClockKt so the
		 * first HOME composition cannot flash a duplicate or leave a blank clock.
		 */
		StatusBarRootComposableClass
				.before("invoke")
				.run(param -> {
					try {
						Object clock = getObjectField(param.thisObject, "f$2");
						if (clock instanceof View) bindPhoneStatusbarView((View) clock);
					} catch (Throwable ignored) {}
					/* Lambda0 can be invoked as an unchanged Compose skip. Only create
					 * a view when the user really enabled a custom clock. */
					if (shouldRenderCanaryOverlay()) refreshClockRenderer();
				});


		StatusBarClockComposableClass
				.before("invoke")
				.run(param -> {
					/* This lambda is the verified HOME-only parent of ClockKt. Keep
					 * the nesting marker even while custom rendering is disabled so
					 * ClockKt's after-hook can retire an old overlay only after the
					 * native Compose clock has actually been invoked. */
					statusBarClockCompositionDepth.set(statusBarClockCompositionDepth.get() + 1);
				});

		StatusBarClockComposableClass
				.after("invoke")
				.run(param -> {
					int depth = statusBarClockCompositionDepth.get();
					if (depth > 1) {
						statusBarClockCompositionDepth.set(depth - 1);
					} else if (depth == 1) {
						statusBarClockCompositionDepth.remove();
					}
				});

		ClockComposableClass
				.before(Pattern.compile(".*Clock.*"))
				.run(param -> {
					if (statusBarClockCompositionDepth.get() > 0 && shouldUseCanaryOverlay()) {
						param.setResult(null);
					}
				});

		ClockComposableClass
				.after(Pattern.compile(".*Clock.*")).run(param -> {
					/* Removing a previous native overlay here is safe: this exact
					 * HOME ClockKt invocation has just restored its Compose content. */
					if (statusBarClockCompositionDepth.get() > 0 && !shouldRenderCanaryOverlay()) {
						removeCanaryClockOverlay();
					}
				});

		//clock mods
		ClockClass
				.before("getSmallTime")
				.run(param -> {
					// The CANARY overlay owns formatting. Keep the hidden compatibility
					// Clock stock so StatusBarRoot bounds bookkeeping remains valid.
					if (mModernStatusBar) return;
					setObjectField(param.thisObject, "mAmPmStyle", AM_PM_STYLE_GONE);
					setObjectField(param.thisObject, "mShowSeconds", mShowSeconds);
				});

		ClockClass
				.after("getSmallTime")
				.run(param -> {
					if (mModernStatusBar || param.thisObject != mClockView)
						return; // CANARY uses the standalone overlay; do not alter QS.

					SpannableStringBuilder result = new SpannableStringBuilder();
					result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor)); //before clock
					SpannableStringBuilder clockText = SpannableStringBuilder.valueOf((CharSequence) param.getResult()); //THE clock
					if (clockColor != null) {
						clockText.setSpan(new NetworkTraffic.TrafficStyle(clockColor), 0, (clockText).length(),
								Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					}
					result.append(clockText);
					if (mAmPmStyle != AM_PM_STYLE_GONE) {
						result.append(getFormattedString("$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor));
					}
					result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor)); //after clock

					if (getAdditionalInstanceField(param.thisObject, "stringFormatCallBack") == null) {
						FormattedStringCallback callback = () -> {
							if (!mShowSeconds) //don't update again if it's going to do it every second anyway
								updateClock();
						};

						stringFormatter.registerCallback(callback);
						setAdditionalInstanceField(param.thisObject, "stringFormatCallBack", callback);
					}
					param.setResult(result);
				});

		//using clock colors for network traffic and battery bar
		ClockClass
				.after("onDarkChanged")
				.run(param -> {
					if (param.thisObject != mClockView)
						return; //We don't want colors of QS header. only statusbar

					updateClockColor();
					if (mModernStatusBar) refreshClockRenderer();
					if (BatteryBarView.hasInstance()) {
						refreshBatteryBar(BatteryBarView.getInstance());
					}
				});

		//region mobile roaming
		ReflectedClass MobileIconsInteractorImplClass = ReflectedClass.of("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl");

		//we must use the classes defined in the apk. using our own will fail
		ReflectedClass ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow");

		MobileIconsInteractorImplClass
				.after("getMobileConnectionInteractorForSubId")
				.run(param -> {
					if (HideRoamingState) {
						setObjectField(param.getResult(), "isRoaming", ReadonlyStateFlowClass.getClazz().getConstructors()[0].newInstance(getStateFlowImplOf(false)));
					}
				});
		//endregion
	}

	private void createCenterIconArea() {
		if (mCenteredIconArea != null && mCenteredIconArea.getParent() == mPhoneStatusbarView) return;
		mCenteredIconArea = new LinearLayout(mContext);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT);
		lp.gravity = Gravity.CENTER;
		mCenteredIconArea.setLayoutParams(lp);
		mPhoneStatusbarView.addView(mCenteredIconArea);
	}

	private void bindPhoneStatusbarView(View candidate) {
		if (candidate == null) return;

		View current = candidate;
		FrameLayout phoneStatusbarView = null;
		while (current != null) {
			if (current instanceof FrameLayout
					&& "com.android.systemui.statusbar.phone.PhoneStatusBarView".equals(current.getClass().getName())) {
				phoneStatusbarView = (FrameLayout) current;
				break;
			}
			ViewParent parent = current.getParent();
			current = parent instanceof View ? (View) parent : null;
		}
		if (phoneStatusbarView == null) return;

		boolean rootChanged = mPhoneStatusbarView != phoneStatusbarView;
		mPhoneStatusbarView = phoneStatusbarView;

		if (rootChanged) {
			/* A recreated status bar owns a new notification/Compose hierarchy.
			 * Never place an overlay into one of the old display's wrappers. */
			mCanaryClockAttached = false;
			mNotificationIconContainer = null;
			mNotificationContainerContainer = null;
			mLeftVerticalSplitContainer = null;
			mLeftExtraRowContainer = null;
			mCenteredIconArea = null;
		}

		View clock = mPhoneStatusbarView.findViewById(idOf("clock"));
		if (clock instanceof TextView) {
			mClockView = (TextView) clock;
		}

		View startSide = mPhoneStatusbarView.findViewById(idOf("status_bar_start_side_except_heads_up"));
		mStatusbarStartSide = startSide instanceof ViewGroup ? (ViewGroup) startSide : null;

		View systemIconArea = mPhoneStatusbarView.findViewById(idOf("statusIcons"));
		mSystemIconArea = systemIconArea instanceof LinearLayout ? (LinearLayout) systemIconArea : null;

	}

	private boolean shouldUseCanaryOverlay() {
		return shouldRenderCanaryOverlay()
				&& mCanaryClockAttached
				&& mCanaryClockOverlay != null
				&& mCanaryClockOverlay.isAttachedToWindow()
				&& mCanaryClockOverlay.getVisibility() == VISIBLE;
	}

	private boolean shouldRenderCanaryOverlay() {
		/* Preserve the stock Compose renderer when PixelXpert is not changing the
		 * clock. Besides avoiding needless view work this also makes a user who
		 * resets every option return to the exact CANARY default. */
		return mModernStatusBar
				&& (clockPosition != POSITION_LEFT
				|| notificationAreaMultiRow
				|| mShowSeconds
				|| mAmPmStyle != AM_PM_STYLE_GONE
				|| !(mStringFormatBefore + mStringFormatAfter).trim().isEmpty()
				|| clockColor != null
				|| mBeforeClockColor != null
				|| mAfterClockColor != null);
	}

	private void refreshClockRenderer() {
		if (!mModernStatusBar) {
			removeCanaryClockOverlay();
			placeClock();
			updateClock();
			return;
		}
		if (mPhoneStatusbarView == null) return;
		if (!shouldRenderCanaryOverlay()) {
			/* If a customized overlay was already visible, keep it as a temporary
			 * fallback. Lambda7's after-hook removes it only after ClockKt has
			 * rendered the stock clock again, preventing a blank hand-over. */
			if (mCanaryClockOverlay != null && mCanaryClockOverlay.getParent() != null) {
				refreshCanaryClockText();
			}
			return;
		}

		ensureCanaryClockOverlay();
		if (mCanaryClockOverlay == null) return;
		placeCanaryClockOverlay();
		mCanaryClockOverlay.setVisibility(VISIBLE);
		refreshCanaryClockText();
		mCanaryClockAttached = mCanaryClockOverlay.isAttachedToWindow();

		if (mCanaryClockAttached) {
			scheduleCanaryClockTick();
		} else {
			/* Do not suppress ClockKt until our native view is on screen. A
			 * follow-up root recomposition performs the hand-over after attach;
			 * this favors the stock clock for one frame over a possible blank one. */
			mCanaryClockOverlay.post(() -> {
				if (mCanaryClockOverlay == null || !shouldRenderCanaryOverlay()) return;
				placeCanaryClockOverlay();
				mCanaryClockAttached = mCanaryClockOverlay.isAttachedToWindow();
				refreshCanaryClockText();
				if (mCanaryClockAttached) scheduleCanaryClockTick();
			});
		}
	}

	private void removeCanaryClockOverlay() {
		if (mCanaryClockOverlay == null) return;
		mCanaryClockOverlay.removeCallbacks(mCanaryClockTick);
		ViewParent parent = mCanaryClockOverlay.getParent();
		if (parent instanceof ViewGroup) {
			((ViewGroup) parent).removeView(mCanaryClockOverlay);
		}
		mCanaryClockOverlay.setVisibility(GONE);
		mCanaryClockAttached = false;
		setHeights();
	}

	private boolean belongsToCurrentPhoneStatusbar(View view) {
		View current = view;
		while (current != null) {
			if (current == mPhoneStatusbarView) return true;
			ViewParent parent = current.getParent();
			current = parent instanceof View ? (View) parent : null;
		}
		return false;
	}

	private void ensureCanaryClockOverlay() {
		if (mCanaryClockOverlay == null) {
			mCanaryClockOverlay = new TextView(mContext);
			mCanaryClockOverlay.setSingleLine(true);
			mCanaryClockOverlay.setGravity(Gravity.CENTER_VERTICAL);
			mCanaryClockOverlay.setIncludeFontPadding(false);
			mCanaryClockOverlay.setLayoutParams(new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
		} else if (!belongsToCurrentPhoneStatusbar(mCanaryClockOverlay)) {
			mCanaryClockOverlay.removeCallbacks(mCanaryClockTick);
			ViewParent parent = mCanaryClockOverlay.getParent();
			if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(mCanaryClockOverlay);
			mCanaryClockAttached = false;
		}
		copyCanaryClockAppearance();
	}

	private void copyCanaryClockAppearance() {
		if (mCanaryClockOverlay == null || mClockView == null) return;
		try {
			mCanaryClockOverlay.setTextSize(0, mClockView.getTextSize());
			mCanaryClockOverlay.setTypeface(mClockView.getTypeface());
			mCanaryClockOverlay.setLetterSpacing(mClockView.getLetterSpacing());
			mCanaryClockOverlay.setFontFeatureSettings(mClockView.getFontFeatureSettings());
			mCanaryClockOverlay.setTextColor(mClockView.getTextColors());
		} catch (Throwable ignored) {}
	}

	private void placeCanaryClockOverlay() {
		if (mCanaryClockOverlay == null || mPhoneStatusbarView == null) return;
		ViewGroup targetArea = null;
		Integer index = null;
		switch (clockPosition) {
			case POSITION_LEFT:
				if (notificationAreaMultiRow && mLeftExtraRowContainer != null) {
					targetArea = mLeftExtraRowContainer;
					index = 0;
				} else {
					targetArea = mStatusbarStartSide;
					index = 1;
				}
				mCanaryClockOverlay.setPadding(0, 0, leftClockPadding, 0);
				break;
			case POSITION_CENTER:
				if (!(mCenteredIconArea instanceof ViewGroup)) createCenterIconArea();
				if (mCenteredIconArea instanceof ViewGroup) targetArea = (ViewGroup) mCenteredIconArea;
				mCanaryClockOverlay.setPadding(rightClockPadding, 0, rightClockPadding, 0);
				break;
			case POSITION_RIGHT:
				if (mSystemIconArea != null && mSystemIconArea.getParent() instanceof ViewGroup) {
					targetArea = (ViewGroup) mSystemIconArea.getParent();
				}
				mCanaryClockOverlay.setPadding(rightClockPadding, 0, 0, 0);
				break;
		}
		if (targetArea == null) return;

		ViewParent parent = mCanaryClockOverlay.getParent();
		if (parent instanceof ViewGroup && parent != targetArea) {
			((ViewGroup) parent).removeView(mCanaryClockOverlay);
		}

		ViewGroup.LayoutParams currentParams = mCanaryClockOverlay.getLayoutParams();
		if (targetArea instanceof LinearLayout) {
			LinearLayout.LayoutParams params = currentParams instanceof LinearLayout.LayoutParams
					? (LinearLayout.LayoutParams) currentParams
					: new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT);
			params.width = WRAP_CONTENT;
			params.height = MATCH_PARENT;
			params.gravity = Gravity.CENTER_VERTICAL;
			mCanaryClockOverlay.setLayoutParams(params);
		}

		if (mCanaryClockOverlay.getParent() == null) {
			if (index != null) {
				targetArea.addView(mCanaryClockOverlay, Math.min(index, targetArea.getChildCount()));
			} else {
				targetArea.addView(mCanaryClockOverlay);
			}
		} else if (index != null && targetArea.indexOfChild(mCanaryClockOverlay) != Math.min(index, targetArea.getChildCount() - 1)) {
			targetArea.removeView(mCanaryClockOverlay);
			targetArea.addView(mCanaryClockOverlay, Math.min(index, targetArea.getChildCount()));
		}
	}

	private CharSequence getCanaryClockText() {
		if (mClockView != null) {
			try {
				/* In 系统界面_CANARY.APK the XML Clock's style default is GONE
				 * (2), while mAmPmStyle itself is final. Keep that platform-owned
				 * field untouched: the visible overlay appends the user-selected
				 * AM/PM text below. mShowSeconds is mutable and only controls the
				 * locale-aware time skeleton returned by getSmallTime(). */
				setObjectField(mClockView, "mShowSeconds", mShowSeconds);
				Object calendar = getObjectField(mClockView, "mCalendar");
				if (calendar != null) callMethod(calendar, "setTimeInMillis", System.currentTimeMillis());
				Object text = callMethod(mClockView, "getSmallTime");
				if (text instanceof CharSequence) return (CharSequence) text;
			} catch (Throwable ignored) {
				// Use the public fallback until the compatibility Clock is initialized.
			}
		}

		java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
				android.text.format.DateFormat.is24HourFormat(mContext)
						? (mShowSeconds ? "HH:mm:ss" : "HH:mm")
						: (mShowSeconds ? "h:mm:ss" : "h:mm"),
				java.util.Locale.getDefault());
		return format.format(new java.util.Date());
	}

	private boolean shouldAppendCanaryAmPm() {
		return mAmPmStyle != AM_PM_STYLE_GONE
				&& !android.text.format.DateFormat.is24HourFormat(mContext);
	}

	private void refreshCanaryClockText() {
		if (mCanaryClockOverlay == null) return;
		try {
			CharSequence clockText = getCanaryClockText();
			SpannableStringBuilder result = new SpannableStringBuilder();
			result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor));
			int clockStart = result.length();
			result.append(clockText);
			if (clockColor != null) {
				result.setSpan(new NetworkTraffic.TrafficStyle(clockColor), clockStart, result.length(),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			if (shouldAppendCanaryAmPm()) {
				result.append(getFormattedString("$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor));
			}
			result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor));
			mCanaryClockOverlay.setText(result);
			mCanaryClockOverlay.setContentDescription(result);
			if (clockColor == null && mClockView != null) {
				mCanaryClockOverlay.setTextColor(mClockView.getTextColors());
			}
			if (getAdditionalInstanceField(mCanaryClockOverlay, "stringFormatCallBack") == null) {
				FormattedStringCallback callback = () -> {
					if (mCanaryClockOverlay != null) mCanaryClockOverlay.post(this::refreshCanaryClockText);
				};
				stringFormatter.registerCallback(callback);
				setAdditionalInstanceField(mCanaryClockOverlay, "stringFormatCallBack", callback);
			}
		} catch (Throwable ignored) {
			// A partially created CANARY root must not break the stock Compose clock.
		}
	}

	private void scheduleCanaryClockTick() {
		if (!shouldUseCanaryOverlay()) return;
		mCanaryClockOverlay.removeCallbacks(mCanaryClockTick);
		if (!mCanaryClockOverlay.isAttachedToWindow()) return;
		long interval = mShowSeconds ? 1_000L : 60_000L;
		long delay = interval - (System.currentTimeMillis() % interval) + 20L;
		mCanaryClockOverlay.postDelayed(mCanaryClockTick, delay);
	}

	private void updateClockColor() {
		if(mClockView == null) return;

		currentClockColor = mClockView.getTextColors().getDefaultColor();
		if (mCanaryClockOverlay != null && clockColor == null) {
			mCanaryClockOverlay.setTextColor(mClockView.getTextColors());
		}

		for (StatusbarTextColorCallback callback : mTextColorCallbacks) {
			callback.onTextColorChanged(currentClockColor);
		}
	}

	public static @ColorInt int getCurrentClockColor() {
		return currentClockColor;
	}

	public static int registerTextColorCallback(StatusbarTextColorCallback callback) {
		mTextColorCallbacks.add(callback);
		return currentClockColor;
	}

	private void updateStatusbarHeight() {
		try {
			callMethod(mPhoneStatusbarView, "updateStatusBarHeight");
		} catch (Throwable ignored) {
			

		}
	}

	//region double row left area
	@SuppressLint("DiscouragedApi")
	private void makeLeftSplitArea() {
		mNotificationIconContainer = mPhoneStatusbarView.findViewById(idOf("notificationIcons"));
		if (mNotificationIconContainer == null) return;

		mNotificationContainerContainer = new LinearLayout(mContext);
		mNotificationContainerContainer.setClipChildren(false); //allowing headsup icon to go beyond

		if (mLeftVerticalSplitContainer == null) {
			mLeftVerticalSplitContainer = new LinearLayout(mContext);
			mLeftVerticalSplitContainer.setClipChildren(false); //allowing headsup icon to go beyond
		} else {
			mLeftVerticalSplitContainer.removeAllViews();
			if (mLeftVerticalSplitContainer.getParent() != null)
				((ViewGroup) mLeftVerticalSplitContainer.getParent()).removeView(mLeftVerticalSplitContainer);
		}

		mLeftVerticalSplitContainer.setOrientation(VERTICAL);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
		int margin = ResourceTools.dpToPx(mContext, 4);
		lp.topMargin = margin;
		lp.bottomMargin = margin;

		mLeftVerticalSplitContainer.setLayoutParams(lp);
		mLeftVerticalSplitContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> setHeights());

		LayoutTransition layoutTransition = new LayoutTransition();
		layoutTransition.enableTransitionType(LayoutTransition.CHANGING);
		layoutTransition.setDuration(200);
		mLeftVerticalSplitContainer.setLayoutTransition(layoutTransition);

		mLeftExtraRowContainer = new ShyLinearLayout(mContext);
		mLeftVerticalSplitContainer.addView(mLeftExtraRowContainer, 0);

		if (!(mNotificationIconContainer.getParent() instanceof ViewGroup)) return;
		ViewGroup parent = (ViewGroup) mNotificationIconContainer.getParent();

		parent.addView(mLeftVerticalSplitContainer, parent.indexOfChild(mNotificationIconContainer));
		parent.removeView(mNotificationIconContainer);
		mLeftVerticalSplitContainer.addView(mNotificationContainerContainer);

		repositionOngoingChip();

		mNotificationContainerContainer.addView(mNotificationIconContainer);

		ViewGroup.LayoutParams notificationIconParams = mNotificationIconContainer.getLayoutParams();
		if (notificationIconParams instanceof LinearLayout.LayoutParams) {
			((LinearLayout.LayoutParams) notificationIconParams).weight = 100;
		} else {
			mNotificationIconContainer.setLayoutParams(new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, 100));
		}
		mNotificationIconContainer.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
			@Override
			public void onChildViewAdded(View parent, View child) {
				mNotificationContainerContainer.setVisibility(VISIBLE);
				setHeights();
			}

			@Override
			public void onChildViewRemoved(View parent, View child) {
				if (mNotificationIconContainer.getChildCount() == 0) {
					mNotificationContainerContainer.setVisibility(GONE);
					setHeights();
				}
			}
		});

		if (mStatusbarStartSide != null && mStatusbarStartSide.getParent() instanceof View) {
			((View) mStatusbarStartSide.getParent()).getLayoutParams().height = MATCH_PARENT;
			mStatusbarStartSide.getLayoutParams().height = MATCH_PARENT;
		}
		mLeftVerticalSplitContainer.getLayoutParams().height = MATCH_PARENT;
	}

	private void repositionOngoingChip() {
		// New SystemUI puts the clock and the ongoing-activity chip in the same
		// StatusBarRoot ComposeView. Moving that view into the notification row
		// makes the clock disappear whenever the row is GONE. Leave the modern
		// host in its original start-side container.
		if (mModernStatusBar) return;
		View ongoingChipComposeView = findComposeView(mPhoneStatusbarView.findViewById(idOf("status_bar_start_side_except_heads_up")));
		if (ongoingChipComposeView != null) {
			reAddView(mNotificationContainerContainer, ongoingChipComposeView);
		}
	}

	private View findComposeView(ViewGroup parent) {
		if (parent == null) return null;
		for(int i = 0; i < parent.getChildCount(); i++)
		{
			View child = parent.getChildAt(i);
			if(child.getClass().getName().endsWith("ComposeView"))
				return child;
		}
		return null;
	}


	private void setHeights() {
		if (mPhoneStatusbarView == null || mNotificationContainerContainer == null || mLeftExtraRowContainer == null) return;
		@SuppressLint("DiscouragedApi") int statusbarHeight = mPhoneStatusbarView.getLayoutParams().height
				- mContext.getResources().getDimensionPixelSize(dimenIdOf("status_bar_padding_top"));

		mNotificationContainerContainer.getLayoutParams().height = (mLeftExtraRowContainer.getVisibility() == VISIBLE) ? statusbarHeight / 2 : MATCH_PARENT;
		mLeftExtraRowContainer.getLayoutParams().height = ((mNotificationContainerContainer.getVisibility() == VISIBLE) ? statusbarHeight / 2 : MATCH_PARENT);
		if (networkOnSBEnabled && networkTrafficSB != null && networkTrafficSB.getLayoutParams() != null) {
			networkTrafficSB.getLayoutParams().height = statusbarHeight / ((networkTrafficPosition == POSITION_LEFT && notificationAreaMultiRow) ? 2 : 1);
		}
	}
	//endregion

	//region battery bar related
	private void refreshBatteryBar(BatteryBarView instance) {
		BatteryBarView.setStaticColor(batteryLevels, batteryColors, indicateCharging, chargingColor, indicateFastCharging, fastChargingColor, indicatePowerSave, powerSaveColor, BBarTransitColors, BBAnimateCharging);
		instance.setVisibility((BBarEnabled) ? VISIBLE : GONE);
		instance.setColorful(BBarColorful);
		instance.setOnlyWhileCharging(BBOnlyWhileCharging);
		instance.setOnTop(!BBOnBottom);
		instance.setAlphaPct(BBOpacity);
		instance.setBarHeight(Math.round(BBarHeight / 10f) + 5);
		instance.setCenterBased(BBSetCentered);
		instance.refreshLayout();
	}

	private void placeBatteryBar() {
		if (mPhoneStatusbarView == null) return;
		try {
			BatteryBarView batteryBarView = BatteryBarView.getInstance(mContext);
			try {
				((ViewGroup) batteryBarView.getParent()).removeView(batteryBarView);
			} catch (Throwable ignored) {}
			// PhoneStatusBarView is a FrameLayout in the CANARY root. Give the bar
			// parent-compatible parameters after a status-bar recreation.
			batteryBarView.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			mPhoneStatusbarView.addView(batteryBarView);
			refreshBatteryBar(batteryBarView);
		} catch (Throwable ignored) {}
	}
	//endregion

	//region statusbar icon holder
	private Object getStatusbarIconFor(Icon icon, String slotName) {
		try {
			Object statusbarIcon = ObjenesisHelper.newInstance(StatusBarIconClass.getClazz());

			setObjectField(statusbarIcon, "visible", true);

			//noinspection JavaReflectionMemberAccess
			setObjectField(statusbarIcon, "user", UserHandle.class.getDeclaredConstructor(int.class).newInstance(0));
			setObjectField(statusbarIcon, "pkg", BuildConfig.APPLICATION_ID);
			setObjectField(statusbarIcon, "icon", icon);
			setObjectField(statusbarIcon, "iconLevel", 0);
			setObjectField(statusbarIcon, "number", 0);
			setObjectField(statusbarIcon, "contentDescription", slotName);

			return statusbarIcon;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private Object getStatusbarIconHolderFor(Object statusbarIcon) {
		if (statusbarIcon == null || StatusBarIconHolderClass == null
				|| StatusBarIconHolderClass.getClazz() == null) return null;
		try {
			Object holder = ObjenesisHelper.newInstance(StatusBarIconHolderClass.getClazz());
			String[] iconField = new String[1];
			Arrays.stream(StatusBarIconHolderClass.getClazz().getFields()).forEach(field ->
			{
				if (field.getName().toLowerCase().contains("icon"))
					iconField[0] = field.getName();
			});

			if (iconField[0] == null) return null;
			setObjectField(holder, iconField[0], statusbarIcon);
			return holder;
		} catch (Throwable ignored) {
			return null;
		}
	}

	//endregion

	//region vo_data related
	private void initVoData() {
		try {
			if (!telephonyCallbackRegistered) {
				if (StatusBarIconClass == null || StatusBarIconClass.getClazz() == null
						|| StatusBarIconHolderClass == null || StatusBarIconHolderClass.getClazz() == null
						|| SystemUtils.TelephonyManager() == null) return;

				Icon volteIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_volte);
				Object volteStatusbarIcon = getStatusbarIconFor(volteIcon, VO_LTE_SLOT);
				volteStatusbarIconHolder = getStatusbarIconHolderFor(volteStatusbarIcon);

				Icon vowifiIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vowifi);
				Object vowifiStatusbarIcon = getStatusbarIconFor(vowifiIcon, VO_WIFI_SLOT);
				vowifiStatusbarIconHolder = getStatusbarIconHolderFor(vowifiStatusbarIcon);

				if ((VolteIconEnabled && volteStatusbarIconHolder == null)
						|| (VowifiIconEnabled && vowifiStatusbarIconHolder == null)) return;

				SystemUtils.TelephonyManager().registerTelephonyCallback(voDataExec, voDataCallback);
				telephonyCallbackRegistered = true;
			}
			updateVoData(true);
		} catch (Throwable ignored) {
			// Telephony and icon-controller initialization are independent in CANARY.
			// The next binder attachment or service-state callback retries safely.
		}
	}

	private void removeVoDataCallback() {
		try {
			//noinspection DataFlowIssue
			SystemUtils.TelephonyManager().unregisterTelephonyCallback(voDataCallback);
			telephonyCallbackRegistered = false;
		} catch (Exception ignored) {						

		}
		removeSBIconSlot(VO_LTE_SLOT);
		removeSBIconSlot(VO_WIFI_SLOT);
	}

	private class serverStateCallback extends TelephonyCallback implements
			TelephonyCallback.ServiceStateListener {
		@Override
		public void onServiceStateChanged(@NonNull ServiceState serviceState) {
			updateVoData(false);
		}
	}

	private void updateVoData(boolean force) {
		try {
			if (SystemUtils.TelephonyManager() == null) return;
			Object wifiCallingAvailable = callMethod(SystemUtils.TelephonyManager(), "isWifiCallingAvailable");
			Object volteAvailable = callMethod(SystemUtils.TelephonyManager(), "isVolteAvailable");
			if (!(wifiCallingAvailable instanceof Boolean) || !(volteAvailable instanceof Boolean)) return;

			boolean voWifiAvailable = (Boolean) wifiCallingAvailable;
			boolean volteStateAvailable = (Boolean) volteAvailable;

			if (lastVolteAvailable != volteStateAvailable || force) {
				lastVolteAvailable = volteStateAvailable;
				if (volteStateAvailable && VolteIconEnabled) {
					setSBIconSlot(VO_LTE_SLOT, volteStatusbarIconHolder);
				} else {
					removeSBIconSlot(VO_LTE_SLOT);
				}
			}

			if (lastVowifiAvailable != voWifiAvailable || force) {
				lastVowifiAvailable = voWifiAvailable;
				if (voWifiAvailable && VowifiIconEnabled) {
					setSBIconSlot(VO_WIFI_SLOT, vowifiStatusbarIconHolder);
				} else {
					removeSBIconSlot(VO_WIFI_SLOT);
				}
			}
		} catch (Throwable ignored) {
			// IMS APIs can be unavailable before the active subscription is bound.
		}
	}

	private void setSBIconSlot(String slot, Object holder) {
		View statusbarView = mPhoneStatusbarView;
		Object iconController = mStatusBarIconController;
		if (statusbarView == null || iconController == null || holder == null) return;

		statusbarView.post(() -> {
			if (mStatusBarIconController != iconController) return;
			try {
				callMethod(iconController, "setIcon", slot, holder);
			} catch (Throwable ignored) {}
		});
	}

	private void removeSBIconSlot(String slot) {
		View statusbarView = mPhoneStatusbarView;
		Object iconController = mStatusBarIconController;
		if (statusbarView == null || iconController == null) return;

		statusbarView.post(() -> {
			if (mStatusBarIconController != iconController) return;
			try {
				callMethod(iconController, "removeAllIconsForSlot", slot, false);
			} catch (Throwable ignored) {}
		});
	}
	//endregion

	//region network traffic related
	private void placeNTSB() {
		if (networkTrafficSB == null) {
			return;
		}
		try {
			((ViewGroup) networkTrafficSB.getParent()).removeView(networkTrafficSB);
		} catch (Exception ignored) {
		}
		if (!networkOnSBEnabled) return;

		try {
			switch (networkTrafficPosition) {
				case POSITION_RIGHT:
					if (mSystemIconArea == null || !(mSystemIconArea.getParent() instanceof ViewGroup)) return;
					((ViewGroup) mSystemIconArea.getParent()).addView(networkTrafficSB, 0);
					networkTrafficSB.setPadding(rightClockPadding, 0, leftClockPadding, 0);
					break;
				case POSITION_LEFT:
					if (notificationAreaMultiRow) {
						if (mLeftExtraRowContainer == null) return;
						mLeftExtraRowContainer.addView(networkTrafficSB, mLeftExtraRowContainer.getChildCount());
					} else {
						if (mStatusbarStartSide == null) return;
						mStatusbarStartSide.addView(networkTrafficSB, 1);
					}
					networkTrafficSB.setPadding(0, 0, leftClockPadding, 0);
					break;
				case POSITION_CENTER:
					if (mCenteredIconArea == null) createCenterIconArea();
					if (!(mCenteredIconArea instanceof ViewGroup)) return;
					((ViewGroup) mCenteredIconArea).addView(networkTrafficSB);
					networkTrafficSB.setPadding(rightClockPadding, 0, leftClockPadding, 0);
					break;
			}
			ViewGroup.LayoutParams ntsbLayoutParams = networkTrafficSB.getLayoutParams();
			if (ntsbLayoutParams instanceof LinearLayout.LayoutParams) {
				((LinearLayout.LayoutParams) ntsbLayoutParams).gravity = Gravity.CENTER_VERTICAL;
			} else if (ntsbLayoutParams instanceof FrameLayout.LayoutParams) {
				((FrameLayout.LayoutParams) ntsbLayoutParams).gravity = Gravity.CENTER_VERTICAL;
			}
			networkTrafficSB.setLayoutParams(ntsbLayoutParams);
		} catch (Throwable ignored) {}
	}
	//endregion

	//region icon tap related
	class ClickListener implements View.OnClickListener, View.OnLongClickListener {
		@Override
		public void onClick(View v) {
			String name = mContext.getResources().getResourceName(v.getId());

			if (name.endsWith("clock")) {
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", new Intent(AlarmClock.ACTION_SHOW_ALARMS), 0);
			} else if (name.endsWith("date")) {
				Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
				builder.appendPath("time");
				builder.appendPath(Long.toString(System.currentTimeMillis()));
				Intent todayIntent = new Intent(Intent.ACTION_VIEW, builder.build());
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", todayIntent, 0);
			}
		}

		@Override
		public boolean onLongClick(View v) {
			String name = mContext.getResources().getResourceName(v.getId());

			if (name.endsWith("clock") || name.endsWith("date")) {
				Intent mIntent = new Intent(Intent.ACTION_MAIN);
				mIntent.setClassName("com.android.settings",
						"com.android.settings.Settings$DateTimeSettingsActivity");
				callMethod(mActivityStarter, "startActivity", mIntent, true /* dismissShade */);
				return true;
			}
			return false;
		}
	}
	//endregion

	//region clock and date related
	private void placeClock() {
		if (mModernStatusBar || mClockView == null) return;
		if (!(mClockView.getParent() instanceof ViewGroup)) return;
		ViewGroup parent = (ViewGroup) mClockView.getParent();
		ViewGroup targetArea = null;
		Integer index = null;

		switch (clockPosition) {
			case POSITION_LEFT:
				if (notificationAreaMultiRow) {
					targetArea = mLeftExtraRowContainer;
					index = 0;
				} else {
					targetArea = mStatusbarStartSide;
					index = 1;
				}
				mClockView.setPadding(0, 0, leftClockPadding, 0);
				break;
			case POSITION_CENTER:
				if (!(mCenteredIconArea instanceof ViewGroup)) createCenterIconArea();
				if (mCenteredIconArea instanceof ViewGroup) targetArea = (ViewGroup) mCenteredIconArea;
				mClockView.setPadding(rightClockPadding, 0, rightClockPadding, 0);
				break;
			case POSITION_RIGHT:
				mClockView.setPadding(rightClockPadding, 0, 0, 0);
				if (mSystemIconArea != null && mSystemIconArea.getParent() instanceof ViewGroup) {
					targetArea = (ViewGroup) mSystemIconArea.getParent();
				}
				break;
		}
		if (targetArea == null || parent == targetArea) return;
		parent.removeView(mClockView);
		if (index != null) targetArea.addView(mClockView, Math.min(index, targetArea.getChildCount()));
		else targetArea.addView(mClockView);
	}

	private final StringFormatter stringFormatter = new StringFormatter();

	private CharSequence getFormattedString(String dateFormat, boolean small, @Nullable @ColorInt Integer textColor) {
		if (dateFormat.isEmpty()) return "";

		//There's some format to work on
		SpannableStringBuilder formatted = new SpannableStringBuilder(stringFormatter.formatString(dateFormat));

		if (small) {
			//small size requested
			CharacterStyle style = new RelativeSizeSpan(0.7f);
			formatted.setSpan(style, 0, formatted.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		if (textColor != null) {
			formatted.setSpan(new NetworkTraffic.TrafficStyle(textColor), 0, (formatted).length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}

		return formatted;
	}

	//endregion
	//region callbacks
	public static void registerClockVisibilityCallback(ClockVisibilityCallback callback) {
		clockVisibilityCallbacks.add(callback);
	}

	@SuppressWarnings("unused")
	public static void unRegisterClockVisibilityCallback(ClockVisibilityCallback callback) {
		clockVisibilityCallbacks.remove(callback);
	}

	public interface ClockVisibilityCallback extends Callback {
		void OnVisibilityChanged(boolean isVisible);
	}
	//endregion

	//region combined signal icons
	private void wifiVisibleChanged() {
		try { //don't crash the system if failed
			//inspired from from TunerServiceImpl#reloadAll
			String hideListString = Settings.Secure.getString(
					(ContentResolver) getObjectField(mTunerService, "mContentResolver")
					, ICON_HIDE_LIST);

			if (CombineSignalIcons && mWifiVisible) {
				if (hideListString == null || hideListString.isEmpty()) {
					hideListString = "mobile";
				} else if (!hideListString.contains("mobile")) {
					hideListString = hideListString + ",mobile";
				}
			}
			@SuppressWarnings("unchecked")
			Set<Object> tunables = (Set<Object>) callMethod(getObjectField(mTunerService, "mTunableLookup"), "get", ICON_HIDE_LIST);

			String finalHideListString = hideListString;
			mPhoneStatusbarView.post(() -> {
				for (Object tunable : tunables) {
					callMethod(tunable, "onTuningChanged", ICON_HIDE_LIST, finalHideListString);
				}
			});
		} catch (Throwable ignored) {}
	}
	//endregion

	public interface StatusbarTextColorCallback {
		void onTextColorChanged(int textColor);
	}
}