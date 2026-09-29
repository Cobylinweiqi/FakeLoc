package io.github.coby.fakeloc.xposed.hooks

import io.github.coby.fakeloc.xposed.HookKit
import io.github.coby.fakeloc.xposed.HookState
import io.github.coby.fakeloc.xposed.LocationPayload
import java.lang.reflect.Method

/**
 * Hooks inside `system_server`, installed only when `system` (or `android`) is in
 * the module's LSPosed scope.
 *
 * This is the layer with real leverage over **Google Play Services**. A fused fix
 * is computed inside the GMS process from the raw provider data it is allowed to
 * read; if that raw data is already fake, the fused result is fake too, for every
 * app, with no GMS-internal hooks that would rot on the next Play Services
 * update. That is the whole reason this hook set exists.
 *
 * ## A deliberate simplification
 *
 * The upstream project this is modelled on splits `LocationProviderManager`'s
 * registration map per caller package, delivering a fake fix to matching
 * registrations and the real one to everyone else. That is a *lot* of reflective
 * surgery — reaching into `mRegistrations`, rebuilding the map, re-entering
 * `acceptLocationChange` / `executeOperation` — and any mismatch leaves the map
 * half-rewritten, which is a `system_server` crash.
 *
 * Here the payload is simply overwritten in place. The trade-off is explicit:
 * while system scope is enabled, *every* process served by these providers sees
 * the fake fix, not just the packages ticked in the manager. That is what
 * enabling system-wide interception means, and it buys a hook set that cannot
 * corrupt framework state.
 *
 * ## Why the payload shapes matter here too
 *
 * `onReportLocation` does not hand out a bare `Location`: from Android 12 on it
 * is a container object whose backing list is private. [LocationPayload] knows
 * every shape, so the same code covers a bare fix, a `List<Location>` and a
 * container, and it writes into the real fields — which is what makes the fake
 * survive being read by a native renderer rather than only by a Java getter.
 */
internal object FrameworkLocationHooks {

    private const val TAG = "Framework"

    /** Path labels for the one-shot delivery breadcrumbs in [Diag]. */
    private const val PATH_PULL = "system:getLastLocation"
    private const val PATH_PUSH = "system:onReportLocation"

    private val LOCATION_SERVICE_CLASSES = arrayOf(
        "com.android.server.location.LocationManagerService",
        "com.android.server.LocationManagerService",
    )

    /** Android 12 moved provider bookkeeping into its own package. */
    private val PROVIDER_MANAGER_CLASSES = arrayOf(
        "com.android.server.location.provider.LocationProviderManager",
        "com.android.server.location.LocationProviderManager",
    )

    private val GNSS_SERVICE_CLASSES = arrayOf(
        "com.android.server.location.gnss.GnssManagerService",
    )

    /**
     * Raw-GNSS entry points. Their data is measured by the receiver itself, so it
     * would contradict the fabricated fix and is a strong detector signal. Real
     * devices frequently report these as unsupported, so suppressing them is not
     * itself suspicious.
     */
    private val GNSS_REGISTRATIONS = arrayOf(
        "addGnssMeasurementsListener",
        "addGnssNavigationMessageListener",
        "addGnssAntennaInfoListener",
        "addGnssBatchingCallback",
        "registerGnssNmeaCallback",
        "registerGnssStatusCallback",
    )

    fun install(classLoader: ClassLoader) {
        hookLastLocation(classLoader)
        hookProviderReports(classLoader)
        suppressRawGnss(classLoader)
    }

    // --------------------------------------------------------- pull path

    private fun hookLastLocation(classLoader: ClassLoader) {
        val serviceClass = HookKit.findClass(classLoader, *LOCATION_SERVICE_CLASSES)
        if (serviceClass == null) {
            HookKit.warn(TAG, "LocationManagerService not found")
            return
        }

        HookKit.hookAllLogged(serviceClass, "getLastLocation", TAG) { chain ->
            val result = chain.proceed()
            if (HookState.active()) {
                LocationPayload.overwriteAll(result, PATH_PULL)
            }
            result
        }
    }

    // --------------------------------------------------------- push path

    private fun hookProviderReports(classLoader: ClassLoader) {
        val providerClass = HookKit.findClass(classLoader, *PROVIDER_MANAGER_CLASSES)
        if (providerClass == null) {
            // Pre-12 keeps the dispatch inside LocationManagerService; the
            // getLastLocation hook above still covers the pull side there.
            HookKit.info(TAG, "LocationProviderManager not found; push path not hooked")
            return
        }

        HookKit.hookAllLogged(providerClass, "onReportLocation", TAG) { chain ->
            if (HookState.active()) {
                chain.args.forEach { argument -> LocationPayload.overwriteAll(argument, PATH_PUSH) }
            }
            chain.proceed()
        }
    }

    // ------------------------------------------------------- raw GNSS

    private fun suppressRawGnss(classLoader: ClassLoader) {
        val serviceClass = HookKit.findClass(classLoader, *GNSS_SERVICE_CLASSES) ?: return

        for (methodName in GNSS_REGISTRATIONS) {
            val methods = HookKit.methodsNamed(serviceClass, methodName)
            if (methods.isEmpty()) continue

            HookKit.hookAllLogged(serviceClass, methodName, TAG) { chain ->
                if (!HookState.antiMockEngaged()) {
                    return@hookAllLogged chain.proceed()
                }
                val executable = chain.executable
                HookKit.defaultValueFor(executable as? Method)
            }
        }
    }
}
