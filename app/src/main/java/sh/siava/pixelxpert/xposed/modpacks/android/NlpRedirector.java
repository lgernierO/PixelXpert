package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

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
 */
/** @noinspection RedundantThrows*/
@FrameworkModPack
public class NlpRedirector extends XposedModPack {
	private static final String SUPPLIER_CLASS = "com.android.server.servicewatcher.CurrentUserServiceSupplier";
	private static final String NETWORK_ACTION = "com.android.location.service.v3.NetworkLocationProvider";
	private static final String FUSED_ACTION = "com.android.location.service.FusedLocationProvider";
	private static final String GEOCODER_ACTION = "com.android.location.service.GeocodeProvider";

	private static boolean NlpRedirectEnabled = false;
	private static String NlpRedirectTarget = "com.xiaomi.metoknlp";
	private static boolean NlpRedirectGeocoder = true;
	private static boolean NlpRedirectFused = false;

	public NlpRedirector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		NlpRedirectEnabled = Xprefs.getBoolean("NlpRedirectEnabled", false);
		NlpRedirectTarget = Xprefs.getString("NlpRedirectTarget", "com.xiaomi.metoknlp").trim();
		NlpRedirectGeocoder = Xprefs.getBoolean("NlpRedirectGeocoder", true);
		NlpRedirectFused = Xprefs.getBoolean("NlpRedirectFused", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try {
			ReflectedClass Supplier = ReflectedClass.of(SUPPLIER_CLASS);

			Supplier.before("createFromConfig")
					.run(param -> {
						if (!NlpRedirectEnabled || NlpRedirectTarget.isEmpty()) return;

						String action = param.getArg(1);

						boolean redirect = NETWORK_ACTION.equals(action)
								|| (NlpRedirectFused && FUSED_ACTION.equals(action))
								|| (NlpRedirectGeocoder && GEOCODER_ACTION.equals(action));

						if (!redirect) return;

						try {
							//same as createFromConfig, but with an explicit package filter
							Object redirectedSupplier = Supplier.callStaticMethod("create",
									param.getArg(0), action, NlpRedirectTarget, null, null);
							param.setResult(redirectedSupplier);
						} catch (Throwable ignored) {
						}
					});
		} catch (Throwable ignored) {
		}
	}
}
