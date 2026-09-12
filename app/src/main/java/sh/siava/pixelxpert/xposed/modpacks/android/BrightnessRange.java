package sh.siava.pixelxpert.xposed.modpacks.android;


import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.util.List;

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
	private static boolean disableBrightnessCap = false;

	public BrightnessRange(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		try {
			List<Float> BrightnessRange = Xprefs.getSliderValues("BrightnessRange", 100f);
			if (BrightnessRange.size() == 2) {
				minimumBrightnessLevel = BrightnessRange.get(0) / 100;
				maximumBrightnessLevel = BrightnessRange.get(1) / 100;
			}
		} catch (Throwable ignored) {
		}
		disableBrightnessCap = Xprefs.getBoolean("DisableBrightnessCap", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try { //framework
			ReflectedClass DisplayPowerControllerClass = ReflectedClass.of("com.android.server.display.DisplayPowerController");

			DisplayPowerControllerClass
					.before("clampScreenBrightness")
					.run(param -> {
						if (minimumBrightnessLevel == 0f && maximumBrightnessLevel == 1f) return;

						param.args[0] = Math.min(
								Math.max(
										param.getArg(0),
										minimumBrightnessLevel),
								maximumBrightnessLevel);
					});

		} catch (Throwable ignored) {
		}

		try { //framework: remove only the external DisplayManager.setBrightnessCap limit
			ReflectedClass ExternalBrightnessModifierClass = ReflectedClass.of("com.android.server.display.brightness.clamper.ExternalBrightnessModifier");

			ExternalBrightnessModifierClass
					.after("shouldApplyCap")
					.run(param -> {
						if (disableBrightnessCap) {
							param.setResult(false);
						}
					});

		} catch (Throwable ignored) {
		}

		try { //framework: Android 17 - the REAL cap valve for the built-in display.
			// Verified against Android 17 smali:
			// - applied brightness: DPC.updatePowerStateInternal -> clampScreenBrightness()
			//   constrains value into [HBM.getCurrentBrightnessMin(), HBM.getCurrentBrightnessMax()]
			// - SystemUI slider max: saveBrightnessInfo() caches brightnessMax =
			//   min(HBM.getCurrentBrightnessMax(), state.max) -> BrightnessInfo.brightnessMaximum
			// - HBM.getCurrentBrightnessMax() returns the HBM transitionPoint (cap) when
			//   HBM is currently not allowed, else the device max (1.0f)
			// NOTE: thermal / max-lux / low-power / dim modifiers run in BrightnessClamperController
			// independently of this path, so they keep working (by design).
			ReflectedClass HighBrightnessModeControllerClass = ReflectedClass.of("com.android.server.display.HighBrightnessModeController");

			HighBrightnessModeControllerClass
					.after("getCurrentBrightnessMax")
					.run(param -> {
						if (disableBrightnessCap) {
							param.setResult(1f);
						} else if (maximumBrightnessLevel < 1f) {
							param.setResult(maximumBrightnessLevel);
						}
					});

			HighBrightnessModeControllerClass
					.after("getCurrentBrightnessMin")
					.run(param -> {
						if (minimumBrightnessLevel > 0f) {
							param.setResult(minimumBrightnessLevel);
						}
					});

		} catch (Throwable ignored) {
		}

		try { //SystemUI
			ReflectedClass BrightnessInfoClass = ReflectedClass.of("android.hardware.display.BrightnessInfo");

			BrightnessInfoClass
					.afterConstruction()
					.run(param -> {
						if (minimumBrightnessLevel > 0f) {
							setObjectField(param.thisObject, "brightnessMinimum", minimumBrightnessLevel);
						}
						if (maximumBrightnessLevel < 1f) {
							setObjectField(param.thisObject, "brightnessMaximum", maximumBrightnessLevel);
						}
					});
		} catch (Throwable ignored) {
		}
	}
}