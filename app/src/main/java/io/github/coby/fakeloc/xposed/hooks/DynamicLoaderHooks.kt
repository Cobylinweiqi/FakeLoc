package io.github.coby.fakeloc.xposed.hooks

import io.github.coby.fakeloc.xposed.HookKit

/**
 * Finds the class loader a map SDK actually lives in, and arms the channels
 * there.
 *
 * ## The problem
 *
 * A class loader is the boundary a hook cannot cross by itself. Installing
 * `AmapLocationListener.onLocationChanged` against the app's loader does nothing
 * if `AMapLocation` was resolved somewhere else: the loaded class is a *different*
 * `Class` object, and the hook is attached to a class this app never calls.
 *
 * Chinese map SDKs are routinely loaded that way. They are large, they are often
 * delivered as a dynamic feature module or downloaded after install, and the
 * host app — especially one built with a plugin framework like RePlugin or
 * Shadow — runs them in a `DexClassLoader` of its own. In that arrangement the
 * app looks completely un-hooked no matter how many hooks are installed, and the
 * symptom (this module appears to do nothing to this particular app) gives no
 * hint as to why.
 *
 * ## How it is caught
 *
 * `ClassLoader.loadClass` is the single choke point every one of those loads
 * passes through, including the ones the app performs itself. When a requested
 * name carries one of the SDK prefixes below, the loader that is doing the
 * loading is by definition the loader the SDK lives in — so it is the loader
 * [MapSdkHooks] is pointed at.
 *
 * ## What it costs
 *
 * This is the hottest hook in the module — every class load in the process goes
 * through it, and an app can load tens of thousands during startup. The body is
 * therefore ordered cheapest-first: the original call proceeds untouched, then a
 * single `startsWith` over three short prefixes rejects essentially everything.
 * Nothing is allocated on the rejecting path beyond the argument list libxposed
 * builds for every hook regardless.
 *
 * The [MapSdkHooks.install] it delegates to is idempotent per loader, so an SDK
 * that loads a hundred of its own classes arms the channels exactly once.
 */
internal object DynamicLoaderHooks {

    private const val TAG = "Loader"

    private const val CLASS_LOADER = "java.lang.ClassLoader"

    /**
     * Namespaces that identify a location SDK, and only a location SDK.
     *
     * Prefixes rather than class names: the entry point differs between SDK
     * versions (`AMapLocationClient` vs `AMapLocationClientOption`, `LocationClient`
     * vs `LocationClientOption`) and the package never does.
     */
    private val SDK_PREFIXES = arrayOf(
        "com.amap.api.location.",
        "com.baidu.location.",
        "com.tencent.map.geolocation.",
    )

    /**
     * Re-entrancy guard.
     *
     * Arming a channel resolves classes, which goes back through this hook. The
     * per-loader guard in [MapSdkHooks] already makes the nested call a no-op;
     * this only keeps that from being attempted (and logged) at every level of
     * the descent.
     *
     * A bare `ThreadLocal` rather than `ThreadLocal.withInitial`: the initial
     * value is `null`, and `get() != true` reads the same as a `false` default
     * without boxing one on every thread that touches a class load.
     */
    private val resolving = ThreadLocal<Boolean>()

    fun install() {
        val hooked = HookKit.hookAll(ClassLoader::class.java, "loadClass", TAG) { chain ->
            val forwarded = chain.proceed()

            val name = chain.args.getOrNull(0) as? String
            if (name != null && name.startsWithAny(SDK_PREFIXES) && resolving.get() != true) {
                val source = chain.thisObject as? ClassLoader
                if (source != null) arm(name, source)
            }
            forwarded
        }
        if (hooked > 0) {
            HookKit.info(TAG, "watching for map SDK class loaders ($hooked loadClass overload(s))")
        }
    }

    private fun arm(className: String, source: ClassLoader) {
        resolving.set(true)
        try {
            HookKit.info(TAG, "$className arrived via ${source.javaClass.name}; arming channels there")
            MapSdkHooks.install(source)
        } catch (t: Throwable) {
            HookKit.error(TAG, "arming channels for $className failed", t)
        } finally {
            resolving.set(false)
        }
    }

    /**
     * A plain loop rather than `any { }`: this runs on every class load in the
     * process, and the inlined loop skips the lambda allocation the stdlib
     * version needs (Kotlin does not inline `any` for an array receiver).
     */
    private fun String.startsWithAny(prefixes: Array<String>): Boolean {
        for (prefix in prefixes) {
            if (startsWith(prefix)) return true
        }
        return false
    }
}
