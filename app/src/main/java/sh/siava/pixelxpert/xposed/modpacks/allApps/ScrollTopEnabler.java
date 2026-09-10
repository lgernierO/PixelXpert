package sh.siava.pixelxpert.xposed.modpacks.allApps;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.findFieldIfExists;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
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
 * The trigger side lives in StatusbarGestures (SystemUI process): it taps the
 * native WMS dispatchScrollToTop first, then ALWAYS sends ACTION_SCROLL_TOP
 * to the module package. This pack registers a receiver for it and performs
 * the MIUI-style scroll: reverse-engineered from HyperOS's
 * MiuiInputManagerService.scrollToTop() -> per-window listeners, which
 * reflectively scroll whatever scrollable view the window contains
 * (androidx RecyclerView, WebView and custom containers included - none of
 * which implement the AOSP onScrollToTop).
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

	/** View roots of windows that currently hold input focus, per process. */
	private static final java.util.Set<View> mFocusedRoots =
			java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		// MIUI mechanism, transplanted: remember every view root created in
		// this process; on the tap broadcast the one holding window focus is
		// scrolled MIUI-style. Weak refs so roots are never leaked.
		ReflectedClass.ofIfPossible("android.view.ViewRootImpl")
				.afterConstruction()
				.run(param -> mFocusedRoots.add((View) param.thisObject));

		// Framework widget set (field mScrollToTopEnabled on all three):
		enableFor("android.widget.AbsListView", "mScrollToTopEnabled");
		enableFor("android.widget.ScrollView", "mScrollToTopEnabled");
		enableFor("com.android.internal.widget.RecyclerView", "mScrollToTopEnabled");

		// AndroidX copies bundled inside apps implement the same pipeline with
		// their own flags. NestedScrollView names its field mIsScrollToTopEnabled.
		enableFor("androidx.core.widget.NestedScrollView", "mIsScrollToTopEnabled");
		enableFor("androidx.recyclerview.widget.RecyclerView", "mScrollToTopEnabled");

		// MIUI-style trigger: StatusbarGestures (SystemUI) sends this after the
		// native WMS call. Every injected process receives it; only the one
		// whose window currently holds input focus acts on it.
		BroadcastReceiver scrollTopReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (!StatusbarTapScrollTop) return;
				// SystemUI hosts this pack too and its status-bar window holds
				// focus right after the tap - scrolling there would yank the
				// QS list instead of the foreground app.
				if (Constants.SYSTEM_UI_PACKAGE.equals(mContext.getPackageName())) return;
				// MIUI posts the scroll onto the view-root handler queue; the
				// main looper is the safe equivalent for every window here.
				new Handler(Looper.getMainLooper()).post(ScrollTopEnabler::performMiuiScroll);
			}
		};
		try {
			mContext.registerReceiver(scrollTopReceiver,
					new IntentFilter(Constants.ACTION_SCROLL_TOP),
					Context.RECEIVER_EXPORTED);
		} catch (Throwable ignored) {
			try {
				mContext.registerReceiver(scrollTopReceiver,
						new IntentFilter(Constants.ACTION_SCROLL_TOP));
			} catch (Throwable ignored2) {}
		}
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
	/**
	 * Scans the focused window for scrollable views and scrolls them to the
	 * top. Mirrors MIUI's ViewRootImplStubImpl ScrollToTopListener: recursive
	 * discovery (canScrollVertically(-1)) plus reflective scrolling, so it
	 * covers androidx RecyclerView, WebView and custom containers that never
	 * implemented the AOSP onScrollToTop pipeline.
	 */
	private static void performMiuiScroll() {
		try {
			View root = findFocusedRoot();
			if (root == null) return;
			mFocusedRoots.remove(root); // stale after this use, refreshed on refocus
			scrollToTopRecursive(root);
		} catch (Throwable ignored) {}
	}

	/** The captured view root whose window currently holds input focus. */
	private static View findFocusedRoot() {
		synchronized (mFocusedRoots) {
			for (View view : mFocusedRoots) {
				try {
					if (view.hasWindowFocus()) return view;
				} catch (Throwable ignored) {}
			}
		}
		return null;
	}

	/**
	 * Depth-first pass over the view tree. Every view that can still scroll
	 * up is scrolled to top; children of scrollable containers are scanned
	 * too (matching MIUI's findScrollableViews behaviour).
	 */
	private static void scrollToTopRecursive(View view) {
		if (view == null || view.getVisibility() != View.VISIBLE) return;
		if (view.canScrollVertically(-1)) {
			scrollViewToTop(view);
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				scrollToTopRecursive(group.getChildAt(i));
			}
		}
	}

	/**
	 * Reflective scroll, ordered by MIUI's preference: RecyclerView list
	 * position APIs first, legacy AbsListView selection, then generic
	 * full-scroll fallbacks. Each attempt is independent - the first one
	 * that exists and does not throw wins.
	 */
	private static void scrollViewToTop(View view) {
		String[] methodNames = {
				"smoothScrollToPosition", "scrollToPosition", "setSelection",
				"smoothScrollTo", "fullScroll", "pageUp", "scrollToTop"
		};
		for (String name : methodNames) {
			try {
				java.lang.reflect.Method target = null;
				for (java.lang.reflect.Method m : view.getClass().getMethods()) {
					if (m.getName().equals(name)) {
						Class<?>[] p = m.getParameterTypes();
						if (p.length == 1 && (p[0] == int.class)) { target = m; break; }
						if (p.length == 0) { target = m; break; }
					}
				}
				if (target == null) continue;
				Class<?>[] p = target.getParameterTypes();
				target.invoke(view, p.length == 1 ? 0 : new Object[0]);
				return;
			} catch (Throwable ignored) {}
		}
		// Last resort: nudge generic scrollers (ScrollView#scrollTo style).
		try {
			view.scrollTo(0, 0);
		} catch (Throwable ignored) {}
	}
}
