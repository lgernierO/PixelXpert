package sh.siava.pixelxpert.xposed.modpacks.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Runs inside system_server (services.jar scope).  Receives the tap-to-top
 * request from the SystemUI side and injects MOVE_HOME through the real
 * InputManagerService instance - the same privileged path OEM ROMs use,
 * bypassing hidden-API restrictions on the client InputManager wrapper.
 * <p>
 * All signatures verified against the device's actual services.jar:
 * InputManagerService has a public injectInputEvent(InputEvent, int) method.
 */
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class ScrollTopInjector extends XposedModPack {
	/** InputManagerService.INJECT_INPUT_EVENT_MODE_ASYNC */
	private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

	/** Captured InputManagerService instance (singleton in system_server). */
	private static Object mInputManagerService = null;

	public ScrollTopInjector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) {
		// Capture the one-and-only InputManagerService instance.
		ReflectedClass InputManagerServiceClass = ReflectedClass.ofIfPossible(
				"com.android.server.input.InputManagerService");
		InputManagerServiceClass
				.afterConstruction()
				.run(param -> mInputManagerService = param.thisObject);

		BroadcastReceiver receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				new Thread(() -> {
					try {
						injectMoveHome();
					} catch (Throwable ignored) {}
				}).start();
			}
		};

		IntentFilter filter = new IntentFilter(Constants.ACTION_SCROLL_TOP);
		// Signature|privileged gate: SystemUI holds STATUS_BAR_SERVICE, so only
		// the trusted status-bar sender can trigger a MOVE_HOME injection.
		mContext.registerReceiver(receiver, filter,
				"android.permission.STATUS_BAR_SERVICE", null,
				Context.RECEIVER_EXPORTED);
	}

	/**
	 * Calls the captured InputManagerService directly.  Being in-process, this
	 * is the same code path WindowKeyboardFlattenStateManager and the OEM
	 * scroll-to-top implementations use - no binder, no hidden API walls.
	 */
	private static void injectMoveHome() throws Exception {
		if (mInputManagerService == null) return;

		long now = SystemClock.uptimeMillis();
		KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME, 0,
				KeyEvent.META_CTRL_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
				KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
		KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME, 0,
				KeyEvent.META_CTRL_ON, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
				KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

		// Direct reflection on the captured instance.
		java.lang.reflect.Method inject = mInputManagerService.getClass()
				.getMethod("injectInputEvent", InputEvent.class, int.class);
		inject.setAccessible(true);
		inject.invoke(mInputManagerService, down, INJECT_INPUT_EVENT_MODE_ASYNC);
		inject.invoke(mInputManagerService, up, INJECT_INPUT_EVENT_MODE_ASYNC);
	}
}