package io.github.cobylinweiqi.fakeloc.xposed.hooks

import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import io.github.cobylinweiqi.fakeloc.xposed.LocationPayload

/**
 * Google Play Services hooks, for apps that read their position through
 * `FusedLocationProviderClient` instead of the platform `LocationManager`.
 *
 * The GMS SDK is compiled into the host app, so its classes are present in the
 * app's own ClassLoader and can be hooked from here. Only the two entry points
 * that actually hand a `Location` to app code are targeted:
 *
 *  * `LocationResult.getLocations()` — the batched payload behind
 *    `requestLocationUpdates(LocationRequest, LocationCallback, Looper)`;
 *  * `LocationResult.getLastLocation()` — the convenience single-fix accessor.
 *
 * There is deliberately no attempt to hook `Task` plumbing or the obfuscated
 * `com.google.android.gms.internal.location.*` classes: those names are
 * reshuffled on every GMS release, so such hooks rot within weeks. The durable
 * way to correct a fused fix is at the data source — enabling system scope puts
 * `system_server` in the module's scope, and GMS then fuses *our* coordinates
 * because the raw provider data it reads is already fake. See
 * [FrameworkLocationHooks].
 *
 * Apps without Play Services simply have no such classes, and the lookup is a
 * silent no-op.
 */
internal object PlayServicesHooks {

    private const val TAG = "Gms"

    private const val PATH = "gms"

    private const val LOCATION_RESULT = "com.google.android.gms.location.LocationResult"

    private val METHODS = arrayOf("getLocations", "getLastLocation")

    fun install(classLoader: ClassLoader) {
        val locationResult = runCatching {
            Class.forName(LOCATION_RESULT, false, classLoader)
        }.getOrNull()

        if (locationResult == null) {
            HookKit.info(TAG, "Play Services absent in ${HookState.packageName()}; skipping")
            return
        }

        for (methodName in METHODS) {
            HookKit.hookAllLogged(locationResult, methodName, TAG) { chain ->
                val result = chain.proceed()
                if (HookState.active()) {
                    LocationPayload.overwriteAll(result, PATH)
                }
                result
            }
        }
    }
}
