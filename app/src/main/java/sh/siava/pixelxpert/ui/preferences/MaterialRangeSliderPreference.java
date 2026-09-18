package sh.siava.pixelxpert.ui.preferences;

import static sh.siava.pixelxpert.ui.preferences.Utils.setBackgroundResource;
import static sh.siava.pixelxpert.ui.preferences.Utils.setFirstAndLastItemMargin;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;

import sh.siava.pixelxpert.R;
import sh.siava.rangesliderpreference.RangeSliderPreference;

public class MaterialRangeSliderPreference extends RangeSliderPreference {

	/**
	 * Return null so that PreferenceHelper.setupPreference() skips setSummary().
	 * setSummary() triggers notifyChanged() → onBindViewHolder rebind,
	 * which resets the slider to the last saved value while dragging.
	 * The value display is updated by the slider's own touch listener.
	 */
	@Override
	public CharSequence getSummary() {
		return null;
	}

	public MaterialRangeSliderPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		initResource();
	}

	@Override
	public void onBindViewHolder(@NonNull PreferenceViewHolder holder)
	{
		super.onBindViewHolder(holder);

		holder.setDividerAllowedAbove(false);
		holder.setDividerAllowedBelow(false);

		setFirstAndLastItemMargin(holder);
		setBackgroundResource(this, holder);
	}

	private void initResource() {
		setLayoutResource(R.layout.custom_preference_range_slider);
	}
}
