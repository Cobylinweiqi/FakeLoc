package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import io.github.cobylinweiqi.fakeloc.xposed.Diag
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import io.github.cobylinweiqi.fakeloc.xposed.LocationPayload
import io.github.cobylinweiqi.fakeloc.xposed.MockTrace
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Consumer

/**
 * App-process hooks on `android.location.Location` and the pull-style
 * `android.location.LocationManager` APIs.
 *
 * Two layers are installed, and they cover different things:
 *
 *  * **Field getters** ([installLocationGetters]) — every app that already holds
 *    a `Location` object and reads `getLatitude()` from it. This is the layer
 *    that makes an *existing* fix wrong, rather than only future ones.
 *  * **Whole-object returns** ([installLocationManager]) — `getLastKnownLocation`
 *    and `getCurrentLocation`, which hand back an object that would otherwise
 *    carry the real coordinate straight past the getters (they cannot, since the
 *    getters are hooked too, but returning a clean fake avoids leaking the real
 *    value through fields we do not override such as `getTime()`).
 *
 * Everything is gated on [HookState.active], evaluated per call rather than at
 * install time, so the user flipping the switch in the manager takes effect
 * without restarting the target app.
 */
internal object LocationHooks {

    private const val TAG = "Location"

    /** Path labels for the one-shot delivery breadcrumbs in [Diag]. */
    private const val PATH_LAST_KNOWN = "lastKnown"
    private const val PATH_ONE_SHOT = "oneShot"

    fun install(classLoader: ClassLoader) {
        installLocationGetters(classLoader)
        installLocationManager(classLoader)
    }

    // ----------------------------------------------------- Location fields

    private fun installLocationGetters(classLoader: ClassLoader) {
        val locationClass = HookKit.findClass(classLoader, "android.location.Location")
        if (locationClass == null) {
            HookKit.error(TAG, "android.location.Location not found")
            return
        }

        // --- the two fields that are always overridden -------------------
        // No `chain.proceed()` on the hit path: the original value is exactly
        // what we are replacing, so computing it first would be wasted work on
        // the hottest hooks in the module.
        HookKit.hookAllLogged(locationClass, "getLatitude", TAG) { chain ->
            if (!HookState.active()) return@hookAllLogged chain.proceed()
            Diag.noteGetterRead()
            HookState.latitude()
        }
        HookKit.hookAllLogged(locationClass, "getLongitude", TAG) { chain ->
            if (!HookState.active()) return@hookAllLogged chain.proceed()
            HookState.longitude()
        }

        // --- optional fields, each behind its own toggle -------------------
        HookKit.hookAllLogged(locationClass, "getAccuracy", TAG) { chain ->
            val cfg = HookState.config()
            if (!cfg.useAccuracy || !HookState.active()) return@hookAllLogged chain.proceed()
            cfg.accuracy
        }
        HookKit.hookAllLogged(locationClass, "getAltitude", TAG) { chain ->
            val cfg = HookState.config()
            if (!cfg.useAltitude || !HookState.active()) return@hookAllLogged chain.proceed()
            cfg.altitude
        }
        HookKit.hookAllLogged(locationClass, "getSpeed", TAG) { chain ->
            val cfg = HookState.config()
            if (!cfg.useSpeed || !HookState.active()) return@hookAllLogged chain.proceed()
            cfg.speed
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // `getMslAltitudeMeters()` only exists from Android 14 on.
            HookKit.hookAllLogged(locationClass, "getMslAltitudeMeters", TAG) { chain ->
                val cfg = HookState.config()
                if (!cfg.useAltitude || !HookState.active()) return@hookAllLogged chain.proceed()
                cfg.altitude
            }
        }

        // --- provider name -------------------------------------------------
        // A fix delivered as provider "mock" or "<pkg>.mock" gives the game away
        // even when every coordinate is right, so unknown provider names are
        // reported as `gps`.
        HookKit.hookAllLogged(locationClass, "getProvider", TAG) { chain ->
            val provider = chain.proceed() as? String
            if (!HookState.active()) return@hookAllLogged provider
            HookState.normaliseProvider(provider)
        }

        installMockTraceProbes(locationClass)
    }

