package sh.siava.pixelxpert;

import static sh.siava.pixelxpert.Constants.DEFAULT_PREFS_FILE_NAME;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dagger.hilt.android.HiltAndroidApp;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import sh.siava.pixelxpert.service.RootProvider;
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences;
import sh.siava.pixelxpert.utils.PreferenceXMLParser;

@HiltAndroidApp
public class PixelXpert extends Application {

	/** @noinspection unused*/
	public static final String TAG = "PixelXpertSingleton";
	private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());


	private static PixelXpert instance;
	private volatile boolean mCoreRootServiceBound = false;
	private final AtomicBoolean mRootServiceConnecting = new AtomicBoolean(false);
	private final AtomicBoolean mPreferenceInitInProgress = new AtomicBoolean(false);
	public final CountDownLatch mRootServiceConnected = new CountDownLatch(1);
	public final CountDownLatch mPreferencesInitialized = new CountDownLatch(1);

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
		if (!mPreferenceInitInProgress.compareAndSet(false, true)) return;
		CompletableFuture.runAsync(() -> {
			try {
				ExtendedSharedPreferences preferences = getDefaultPreferences();
				if(resetAll) preferences.edit().clear().commit();

				boolean initialized = preferences.getBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, false);
				int schemaVersion = preferences.getInt(ExtendedSharedPreferences.PREFS_SCHEMA_VERSION_KEY, -1);
				if (!resetAll && initialized && schemaVersion == BuildConfig.VERSION_CODE) return;

				if (initialized) setPrefsValidity(false);

				Class<?> xmlClass = getClassLoader().loadClass(R.xml.class.getName());
				Field[] prefPages = xmlClass.getFields();
				for (Field prefPage : prefPages)
				{
					//noinspection DataFlowIssue
					initiatePref((int) prefPage.get(null));
				}

				preferences.edit()
						.putInt(ExtendedSharedPreferences.PREFS_SCHEMA_VERSION_KEY, BuildConfig.VERSION_CODE)
						.putBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, true)
						.commit();
			} catch (Throwable t) {
				Log.e(TAG, "Failed to initialize preferences", t);
			} finally {
				mPreferenceInitInProgress.set(false);
				mPreferencesInitialized.countDown();
			}
		});
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
			Intent intent = new Intent(this, RootProvider.class);
			mCoreRootServiceConnection = new ServiceConnection() {
				@Override
				public void onServiceConnected(ComponentName name, IBinder service) {
					mCoreRootServiceBound = true;
					mCoreRootService = IRootProviderService.Stub.asInterface(service);
					mRootServiceConnecting.set(false);
					mRootServiceConnected.countDown();
				}

				@Override
				public void onServiceDisconnected(ComponentName name) {
					mCoreRootServiceBound = false;
					mCoreRootService = null;
					mRootServiceConnecting.set(false);
				}
			};

			mainThreadHandler.post(() -> RootService.bind(intent, mCoreRootServiceConnection));

			boolean connected = mRootServiceConnected.await(10, TimeUnit.SECONDS);
			if (!connected) mRootServiceConnecting.set(false);
			return connected;
		} catch (Exception ignored) {
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
