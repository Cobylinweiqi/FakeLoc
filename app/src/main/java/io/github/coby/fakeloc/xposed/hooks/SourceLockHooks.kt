package io.github.coby.fakeloc.xposed.hooks

import io.github.coby.fakeloc.xposed.Diag
import io.github.coby.fakeloc.xposed.HookKit
import io.github.coby.fakeloc.xposed.HookState

/**
 * Denies the target app every *raw* source it could use to work out a real
 * position on its own.
 *
 * ## Why this layer exists at all
 *
 * Every other hook in this module works by rewriting the fix the platform hands
 * over. That is enough only while the app actually asks the platform. A location
 * SDK that ships its own engine can, and in practice does, bypass it:
 *
 *  * it can read raw GNSS (NMEA `GGA` carries latitude and longitude verbatim,
 *    measurements and navigation messages carry everything needed for its own
 *    PVT solution);
 *  * it can build a WiFi/cell fingerprint and ask its own server where those
 *    access points are — a fix computed on somebody else's hardware, which no
 *    amount of hooking inside this process can rewrite.
 *
 * The observable symptom of both is the same and is exactly what this layer is
 * for: the map jumps to the injected position first (that came through the
 * platform, so we rewrote it) and then drifts back to the real one a few seconds
 * later once the app's own engine has an answer.
 *
 * Cutting the inputs is the only interception that holds regardless of how the
 * engine is built, and it is safe to do here because it runs in the *scoped app
 * process only*. The system's own positioning keeps working for every other app.
 *
 * ## What is deliberately left alone
 *
 * | Left intact | Why |
 * |---|---|
 * | `registerGnssStatusCallback`, `addGpsStatusListener` | satellite visibility carries no position — denying it only makes the app declare GPS unusable and fall back to network positioning, which is the opposite of the goal |
 * | `WifiManager.getConnectionInfo` | the fix is already unusable once the scan list is empty; rewriting a `WifiInfo` means fabricating BSSID/SSID strings that some callers parse, and the trade is not worth it |
 * | everything IP-derived | blocking that means blocking the network, which is the app itself |
 */
internal object SourceLockHooks {

    private const val TAG = "Lock"

    private const val WIFI_MANAGER = "android.net.wifi.WifiManager"
    private const val TELEPHONY_MANAGER = "android.telephony.TelephonyManager"
    private const val LOCATION_MANAGER = "android.location.LocationManager"

    /**
     * GNSS entry points that carry real measurements or real coordinates.
     *
     * `GnssMeasurementsEvent` and `GnssNavigationMessage` are the raw input a PVT
     * solver runs on; NMEA is the same data already decoded, with `GGA` holding
     * the position outright. All of them are boolean-returning registrations, so
     * answering `true` without calling through leaves the caller believing the
     * subscription succeeded — no exception, no retry loop, just no data.
     */
    private val RAW_GNSS_ENTRIES = arrayOf(
        "addNmeaListener",
        "registerGnssMeasurementsCallback",
        "registerGnssNavigationMessageCallback",
        "addGnssBatchingCallback",
    )

    fun install(classLoader: ClassLoader) {
        installFingerprintLock(classLoader)
        installRawGnssLock(classLoader)
    }

    // --------------------------------------------------------- fingerprint

    /**
     * Empties the two fingerprint inputs a server-side positioning request is
     * built from.
     *
     * Empty is a *legitimate* state for both — a device with WiFi scanning off
     * and no visible cells reports exactly this — so nothing downstream has to
     * special-case it, and the app cannot tell "hidden" from "nothing in range".
     */
    private fun installFingerprintLock(classLoader: ClassLoader) {
        HookKit.findClass(classLoader, WIFI_MANAGER)?.let { wifi ->
            denyReturning(wifi, "getScanResults") { emptyList<Any>() }
        }

        HookKit.findClass(classLoader, TELEPHONY_MANAGER)?.let { telephony ->
            denyReturning(telephony, "getAllCellInfo") { emptyList<Any>() }
            denyReturning(telephony, "getNeighboringCellInfo") { emptyList<Any>() }
            denyReturning(telephony, "getCellLocation") { null }
            // The callback flavour of the same query: never proceeding means the
            // callback never fires, which is what "no cell information" looks like.
            denyReturning(telephony, "requestCellInfoUpdate") { null }
        }
    }

    // ------------------------------------------------------------ raw GNSS

    private fun installRawGnssLock(classLoader: ClassLoader) {
        val manager = HookKit.findClass(classLoader, LOCATION_MANAGER) ?: run {
            HookKit.warn(TAG, "$LOCATION_MANAGER not found; raw GNSS not locked")
            return
        }

        for (entry in RAW_GNSS_ENTRIES) {
            val hooked = HookKit.hookAll(manager, entry, TAG) { chain ->
                if (!HookState.sourceLockEngaged()) return@hookAll chain.proceed()
                Diag.event(entry, "$entry denied — raw GNSS is not reaching this app")
                true
            }
            if (hooked > 0) {
                HookKit.info(TAG, "$entry denied ($hooked overload(s))")
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    /**
     * Installs a hook that answers [reply] instead of calling through, while the
     * source lock is engaged.
     *
     * [HookKit.hookAll] rather than [HookKit.hookAllLogged] on purpose: the
     * deprecated members below are absent on newer releases, and a warning for a
     * method that this ROM legitimately does not have is noise that buries the
     * warnings that matter. Presence is reported positively instead.
     */
    private inline fun denyReturning(
        clazz: Class<*>,
        methodName: String,
        crossinline reply: () -> Any?,
    ) {
        val hooked = HookKit.hookAll(clazz, methodName, TAG) { chain ->
            if (!HookState.sourceLockEngaged()) return@hookAll chain.proceed()
            Diag.event(methodName, "$methodName denied — no fingerprint is leaving this app")
            reply()
        }
        if (hooked > 0) {
            HookKit.info(TAG, "$methodName denied ($hooked overload(s))")
        }
    }
}
