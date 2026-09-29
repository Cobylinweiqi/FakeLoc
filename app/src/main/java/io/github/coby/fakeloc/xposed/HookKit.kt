package io.github.coby.fakeloc.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections

/**
 * Thin toolbox shared by every hook installer.
 *
 * Why a singleton instead of threading `XposedInterface` through every
 * constructor: exactly one [XposedInterface] instance exists per process, it is
 * bound once from `ModuleEntry.onModuleLoaded`, and every installer would
 * otherwise carry the same field around. The other reason is logging — routing
 * every message through `XposedModule.log` (which lands in LSPosed's own log
 * viewer) instead of `android.util.Log` means the module's output is visible
 * without a `logcat` session.
 *
 * All reflection helpers swallow their exceptions and report `null` / `0`. A
 * hook that cannot be installed must never take the target app down with it.
 */
internal object HookKit {

    private const val ROOT_TAG = "FakeLoc"

    @Volatile
    private var moduleRef: XposedInterface? = null

    /** Process-scoped guard so a hook set is installed at most once per process. */
    private val installedKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun bind(module: XposedInterface) {
        moduleRef = module
    }

    private val module: XposedInterface?
        get() = moduleRef

    /**
     * `true` the first time [key] is seen in this process, `false` afterwards.
     * Used because libxposed may announce the same package through more than one
     * callback (`onPackageReady` and `onSystemServerStarting` both fire for
     * `system_server`, for instance).
     */
    fun firstTime(key: String): Boolean = installedKeys.add(key)

    // ------------------------------------------------------------- logging

    fun info(tag: String, message: String) = log(Log.INFO, tag, message)
    fun warn(tag: String, message: String) = log(Log.WARN, tag, message)
    fun error(tag: String, message: String) = log(Log.ERROR, tag, message)
    fun error(tag: String, message: String, throwable: Throwable) =
        log(Log.ERROR, tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}")

    fun log(priority: Int, tag: String, message: String) {
        val logger = moduleRef
        if (logger != null) {
            // LSPosed's own log — readable from the manager app, shows up as
            // "FakeLoc/SubTag".
            runCatching { logger.log(priority, "$ROOT_TAG/$tag", message) }
        } else {
            runCatching { Log.println(priority, "$ROOT_TAG/$tag", message) }
        }
    }

    // --------------------------------------------------------- class lookup

    /**
     * Returns the first of [names] that resolves, or `null`.
     *
     * AOSP moved framework internals between releases and OEMs (MIUI, EMUI,
     * OneUI) rename them again, so every hook site passes the historical names
     * in newest-first order rather than assuming one path.
     */
    fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                // Try the next candidate.
            }
        }
        return null
    }

    /** Walks the hierarchy so `@hide` fields declared in a base class are found. */
    fun findField(clazz: Class<*>, vararg names: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (name in names) {
                try {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    // next candidate / next superclass
                } catch (_: Throwable) {
                    return null
                }
            }
            current = current.superclass
        }
        return null
    }

    /** Declared-then-inherited method lookup, with an optional signature filter. */
    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                // Keep walking up.
            } catch (_: Throwable) {
                return null
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Every method called [name] on [clazz] or any of its ancestors, de-duplicated
     * by signature. Hooking all overloads is deliberate: `checkOpNoThrow` and
     * `requestLocationUpdates` each have several, and a detector that only gets
     * through on the un-hooked overload would defeat the whole exercise.
     */
    fun methodsNamed(clazz: Class<*>, name: String): List<Method> {
        val found = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = try {
                current.declaredMethods
            } catch (_: Throwable) {
                emptyArray<Method>()
            }
            for (method in declared) {
                if (method.name != name) continue
                val signature = buildString {
                    append(method.name)
                    append('(')
                    append(method.parameterTypes.joinToString(",") { it.name })
                    append(')')
                }
                found.putIfAbsent(signature, method)
            }
            current = current.superclass
        }
        return found.values.toList()
    }

    // -------------------------------------------------------------- hooking

    /**
     * Intercepts every overload of [methodName] on [clazz].
     *
     * @return how many overloads were successfully hooked. Zero is not an error:
     *   it just means this ROM does not declare the method, and the caller
     *   carries on with the hooks that did land.
     */
    fun hookAll(clazz: Class<*>, methodName: String, tag: String, hooker: Hooker): Int {
        val active = module ?: return 0
        val methods = methodsNamed(clazz, methodName)
        if (methods.isEmpty()) return 0

        var hooked = 0
        for (method in methods) {
            try {
                active.hook(method).intercept(hooker)
                hooked++
            } catch (t: Throwable) {
                error(tag, "hook ${clazz.simpleName}#$methodName failed", t)
            }
        }
        return hooked
    }

    /** [hookAll] that reports its outcome through the module log. */
    fun hookAllLogged(clazz: Class<*>, methodName: String, tag: String, hooker: Hooker) {
        val methods = methodsNamed(clazz, methodName)
        if (methods.isEmpty()) {
            warn(tag, "${clazz.simpleName}#$methodName not present on this ROM")
            return
        }
        val hooked = hookAll(clazz, methodName, tag, hooker)
        if (hooked < methods.size) {
            warn(tag, "${clazz.simpleName}#$methodName: hooked $hooked/${methods.size} overload(s)")
        } else {
            info(tag, "${clazz.simpleName}#$methodName hooked ($hooked overload(s))")
        }
    }

    /** Resolves and hooks in one step; no-op when the class is missing. */
    fun hookClass(classLoader: ClassLoader, className: String, methodName: String, tag: String, hooker: Hooker) {
        val clazz = findClass(classLoader, className) ?: run {
            warn(tag, "class $className not found")
            return
        }
        hookAllLogged(clazz, methodName, tag, hooker)
    }

    /**
     * Fabricates a return value matching [method]'s primitive return type.
     *
     * Needed when a hook wants to *suppress* a framework call (block a GNSS
     * registration, swallow a `getCurrentLocation` for a spoofed caller) and the
     * original would otherwise start a real, unsimulated data stream.
     */
    fun defaultValueFor(method: Method?): Any? = when (method?.returnType) {
        null -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
