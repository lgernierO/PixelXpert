package sh.siava.pixelxpert.xposed.modpacks.android;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
@FrameworkModPack
public class BrightnessRange extends XposedModPack {
	private static float minimumBrightnessLevel = 0f;
	private static float maximumBrightnessLevel = 1f;
	private static boolean mBrightnessRangeEnabled = false;
	private static boolean mDisableBrightnessCap = false;

	public BrightnessRange(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		try {
			mBrightnessRangeEnabled = Xprefs.getBoolean("BrightnessRange", false);
			mDisableBrightnessCap = Xprefs.getBoolean("DisableBrightnessCap", false);
			List<Float> BrightnessRange = Xprefs.getSliderValues("BrightnessRange", 100f);
			if (BrightnessRange.size() == 2) {
				minimumBrightnessLevel = BrightnessRange.get(0) / 100;
				maximumBrightnessLevel = BrightnessRange.get(1) / 100;
			}
		} catch (Throwable ignored) {
		}
	}

	// 上限：去除亮度限制时为硬件最大 1.0f；否则用滑条上限（默认 1.0f 即不限制）
	private static float getCapMax() {
		if (mDisableBrightnessCap) return 1f;
		if (maximumBrightnessLevel < 1f) return maximumBrightnessLevel;
		return 1f;
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		// DisplayPowerController：手动亮度钳制点（mScreenBrightnessRangeMaximum final 字段）
		try {
			ReflectedClass.of("com.android.server.display.DisplayPowerController")
					.afterConstruction()
					.run(param -> applyCap(param.thisObject));
		} catch (Throwable ignored) {
		}

		// AutomaticBrightnessController：自动亮度钳制点（之前漏了这处，导致自动亮度无效）
		try {
			ReflectedClass.of("com.android.server.display.AutomaticBrightnessController")
					.afterConstruction()
					.run(param -> applyCap(param.thisObject));
		} catch (Throwable ignored) {
		}

		// SystemUI 侧滑条范围（BrightnessRange 模式）
		try {
			ReflectedClass.of("android.hardware.display.BrightnessInfo")
					.afterConstruction()
					.run(param -> {
						if (mBrightnessRangeEnabled && minimumBrightnessLevel > 0f) {
							setFloatField(param.thisObject, "brightnessMinimum", minimumBrightnessLevel);
						}
						if (mBrightnessRangeEnabled && maximumBrightnessLevel < 1f) {
							setFloatField(param.thisObject, "brightnessMaximum", maximumBrightnessLevel);
						}
					});
		} catch (Throwable ignored) {
		}
	}

	// 直接改写 final 亮度范围字段：方法体内部钳制会自动突破，applied 亮度与滑条上限同步生效
	private static void applyCap(Object controller) {
		try {
			setFloatField(controller, "mScreenBrightnessRangeMaximum", getCapMax());
			if (mBrightnessRangeEnabled && minimumBrightnessLevel > 0f) {
				setFloatField(controller, "mScreenBrightnessRangeMinimum", minimumBrightnessLevel);
			}
		} catch (Throwable ignored) {
		}
	}

	private static void setFloatField(Object obj, String name, float value) {
		try {
			Field f = obj.getClass().getDeclaredField(name);
			f.setAccessible(true);
			f.setFloat(obj, value);
		} catch (Throwable ignored) {
		}
	}
}
