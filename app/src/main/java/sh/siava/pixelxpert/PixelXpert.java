package sh.siava.pixelxpert;

import static sh.siava.pixelxpert.Constants.DEFAULT_PREFS_FILE_NAME;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.downloader.PRDownloader;
import com.downloader.PRDownloaderConfig;
import com.google.android.material.color.DynamicColors;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dagger.hilt.android.HiltAndroidApp;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import sh.siava.pixelxpert.service.RootProvider;
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences;
import sh.siava.pixelxpert.utils.PreferenceXMLParser;
import sh.siava.pixelxpert.utils.StateGate;

@HiltAndroidApp
public class PixelXpert extends Application {

	/** @noinspection unused*/
	public static final String TAG = "PixelXpertSingleton";
	private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());


	private static PixelXpert instance;
	private volatile boolean mCoreRootServiceBound = false;
	private final AtomicBoolean mRootServiceConnecting = new AtomicBoolean(false);
	//preference passes are serialised on this monitor so a second caller cannot interleave its
	//mute/unmute writes with a pass that is still publishing
	private final Object mPrefPassLock = new Object();
	private boolean mPreferenceInitRunning = false;
	//bumped whenever a root connection attempt is (re)started or abandoned, so a callback that
	//belongs to a dead attempt can never drive shared state behind a fresh attempt's back
	private final AtomicLong mRootConnGeneration = new AtomicLong(0);
	/** resettable readiness gates (the old CountDownLatch(1) fields could never re-arm) */
	public final StateGate mRootServiceConnected = new StateGate(false);
	public final StateGate mPreferencesInitialized = new StateGate(false);

	private ServiceConnection mCoreRootServiceConnection;
	private IRootProviderService mCoreRootService;
	private volatile XposedService mXposedService;
	private final Object mXposedServiceLock = new Object();
	private final List<XposedServiceCallback> mPendingXposedServiceCallbacks = new ArrayList<>();

	public void onCreate() {
		super.onCreate();
		instance = this;

		PRDownloaderConfig config = PRDownloaderConfig.newBuilder()
				.setReadTimeout(30_000)
				.setConnectTimeout(30_000)
				.build();
		PRDownloader.initialize(getApplicationContext(), config);

		initiatePreferences(false);

		tryConnectRootService();
		DynamicColors.applyToActivitiesIfAvailable(this);

		registerXposedServiceListener();
	}

	private void registerXposedServiceListener() {
		XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
			@Override
			public void onServiceBind(@NonNull XposedService service) {
				List<XposedServiceCallback> callbacks;
				synchronized (mXposedServiceLock) {
					mXposedService = service;
					callbacks = new ArrayList<>(mPendingXposedServiceCallbacks);
					mPendingXposedServiceCallbacks.clear();
				}
				for (XposedServiceCallback callback : callbacks) {
					mainThreadHandler.post(() -> callback.serviceReady(service));
				}
			}

			@Override
			public void onServiceDied(@NonNull XposedService service) {
				synchronized (mXposedServiceLock) {
					mXposedService = null;
				}
			}
		});
	}

	public ExtendedSharedPreferences getDefaultPreferences()
	{
		return ExtendedSharedPreferences.from(this.createDeviceProtectedStorageContext()
				.getSharedPreferences(DEFAULT_PREFS_FILE_NAME, Context.MODE_PRIVATE));
	}

	@SuppressLint("ApplySharedPref")
	public void initiatePreferences(boolean resetAll) {
		new Thread(() -> {
			//Never drop a request while another pass owns the scan: the import flow mutes the UI with
			//setPrefsValidity(false) before calling us, so silently returning there would leave every
			//fragment deaf until reboot. Wait for the running pass to publish, then take over.
			synchronized (mPrefPassLock) {
				long deadline = SystemClock.uptimeMillis() + 20_000;
				while (mPreferenceInitRunning && SystemClock.uptimeMillis() < deadline) {
					try {
						mPrefPassLock.wait(Math.max(1, deadline - SystemClock.uptimeMillis()));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
				if (mPreferenceInitRunning)
					Log.e(TAG, "Previous preference pass is stuck, proceeding anyway");
				mPreferenceInitRunning = true;
			}
			try {
				ExtendedSharedPreferences preferences = getDefaultPreferences();
				boolean initialized = preferences.getBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, false);
				int schemaVersion = preferences.getInt(ExtendedSharedPreferences.PREFS_SCHEMA_VERSION_KEY, -1);

				if (resetAll || !initialized || schemaVersion != BuildConfig.VERSION_CODE) {
					//close the gate so the splash cannot trust a stale "ready" from an earlier run, and
					//mute UI listeners before the first write: clear() plus every default stored below
					//fires a change notification each, and an unmuted pass would push a full
					//updateScreen() through the open fragment per key
					mPreferencesInitialized.close();
					setPrefsValidity(false);
					if (resetAll) preferences.edit().clear().commit();

					Class<?> xmlClass = getClassLoader().loadClass(R.xml.class.getName());
					Field[] prefPages = xmlClass.getFields();
					for (Field prefPage : prefPages)
					{
						//noinspection DataFlowIssue
						initiatePref((int) prefPage.get(null));
					}

					preferences.edit()
							.putInt(ExtendedSharedPreferences.PREFS_SCHEMA_VERSION_KEY, BuildConfig.VERSION_CODE)
							.commit();
				}
				//warm relaunch (KSU action button, quick reopen) skips the scan entirely
			} catch (Throwable t) {
				Log.e(TAG, "Failed to initialize preferences", t);
			} finally {
				//hand the UI back unconditionally, like the original code did on its normal path. Doing
				//it here as well means a scan that threw can no longer leave every fragment deaf to
				//preference changes until the next reboot, and a caller that muted us first (the import
				//flow) is not left with a permanently deaf UI either
				setPrefsValidity(true);
				//opening the gate even after a failure keeps the old behaviour of never locking the user
				//out over a missing default; the schema version was not persisted in that case, so the
				//next cold start still re-scans
				mPreferencesInitialized.open();
				//released last: no new pass may start publishing until this one is fully done
				synchronized (mPrefPassLock) {
					mPreferenceInitRunning = false;
					mPrefPassLock.notifyAll();
				}
			}
		}).start();
	}

	@SuppressLint("ApplySharedPref")
	public void setPrefsValidity(boolean valid)
	{
		getDefaultPreferences().edit().putBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, valid).commit();
	}

	private void initiatePref(int resID)
	{
		try {
			PreferenceXMLParser.setDefaultsFromXml(this, resID, getDefaultPreferences());
		}
		catch (Throwable ignored){}
	}

	/** @noinspection unused*/
	public IRootProviderService getRootService()
	{
		return mCoreRootService;
	}

	public static PixelXpert get() {
		if (instance == null) {
			throw new IllegalStateException("PixelXpert Application is not initialized");
		}
		return instance;
	}

	/** @noinspection BooleanMethodIsAlwaysInverted*/
	public boolean isCoreRootServiceBound() {
		return mCoreRootServiceBound;
	}

	public boolean hasRootAccess()
	{
		return Shell.getShell().isRoot();
	}

	public void tryConnectRootService()
	{
		if (mCoreRootServiceBound || !mRootServiceConnecting.compareAndSet(false, true)) return;
		new Thread(() -> {
			connectRootService();
		}).start();
	}

	private boolean connectRootService() {
		try {
			// Start RootService connection
			final long generation = mRootConnGeneration.incrementAndGet();
			Intent intent = new Intent(this, RootProvider.class);
			mCoreRootServiceConnection = new ServiceConnection() {
				@Override
				public void onServiceConnected(ComponentName name, IBinder service) {
					if (generation != mRootConnGeneration.get()) return;
					//publish the binder first: isCoreRootServiceBound() is the guard callers use
					//before touching getRootService(), so it must never be true while it is null
					mCoreRootService = IRootProviderService.Stub.asInterface(service);
					mCoreRootServiceBound = true;
					mRootServiceConnecting.set(false);
					mRootServiceConnected.open();
				}

				@Override
				public void onServiceDisconnected(ComponentName name) {
					if (generation != mRootConnGeneration.get()) return;
					mCoreRootServiceBound = false;
					mCoreRootService = null;
					mRootServiceConnecting.set(false);
					//re-arm the gate: a later reconnect must pass onServiceConnected again
					mRootServiceConnected.close();
				}
			};

			mainThreadHandler.post(() -> RootService.bind(intent, mCoreRootServiceConnection));

			boolean connected = mRootServiceConnected.await(10_000);
			if (!connected) {
				//abandon this attempt so a binder that shows up after the timeout cannot re-open
				//the gate behind the back of the next attempt
				mRootConnGeneration.incrementAndGet();
				mRootServiceConnecting.set(false);
			}
			return connected;
		} catch (Exception ignored) {
			mRootConnGeneration.incrementAndGet();
			mRootServiceConnecting.set(false);
			return false;
		}
	}

	public void getXposedService(XposedServiceCallback callback, boolean restartOnFail)
	{
		XposedService service;
		synchronized (mXposedServiceLock) {
			service = mXposedService;
			if (service == null && !mPendingXposedServiceCallbacks.contains(callback)) {
				mPendingXposedServiceCallbacks.add(callback);
			}
		}
		if (service != null) {
			XposedService readyService = service;
			mainThreadHandler.post(() -> callback.serviceReady(readyService));
		}
	}


	public String[] runRootCommand(String command) {
		try {
			List<String> result = Shell.cmd(command).exec().getOut();
			return result.toArray(new String[0]);
		}
		catch (Throwable t)
		{
			return new String[0];
		}
	}


	public interface XposedServiceCallback
	{
		void serviceReady(XposedService service);
	}
}
