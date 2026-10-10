package sh.siava.pixelxpert.utils;

import static sh.siava.pixelxpert.ui.preferences.preferencesearch.SearchPreferenceResult.highlightPreference;
import static sh.siava.pixelxpert.utils.MiscUtils.dpToPx;
import static sh.siava.pixelxpert.utils.MiscUtils.setOnBackPressedDispatcherCallback;
import static sh.siava.pixelxpert.utils.MiscUtils.setupToolbar;
import static sh.siava.pixelxpert.utils.PreferenceHelper.checkIfRequiresSystemUIRestart;

import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import dagger.hilt.android.EntryPointAccessors;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.di.StateManagerEntryPoint;
import sh.siava.pixelxpert.service.tileServices.SleepOnSurfaceTileService;
import sh.siava.pixelxpert.ui.misc.StateManager;
import sh.siava.rangesliderpreference.RangeSliderPreference;

public abstract class ControlledPreferenceFragmentCompat extends PreferenceFragmentCompat {

	public ExtendedSharedPreferences mPreferences;
	private final OnSharedPreferenceChangeListener changeListener = (sharedPreferences, key) -> {
		updateScreen(key);
		checkIfRequiresSystemUIRestart(getContext(), key);

		// Notify QS tile services so tiles reflect the change immediately
		// instead of only after the user exits and re-enters the panel
		if ("SleepOnFlatScreen".equals(key)) {
			SleepOnSurfaceTileService.onPrefsChanged();
		}
	};
	//a reset/import pass rewrites every value while UI listeners are muted (to avoid one full
	//repaint per key), so the page has to be refreshed once when that pass publishes
	private final Runnable prefsReadyListener = () -> {
		//the callback is posted from a worker thread's completion, so the fragment may already be
		//gone or mid-teardown when it lands
		if (isAdded() && getPreferenceScreen() != null) updateScreen(null);
	};
	private static boolean firstAppLaunch = true;
	protected StateManager stateManager;

	protected boolean isBackButtonEnabled() {
		return true;
	}

	public boolean getBackButtonEnabled() {
		return isBackButtonEnabled();
	}

	public abstract String getTitle();

	public abstract int getLayoutResource();

	protected int getDefaultThemeResource() {
		return R.style.PrefsThemeCollapsingToolbar;
	}

	public int getThemeResource() {
		return getDefaultThemeResource();
	}

	@NonNull
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		inflater.getContext().setTheme(getThemeResource());
		return super.onCreateView(inflater, container, savedInstanceState);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		AppCompatActivity baseContext = (AppCompatActivity) getActivity();

		setupToolbar(this, view, getTitle(), getBackButtonEnabled());

		setOnBackPressedDispatcherCallback(requireActivity(), this);

		if (baseContext != null) {
			AppBarLayout appBarLayout = baseContext.findViewById(R.id.appBarLayout);
			if (appBarLayout != null && Objects.equals(getTitle(), getString(R.string.app_name)) && firstAppLaunch) {
				appBarLayout.setExpanded(true, false);
				firstAppLaunch = false;
			}
		}

		this.stateManager = EntryPointAccessors
				.fromApplication(PixelXpert.get(), StateManagerEntryPoint.class)
				.getStateManager();

		RecyclerView recyclerView = view.findViewById(androidx.preference.R.id.recycler_view);

		if (recyclerView != null) {
			recyclerView.setClipToPadding(false);

			ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
				Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
				boolean isRtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

				if (insets.left > 0 || insets.right > 0) {
					int endInset = isRtl ? insets.left : insets.right;

					ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) recyclerView.getLayoutParams();
					if (isRtl) params.leftMargin = endInset;
					else params.rightMargin = endInset;
					recyclerView.setLayoutParams(params);
				}

				return windowInsets;
			});

			// Gap to avoid overlapping with the FAB
			AtomicBoolean requiresSystemUiRestart = new AtomicBoolean(Boolean.TRUE.equals(stateManager.getRequiresSystemUIRestart().getValue()));
			AtomicBoolean requiresDeviceRestart = new AtomicBoolean(Boolean.TRUE.equals(stateManager.getRequiresDeviceRestart().getValue()));

			stateManager.getRequiresSystemUIRestart().observe(getViewLifecycleOwner(), isRequired -> {
				requiresSystemUiRestart.set(isRequired);
				updatePaddingBottom(recyclerView, requiresSystemUiRestart, requiresDeviceRestart);
			});

			stateManager.getRequiresDeviceRestart().observe(getViewLifecycleOwner(), isRequired -> {
				requiresDeviceRestart.set(isRequired);
				updatePaddingBottom(recyclerView, requiresSystemUiRestart, requiresDeviceRestart);
			});
		}

		if (getArguments() != null) {
			Bundle bundle = getArguments();
			if (bundle.containsKey("searchKey")) {
				highlightPreference(this, view, bundle.getString("searchKey"));
			}
		}
	}

	private static void updatePaddingBottom(RecyclerView recyclerView, AtomicBoolean requiresSystemUiRestart, AtomicBoolean requiresDeviceRestart) {
		boolean isTabletDevice = DisplayUtils.isTablet();

		try {
			recyclerView.setPadding(
					recyclerView.getPaddingLeft(),
					recyclerView.getPaddingTop(),
					recyclerView.getPaddingRight(),
					dpToPx((isTabletDevice ? 68 : 18) + (requiresSystemUiRestart.get() || requiresDeviceRestart.get() ? 74 : 0))
			);
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		getPreferenceManager().setStorageDeviceProtected();
		setPreferencesFromResource(getLayoutResource(), rootKey);
	}

	@NonNull
	@Override
	public RecyclerView.Adapter<?> onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
		mPreferences = PixelXpert.get().getDefaultPreferences();

		mPreferences.registerOnSharedPreferenceChangeListener(changeListener);
		PixelXpert.get().addOnPrefsReadyListener(prefsReadyListener);

		updateScreen(null);

		return super.onCreateAdapter(preferenceScreen);
	}

	@Override
	public void onDestroy() {
		if (mPreferences != null) {
			mPreferences.unregisterOnSharedPreferenceChangeListener(changeListener);
		}
		PixelXpert.get().removeOnPrefsReadyListener(prefsReadyListener);
		super.onDestroy();
	}

	public void updateScreen(String key) {
		if (key == null || getPreferenceScreen() == null) {
			//full pass: initial bind or a reset/import that rewrote everything
			PreferenceHelper.setupAllPreferences(this.getPreferenceScreen());
			return;
		}
		//targeted refresh: re-evaluate the changed preference, then its dependent rows. A switch,
		//checkbox or dropdown anywhere on the page can gate the visibility/enabled state of other
		//rows (see PreferenceHelper.isVisible/isEnabled - e.g. carrierTextMod->carrierTextValue,
		//NetworkStatsStartBase->NetworkStatsStart*, networkOnSBEnabled->networkTraffic*), so those
		//sub-options have to be refreshed in place instead of only after the user leaves and
		//re-enters the section. Sliders are the one exception: a drag fires this listener on every
		//tick and a hierarchy walk mid-drag rebinds the slider row (resetting the thumb to the last
		//saved value), so a slider change refreshes only itself.
		Preference changed = PreferenceHelper.findPreference(getPreferenceScreen(), key);
		if (changed == null) return;
		PreferenceHelper.setupPreference(changed);
		if (!(changed instanceof RangeSliderPreference)) {
			PreferenceHelper.setupChildren(getPreferenceScreen());
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		PreferenceHelper.setupMainSwitches(this.getPreferenceScreen());
	}
}