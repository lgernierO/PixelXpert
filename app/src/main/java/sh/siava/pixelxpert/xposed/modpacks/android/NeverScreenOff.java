package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;

import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Adds a "Never" screen timeout option to the CANARY Settings app and gives its
 * persisted sentinel a practical never-expiring meaning in system_server.
 */
@SuppressWarnings("RedundantThrows")
@SettingsModPack
@FrameworkModPack
public class NeverScreenOff extends XposedModPack {
	// ScreenTimeoutConstants reads SCREEN_OFF_TIMEOUT through getIntForUser().
	private static final int NEVER_TIMEOUT_SENTINEL = Integer.MAX_VALUE;
	private static final String NEVER_KEY = Integer.toString(NEVER_TIMEOUT_SENTINEL);
	// Custom key holding the normal timeout value chosen right before "Never" was picked.
	private static final String PREVIOUS_TIMEOUT_KEY = "px_previous_screen_timeout";
	// Keep addition with uptime safely below Long.MAX_VALUE while remaining effectively infinite.
	private static final long EFFECTIVELY_NEVER_TIMEOUT = Long.MAX_VALUE / 4;
	private static final long DEFAULT_TIMEOUT = 30_000L;

	private static boolean neverScreenOffEnabled;

	public NeverScreenOff(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;

		neverScreenOffEnabled = Xprefs.getBoolean("NeverScreenOff", false);
		if (!neverScreenOffEnabled
				&& (key.length == 0 || Arrays.asList(key).contains("NeverScreenOff"))) {
			restorePreviousTimeoutIfNeeded();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) throws Throwable {
		if (Constants.SETTINGS_PACKAGE.equals(param.getPackageName())) {
			hookSettingsTimeoutArrays();
		} else if (XPLauncher.isSystemServer) {
			hookFrameworkTimeoutValue();
		}
	}

	private void hookSettingsTimeoutArrays() {
		Resources resources = mContext.getResources();
		int entriesId = resources.getIdentifier("screen_timeout_entries", "array", Constants.SETTINGS_PACKAGE);
		int valuesId = resources.getIdentifier("screen_timeout_values", "array", Constants.SETTINGS_PACKAGE);
		if (entriesId == 0 || valuesId == 0) return;

		ReflectedClass.of(Resources.class)
				.after("getStringArray")
				.run(param -> {
					if (!neverScreenOffEnabled
							|| param.args.length == 0
							|| !(param.args[0] instanceof Integer resourceId)
							|| !(param.thisObject instanceof Resources targetResources)
							|| !isSettingsResource(targetResources, resourceId)) {
						return;
					}

					String[] original = param.getResult();
					if (original == null) return;

					if (resourceId == entriesId) {
						param.setResult(appendIfMissing(
								original,
							XPLauncher.moduleResources.getString(R.string.never_screen_off_option)));
					} else if (resourceId == valuesId) {
						param.setResult(appendIfMissing(original, Integer.toString(NEVER_TIMEOUT_SENTINEL)));
					}
				});

		// Save the normal timeout chosen right before the user picks "Never", so that
		// disabling the feature can restore exactly that previous option.
		ReflectedClass.of("com.android.settings.display.ScreenTimeoutSettings")
				.before("setDefaultKey")
				.run(param -> {
					if (!neverScreenOffEnabled
							|| param.args.length == 0
							|| !NEVER_KEY.equals(String.valueOf(param.args[0]))) {
						return;
					}
					try {
						long current = Settings.System.getLong(
								mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT);
						if (current != NEVER_TIMEOUT_SENTINEL) {
							Settings.System.putLong(
									mContext.getContentResolver(), PREVIOUS_TIMEOUT_KEY, current);
						}
					} catch (Throwable ignored) {
					}
				});

		ReflectedClass.of("com.android.settings.display.ScreenTimeoutPreferenceController")
				.after("getTimeoutSummary")
				.run(param -> {
					if (neverScreenOffEnabled
							&& param.args.length > 0
							&& param.args[0] instanceof Number maximumTimeout
							&& maximumTimeout.longValue() >= NEVER_TIMEOUT_SENTINEL
							&& isNeverTimeoutSelected()) {
						param.setResult(XPLauncher.moduleResources.getString(R.string.never_screen_off_option));
					}
				});
	}

	private void hookFrameworkTimeoutValue() {
		ReflectedClass.of("com.android.server.power.ScreenTimeoutConstants")
				.after("getScreenOffTimeoutSettingLocked")
				.run(param -> {
					if (neverScreenOffEnabled
							&& param.getResult() instanceof Number value
							&& value.longValue() == NEVER_TIMEOUT_SENTINEL) {
						param.setResult(EFFECTIVELY_NEVER_TIMEOUT);
					}
				});
	}

	private boolean isNeverTimeoutSelected() {
		try {
			return Settings.System.getLong(
					mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT)
					== NEVER_TIMEOUT_SENTINEL;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void restorePreviousTimeoutIfNeeded() {
		try {
			if (!isNeverTimeoutSelected()) return;
			long previous = Settings.System.getLong(
					mContext.getContentResolver(), PREVIOUS_TIMEOUT_KEY, DEFAULT_TIMEOUT);
			if (previous == NEVER_TIMEOUT_SENTINEL || previous <= 0) {
				previous = DEFAULT_TIMEOUT;
			}
			Settings.System.putLong(mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, previous);
			Settings.System.putLong(mContext.getContentResolver(), PREVIOUS_TIMEOUT_KEY, -1L);
		} catch (Throwable ignored) {
		}
	}

	private static boolean isSettingsResource(Resources resources, int resourceId) {
		try {
			return Constants.SETTINGS_PACKAGE.equals(resources.getResourcePackageName(resourceId));
		} catch (Resources.NotFoundException ignored) {
			return false;
		}
	}

	private static String[] appendIfMissing(String[] values, String value) {
		for (String existing : values) {
			if (value.equals(existing)) return values;
		}
		String[] result = Arrays.copyOf(values, values.length + 1);
		result[values.length] = value;
		return result;
	}
}
