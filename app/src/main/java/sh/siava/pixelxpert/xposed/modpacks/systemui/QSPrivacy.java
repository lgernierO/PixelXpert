package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.view.View.GONE;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod;
import static sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class QSPrivacy extends XposedModPack {
	private static final String QUICK_SETTINGS_SCENE = "quick_settings";
	private static final String QUICK_SETTINGS_OVERLAY = "quick_settings_shade";

	private static volatile boolean hideCarrierText;
	private static volatile boolean allowPullDownOnLockscreen;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final KeyguardManager keyguardManager;
	private final Map<View, Integer> hiddenTextVisibilities = new WeakHashMap<>();
	private WeakReference<Object> carrierGroupController = new WeakReference<>(null);

	public QSPrivacy(Context context) {
		super(context);
		keyguardManager = context.getSystemService(KeyguardManager.class);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;

		boolean wasHidingCarrierText = hideCarrierText;
		hideCarrierText = Xprefs.getBoolean("HideQSCarrierText", false);
		allowPullDownOnLockscreen = Xprefs.getBoolean("QSPulldownOnLockscreen", true);

		if (wasHidingCarrierText != hideCarrierText) {
			Object controller = carrierGroupController.get();
			if (controller != null) {
				mainHandler.post(() -> {
					applyCarrierTextVisibility(controller);
					if (!hideCarrierText) {
						requestCarrierTextRefresh(controller);
					}
				});
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam packageReadyParam) throws Throwable {
		ReflectedClass shadeCarrierGroupControllerClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.shade.carrier.ShadeCarrierGroupController");
		ReflectedClass disabledContentInteractorClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.scene.domain.interactor.DisabledContentInteractor");
		ReflectedClass shadeInteractorClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.shade.domain.interactor.ShadeInteractorImpl");
		ReflectedClass sceneContainerInteractorClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl");

		ReflectedClass sceneContainerViewModelClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel");

		shadeCarrierGroupControllerClass
				.afterConstruction()
				.run(param -> {
					carrierGroupController = new WeakReference<>(param.thisObject);
					applyCarrierTextVisibility(param.thisObject);
				});

		for (String method : new String[]{"showSingleText", "handleUpdateState", "updateModernMobileIcons"}) {
			shadeCarrierGroupControllerClass
					.after(method)
					.run(param -> applyCarrierTextVisibility(param.thisObject));
		}

		disabledContentInteractorClass
				.before("isDisabled")
				.run(param -> {
					if (shouldBlockQuickSettingsOnLockscreen()
							&& param.args.length > 0
							&& isQuickSettingsContent(param.args[0])) {
						param.setResult(true);
					}
				});

		// CANARY checks this callback before every user-driven scene or overlay
		// change, including a second pull from notification shade into QS.
		sceneContainerViewModelClass
				.before("isFalsingAllowingContentChange")
				.run(param -> {
					if (shouldBlockQuickSettingsOnLockscreen()
							&& param.args.length > 1
							&& isQuickSettingsContent(param.args[1])) {
						param.setResult(false);
					}
				});

		blockQuickSettingsExpansion(shadeInteractorClass);
		blockQuickSettingsExpansion(sceneContainerInteractorClass);
	}

	private void blockQuickSettingsExpansion(ReflectedClass interactorClass) {
		interactorClass
				.before("expandQuickSettingsShade")
				.run(param -> {
					if (shouldBlockQuickSettingsOnLockscreen()) {
						param.setResult(null);
					}
				});
	}

	private boolean shouldBlockQuickSettingsOnLockscreen() {
		if (allowPullDownOnLockscreen || keyguardManager == null) return false;

		try {
			return keyguardManager.isKeyguardLocked();
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isQuickSettingsContent(Object contentKey) {
		try {
			String debugName = getObjectField(contentKey, "debugName");
			return QUICK_SETTINGS_SCENE.equals(debugName)
					|| QUICK_SETTINGS_OVERLAY.equals(debugName);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void applyCarrierTextVisibility(Object controller) {
		if (controller == null) return;

		try {
			TextView noSimText = getObjectField(controller, "mNoSimTextView");
			applyTextVisibility(noSimText);
		} catch (Throwable ignored) {
		}

		try {
			Object[] carrierGroups = getObjectField(controller, "mCarrierGroups");
			for (Object carrierGroup : carrierGroups) {
				if (!(carrierGroup instanceof View groupView)) continue;

				// CANARY keeps keyguard carrier text separate from these QS-only views.
				applyTextVisibility(findViewByName(groupView, "shade_carrier_text"));
				applyTextVisibility(findViewByName(groupView, "mobile_carrier_text"));
			}
		} catch (Throwable ignored) {
		}
	}

	private View findViewByName(View root, String idName) {
		int id = mContext.getResources().getIdentifier(idName, "id", mContext.getPackageName());
		return id == 0 ? null : root.findViewById(id);
	}

	private void applyTextVisibility(View view) {
		if (view == null) return;

		if (hideCarrierText) {
			hiddenTextVisibilities.putIfAbsent(view, view.getVisibility());
			view.setVisibility(GONE);
			return;
		}

		Integer originalVisibility = hiddenTextVisibilities.remove(view);
		if (originalVisibility != null) {
			view.setVisibility(originalVisibility);
		}
	}

	private void requestCarrierTextRefresh(Object controller) {
		try {
			Object carrierTextManager = getObjectField(controller, "mCarrierTextManager");
			callMethod(carrierTextManager, "updateCarrierText");
		} catch (Throwable ignored) {
		}
	}
}
