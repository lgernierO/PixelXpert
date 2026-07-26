package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class ScreenRecord extends XposedModPack {
	private static boolean InsecureScreenRecord = false;

	public ScreenRecord(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		InsecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass.of(MediaProjection.class)
				.before("createVirtualDisplay")
				.run(param -> {
					Method method = (Method) param.method;
					if (InsecureScreenRecord && hasVirtualDisplayFlags(method, param.args)) {
						int flags = (int) param.args[4];
						flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
						param.args[4] = flags;
					}
				});
	}

	/**
	 * The latest SystemUI still calls MediaProjection#createVirtualDisplay with
	 * the display flags at index 4 from ScreenMediaRecorder.start(). Do not tie
	 * this to one exact overload: newer framework revisions can append optional
	 * arguments while retaining the same flags position.
	 */
	private static boolean hasVirtualDisplayFlags(Method method, Object[] args) {
		return method.getParameterCount() > 4
				&& args.length > 4
				&& method.getParameterTypes()[4] == int.class
				&& args[4] instanceof Integer;
	}
}
