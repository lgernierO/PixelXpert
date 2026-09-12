package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
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

	// DPC / ABC instances seen since boot, so a preference toggle applies immediately
	// without waiting for the display controllers to be re-created.
	private static final List<WeakReference<Object>> sClampControllers =
			Collections.synchronizedList(new ArrayList<>());

	// Vendor limits captured before the first modification, per controller instance, so that
	// turning the features off restores the original ceiling instead of leaving 1.0 behind
	// (which would bypass the OEM calibration until reboot).
	private static final Map<Object, float[]> sOriginalLimits =
			Collections.synchronizedMap(new WeakHashMap<>());

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
				minimumBrightnessLevel = BrightnessRange.get(0) / 100f;
				maximumBrightnessLevel = BrightnessRange.get(1) / 100f;
			}
		} catch (Throwable ignored) {
		}
		applyCapToKnownControllers();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		// Android 17 evidence (services.jar disassembly): both DisplayPowerController and
		// AutomaticBrightnessController clamp brightness with
		//   BrightnessUtils.constrain(value, mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum)
		// using those two *fields* - the incoming argument is never re-used, so hooking
		// clampScreenBrightness before/after cannot lift the ceiling. Rewriting the field is
		// the only way, and it fixes manual + auto brightness with one mechanism.
		hookClampController("com.android.server.display.DisplayPowerController");
		hookClampController("com.android.server.display.AutomaticBrightnessController");

		// In auto-brightness, AutomaticBrightnessController.updateAutoBrightness() derives the
		// target ceiling from getMaxBrightness() -> HighBrightnessModeController
		// .getCurrentBrightnessMax(), which returns the HBM transition point (e.g. 0.6) while
		// HBM is not allowed. Without this the target is already capped before it reaches the
		// clamp, so lifting the field alone would not make auto-brightness brighter.
		try {
			ReflectedClass.of("com.android.server.display.HighBrightnessModeController")
					.after("getCurrentBrightnessMax")
					.run(param -> {
						if (mDisableBrightnessCap) param.setResult(1f);
					});
		} catch (Throwable ignored) {
		}

		// Ceiling reported to SystemUI: DisplayPowerController.getBrightnessInfo() copies
		// mScreenBrightnessRangeMaximum into BrightnessInfo.brightnessMax *after* construction,
		// so patch the returned object instead of its constructor.
		try {
			ReflectedClass.of("com.android.server.display.DisplayPowerController")
					.after("getBrightnessInfo")
					.run(param -> {
						Object brightnessInfo = param.getResult();
						if (brightnessInfo == null) return;
						if (mDisableBrightnessCap) {
							setFloatField(brightnessInfo, "brightnessMax", 1f);
						} else if (mBrightnessRangeEnabled) {
							if (minimumBrightnessLevel > 0f)
								setFloatField(brightnessInfo, "brightnessMin", minimumBrightnessLevel);
							if (maximumBrightnessLevel < 1f)
								setFloatField(brightnessInfo, "brightnessMax", maximumBrightnessLevel);
						}
					});
		} catch (Throwable ignored) {
		}
	}

	private static void hookClampController(String className) {
		try {
			ReflectedClass.of(className)
					.afterConstruction()
					.run(param -> {
						sClampControllers.add(new WeakReference<>(param.thisObject));
						applyCap(param.thisObject);
					});
		} catch (Throwable ignored) {
		}
	}

	private static void applyCapToKnownControllers() {
		synchronized (sClampControllers) {
			sClampControllers.removeIf(ref -> ref.get() == null);
			for (WeakReference<Object> ref : sClampControllers) {
				Object controller = ref.get();
				if (controller != null) applyCap(controller);
			}
		}
	}

	private static void applyCap(Object controller) {
		try {
			boolean active = mDisableBrightnessCap || mBrightnessRangeEnabled;
			float[] original = sOriginalLimits.get(controller);

			if (!active) {
				// Restore the vendor limits once the features are turned off.
				if (original != null) {
					setFloatField(controller, "mScreenBrightnessRangeMinimum", original[0]);
					setFloatField(controller, "mScreenBrightnessRangeMaximum", original[1]);
					sOriginalLimits.remove(controller);
				}
				return;
			}

			if (original == null) {
				original = new float[]{
						getFloatField(controller, "mScreenBrightnessRangeMinimum", 0f),
						getFloatField(controller, "mScreenBrightnessRangeMaximum", 1f)
				};
				sOriginalLimits.put(controller, original);
			}

			if (mDisableBrightnessCap) {
				setFloatField(controller, "mScreenBrightnessRangeMaximum", 1f);
			} else if (maximumBrightnessLevel < 1f) {
				setFloatField(controller, "mScreenBrightnessRangeMaximum", maximumBrightnessLevel);
			} else {
				setFloatField(controller, "mScreenBrightnessRangeMaximum", original[1]);
			}
			if (mBrightnessRangeEnabled && minimumBrightnessLevel > 0f) {
				setFloatField(controller, "mScreenBrightnessRangeMinimum", minimumBrightnessLevel);
			} else {
				setFloatField(controller, "mScreenBrightnessRangeMinimum", original[0]);
			}
		} catch (Throwable ignored) {
		}
	}

	private static float getFloatField(Object obj, String name, float fallback) {
		try {
			Field f = obj.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.getFloat(obj);
		} catch (Throwable ignored) {
			return fallback;
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
