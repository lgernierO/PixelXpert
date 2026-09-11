/*
 * PixelXpert Zygisk module - LSPlant edition.
 *
 * Proven skeleton (pure-C ABI entry, /system APK location, polling thread)
 * kept from the working pure-C module; the engine path is upgraded:
 * ZygiskEntry (Java) is loaded from the module APK into every app process,
 * then LSPlant hooks ViewGroup.dispatchScrollToTop(I)Z so the MIUI-style
 * scroll engine is driven directly by the native WMS dispatch instead of
 * the broadcast fallback.
 *
 * LSPlant init: ShadowHook (inline hooks) + xdl (libart symbols incl.
 * .gnu_debugdata minisymtab on Android 15+).
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

#include <string_view>
#include <string>

#include "lsplant.hpp"
#include "shadowhook.h"
#include "xdl.h"
#include "zygisk.h"

#define LOG_TAG "PixelXpert-Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define ENGINE_CLASS "sh/siava/pixelxpert/zygisk/ZygiskEntry"
#define APP_POLL_TRIES 150
#define APP_POLL_INTERVAL_US 200000

static JavaVM *g_jvm = NULL;
static bool g_skip_process = false;

static JNIEnv *get_env(void) {
	JNIEnv *env = NULL;
	if (!g_jvm || g_jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK)
		return NULL;
	return env;
}

static bool jstr(JNIEnv *env, jstring *jstr_ptr, char *out, size_t out_len) {
	out[0] = '\0';
	if (!jstr_ptr || !*jstr_ptr) return false;
	const char *s = env->GetStringUTFChars(*jstr_ptr, NULL);
	if (!s) return false;
	size_t n = strlen(s);
	if (n >= out_len) n = out_len - 1;
	memcpy(out, s, n);
	out[n] = '\0';
	env->ReleaseStringUTFChars(*jstr_ptr, s);
	return true;
}

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
	*slash = '\0';
	slash = strrchr(path, '/');
	if (!slash) return;
	*slash = '\0';
	char probe[600];
	snprintf(probe, sizeof(probe), "%s/module.prop", path);
	if (access(probe, R_OK) != 0) return;
	snprintf(out, out_len, "%s", path);
}

/* ---- LSPlant plumbing -------------------------------------------------- */

static void *inline_hooker(void *target, void *hooker) {
	void *orig = nullptr;
	return shadowhook_hook_func_addr(target, hooker, &orig) ? orig : nullptr;
}

static bool inline_unhooker(void *func) {
	return shadowhook_unhook(func) == 0;
}

static void *art_symbol_resolver(std::string_view name) {
	static void *handle = nullptr;
	if (!handle) {
		handle = xdl_open("libart.so", XDL_TRY_FORCE_LOAD);
		if (!handle) return nullptr;
	}
	size_t size = 0;
	std::string buf(name);
	void *sym = xdl_sym(handle, buf.c_str(), &size);
	if (!sym) sym = xdl_dsym(handle, buf.c_str(), &size);
	return sym;
}

static void *art_symbol_prefix_resolver(std::string_view prefix) {
	/* xdl has no prefix iterator; LSPlant only uses prefix resolution as a
	 * fallback for mangled internals. Exact-name resolution above covers the
	 * required symbols on Android 9-17 (verified against libart dynsym +
	 * .gnu_debugdata minisymtab). */
	(void) prefix;
	return nullptr;
}

static bool lsplant_init(JNIEnv *env) {
	lsplant::InitInfo info{
			.inline_hooker = inline_hooker,
			.inline_unhooker = inline_unhooker,
			.art_symbol_resolver = art_symbol_resolver,
			.art_symbol_prefix_resolver = art_symbol_prefix_resolver,
	};
	if (!lsplant::Init(env, info)) {
		LOGE("lsplant Init failed");
		return false;
	}
	return true;
}

/**
 * Hook ViewGroup.dispatchScrollToTop(I)Z so the native WMS dispatch drives
 * the scroll engine. Callback signature per LSPlant: the generated stub
 * calls callback_method(hooker_object, Object[]{this, args...}) and expects
 * Object back (boxed boolean for a boolean method).
 */
