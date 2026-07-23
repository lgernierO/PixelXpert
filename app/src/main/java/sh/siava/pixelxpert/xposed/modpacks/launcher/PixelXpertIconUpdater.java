package sh.siava.pixelxpert.xposed.modpacks.launcher;

import android.content.Context;
import android.os.UserHandle;

import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class PixelXpertIconUpdater extends XposedModPack {
	private Object LauncherModel;

	public PixelXpertIconUpdater(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass LauncherModelClass = ReflectedClass.of("com.android.launcher3.LauncherModel");
		ReflectedClass BaseActivityClass = ReflectedClass.of("com.android.launcher3.BaseActivity");
		ReflectedClass PackageUpdatedTaskClass = ReflectedClass.ofIfPossible("com.android.launcher3.model.tasks.PackageUpdatedTask");

		BaseActivityClass
				.after("onResume")
				.run(param -> {
					if (LauncherModel == null) return;
					try {
						XposedHelpers.callMethod(LauncherModel, "onAppIconChanged", BuildConfig.APPLICATION_ID, UserHandle.getUserHandleForUid(0));
					} catch (Throwable ignored) {
						try {
							// Android 17 moved this callback into a public model update task.
							Object updateTask = PackageUpdatedTaskClass.getClazz()
									.getConstructor(String[].class, UserHandle.class, boolean.class)
									.newInstance(new String[]{BuildConfig.APPLICATION_ID}, UserHandle.getUserHandleForUid(0), true);
							XposedHelpers.callMethod(LauncherModel, "enqueueModelUpdateTask", updateTask);
						} catch (Throwable modern) {
							try {
								XposedHelpers.callMethod(LauncherModel, "forceReload", "PixelXpert icon changed");
							} catch (Throwable legacy) {
								try { XposedHelpers.callMethod(LauncherModel, "forceReload"); } catch (Throwable ignoredAgain) {}
							}
						}
					}
				});

		LauncherModelClass
				.afterConstruction()
				.run(param -> LauncherModel = param.thisObject);
	}
}
