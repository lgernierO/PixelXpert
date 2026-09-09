package sh.siava.pixelxpert.xposed.modpacks.allApps;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

import android.content.Context;
import android.widget.AbsListView;
import android.widget.ScrollView;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.CommonModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * App-side half of the status-bar tap-to-top feature (MIUI's double-tap
 * counterpart, adapted to a single tap).
 * <p>
 * Android 17 ships the OEM scroll-to-top pipeline natively: WMS
 * dispatchScrollToTop(displayId, taskId, x) reaches the focused app window
 * and ViewGroup.dispatchScrollToTop recurses towards the scrollable child
 * (verified in this device's framework.jar). The receiving views -
 * AbsListView, ScrollView, the internal RecyclerView and androidx
 * NestedScrollView - gate their onScrollToTop implementation behind a
 * boolean flag. Bytecode comparison of both Pixel and MIUI framework.jar
 * shows BOTH hardcode the flag to true in the view constructors, so the
 * real gap on Pixel was never the flag: no SystemUI entry point ever
 * called dispatchScrollToTop. StatusbarGestures fills that trigger side;
 * this pack stays as a safety net for views whose layout XML explicitly
 * opts out via the scroll-to-top attribute, and for androidx copies whose
 * XML default path differs.
 * <p>
 * This pack (loaded into every app process) flips the switch after the view
 * constructors run, mirroring what MIUI's framework does framework-wide.
 * <p>
 * IMPORTANT: this only runs in processes that LSPosed injects - i.e. apps
 * enabled in the module's scope (LSPosed manager &gt; PixelXpert &gt; scope).
 * The default scope.list only covers system apps; users must add their
 * target apps (browser, etc.) and force-stop them once for the hook to load.
 * <p>
 * The trigger side lives in StatusbarGestures (SystemUI process), which calls
 * the native WMS entry point directly - SystemUI holds the STATUS_BAR_SERVICE
 * permission it requires.
 */
@SuppressWarnings("RedundantThrows")
@CommonModPack
public class ScrollTopEnabler extends XposedModPack {
	private static boolean StatusbarTapScrollTop = false;

	public ScrollTopEnabler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		StatusbarTapScrollTop = Xprefs.getBoolean("StatusbarTapScrollTop", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		// The flag is read inside onScrollToTop at dispatch time, so a
		// constructor-time write is enough - no per-view state needed.

		// Framework widget set (field mScrollToTopEnabled on all three):
		enableFor("android.widget.AbsListView", "mScrollToTopEnabled");
		enableFor("android.widget.ScrollView", "mScrollToTopEnabled");
		enableFor("com.android.internal.widget.RecyclerView", "mScrollToTopEnabled");

		// AndroidX copies bundled inside apps implement the same pipeline with
		// their own flags. NestedScrollView names its field mIsScrollToTopEnabled.
		enableFor("androidx.core.widget.NestedScrollView", "mIsScrollToTopEnabled");
		enableFor("androidx.recyclerview.widget.RecyclerView", "mScrollToTopEnabled");
	}

	/**
	 * Hooks every construction of {@code className} (resolved against the
	 * current app's classloader) and force-enables the scroll-to-top flag.
	 * Silently skipped when the app does not bundle the class.
	 */
	private static void enableFor(String className, String fieldName) {
		try {
			ReflectedClass recyclerClass = ReflectedClass.ofIfPossible(className);
			if (recyclerClass.getClazz() == null) return;

			// App-bundled androidx copies vary: some versions ship without the
			// scroll-to-top field at all (verified in this device's CANARY
			// SystemUI). Writing a missing field throws from inside the hook
			// callback, so verify the field once before subscribing.
			if (findFieldIfExists(recyclerClass.getClazz(), fieldName) == null) return;

			recyclerClass
					.afterConstruction()
					.run(param -> {
						if (StatusbarTapScrollTop) {
							setObjectField(param.thisObject, fieldName, true);
						}
					});
		} catch (Throwable ignored) {}
	}
}