    /**
     * Hooks the four carriers of the "mock fix" bit listed in [MockTrace].
     *
     * `isMock` / `isFromMockProvider` / `setMock` / `setIsFromMockProvider` are
     * looked up by name only — none of them is public API on every supported
     * release, so they are never referenced at compile time.
     *
     * Exposed separately because `system_server` needs exactly this subset and
     * none of the `LocationManager` hooks.
     */
    fun installMockTraceProbes(classLoader: ClassLoader) {
        val locationClass = HookKit.findClass(classLoader, "android.location.Location") ?: return
        installMockTraceProbes(locationClass)
    }

    private fun installMockTraceProbes(locationClass: Class<*>) {
        HookKit.hookAllLogged(locationClass, "isFromMockProvider", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            false
        }
        HookKit.hookAllLogged(locationClass, "isMock", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            false
        }
        HookKit.hookAllLogged(locationClass, "setIsFromMockProvider", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            chain.proceed(arrayOf<Any?>(false))
        }
        HookKit.hookAllLogged(locationClass, "setMock", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            chain.proceed(arrayOf<Any?>(false))
        }

        HookKit.hookAllLogged(locationClass, "getExtras", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            MockTrace.scrubBundle(chain.proceed() as? Bundle)
        }
        HookKit.hookAllLogged(locationClass, "setExtras", TAG) { chain ->
            if (!HookState.antiMockEngaged()) return@hookAllLogged chain.proceed()
            val incoming = chain.args.getOrNull(0) as? Bundle
            chain.proceed(arrayOf<Any?>(MockTrace.scrubBundle(incoming)))
        }

        // `set(Location)` copies the whole field set — including the mock bit —
        // from the argument, undoing any scrub that already happened on a clone.
        HookKit.hookAllLogged(locationClass, "set", TAG) { chain ->
            val result = chain.proceed()
            if (HookState.antiMockEngaged()) {
                (chain.thisObject as? Location)?.let(MockTrace::scrub)
            }
            result
        }
    }

    // ---------------------------------------------------- LocationManager

