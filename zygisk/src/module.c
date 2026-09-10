/*
 * PixelXpert Zygisk module - pure C implementation.
 *
 * Based on the proven-loadable Zygisk-Loader pattern (C struct ABI
 * registration, no C++ runtime, no templates). Loads the Java engine
 * (sh.siava.pixelxpert.zygisk.ZygiskEntry) from the module APK into every
 * app process, enabling status-bar tap-to-top without LSPosed scope.
 *
 * Two constraints handled here:
 *  - Zygisk Next loads the .so through its builtin linker from a memfd,
 *    so dladdr() cannot resolve the module root; the engine APK is taken
 *    from the Magisk-mounted /system path instead (world-readable).
 *  - postAppSpecialize runs before the Java Application object exists,
 *    so engine loading happens on a worker thread that polls
 *    ActivityThread.currentApplication() until it is available.
 */
#include <jni.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <dlfcn.h>
#include <pthread.h>
#include <android/log.h>
#include "zygisk.h"

#define LOG_TAG "PixelXpert-Zygisk"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define ENGINE_CLASS "sh/siava/pixelxpert/zygisk/ZygiskEntry"
#define APP_POLL_TRIES 150   /* 150 * 200ms = 30s max wait for Application */
#define APP_POLL_INTERVAL_US 200000

static JavaVM *g_jvm = NULL;
static bool g_skip_process = false;

static JNIEnv *get_env(void) {
	JNIEnv *env = NULL;
	if (!g_jvm || (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
		return NULL;
	return env;
}

static bool jstr(JNIEnv *env, jstring *jstr_ptr, char *out, size_t out_len) {
	out[0] = '\0';
	if (!jstr_ptr || !*jstr_ptr) return false;
	const char *s = (*env)->GetStringUTFChars(env, *jstr_ptr, NULL);
	if (!s) return false;
	size_t n = strlen(s);
	if (n >= out_len) n = out_len - 1;
	memcpy(out, s, n);
	out[n] = '\0';
	(*env)->ReleaseStringUTFChars(env, *jstr_ptr, s);
	return true;
}

/**
 * Locate the module APK.
 * 1) Magisk-mounted priv-app copy: world-readable, works from app UIDs
 *    even when the .so was loaded from a memfd by the builtin linker.
 * 2) Module-root copy: only usable when dladdr() resolves the real path
 *    (plain Magisk loads); best effort, app UIDs usually cannot read it.
 */
static bool find_module_apk(const char *root, char *out, size_t out_len) {
	static const char *mounted = "/system/priv-app/PixelXpert/PixelXpert.apk";
	if (access(mounted, R_OK) == 0) {
		snprintf(out, out_len, "%s", mounted);
		return true;
	}
	if (!root[0]) return false;
	char base[600];
	snprintf(base, sizeof(base), "%s/system/priv-app/PixelXpert", root);
	DIR *dir = opendir(base);
	if (!dir) return false;
	struct dirent *de;
	while ((de = readdir(dir)) != NULL) {
		size_t n = strlen(de->d_name);
		if (n > 4 && strcmp(de->d_name + n - 4, ".apk") == 0) {
			snprintf(out, out_len, "%s/%s", base, de->d_name);
			closedir(dir);
			return true;
		}
	}
	closedir(dir);
	return false;
}

/** Best-effort module root via dladdr; empty string when unavailable. */
static void find_module_root(char *out, size_t out_len) {
	out[0] = '\0';
	Dl_info info;
	memset(&info, 0, sizeof(info));
	if (dladdr((void *) &find_module_root, &info) == 0 || !info.dli_fname)
		return;
	char path[512];
	snprintf(path, sizeof(path), "%s", info.dli_fname);
	char *slash = strrchr(path, '/');
	if (!slash) return;
	*slash = '\0';                       // .../zygisk
	slash = strrchr(path, '/');
	if (!slash) return;
	*slash = '\0';                       // module root
	char probe[600];
	snprintf(probe, sizeof(probe), "%s/module.prop", path);
	if (access(probe, R_OK) != 0) return;  // app UIDs cannot read /data/adb
	snprintf(out, out_len, "%s", path);
}

/**
 * Load ZygiskEntry from the module APK and hand it the Application context.
 * Must run on a worker thread: postAppSpecialize fires before the Java
 * Application exists, so poll currentApplication() until it appears.
 */
static void *engine_thread(void *arg) {
	char *apk_path = arg;

	JavaVMAttachArgs aargs;
	memset(&aargs, 0, sizeof(aargs));
	aargs.version = JNI_VERSION_1_6;
	aargs.name = "PixelXpert-Zygisk";
	JNIEnv *env = NULL;
	bool attached = false;
	if (g_jvm && (*g_jvm)->AttachCurrentThread(g_jvm, &env, &aargs) == JNI_OK)
		attached = true;
	if (!env) {
		LOGE("AttachCurrentThread failed");
		free(apk_path);
		return NULL;
	}

	/* Wait for the Application object to be created. */
	jobject app = NULL;
	for (int i = 0; i < APP_POLL_TRIES; i++) {
		jclass at_class = (*env)->FindClass(env, "android/app/ActivityThread");
		if (!at_class) { (*env)->ExceptionClear(env); break; }
		jmethodID cur_app = (*env)->GetStaticMethodID(env, at_class,
		                                              "currentApplication",
		                                              "()Landroid/app/Application;");
		if (!cur_app) { (*env)->ExceptionClear(env); break; }
		app = (*env)->CallStaticObjectMethod(env, at_class, cur_app);
		if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); app = NULL; }
		if (app) break;
		usleep(APP_POLL_INTERVAL_US);
	}
	if (!app) {
		LOGE("application never appeared for %s", apk_path);
		if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
		free(apk_path);
		return NULL;
	}

	jclass cl_class = (*env)->FindClass(env, "java/lang/ClassLoader");
	if (!cl_class) goto fail;
	jmethodID get_sys = (*env)->GetStaticMethodID(env, cl_class, "getSystemClassLoader",
	                                              "()Ljava/lang/ClassLoader;");
	if (!get_sys) goto fail;
	jobject sys_cl = (*env)->CallStaticObjectMethod(env, cl_class, get_sys);
	if (!sys_cl) goto fail;

	jclass dex_class = (*env)->FindClass(env, "dalvik/system/DexClassLoader");
	if (!dex_class) goto fail;
	jmethodID dex_ctor = (*env)->GetMethodID(env, dex_class, "<init>",
	                                         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
	if (!dex_ctor) goto fail;

	jstring j_apk = (*env)->NewStringUTF(env, apk_path);
	if (!j_apk) goto fail;
	jobject dex_cl = (*env)->NewObject(env, dex_class, dex_ctor, j_apk, NULL, NULL, sys_cl);
	if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto fail; }
	if (!dex_cl) goto fail;

	jmethodID load_class = (*env)->GetMethodID(env, cl_class, "loadClass",
	                                           "(Ljava/lang/String;)Ljava/lang/Class;");
	if (!load_class) goto fail;
	jstring j_entry = (*env)->NewStringUTF(env, ENGINE_CLASS);
	if (!j_entry) goto fail;
	jclass entry = (*env)->CallObjectMethod(env, dex_cl, load_class, j_entry);
	if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto fail; }
	if (!entry) goto fail;

	jmethodID attach = (*env)->GetStaticMethodID(env, entry, "attachContext",
	                                             "(Landroid/content/Context;)V");
	if (attach) {
		(*env)->CallStaticVoidMethod(env, entry, attach, app);
		if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	}
	jmethodID init = (*env)->GetStaticMethodID(env, entry, "init",
	                                           "(Landroid/content/Context;)V");
	if (init) {
		(*env)->CallStaticVoidMethod(env, entry, init, app);
		if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	}
	LOGI("engine loaded from %s", apk_path);
	(*env)->DeleteLocalRef(env, app);
	if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
	free(apk_path);
	return NULL;

