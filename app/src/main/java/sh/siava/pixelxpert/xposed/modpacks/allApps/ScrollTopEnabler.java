package sh.siava.pixelxpert.xposed.modpacks.allApps;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
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
 * AbsListView, ScrollView and the internal RecyclerView - all gate their
 * onScrollToTop implementation behind mScrollToTopEnabled, which is only set
 * from a layout XML attribute and defaults to false. That is exactly why the
 * feature is dormant on Pixel: nothing ever opts apps in.
 * <p>
 * This pack (loaded into every app process) flips the switch after the view
 * constructors run, mirroring what MIUI's framework does framework-wide.
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
		try {
			ReflectedClass.of(AbsListView.class)
					.afterConstruction()
					.run(param -> {
						if (StatusbarTapScrollTop) {
							setObjectField(param.thisObject, "mScrollToTopEnabled", true);
						}
					});
		} catch (Throwable ignored) {}

		try {
			ReflectedClass.of(ScrollView.class)
					.afterConstruction()
					.run(param -> {
						if (StatusbarTapScrollTop) {
							setObjectField(param.thisObject, "mScrollToTopEnabled", true);
						}
					});
		} catch (Throwable ignored) {}

		// The internal RecyclerView (com.android.internal.widget) implements
		// onScrollToTop the same way. It extends ViewGroup, not AbsListView.
		try {
			ReflectedClass recyclerClass = ReflectedClass.ofIfPossible("com.android.internal.widget.RecyclerView");
			if (recyclerClass.getClazz() != null) {
				recyclerClass
						.afterConstruction()
						.run(param -> {
							if (StatusbarTapScrollTop) {
								setObjectField(param.thisObject, "mScrollToTopEnabled", true);
							}
						});
			}
		} catch (Throwable ignored) {}
	}
}