static bool hook_dispatch_scroll_to_top(JNIEnv *env, jobject entry_class_ref) {
	jclass viewGroupClass = env->FindClass("android/view/ViewGroup");
	if (!viewGroupClass) { if (env->ExceptionCheck()) env->ExceptionClear(); return false; }
	jmethodID target = env->GetMethodID(viewGroupClass, "dispatchScrollToTop", "(I)Z");
	if (!target) { if (env->ExceptionCheck()) env->ExceptionClear(); return false; }

	jclass entryClass = static_cast<jclass>(entry_class_ref);
	// LSPlant requires hooker_object instanceof the callback's declaring
	// class - use a ZygiskEntry instance (default constructor).
	jmethodID entryCtor = env->GetMethodID(entryClass, "<init>", "()V");
	jobject hooker_object = entryCtor ? env->NewObject(entryClass, entryCtor) : NULL;
	if (!hooker_object) { if (env->ExceptionCheck()) env->ExceptionClear(); return false; }

	jmethodID callback = env->GetStaticMethodID(entryClass, "onDispatchScrollToTop",
	                                            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
	if (!callback) { if (env->ExceptionCheck()) env->ExceptionClear(); return false; }

	jobject backup = lsplant::Hook(env, (jobject) target, hooker_object, (jobject) callback);
	if (!backup) return false;
	return true;
}

/* ---- Engine loading (proven skeleton) ---------------------------------- */

static void *engine_thread(void *arg) {
	char *apk_path = (char *) arg;

	JavaVMAttachArgs aargs;
	memset(&aargs, 0, sizeof(aargs));
	aargs.version = JNI_VERSION_1_6;
	aargs.name = "PixelXpert-Zygisk";
	JNIEnv *env = NULL;
	bool attached = false;
	if (g_jvm && g_jvm->AttachCurrentThread(&env, &aargs) == JNI_OK)
		attached = true;
	if (!env) {
		LOGE("AttachCurrentThread failed");
		free(apk_path);
		return NULL;
	}

	/* Wait for the Application object to be created. */
	jobject app = NULL;
	for (int i = 0; i < APP_POLL_TRIES; i++) {
		jclass at_class = env->FindClass("android/app/ActivityThread");
		if (!at_class) { env->ExceptionClear(); break; }
		jmethodID cur_app = env->GetStaticMethodID(at_class,
		                                              "currentApplication",
		                                              "()Landroid/app/Application;");
		if (!cur_app) { env->ExceptionClear(); break; }
		app = env->CallStaticObjectMethod(at_class, cur_app);
		if (env->ExceptionCheck()) { env->ExceptionClear(); app = NULL; }
		if (app) break;
		usleep(APP_POLL_INTERVAL_US);
	}
	if (!app) {
		LOGE("application never appeared for %s", apk_path);
		if (attached) g_jvm->DetachCurrentThread();
		free(apk_path);
		return NULL;
	}

	jclass cl_class = env->FindClass("java/lang/ClassLoader");
	jmethodID get_sys = cl_class ? env->GetStaticMethodID(cl_class, "getSystemClassLoader",
	                                              "()Ljava/lang/ClassLoader;") : NULL;
	jobject sys_cl = get_sys ? env->CallStaticObjectMethod(cl_class, get_sys) : NULL;

	jclass dex_class = env->FindClass("dalvik/system/DexClassLoader");
	jobject dex_cl = NULL;
	if (dex_class && sys_cl) {
		jmethodID dex_ctor = env->GetMethodID(dex_class, "<init>",
		                                         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
		jstring j_apk = env->NewStringUTF(apk_path);
		if (dex_ctor && j_apk) {
			dex_cl = env->NewObject(dex_class, dex_ctor, j_apk, NULL, NULL, sys_cl);
			if (env->ExceptionCheck()) { env->ExceptionClear(); dex_cl = NULL; }
		}
	}
	jclass entry = NULL;
	if (dex_cl && cl_class) {
		jmethodID load_class = env->GetMethodID(cl_class, "loadClass",
		                                           "(Ljava/lang/String;)Ljava/lang/Class;");
		jstring j_entry = env->NewStringUTF(ENGINE_CLASS);
		if (load_class && j_entry) {
			entry = (jclass) env->CallObjectMethod(dex_cl, load_class, j_entry);
			if (env->ExceptionCheck()) { env->ExceptionClear(); entry = NULL; }
		}
	}
	if (!entry) {
		LOGE("engine load failed from %s", apk_path);
		if (app) env->DeleteLocalRef(app);
		if (attached) g_jvm->DetachCurrentThread();
		free(apk_path);
		return NULL;
	}

	jmethodID attach = env->GetStaticMethodID(entry, "attachContext",
	                                             "(Landroid/content/Context;)V");
	if (attach) {
		env->CallStaticVoidMethod(entry, attach, app);
		if (env->ExceptionCheck()) env->ExceptionClear();
	}

	/* LSPlant init + hook must run with a valid JNIEnv; this worker thread
	 * is fine. Hook failure is non-fatal: the broadcast engine still works. */
	if (lsplant_init(env)) {
		if (hook_dispatch_scroll_to_top(env, entry)) {
			LOGI("lsplant hook installed");
		} else {
			LOGE("lsplant hook failed (broadcast fallback stays active)");
		}
	}

	jmethodID init = env->GetStaticMethodID(entry, "init",
	                                           "(Landroid/content/Context;)V");
	if (init) {
		env->CallStaticVoidMethod(entry, init, app);
		if (env->ExceptionCheck()) env->ExceptionClear();
	}
	LOGI("engine loaded from %s", apk_path);
	if (attached) g_jvm->DetachCurrentThread();
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
	if (strncmp(name, "sh.siava.pixelxpert", 19) == 0 ||
	    strcmp(name, "com.android.systemui") == 0) {
		g_skip_process = true;
	}
}

static void post_app(void *impl, const struct zygisk_app_specialize_args *args) {
	(void) impl;
	if (g_skip_process) return;

	char root[512];
	find_module_root(root, sizeof(root));
	char apk[600];
	if (!find_module_apk(root, apk, sizeof(apk))) {
		LOGE("module APK not found");
		return;
	}
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
	g_skip_process = true;
}

static void post_server(void *impl, const struct zygisk_server_specialize_args *args) {
	(void) impl; (void) args;
}

static struct zygisk_module_abi g_abi;

__attribute__((visibility("default")))
void zygisk_module_entry(struct zygisk_api *api, JNIEnv *env) {
	if (!api || !env) return;
	if (env->GetJavaVM(&g_jvm) != JNI_OK) return;

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
