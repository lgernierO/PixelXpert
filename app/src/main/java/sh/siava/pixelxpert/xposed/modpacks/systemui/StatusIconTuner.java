package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods.APP_SWITCH_SLOT;

import android.content.Context;
import android.util.ArraySet;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SystemUIModPack
public class StatusIconTuner extends XposedModPack {
	private static Set<String> SBIgnoredIcons = new ArraySet<>();
	private static Set<String> KGIgnoredIcons = new ArraySet<>();
	private static Set<String> QSIgnoredIcons = new ArraySet<>();

	private Object mSBIconContainer, mQSIconContainer, mKGIconContainer;
	private Object mSBIconManager, mQSIconManager, mKGIconManager;
	private final Map<Object, List<String>> systemBlockLists = new IdentityHashMap<>();
	private boolean applyingBlockList;

	public StatusIconTuner(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		SBIgnoredIcons = Xprefs.getStringSet("SBIgnoredIcons", new ArraySet<>());
		KGIgnoredIcons = Xprefs.getStringSet("KGIgnoredIcons", new ArraySet<>());
		QSIgnoredIcons = Xprefs.getStringSet("QSIgnoredIcons", new ArraySet<>());

		KGIgnoredIcons.add(APP_SWITCH_SLOT);

		applyIgnoredIcons(mSBIconManager, mSBIconContainer, SBIgnoredIcons);
		applyIgnoredIcons(mKGIconManager, mKGIconContainer, KGIgnoredIcons);
		applyIgnoredIcons(mQSIconManager, mQSIconContainer, QSIgnoredIcons);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager");
		if (IconManagerClass.getClazz() == null) {
			IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController$IconManager");
		}

		IconManagerClass.afterConstruction().run(param -> {
			try {
				Object manager = param.thisObject;
				View container = (View) getObjectField(manager, "mGroup");
				String location = getManagerLocation(manager, container);
				systemBlockLists.put(manager, copyBlockList(manager));
				if ("HOME".equals(location)) {
					mSBIconManager = manager;
					mSBIconContainer = container;
					applyIgnoredIcons(manager, container, SBIgnoredIcons);
				} else if ("QS".equals(location)) {
					mQSIconManager = manager;
					mQSIconContainer = container;
					applyIgnoredIcons(manager, container, QSIgnoredIcons);
				} else if ("KEYGUARD".equals(location)) {
					mKGIconManager = manager;
					mKGIconContainer = container;
					applyIgnoredIcons(manager, container, KGIgnoredIcons);
				}
			} catch (Throwable ignored) {}
		});

		// Android 17 moved filtering into IconManager#setBlockList. Keep the
		// system-provided list and append PixelXpert's location-specific slots.
		IconManagerClass.before("setBlockList").run(param -> {
			if (param.args.length == 0 || !(param.args[0] instanceof List)) return;
			Set<String> userSlots = ignoredForManager(param.thisObject);
			if (userSlots == null) return;
			if (!applyingBlockList) {
				systemBlockLists.put(param.thisObject, new ArrayList<>((List<String>) param.args[0]));
			}
			ArrayList<String> merged = new ArrayList<>(systemBlockLists.getOrDefault(param.thisObject, List.of()));
			for (String slot : userSlots) if (!merged.contains(slot)) merged.add(slot);
			param.args[0] = merged;
		});
	}

	private Set<String> ignoredForManager(Object manager) {
		if (manager == mSBIconManager) return SBIgnoredIcons;
		if (manager == mQSIconManager) return QSIgnoredIcons;
		if (manager == mKGIconManager) return KGIgnoredIcons;
		return null;
	}

	private List<String> copyBlockList(Object manager) {
		try { return new ArrayList<>((Collection<String>) getObjectField(manager, "mBlockList")); }
		catch (Throwable ignored) { return new ArrayList<>(); }
	}

	private void applyIgnoredIcons(Object manager, Object container, Set<String> ignoredSlots) {
		setIgnoredIcons(container, ignoredSlots);
		if (manager == null) return;
		try {
			ArrayList<String> merged = new ArrayList<>(systemBlockLists.getOrDefault(manager, List.of()));
			for (String slot : ignoredSlots) if (!merged.contains(slot)) merged.add(slot);
			applyingBlockList = true;
			callMethod(manager, "setBlockList", merged);
		} catch (Throwable ignored) {
		} finally {
			applyingBlockList = false;
		}
	}

	private String getManagerLocation(Object manager, View container) {
		try { return String.valueOf(getObjectField(manager, "mLocation")); }
		catch (Throwable ignored) {}
		ViewParent parent = container;
		while (parent instanceof View) {
			try {
				String id = mContext.getResources().getResourceName(((View) parent).getId());
				if (id.contains("status_bar_end_side_content")) return "HOME";
				if (id.contains("shade_header_system_icons")) return "QS";
				if (id.contains("system_icons_container")) return "KEYGUARD";
			} catch (Throwable ignored) {}
			parent = parent.getParent();
		}
		return "";
	}

	private void setIgnoredIcons(Object container, Set<String> ignoredSlots) {
		if (container == null) return;
		try {
			Collection<String> current = (Collection<String>) getObjectField(container, "mIgnoredSlots");
			current.clear();
			current.addAll(ignoredSlots);
			((View) container).requestLayout();
		} catch (Throwable ignored) {}
	}
}
