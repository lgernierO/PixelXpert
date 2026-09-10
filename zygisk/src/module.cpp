#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>

// Public Zygisk API (topjohnwu, API v5)
#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ModuleBase;

static bool endsWith(const char *s, const char *suffix) {
	size_t ls = strlen(s), lf = strlen(suffix);
	return ls >= lf && strcmp(s + ls - lf, suffix) == 0;
}

/**
 * Locate the module root directory that contains this .so.
 * The .so lives at <root>/zygisk/<abi>.so, so the root is two levels up.
 */
static bool findModuleRoot(char *out, size_t outLen) {
	Dl_info info{};
	if (dladdr((void *) &findModuleRoot, &info) == 0 || info.dli_fname == nullptr)
		return false;

	char path[PATH_MAX];
	snprintf(path, sizeof(path), "%s", info.dli_fname);
	char *slash = strrchr(path, '/');
	if (!slash) return false;
	*slash = '\0';                 // .../zygisk
	slash = strrchr(path, '/');
	if (!slash) return false;
	*slash = '\0';                 // module root

	char probe[PATH_MAX];
	snprintf(probe, sizeof(probe), "%s/module.prop", path);
	if (access(probe, R_OK) != 0) return false;
	snprintf(out, outLen, "%s", path);
	return true;
}

/**
 * Locate the module APK. When Magisk mounts the module, the APK is visible
 * at /system/priv-app/PixelXpert/PixelXpert.apk inside the process; the
 * module-root copy is the fallback.
 */
static bool findModuleApk(const char *root, char *out, size_t outLen) {
	const char *candidates[] = {
			"/system/priv-app/PixelXpert/PixelXpert.apk",
	};
	for (const char *candidate : candidates) {
		if (access(candidate, R_OK) == 0) { snprintf(out, outLen, "%s", candidate); return true; }
	}
	char base[PATH_MAX];
	snprintf(base, sizeof(base), "%s/system/priv-app/PixelXpert", root);
	DIR *dir = opendir(base);
	if (dir) {
		dirent *de;
		while ((de = readdir(dir)) != nullptr) {
			if (endsWith(de->d_name, ".apk")) {
				snprintf(out, outLen, "%s/%s", base, de->d_name);
				closedir(dir);
				return true;
			}
		}
		closedir(dir);
	}
	snprintf(base, sizeof(base), "%s/system/priv-app/PixelXpert", root);
	// also try the module-root copy even if module.prop check passed above
	return false;
}

class PixelXpertZygisk : public ModuleBase {
public:
	void onLoad(Api *api, JNIEnv *env) override {
		this->api = api;
		this->env = env;
	}

	void postAppSpecialize(const AppSpecializeArgs *args) override {
		if (!args || !args->nice_name) return;
		const char *niceName = env->GetStringUTFChars(args->nice_name, nullptr);
		const bool isSelf = niceName && strncmp(niceName, "sh.siava.pixelxpert", 19) == 0;
		if (niceName) env->ReleaseStringUTFChars(args->nice_name, niceName);
		if (isSelf) return;

		static bool initialized = false;
		if (initialized) return;
		initialized = true;

		char root[PATH_MAX] = {};
		char apk[PATH_MAX] = {};
		if (!findModuleRoot(root, sizeof(root))) return;
		if (!findModuleApk(root, apk, sizeof(apk))) return;

		jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
		jmethodID getSysCL = env->GetStaticMethodID(classLoaderClass, "getSystemClassLoader",
		                                            "()Ljava/lang/ClassLoader;");
		jobject sysCL = env->CallStaticObjectMethod(classLoaderClass, getSysCL);

		jclass dexCLClass = env->FindClass("dalvik/system/DexClassLoader");
		jstring jApk = env->NewStringUTF(apk);
		jmethodID dexCtor = env->GetMethodID(dexCLClass, "<init>",
		                                     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
		jobject dexCL = env->NewObject(dexCLClass, dexCtor, jApk, nullptr, nullptr, sysCL);

		if (env->ExceptionCheck()) { env->ExceptionClear(); return; }

		jclass entryClass = static_cast<jclass>(env->CallObjectMethod(dexCL,
		                                                              env->GetMethodID(classLoaderClass, "loadClass",
		                                                                               "(Ljava/lang/String;)Ljava/lang/Class;"),
		                                                              env->NewStringUTF("sh.siava.pixelxpert.zygisk.ZygiskEntry")));
		if (env->ExceptionCheck() || entryClass == nullptr) {
			if (env->ExceptionCheck()) env->ExceptionClear();
			return;
		}

		// postAppSpecialize runs after Application is created
		jclass atClass = env->FindClass("android/app/ActivityThread");
		jmethodID currentApp = env->GetStaticMethodID(atClass, "currentApplication",
		                                              "()Landroid/app/Application;");
		jobject app = env->CallStaticObjectMethod(atClass, currentApp);
		if (!app) return;

		// attachContext first (arms the receiver registration), then init
		// (registers the lifecycle tracker).
		jmethodID attachMethod = env->GetStaticMethodID(entryClass, "attachContext",
		                                                "(Landroid/content/Context;)V");
		if (attachMethod) {
			env->CallStaticVoidMethod(entryClass, attachMethod, app);
			if (env->ExceptionCheck()) env->ExceptionClear();
		}

		jmethodID initMethod = env->GetStaticMethodID(entryClass, "init",
		                                              "(Landroid/content/Context;)V");
		if (!initMethod) return;

		env->CallStaticVoidMethod(entryClass, initMethod, app);
		if (env->ExceptionCheck()) env->ExceptionClear();
	}

private:
	Api *api = nullptr;
	JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(PixelXpertZygisk)
