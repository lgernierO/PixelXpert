package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Redirects system location providers (network NLP, optional fused and geocoder)
 * to a custom provider package, e.g. a ported AMap-based MetokNLP on devices
 * where the default provider is not reachable.
 * <p>
 * Android binds proxy providers via CurrentUserServiceSupplier.createFromConfig(),
 * which resolves the target package from framework config and only matches services
 * from that package (system apps only). We rebuild the supplier with an explicit
 * package filter, so exactly the requested app is bound instead.
 * The target app must be installed as a system/priv-app.
 * <p>
 * The hook is installed synchronously from XPLauncher.initializeSystemServer()
 * (before PHASE_THIRD_PARTY_APPS_CAN_START), so the redirect decision can no
 * longer race with asynchronous preference loading: the hook callback waits
 * (bounded) for preferences before deciding.
 */
/** @noinspection RedundantThrows*/
@FrameworkModPack
public class NlpRedirector extends XposedModPack {
	private static final String TAG = "NlpRedirector";
	private static final String SUPPLIER_CLASS = "com.android.server.servicewatcher.CurrentUserServiceSupplier";
	private static final String NETWORK_ACTION = "com.android.location.service.v3.NetworkLocationProvider";
	private static final String FUSED_ACTION = "com.android.location.service.FusedLocationProvider";
	private static final String GEOCODER_ACTION = "com.android.location.service.GeocodeProvider";

	//decision state, written from prefs and read from the hook callback across threads
	private static volatile boolean PREFS_READY = false;
	private static volatile boolean sEnabled = false;
	private static volatile String sTarget = "com.google.android.gms";
	private static volatile boolean sGeocoder = true;
	private static volatile boolean sFused = false;

	private static volatile boolean HOOK_INSTALLED = false;

	public NlpRedirector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		sEnabled = Xprefs.getBoolean("NlpRedirectEnabled", false);
		sTarget = Xprefs.getString("NlpRedirectTarget", "com.google.android.gms").trim();
		sGeocoder = Xprefs.getBoolean("NlpRedirectGeocoder", true);
		sFused = Xprefs.getBoolean("NlpRedirectFused", false);
		PREFS_READY = true;
		//TEMP-LOG
		Logger.log(TAG + ": prefs updated: enabled=" + sEnabled + " target=" + sTarget
				+ " geocoder=" + sGeocoder + " fused=" + sFused);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		//TEMP-LOG
		Logger.log(TAG + ": modpack onPackageLoaded, hookInstalled=" + HOOK_INSTALLED);
		ensureHookInstalled("modpack-load-fallback");
	}

	/**
	 * Called synchronously by XPLauncher.initializeSystemServer() at the earliest
	 * point of system_server, guaranteeing the hook exists before any provider
	 * supplier is created (PHASE_THIRD_PARTY_APPS_CAN_START).
	 */
	public static void installEarlyHook() {
		//TEMP-LOG
		Logger.log(TAG + ": installEarlyHook requested (early sync path)");
		ensureHookInstalled("early-sync");
	}

	private static synchronized void ensureHookInstalled(String path) {
		if (HOOK_INSTALLED) return;
		try {
			ReflectedClass Supplier = ReflectedClass.of(SUPPLIER_CLASS);

			Supplier.before("createFromConfig")
					.run(param -> {
						try {
							long waitStart = System.currentTimeMillis();
							boolean ready = waitForPrefs(10_000L);
							//TEMP-LOG
							Logger.log(TAG + ": createFromConfig fired: arg0=" + safeType(param.getArg(0))
									+ " action=" + param.getArg(1)
									+ " prefsReady=" + ready + " waitedMs=" + (System.currentTimeMillis() - waitStart));
							if (!ready) {
								Logger.log(TAG + ": prefs unavailable after timeout, stock supplier proceeds this boot");
								return;
							}
							if (!sEnabled || sTarget.isEmpty()) {
								//TEMP-LOG
								Logger.log(TAG + ": redirect disabled or empty target, stock supplier proceeds");
								return;
							}

							String action = param.getArg(1);

							boolean redirect = NETWORK_ACTION.equals(action)
									|| (sFused && FUSED_ACTION.equals(action))
									|| (sGeocoder && GEOCODER_ACTION.equals(action));

							if (!redirect) return;

							Logger.log(TAG + ": redirecting " + action + " to " + sTarget);
							//same as createFromConfig, but with an explicit package filter
							Object redirectedSupplier = Supplier.callStaticMethod("create",
									param.getArg(0), action, sTarget, null, null);
							param.setResult(redirectedSupplier);
							Logger.log(TAG + ": supplier rebuilt for " + action);
						} catch (Throwable t) {
							//let the stock supplier take over, but keep the failure visible for diagnostics
							Logger.log(TAG + ": redirect failed, falling back to stock supplier", t);
						}
					});
			HOOK_INSTALLED = true;
			Logger.log(TAG + ": hooked CurrentUserServiceSupplier.createFromConfig (path=" + path + ")");
		} catch (Throwable t) {
			Logger.log(TAG + ": failed to hook " + SUPPLIER_CLASS, t);
		}
	}

	private static boolean waitForPrefs(long timeoutMs) {
		if (PREFS_READY) return true;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (PREFS_READY) return true;
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return false;
			}
		}
		return PREFS_READY;
	}

	//TEMP-LOG helper
	private static String safeType(Object o) {
		return o == null ? "null" : o.getClass().getName();
	}
}
