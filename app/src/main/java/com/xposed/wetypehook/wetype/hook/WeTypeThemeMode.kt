package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import com.xposed.wetypehook.wetype.graphics.WeTypeColorOsMaterial
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the active keyboard skin, including the host's manual light/dark override. */
internal object WeTypeThemeMode {
    private const val TAG = "MIUIIME.Theme"
    private const val HOST_SKIN_PACKAGE = "com.tencent.wetype.plugin.hld.skin."
    private const val MANAGER_CACHE_PREFIX = "com.xposed.wetypehook.com.tencent.wetype.skin"
    private var installedClassLoader: ClassLoader? = null
    private var installedSourceIdentity: String? = null
    private var installedProvider: (() -> Boolean?)? = null
    private var colorOsSearchSkin: SearchSkin? = null

    @Volatile private var originalSearchSkin: Int? = null

    @Synchronized
    fun install(classLoader: ClassLoader, sourceDir: String) {
        val source = File(sourceDir)
        val sourceIdentity = "$sourceDir\n${source.length()}\n${source.lastModified()}"
        if (installedClassLoader === classLoader && installedSourceIdentity == sourceIdentity) {
            // Settings cleanup may have detached this provider without retiring this object.
            WeTypeSettings.setHostDarkModeProvider(installedProvider)
            return
        }
        installedClassLoader = classLoader
        installedSourceIdentity = sourceIdentity
        installedProvider = null
        colorOsSearchSkin = null
        WeTypeSettings.setHostDarkModeProvider(null)

        val cacheKey = "$MANAGER_CACHE_PREFIX.${Integer.toHexString(sourceDir.hashCode())}"
        val accessor = runCatching {
            readCachedAccessor(classLoader, cacheKey, sourceIdentity)?.let { return@runCatching it }
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                // The log tag identifies the manager without depending on obfuscated names.
                val manager = bridge.findMethod {
                    matcher {
                        returnType = String::class.java.name
                        paramCount = 0
                        usingStrings("ImeSkinManager")
                    }
                }.mapNotNull { result ->
                    runCatching { result.getMethodInstance(classLoader) }.getOrNull()
                }.filter { method ->
                    method.name == "getTAG" &&
                        method.declaringClass.name.startsWith(HOST_SKIN_PACKAGE)
                }.map { it.declaringClass }.distinct().singleOrNull()
                    ?: error("Could not uniquely identify the host skin manager")

                val resolved = resolveDarkModeAccessor(manager)
                    ?: error("Could not uniquely identify the host skin mode accessor")
                // Only strings cross module generations. This avoids retaining the old loader
                // or reopening its native library while Android has not unloaded it yet.
                runCatching { System.setProperty(cacheKey, "$sourceIdentity\n${manager.name}") }
                resolved
            }
        }.onFailure {
            Log.w(TAG, "Host skin mode unavailable; using resource configuration", it)
        }.getOrNull() ?: return

        val failed = AtomicBoolean(false)
        val reading = ThreadLocal<Boolean>()
        val provider: () -> Boolean? = {
            if (failed.get() || reading.get() == true) {
                null
            } else {
                reading.set(true)
                try {
                    runCatching { accessor.invoke(null) as Boolean }.getOrElse {
                        // A broken host accessor must not throw or log on every resource read.
                        if (failed.compareAndSet(false, true)) {
                            Log.w(TAG, "Host skin mode read failed; using resource configuration", it)
                        }
                        null
                    }
                } finally {
                    // Initializing a host skin manager may itself resolve a themed resource.
                    reading.remove()
                }
            }
        }
        installedProvider = provider
        if (WeTypeColorOsMaterial.isSupported) {
            colorOsSearchSkin = runCatching { SearchSkin(accessor.declaringClass) }
                .onFailure { Log.w(TAG, "ColorOS search skin unavailable", it) }.getOrNull()
        }
        WeTypeSettings.setHostDarkModeProvider(provider)
        Log.i(TAG, "Resolved host skin mode from ${accessor.declaringClass.name}")
    }

    fun isPreferredDarkMode(resources: Resources): Boolean {
        // The saved toggle belongs to the selected skin, even while the search editor uses dark colors.
        originalSearchSkin?.let { return it == colorOsSearchSkin?.darkIndex }
        return WeTypeSettings.isHostDarkMode(resources)
    }

    fun updateColorOsSearchSkin(useDark: Boolean, configuration: Configuration? = null) {
        val skin = colorOsSearchSkin ?: return
        if (!useDark && originalSearchSkin == null) return
        runCatching {
            val manager = skin.manager.invoke(null) ?: return
            val current = skin.current.invoke(manager) as Int
            if (useDark) {
                if (current == skin.darkIndex) return
                originalSearchSkin = current
                skin.change.invoke(manager, skin.darkIndex)
            } else {
                val original = originalSearchSkin ?: return
                if (current == skin.darkIndex) skin.restore(manager, original, configuration)
                originalSearchSkin = null
            }
        }.onFailure { Log.w(TAG, "Could not update the ColorOS search skin", it) }
    }

    private class SearchSkin(type: Class<*>) {
        private val configurationHandler = type.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterCount == 2 && method.parameterTypes[1] == Configuration::class.java
        }.apply { isAccessible = true }
        private val owner = type.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == type }
            .apply { isAccessible = true }
        private val managerType = configurationHandler.parameterTypes[0]
        val manager: Method = type.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) && method.parameterCount == 0 && method.returnType == managerType
        }.apply { isAccessible = true }
        val current: Method = managerType.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                method.returnType == Int::class.javaPrimitiveType
        }.apply { isAccessible = true }
        val change: Method = managerType.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.apply { isAccessible = true }

        val darkIndex: Int = type.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 && method.returnType.isEnum
        }.returnType.let { skin ->
            val dark = checkNotNull(skin.enumConstants).single { (it as Enum<*>).name == "SKIN_DARK" }
            skin.declaredMethods.single { method ->
                !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }.invoke(dark) as Int
        }

        fun restore(target: Any, original: Int, configuration: Configuration?) {
            // Re-evaluate the host's preference after a system or manual theme change in Search.
            if (configuration != null && runCatching {
                    configurationHandler.invoke(owner.get(null), target, configuration)
                }.isSuccess) return
            change.invoke(target, original)
        }
    }

    private fun readCachedAccessor(
        classLoader: ClassLoader,
        cacheKey: String,
        sourceIdentity: String
    ): Method? = runCatching {
        val entry = System.getProperty(cacheKey) ?: return@runCatching null
        if (entry.substringBeforeLast('\n') != sourceIdentity) return@runCatching null
        val className = entry.substringAfterLast('\n')
        if (!className.startsWith(HOST_SKIN_PACKAGE)) return@runCatching null
        resolveDarkModeAccessor(Class.forName(className, false, classLoader))
    }.getOrNull()

    private fun resolveDarkModeAccessor(manager: Class<*>): Method? {
        // The manager owns the light, dark, and current skin themes. Requiring those
        // accessors excludes unrelated classes that happen to reuse its log tag.
        val methods = manager.declaredMethods
        val hasTagGetter = methods.any { method ->
            method.name == "getTAG" && method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java && !Modifier.isStatic(method.modifiers)
        }
        if (!hasTagGetter) return null
        val ownsSkinThemes = methods.count { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Resources.Theme::class.java
        } >= 2
        if (!ownsSkinThemes) return null

        return methods.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }
    }
}
