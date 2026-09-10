package sh.siava.pixelxpert.zygisk;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.ref.WeakReference;

/**
 * Zygisk-side engine for the status-bar tap-to-top feature.
 * <p>
 * Loaded by the Zygisk module (zygisk/&lt;abi&gt;.so) in every app process via
 * DexClassLoader from the module APK - no LSPosed scope required. It mirrors
 * the LSPosed engine in ScrollTopEnabler: on the ACTION_SCROLL_TOP broadcast
 * (sent by StatusbarGestures in SystemUI after the native WMS
 * dispatchScrollToTop call) the foreground window's scrollable views are
 * scrolled to the top, MIUI-style: recursive discovery via
 * canScrollVertically(-1) plus reflective scrolling, covering androidx
 * RecyclerView, WebView and custom containers that never implemented the
 * AOSP onScrollToTop pipeline.
 * <p>
 * The feature switch is checked on the trigger side (SystemUI only sends the
 * broadcast when StatusbarTapScrollTop is on), so no preference access is
 * needed here - this process may not have the module's provider available.
 * <p>
 * Pure Java + framework APIs only: this class must never reference Xposed
 * classes, it is loaded in plain app processes.
 */
public class ZygiskEntry {
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final Object LOCK = new Object();
	private static WeakReference<Activity> sResumedActivity = new WeakReference<>(null);
	private static boolean sRegistered = false;

	/** Called by the Zygisk module after the process's Application exists. */
	public static void init(Context context) {
		if (context instanceof Application && !sRegistered) {
			synchronized (LOCK) {
				if (!sRegistered) {
					sRegistered = true;
					((Application) context).registerActivityLifecycleCallbacks(new LifecycleTracker());
				}
			}
		}
	}

	/** Tracks the resumed activity so the broadcast can find the focused window. */
	private static class LifecycleTracker implements Application.ActivityLifecycleCallbacks {
		@Override
		public void onActivityResumed(Activity activity) {
			sResumedActivity = new WeakReference<>(activity);
		}

		@Override
		public void onActivityPaused(Activity activity) {
			Activity current = sResumedActivity.get();
			if (current == activity) sResumedActivity.clear();
		}

		@Override
		public void onActivityCreated(Activity a, Bundle b) {}

		@Override
		public void onActivityStarted(Activity a) {}

		@Override
		public void onActivityStopped(Activity a) {}

		@Override
		public void onActivitySaveInstanceState(Activity a, Bundle outState) {}

		@Override
		public void onActivityDestroyed(Activity a) {}
	}

	static {
		// Register the receiver as soon as this class is loaded; the Context
		// arrives via init() but a static holder would race the first broadcast.
		MAIN.post(ZygiskEntry::registerReceiverWhenReady);
	}

	private static Context sAppContext = null;

	private static void registerReceiverWhenReady() {
		Context context = sAppContext;
		if (context == null) {
			// init() has not run yet (or returned no context) - retry briefly
			MAIN.postDelayed(ZygiskEntry::registerReceiverWhenReady, 1000);
			return;
		}
		BroadcastReceiver receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context c, Intent intent) {
				MAIN.post(ZygiskEntry::performMiuiScroll);
			}
		};
		IntentFilter filter = new IntentFilter("sh.siava.pixelxpert.ACTION_SCROLL_TOP");
		try {
			context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
		} catch (Throwable ignored) {
			try {
				context.registerReceiver(receiver, filter);
			} catch (Throwable ignored2) {}
		}
	}

	/** Called by the Zygisk module once the Application instance exists. */
	public static void attachContext(Context context) {
		if (sAppContext == null) sAppContext = context.getApplicationContext();
	}

	/**
	 * Scans the resumed activity's window for scrollable views and scrolls
	 * them to the top. Mirrors MIUI's ViewRootImplStubImpl listener.
	 */
	private static void performMiuiScroll() {
		try {
			Activity activity = sResumedActivity.get();
			if (activity == null) return;
			Window window = activity.getWindow();
			if (window == null) return;
			View decor = window.peekDecorView() != null ? window.getDecorView() : null;
			if (decor == null) return;
			scrollToTopRecursive(decor);
		} catch (Throwable ignored) {}
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
