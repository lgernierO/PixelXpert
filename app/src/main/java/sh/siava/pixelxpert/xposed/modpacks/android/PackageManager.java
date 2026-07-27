package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;

import java.util.Timer;
import java.util.TimerTask;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * @noinspection RedundantThrows, ConstantValue
 */
@FrameworkModPack
public class PackageManager extends XposedModPack {
	private static final int AUTO_DISABLE_MINUTES = 5;
	private static final String ALLOW_SIGNATURE_PREF = "PM_AllowMismatchedSignature";
	private static final String ALLOW_DOWNGRADE_PREF = "PM_AllowDowngrade";

	public static final int PERMISSION = 4;
	private static final int PERMISSION_GRANTED = 0;

	private static boolean PM_AllowMismatchedSignature = false;
	private static boolean PM_AllowDowngrade = false;
	private Timer autoDisableTimer;

	public PackageManager(Context context) {
		super(context);
	}
	@Override
	public void onPreferenceUpdated(String... Key) {
		PM_AllowMismatchedSignature = Xprefs.getBoolean(ALLOW_SIGNATURE_PREF, false);
		PM_AllowDowngrade = Xprefs.getBoolean(ALLOW_DOWNGRADE_PREF, false);

		if (!PM_AllowDowngrade && !PM_AllowMismatchedSignature) {
			cancelAutoDisable();
			return;
		}

		// system_server calls this once with an empty key while loading the module.
		// That call restores preferences and must not disable an already enabled one-shot action.
		if (Key.length > 0 && (ALLOW_SIGNATURE_PREF.equals(Key[0]) || ALLOW_DOWNGRADE_PREF.equals(Key[0]))) {
			scheduleAutoDisable();
		}
	}

	private synchronized void scheduleAutoDisable() {
		cancelAutoDisable();
		autoDisableTimer = new Timer("PixelXpert-PackageManager", true);
		autoDisableTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				disablePMMods();
			}
		}, AUTO_DISABLE_MINUTES * 60000L);
	}

	private synchronized void cancelAutoDisable() {
		if (autoDisableTimer != null) {
			autoDisableTimer.cancel();
			autoDisableTimer = null;
		}
	}

	private void disablePMMods() {
		Xprefs.edit()
				.putBoolean(ALLOW_SIGNATURE_PREF, false)
				.putBoolean(ALLOW_DOWNGRADE_PREF, false)
				.apply();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try {
			ReflectedClass InstallPackageHelperClass = ReflectedClass.ofIfPossible("com.android.server.pm.InstallPackageHelper");
			ReflectedClass PackageManagerServiceUtilsClass = ReflectedClass.ofIfPossible("com.android.server.pm.PackageManagerServiceUtils");
			ReflectedClass SigningDetailsClass = ReflectedClass.ofIfPossible("android.content.pm.SigningDetails");
			ReflectedClass KeySetManagerServiceClass = ReflectedClass.ofIfPossible("com.android.server.pm.KeySetManagerService");

			try {
				ReflectedClass ActivityManagerServiceClass = ReflectedClass.of("com.android.server.am.ActivityManagerService");

				ActivityManagerServiceClass
						.before("checkBroadcastFromSystem")
						.run(param -> {
							String action = ((Intent) param.args[0]).getAction();

							//noinspection DataFlowIssue
							if (action.startsWith(BuildConfig.APPLICATION_ID + ".ACTION")) {
								param.setResult(null);
							}
						});

				//Granting pixel launcher permission to force stop apps
				ActivityManagerServiceClass
						.before("checkCallingPermission")
						.run(param -> {
							try {
								if ("android.permission.FORCE_STOP_PACKAGES".equals(param.args[0])) {
									if (Constants.LAUNCHER_PACKAGE.equals(
											callMethod(
													getObjectField(param.thisObject, "mInternal"),
													"getPackageNameByPid",
													Binder.getCallingPid()))) {
										param.setResult(PERMISSION_GRANTED);
									}
								}
							} catch (Throwable ignored) {
							}
						});

			} catch (Throwable ignored) {
			}

			// CANARY keeps three checkDowngrade overloads. The name-based helper hooks
			// all three, covering package, system-package and raw-version install paths.
			PackageManagerServiceUtilsClass
					.before("checkDowngrade")
					.run(param -> {
						if (PM_AllowDowngrade) {
							param.setResult(null);
						}
					});

			// CANARY checks upgrade keysets before both install reconciliation paths.
			// Restrict the broad method-name hook to its two-argument result check.
			KeySetManagerServiceClass
					.before("checkUpgradeKeySetLocked")
					.run(param -> {
						if (PM_AllowMismatchedSignature && param.args.length == 2) {
							param.setResult(true);
						}
					});

			SigningDetailsClass
					.before("checkCapability")
					.run(param -> {
						/* services.jar CANARY invokes checkCapability(SigningDetails, 1/8)
						 * for package replacement. Capability 4 belongs to permission
						 * reconciliation and remains limited by the same-package /data
						 * InstallPackageHelper hook below; do not bypass unrelated
						 * SigningDetails capabilities or the String/digest overload. */
						if (PM_AllowMismatchedSignature
								&& param.args.length == 2
								&& param.args[0] != null
								&& "android.content.pm.SigningDetails".equals(param.args[0].getClass().getName())
								&& param.args[1] instanceof Integer) {
							int capability = (Integer) param.args[1];
							if (capability == 1 || capability == 8) {
								param.setResult(true);
							}
						}
					});

			// The standard reconciliation overload has seven arguments on CANARY.
			// Avoid altering future or unrelated same-name overloads.
			PackageManagerServiceUtilsClass
					.before("verifySignatures")
					.run(param -> {
						try {
							if (PM_AllowMismatchedSignature
										&& param.args.length == 7
										&& param.args[0] != null
										&& callMethod(
											callMethod(param.args[0], "getSigningDetails"),
											"getSignatures"
									) != null) {
								param.setResult(true);
							}
						} catch (Throwable ignored) {
						}
					});

			// This permission-ownership method remains separate from update reconciliation.
			// On CANARY its descriptor has exactly three arguments.
			InstallPackageHelperClass
					.before("doesSignatureMatchForPermissions")
					.run(param -> {
						try {
							if (PM_AllowMismatchedSignature
										&& param.args.length == 3
										&& callMethod(param.args[1], "getPackageName").equals(param.args[0])
									&& ((String) callMethod(param.args[1], "getBaseApkPath")).startsWith("/data")) {
								param.setResult(true);
							}
						} catch (Throwable ignored) {
						}
					});
		} catch (Throwable ignored) {
		}
	}
}
