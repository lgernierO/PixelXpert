package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class DocumentsUIFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.documentsui_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.documentsui_prefs;
	}
}
