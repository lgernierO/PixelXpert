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
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class QSPrivacy extends XposedModPack {
	private static final String SHADE_SCENE = "shade";
	private static final String NOTIFICATIONS_SHADE_OVERLAY = "notifications_shade";
	private static final String QUICK_SETTINGS_SCENE = "quick_settings";
	private static final String QUICK_SETTINGS_OVERLAY = "quick_settings_shade";

	private static volatile boolean hideCarrierText;
	private static volatile boolean allowPullDownOnLockscreen;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final KeyguardManager keyguardManager;
	private final Map<View, Integer> hiddenTextVisibilities = new WeakHashMap<>();
	private final Map<Object, Boolean> inlineExpandedRows =
			Collections.synchronizedMap(new WeakHashMap<>());
	private WeakReference<Object> carrierGroupController = new WeakReference<>(null);
	private WeakReference<Object> keyguardViewManager = new WeakReference<>(null);

	public QSPrivacy(Context context) {
		super(context);
		keyguardManager = context.getSystemService(KeyguardManager.class);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;

		boolean wasHidingCarrierText = hideCarrierText;
		boolean wasAllowingPullDown = allowPullDownOnLockscreen;
		hideCarrierText = Xprefs.getBoolean("HideQSCarrierText", false);
		allowPullDownOnLockscreen = Xprefs.getBoolean("QSPulldownOnLockscreen", true);

		if (wasAllowingPullDown != allowPullDownOnLockscreen) {
			inlineExpandedRows.clear();
		}

		if (wasHidingCarrierText != hideCarrierText) {
			Object controller = carrierGroupController.get();
			if (controller != null) {
				mainHandler.post(() -> {
					applyCarrierTextVisibility(controller);
					requestCarrierTextRefresh(controller);
				});
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam packageReadyParam) throws Throwable {
		ReflectedClass shadeCarrierGroupControllerClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.shade.carrier.ShadeCarrierGroupController");
		ReflectedClass shadeHeaderKtClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.shade.ui.composable.ShadeHeaderKt");
		ReflectedClass disabledContentInteractorClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.scene.domain.interactor.DisabledContentInteractor");
		ReflectedClass sceneContainerViewModelClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel");
		ReflectedClass modernShadeCarrierGroupClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView$Companion");
		ReflectedClass statusBarRootKtClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootKt");
		ReflectedClass dragDownHelperClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.DragDownHelper");
		ReflectedClass lockscreenShadeTransitionControllerClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.LockscreenShadeTransitionController");
		ReflectedClass expandableNotificationRowClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
		ReflectedClass statusBarKeyguardViewManagerClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");

		statusBarKeyguardViewManagerClass
				.afterConstruction()
				.run(param -> keyguardViewManager = new WeakReference<>(param.thisObject));

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
		hookComposeCarrierText(shadeHeaderKtClass);
		hookModernCarrierText(modernShadeCarrierGroupClass);

		// SceneContainer filters user gesture targets through this exact CANARY method.
		disabledContentInteractorClass
				.before("isDisabled")
				.run(param -> {
					if (shouldBlockLockscreenShadePullDown()
							&& param.args.length > 0
							&& isLockscreenShadeContent(param.args[0])) {
						param.setResult(true);
					}
				});

		// Reject only user-driven transitions into either notification or QS shade.
		sceneContainerViewModelClass
				.before("isFalsingAllowingContentChange")
				.run(param -> {
					if (shouldBlockLockscreenShadePullDown()
							&& param.args.length > 1
							&& isLockscreenShadeContent(param.args[1])) {
						param.setResult(false);
					}
				});

		blockStatusBarPullDown(statusBarRootKtClass);
		blockLegacyPullDown(dragDownHelperClass);
		hookInlineNotificationExpansion(expandableNotificationRowClass);
		keepNotificationExpansionOnLockscreen(lockscreenShadeTransitionControllerClass);
	}

	private void hookComposeCarrierText(ReflectedClass shadeHeaderKtClass) {
		shadeHeaderKtClass
				.before("CarrierTextNoSubscriptionId")
				.run(param -> {
					if (hideCarrierText) {
						param.setResult(null);
					}
				});
	}

	private void hookModernCarrierText(ReflectedClass modernShadeCarrierGroupClass) {
		modernShadeCarrierGroupClass
				.after("constructAndBind")
				.run(param -> applyModernCarrierTextVisibility(param.getResult()));
	}

	private void applyModernCarrierTextVisibility(Object carrierGroup) {
		try {
			Object binding = getObjectField(carrierGroup, "binding");
			Object carrierBinding = getObjectField(binding, "$shadeCarrierBinding");
			TextView carrierText = getObjectField(carrierBinding, "$carrierTextView");
			applyTextVisibility(carrierText);
			return;
		} catch (Throwable ignored) {
		}

		if (carrierGroup instanceof View groupView) {
			applyTextVisibility(findViewByName(groupView, "mobile_carrier_text"));
		}
	}

	private void blockStatusBarPullDown(ReflectedClass statusBarRootKtClass) {
		statusBarRootKtClass
				.before("dispatchAndConsume")
				.run(param -> {
					if (shouldBlockLockscreenShadePullDown()) {
						if (param.args.length > 0) {
							consumePointerChanges(param.args[0]);
						}
						param.setResult(null);
					}
				});
	}

	private void blockLegacyPullDown(ReflectedClass dragDownHelperClass) {
		for (String methodName : new String[]{"onInterceptTouchEvent", "onTouchEvent"}) {
			dragDownHelperClass
					.before(methodName)
					.run(param -> {
						if (shouldBlockLockscreenShadePullDown()) {
							param.setResult(false);
						}
					});
		}
	}

	private void keepNotificationExpansionOnLockscreen(
			ReflectedClass lockscreenShadeTransitionControllerClass) {
		lockscreenShadeTransitionControllerClass
				.before("goToLockedShade")
				.run(param -> {
					if (shouldBlockLockscreenShadePullDown()
							&& param.args.length == 2
							&& isExpandableNotificationRow(param.args[0])
							&& Boolean.TRUE.equals(param.args[1])) {
						Object row = param.args[0];
						if (canKeepNotificationExpandedInline(row)) {
							inlineExpandedRows.put(row, true);
							try {
								// CANARY normally performs this before transitioning to the shade.
								callMethod(row, "setUserExpanded", true, true);
								requestNotificationHeightUpdate(row, "PX.inlineLockscreenExpansion");
							} catch (Throwable ignored) {
								inlineExpandedRows.remove(row);
							}
						}

						// Keep the user on the lock screen even when inline expansion is unsafe.
						param.setResult(null);
					}
				});
	}

	private void hookInlineNotificationExpansion(ReflectedClass expandableNotificationRowClass) {
		expandableNotificationRowClass
				.before("isExpanded")
				.run(param -> {
					if (param.args.length == 1
							&& Boolean.FALSE.equals(param.args[0])
							&& isInlineNotificationExpansionAllowed(param.thisObject)) {
						param.setResult(true);
					}
				});

		expandableNotificationRowClass
				.after("setUserExpanded")
				.run(param -> {
					if (param.args.length > 0
							&& Boolean.FALSE.equals(param.args[0])
							&& inlineExpandedRows.remove(param.thisObject) != null
							&& shouldBlockLockscreenShadePullDown()) {
						requestNotificationHeightUpdate(param.thisObject, "PX.inlineLockscreenCollapse");
					}
				});
	}

	private boolean isInlineNotificationExpansionAllowed(Object row) {
		if (!Boolean.TRUE.equals(inlineExpandedRows.get(row))
				|| !shouldBlockLockscreenShadePullDown()) {
			return false;
		}

		try {
			return Boolean.TRUE.equals(getObjectField(row, "mUserExpanded"))
					&& canKeepNotificationExpandedInline(row);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean canKeepNotificationExpandedInline(Object row) {
		if (!shouldBlockLockscreenShadePullDown()
				|| !isExpandableNotificationRow(row)
				|| !(row instanceof View rowView)) {
			return false;
		}

		try {
			if (Boolean.TRUE.equals(callMethod(row, "shouldShowPublic"))
					|| Boolean.TRUE.equals(callMethod(row, "isChildInGroup"))
					|| Boolean.TRUE.equals(callMethod(row, "isPromotedOngoing"))
					|| !Boolean.TRUE.equals(callMethod(row, "isExpandable"))
					|| Boolean.TRUE.equals(getObjectField(row, "mSaveSpaceOnLockscreen"))
					|| isPrimaryBouncerShowing()) {
				return false;
			}

			ViewParent parent = rowView.getParent();
			// The CANARY notification stack owns total-height calculation and clipping.
			// Do not reject a row merely because its expanded content exceeds the
			// remaining space; that leaves the row at its collapsed height.
			return parent != null
					&& "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
					.equals(parent.getClass().getName());
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isPrimaryBouncerShowing() {
		Object manager = keyguardViewManager.get();
		if (manager == null) {
			return true;
		}

		try {
			return Boolean.TRUE.equals(callMethod(manager, "primaryBouncerIsOrWillBeShowing"));
		} catch (Throwable ignored) {
			return true;
		}
	}

	private void requestNotificationHeightUpdate(Object row, String reason) {
		try {
			callMethod(row, "notifyHeightChanged", reason, true);
		} catch (Throwable ignored) {
		}
	}

	private boolean shouldBlockLockscreenShadePullDown() {
		if (allowPullDownOnLockscreen || keyguardManager == null) return false;

		try {
			return keyguardManager.isKeyguardLocked();
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isLockscreenShadeContent(Object contentKey) {
		try {
			String debugName = getObjectField(contentKey, "debugName");
			return SHADE_SCENE.equals(debugName)
					|| NOTIFICATIONS_SHADE_OVERLAY.equals(debugName)
					|| QUICK_SETTINGS_SCENE.equals(debugName)
					|| QUICK_SETTINGS_OVERLAY.equals(debugName);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isExpandableNotificationRow(Object view) {
		return view != null
				&& "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
				.equals(view.getClass().getName());
	}

	private void consumePointerChanges(Object pointerEvent) {
		try {
			Object changes = getObjectField(pointerEvent, "changes");
			if (changes instanceof Iterable<?> pointerChanges) {
				for (Object change : pointerChanges) {
					callMethod(change, "consume");
				}
			}
		} catch (Throwable ignored) {
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
				applyModernCarrierTextVisibility(carrierGroup);
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