fail:
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	LOGE("engine load failed from %s", apk_path);
	if (app) (*env)->DeleteLocalRef(env, app);
	if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
	free(apk_path);
	return NULL;
}

static void pre_app(void *impl, struct zygisk_app_specialize_args *args) {
	(void) impl;
	g_skip_process = false;
	JNIEnv *env = get_env();
	if (!env || !args) return;

	char name[256];
	if (!jstr(env, args->nice_name, name, sizeof(name))) return;
	// Skip our own processes and system UI (SystemUI has the LSPosed engine;
	// double-scrolling there is harmless but wasteful).
	if (strncmp(name, "sh.siava.pixelxpert", 19) == 0 ||
	    strcmp(name, "com.android.systemui") == 0) {
		g_skip_process = true;
	}
}

static void post_app(void *impl, const struct zygisk_app_specialize_args *args) {
	(void) impl;
	if (g_skip_process) return;

	char root[512];
	find_module_root(root, sizeof(root));   // best effort; empty under ZN
	char apk[600];
	if (!find_module_apk(root, apk, sizeof(apk))) {
		LOGE("module APK not found");
		return;
	}
	/* Application does not exist yet here - load on a polling thread. */
	char *apk_dup = strdup(apk);
	if (!apk_dup) return;
	pthread_t tid;
	if (pthread_create(&tid, NULL, engine_thread, apk_dup) != 0) {
		free(apk_dup);
		LOGE("pthread_create failed");
	} else {
		pthread_detach(tid);
	}
}

static void pre_server(void *impl, struct zygisk_server_specialize_args *args) {
	(void) impl; (void) args;
	// system_server does not need the engine.
	g_skip_process = true;
}

static void post_server(void *impl, const struct zygisk_server_specialize_args *args) {
	(void) impl; (void) args;
}

static struct zygisk_module_abi g_abi;

__attribute__((visibility("default")))
void zygisk_module_entry(struct zygisk_api *api, JNIEnv *env) {
	if (!api || !env) return;
	if ((*env)->GetJavaVM(env, &g_jvm) != JNI_OK) return;

	memset(&g_abi, 0, sizeof(g_abi));
	g_abi.api_version = ZYGISK_API_VERSION;
	g_abi.impl = api;
	g_abi.preAppSpecialize = pre_app;
	g_abi.postAppSpecialize = post_app;
	g_abi.preServerSpecialize = pre_server;
	g_abi.postServerSpecialize = post_server;

	if (!api->registerModule(api, &g_abi)) {
		LOGE("registerModule failed");
	}
}

__attribute__((visibility("default")))
void zygisk_companion_entry(int client) {
	if (client >= 0) close(client);
}
