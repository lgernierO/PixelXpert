package sh.siava.pixelxpert.xposed.modpacks.gboard

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolves Gboard's obfuscated flag reader and caches it per Gboard version. */
object GboardDexResolver {
    fun interface Callback {
        fun accept(method: Method?)
    }

    private const val CACHE_FILE = "pixelxpert_gboard_cache"
    private const val CACHE_VERSION = "flag_reader_version"
    private const val CACHE_METHOD = "flag_reader_method"
    data class ClipboardHooks(val loader: Method, val retention: Method)
    fun interface ClipboardCallback {
        fun accept(hooks: ClipboardHooks?)
    }
    private const val CLIPBOARD_VERSION = "clipboard_hooks_v2_version"
    private const val CLIPBOARD_LOADER = "clipboard_hooks_v2_loader"
    private const val CLIPBOARD_RETENTION = "clipboard_hooks_v2_retention"

    init {
        runCatching { System.loadLibrary("dexkit") }
    }

    @JvmStatic
    fun resolve(context: Context, classLoader: ClassLoader, callback: Callback) {
        Thread({ callback.accept(resolveFlagReader(context, classLoader)) }, "PixelXpert-GboardFlags").start()
    }

    @JvmStatic
    fun resolveClipboardHooks(context: Context, classLoader: ClassLoader, callback: ClipboardCallback) {
        Thread({ callback.accept(resolveClipboard(context, classLoader)) }, "PixelXpert-GboardClipboard").start()
    }

    private fun resolveFlagReader(context: Context, classLoader: ClassLoader): Method? = runCatching {
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val cache = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
        if (cache.getLong(CACHE_VERSION, -1L) == versionCode) {
            cache.getString(CACHE_METHOD, null)?.let { serialized ->
                validate(DexMethod(serialized).getMethodInstance(classLoader))?.let { return it }
            }
        }

        val resolved = DexKitBridge.create(classLoader, true).use { bridge ->
            val matches = bridge.findMethod {
                matcher {
                    usingStrings("Invalid flag: ")
                    returnType("java.lang.Object")
                }
            }
            if (matches.size != 1) return@use null
            matches.single().toDexMethod()
        } ?: return null

        val method = validate(resolved.getMethodInstance(classLoader)) ?: return null
        cache.edit()
            .putLong(CACHE_VERSION, versionCode)
            .putString(CACHE_METHOD, resolved.serialize())
            .apply()
        method
    }.getOrNull()

    // 两个方法共同确定当前版本可用，未匹配时不启用旧查询路径。
    private fun resolveClipboard(context: Context, classLoader: ClassLoader): ClipboardHooks? = runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val cache = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
        if (cache.getLong(CLIPBOARD_VERSION, -1L) == version) {
            val cached = runCatching cached@ {
                val loader = cache.getString(CLIPBOARD_LOADER, null) ?: return@cached null
                val retention = cache.getString(CLIPBOARD_RETENTION, null) ?: return@cached null
                val readMethod = validate(DexMethod(loader).getMethodInstance(classLoader)) ?: return@cached null
                val ttlMethod = validateRetention(DexMethod(retention).getMethodInstance(classLoader)) ?: return@cached null
                ClipboardHooks(readMethod, ttlMethod)
            }.getOrNull()
            if (cached != null) return cached
        }
        val resolved = DexKitBridge.create(classLoader, true).use { bridge ->
            val loader = bridge.findMethod {
                matcher {
                    usingStrings("timestamp DESC limit %d")
                    returnType("java.lang.Object")
                    paramTypes()
                }
            }
            val retention = bridge.findMethod {
                matcher {
                    usingStrings("Failed to parse retention hours")
                    returnType("long")
                    paramTypes("android.content.Context")
                }
            }
            if (loader.size != 1 || retention.size != 1) return@use null
            loader.single().toDexMethod() to retention.single().toDexMethod()
        } ?: return null
        val loader = validate(resolved.first.getMethodInstance(classLoader)) ?: return null
        val retention = validateRetention(resolved.second.getMethodInstance(classLoader)) ?: return null
        cache.edit().putLong(CLIPBOARD_VERSION, version)
            .putString(CLIPBOARD_LOADER, resolved.first.serialize())
            .putString(CLIPBOARD_RETENTION, resolved.second.serialize()).apply()
        ClipboardHooks(loader, retention)
    }.getOrNull()

    private fun validateRetention(method: Method): Method? = method.takeIf {
        it.returnType == Long::class.javaPrimitiveType && Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(Context::class.java))
    }

    private fun validate(method: Method): Method? {
        return method.takeIf {
            it.parameterCount == 0 &&
                it.returnType == Any::class.java &&
                !Modifier.isStatic(it.modifiers)
        }
    }
}
