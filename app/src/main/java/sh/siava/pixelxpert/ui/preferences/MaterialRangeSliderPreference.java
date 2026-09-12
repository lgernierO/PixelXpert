package sh.siava.pixelxpert.ui.preferences;

import static sh.siava.pixelxpert.ui.preferences.Utils.setBackgroundResource;
import static sh.siava.pixelxpert.ui.preferences.Utils.setFirstAndLastItemMargin;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;

import sh.siava.pixelxpert.R;
import sh.siava.rangesliderpreference.RangeSliderPreference;

public class MaterialRangeSliderPreference extends RangeSliderPreference {

	private PreferenceViewHolder mHolder;

	public MaterialRangeSliderPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		initResource();
	}
	@Override
	public void onBindViewHolder(@NonNull PreferenceViewHolder holder)
	{
		super.onBindViewHolder(holder);

		mHolder = holder;

		holder.setDividerAllowedAbove(false);
		holder.setDividerAllowedBelow(false);

		setFirstAndLastItemMargin(holder);
		setBackgroundResource(this, holder);
	}

	/**
	 * Refresh the summary text in place. A slider row must not be re-bound while the finger is
	 * still on it (most of them save continuously while dragging), so notifyChanged() is avoided.
	 */
	public void refreshSummaryText(CharSequence text) {
		if (mHolder == null || text == null) return;

		if (mHolder.findViewById(android.R.id.summary) instanceof TextView summaryView) {
			summaryView.setText(text);
		}
	}

	private void initResource() {
		setLayoutResource(R.layout.custom_preference_range_slider);
	}
}