    private fun installLocationManager(classLoader: ClassLoader) {
        val managerClass = HookKit.findClass(classLoader, "android.location.LocationManager")
        if (managerClass == null) {
            HookKit.error(TAG, "android.location.LocationManager not found")
            return
        }

        // The provider string is used verbatim so the fake fix looks like it came
        // from the source the app asked for.
        HookKit.hookAllLogged(managerClass, "getLastKnownLocation", TAG) { chain ->
            val original = chain.proceed() as? Location
            if (!HookState.active()) return@hookAllLogged original
            if (original != null) Diag.observeFix(PATH_LAST_KNOWN, original)
            val provider = chain.args.getOrNull(0) as? String
            HookState.buildLocation(original, provider)
        }

        // `getCurrentLocation(provider, cancelSignal, executor, consumer)` — the
        // one-shot async API that `LocationManagerCompat` and many SDKs build on.
        // The consumer is wrapped rather than the listener machinery, because a
        // one-shot callback is never unregistered, so there is no identity
        // comparison to break (unlike `removeUpdates`, see [ListenerHooks]).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HookKit.hookAllLogged(managerClass, "getCurrentLocation", TAG) { chain ->
                if (!HookState.active()) return@hookAllLogged chain.proceed()
                val args = chain.args
                val consumerIndex = args.indexOfFirst { it is Consumer<*> }
                if (consumerIndex < 0) return@hookAllLogged chain.proceed()

                val original = args[consumerIndex] as Consumer<Location>
                val rewritten = args.toMutableList()
                rewritten[consumerIndex] = wrapConsumer(original, classLoader)
                chain.proceed(rewritten.toTypedArray())
            }
        }

        installProviderFiltering(managerClass)
    }

    // -------------------------------------------------- provider visibility

    /**
     * Provider names a stock device legitimately has.
     *
     * | Name | Where it comes from |
     * |---|---|
     * | `gps`, `network`, `passive`, `fused` | AOSP, on every release |
     * | `gnss`, `nlp` | some AOSP forks and older vendor trees |
     * | `lbs` | MediaTek frameworks |
     * | `hiflp` | Qualcomm / Huawei vendor frameworks |
     * | `indoor` | indoor-positioning vendor extensions |
     *
     * The list is a *keep* list on purpose. A test provider is registered under
     * whatever name the mock app chooses — `FakeLoc`, `test`, the joystick's own
     * package name — so `LocationManager.getProviders(true)` is the one place an
     * app can see, by name alone, that a mock app is installed and selected. That
     * is a mock marker like any other, and it is the only marker that survives
     * every other scrub in this module.
     *
     * Erring towards inclusion is deliberate: hiding a vendor provider an app
     * genuinely prefers would be a functional regression, whereas a name that is
     * not on this list is overwhelmingly likely to be a test provider.
     */
    private val STANDARD_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.FUSED_PROVIDER,
        "gnss",
        "nlp",
        "lbs",
        "hiflp",
        "indoor",
    )

    /**
     * Hides test providers from every API an app can enumerate them with.
     *
     * Only the *listing* is filtered — `requestLocationUpdates` is untouched, so
     * an app that already knows a provider name keeps working, and nothing that
     * used to succeed now throws. What disappears is the ability to discover a
     * name that should not exist.
     */
    private fun installProviderFiltering(managerClass: Class<*>) {
        HookKit.hookAllLogged(managerClass, "getProviders", TAG) { chain ->
            val result = chain.proceed()
            if (!HookState.antiMockEngaged()) return@hookAllLogged result
            keepStandardProviders(result)
        }
        HookKit.hookAllLogged(managerClass, "getAllProviders", TAG) { chain ->
            val result = chain.proceed()
            if (!HookState.antiMockEngaged()) return@hookAllLogged result
            keepStandardProviders(result)
        }

        HookKit.hookAllLogged(managerClass, "getBestProvider", TAG) { chain ->
            val best = chain.proceed() as? String
            if (!HookState.antiMockEngaged() || best == null) return@hookAllLogged best
            if (best in STANDARD_PROVIDERS) best else LocationManager.GPS_PROVIDER
        }

        // Asking about a specific name is the other way to enumerate: a detector
        // that guesses cannot be allowed to get a `true`.
        for (methodName in NAMED_PROVIDER_QUERIES) {
            HookKit.hookAllLogged(managerClass, methodName, TAG) { chain ->
                val name = chain.args.firstOrNull() as? String
                if (!HookState.antiMockEngaged() || name == null || name in STANDARD_PROVIDERS) {
                    return@hookAllLogged chain.proceed()
                }
                providerAbsenceReply(methodName)
            }
        }
    }

    /** `getProvider` wants `null`, the predicates want `false`. */
    private fun providerAbsenceReply(methodName: String): Any? =
        if (methodName == "getProvider") null else false

    private val NAMED_PROVIDER_QUERIES = arrayOf(
        "getProvider",
        "hasProvider",
        "isProviderEnabled",
    )

    /** Drops every non-standard name from a provider list, or returns it as-is. */
    private fun keepStandardProviders(result: Any?): Any? {
        if (result !is List<*>) return result
        val kept = result.filter { it is String && it in STANDARD_PROVIDERS }
        return if (kept.size == result.size) result else kept
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapConsumer(original: Consumer<Location>, classLoader: ClassLoader): Consumer<Location> =
        Proxy.newProxyInstance(
            classLoader,
            arrayOf(Consumer::class.java),
            LocationConsumerHandler(original),
        ) as Consumer<Location>

    /**
     * Forwards every callback to the app's own [Consumer] after overwriting the
     * [Location] it carries.
     *
     * `equals`/`hashCode`/`toString` are answered locally so the proxy behaves
     * like a normal object rather than hitting the delegate with reflection.
     */
    private class LocationConsumerHandler(
        private val delegate: Consumer<Location>,
    ) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "equals" -> return proxy === args?.getOrNull(0)
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "FakeLocProxy($delegate)"
                "accept" -> LocationPayload.overwriteAll(args?.getOrNull(0), PATH_ONE_SHOT)
            }
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (t: java.lang.reflect.InvocationTargetException) {
                throw t.targetException ?: t
            }
        }
    }
}
