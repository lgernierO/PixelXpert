package sh.siava.pixelxpert.utils;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import sh.siava.rangesliderpreference.RangeSliderPreference;

public class ExtendedSharedPreferences implements SharedPreferences {
	public static final String IS_PREFS_INITIATED_KEY = "IsPrefsInitiated";
	public static final String PREFS_SCHEMA_VERSION_KEY = "PreferencesSchemaVersion";
	private final SharedPreferences prefs;
	public List<OnSharedPreferenceChangeListener> mOnSharedPreferenceChangeListeners = Collections.synchronizedList(new ArrayList<>());
	boolean mIsPrefsInitiated;

	//must be a field or will be GCed
	OnSharedPreferenceChangeListener listener = (sharedPreferences, key) -> {
		if(IS_PREFS_INITIATED_KEY.equals(key))
		{
			mIsPrefsInitiated = getBoolean(IS_PREFS_INITIATED_KEY, false);
			return;
		}
		if(PREFS_SCHEMA_VERSION_KEY.equals(key)) return;
		//androidx bookkeeping flag written by PreferenceXMLParser - never a real setting
		if(PreferenceXMLParser.KEY_HAS_SET_DEFAULT_VALUES.equals(key)) return;

		if(mIsPrefsInitiated && !mOnSharedPreferenceChangeListeners.isEmpty())
		{
			OnSharedPreferenceChangeListener[] listeners;
			synchronized (mOnSharedPreferenceChangeListeners) {
				listeners = mOnSharedPreferenceChangeListeners.toArray(new OnSharedPreferenceChangeListener[0]);
			}
			for (OnSharedPreferenceChangeListener registeredListener : listeners) {
				registeredListener.onSharedPreferenceChanged(sharedPreferences, key);
			}
		}
	};
	//cache the wrappers: every from() call used to register a new listener on the underlying
	//SharedPreferences without ever unregistering it, so each change was dispatched once per
	//wrapper created so far (making refreshes redundant and their order unpredictable)
	private static final Map<SharedPreferences, ExtendedSharedPreferences> INSTANCES =
			Collections.synchronizedMap(new WeakHashMap<>());

	public static ExtendedSharedPreferences from(SharedPreferences prefs)
	{
		return INSTANCES.computeIfAbsent(prefs, ExtendedSharedPreferences::new);
	}
	private ExtendedSharedPreferences(SharedPreferences prefs)
	{
		this.prefs = prefs;

		mIsPrefsInitiated = prefs.getBoolean(IS_PREFS_INITIATED_KEY, false);

		prefs.registerOnSharedPreferenceChangeListener(listener);
	}
	public int getSliderInt(String key, int defaultVal)
	{
		return RangeSliderPreference.getSingleIntValue(this, key, defaultVal);
	}

	public List<Float> getSliderValues(String key, float defaultValue)
	{
		return RangeSliderPreference.getValues(this, key, defaultValue);
	}

	public float getSliderFloat(String key, float defaultVal)
	{
		return RangeSliderPreference.getSingleFloatValue(this, key, defaultVal);
	}

	@Override
	public Map<String, ?> getAll() {
		return prefs.getAll();
	}

	@Nullable
	@Override
	public String getString(String key, @Nullable String defValue) {
		return prefs.getString(key, defValue);
	}

	@Nullable
	@Override
	public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
		return prefs.getStringSet(key, defValues);
	}

	@Override
	public int getInt(String key, int defValue) {
		return prefs.getInt(key, defValue);
	}

	@Override
	public long getLong(String key, long defValue) {
		return prefs.getLong(key, defValue);
	}

	@Override
	public float getFloat(String key, float defValue) {
		return prefs.getFloat(key, defValue);
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		return prefs.getBoolean(key, defValue);
	}

	@Override
	public boolean contains(String key) {
		return prefs.contains(key);
	}

	@Override
	public Editor edit() {
		return prefs.edit();
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		if (!mOnSharedPreferenceChangeListeners.contains(listener)) {
			mOnSharedPreferenceChangeListeners.add(listener);
		}
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		mOnSharedPreferenceChangeListeners.remove(listener);
	}
}
