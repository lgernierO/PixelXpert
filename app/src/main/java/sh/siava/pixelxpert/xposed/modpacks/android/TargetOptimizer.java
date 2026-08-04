package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;

@FrameworkModPack
public class TargetOptimizer extends XposedModPack {
	public static final String OPTIMIZED_BUILD_KEY = "optimized_build";
	public static final String SYSTEM_RESTART_PENDING_KEY = "system_restart_pending";
	private static final String SYSTEM_RESTART_PENDING_BOOT_COUNT_KEY = "system_restart_pending_boot_count";
	private static final int UNKNOWN_BOOT_COUNT = -1;

	public TargetOptimizer(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	public static boolean isSystemRestartPending(Context context, SharedPreferences preferences) {
		if(!preferences.getBoolean(SYSTEM_RESTART_PENDING_KEY, false)) {
			return false;
		}

		if(!preferences.contains(SYSTEM_RESTART_PENDING_BOOT_COUNT_KEY)) {
			clearSystemRestartPending(preferences);
			return false;
		}

		int restartRequestedAtBootCount = preferences.getInt(SYSTEM_RESTART_PENDING_BOOT_COUNT_KEY, UNKNOWN_BOOT_COUNT);
		int currentBootCount = getBootCount(context);

		if(restartRequestedAtBootCount == UNKNOWN_BOOT_COUNT || currentBootCount == UNKNOWN_BOOT_COUNT) {
			return true;
		}

		if(restartRequestedAtBootCount != currentBootCount) {
			clearSystemRestartPending(preferences);
			return false;
		}

		return true;
	}

	private static int getBootCount(Context context) {
		try {
			return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT);
		}
		catch (Throwable ignored) {
			return UNKNOWN_BOOT_COUNT;
		}
	}

	private static void clearSystemRestartPending(SharedPreferences preferences) {
		preferences.edit()
				.remove(SYSTEM_RESTART_PENDING_KEY)
				.remove(SYSTEM_RESTART_PENDING_BOOT_COUNT_KEY)
				.apply();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		isSystemRestartPending(mContext, Xprefs);

		String optimizedBuild = Xprefs.getString(OPTIMIZED_BUILD_KEY, "");

		if(!Build.ID.equals(optimizedBuild))
		{
			try {
				String[] targetPacks = XPLauncher.moduleResources.getStringArray(R.array.module_scope);

				Arrays.asList(targetPacks).forEach(target ->
						XPLauncher.enqueueProxyCommand(proxy ->
								proxy.runRootCommand(String.format("cmd package compile -m speed -f %s", target))));

				Xprefs.edit()
						.putBoolean(SYSTEM_RESTART_PENDING_KEY, true)
						.putInt(SYSTEM_RESTART_PENDING_BOOT_COUNT_KEY, getBootCount(mContext))
						.putString(OPTIMIZED_BUILD_KEY, Build.ID)
						.apply();
			}
			catch (Throwable ignored)
			{}
		}
	}
}
