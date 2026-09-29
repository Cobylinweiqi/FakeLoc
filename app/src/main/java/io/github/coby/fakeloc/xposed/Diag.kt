package io.github.coby.fakeloc.xposed

import android.location.Location
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diagnostic breadcrumbs written into the LSPosed log, at most once per process
 * per event.
 *
 * ## The problem this solves
 *
 * "It shows my real position again after a few seconds" has three unrelated
 * causes that look identical from the outside:
 *
 *  1. a live-fix callback is never intercepted (wrong class name on this ROM);
 *  2. a callback *is* intercepted but its payload shape is not recognised, so
 *     the real fix is forwarded unmodified;
 *  3. the app never asks the platform at all — it computes a fix itself from raw
 *     inputs (GNSS, WiFi scans, cell towers) or gets one from its own server.
 *
 * Fixing 1 and 2 is useless if the cause is 3, and vice versa. So instead of
 * guessing, each delivery path announces itself once:
 *
 * ```
 * FakeLoc/Diag  path='listener' delivered a fix 0.0 km off the anchor
 * FakeLoc/Diag  path='listener' delivered a fix 12.4 km off the anchor
 * ```
 *
 * The first line says the path is live and the app is reading what we wrote. The
 * second says a **real** fix arrived on that path — before the rewrite, so it is
 * proof the platform still had the truth to hand out.
 *
 * The distance is logged, never the coordinate: it is enough to tell "our value"
 * from "the device's value", and a log that does not contain the user's actual
 * position is one that can be pasted into a bug report.
 */
internal object Diag {

    private const val TAG = "Diag"

    /** Radius the accuracy scale is meaningless below — "same point", not "2 m off". */
    private const val ON_ANCHOR_METERS = 25.0

    /**
     * Records that [path] handed over [location], once per process.
     *
     * `firstTime` is a synchronized set add; this runs once per delivered fix
     * (a handful per second at most), never on `getLatitude()`.
     */
    fun observeFix(path: String, location: Location) {
        if (!HookKit.firstTime("fix:$path")) return

        val cfg = HookState.config()
        val offset = distanceMeters(
            location.latitude,
            location.longitude,
            cfg.latitude,
            cfg.longitude,
        )
        val verdict = if (offset < ON_ANCHOR_METERS) {
            "on the anchor (already ours)"
        } else {
            "%.2f km off the anchor (the platform still had the real fix)".format(offset / 1000.0)
        }
        HookKit.info(TAG, "path='$path' delivered a fix $verdict")
    }

    /** Records that a raw positioning source was denied, once per process. */
    fun event(key: String, message: String) {
        if (HookKit.firstTime("event:$key")) HookKit.info(TAG, message)
    }

    /**
     * `true` the first time a Java getter read is observed.
     *
     * Kept out of [HookKit.firstTime] on purpose: this runs on
     * `Location.getLatitude()`, the hottest hook in the module, and a
     * synchronized set lookup there is not worth a diagnostic. A plain volatile
     * read costs nothing, and a lost race would only mean one missing log line.
     *
     * Its absence from the log is itself the finding: an app that never appears
     * here reads its coordinates somewhere the Java getters cannot reach — the
     * shape of a native renderer or a self-contained location engine.
     */
    fun noteGetterRead(): Boolean {
        if (getterSeen) return false
        getterSeen = true
        HookKit.info(TAG, "Location.getLatitude() is being read — the getter path is live")
        return true
    }

    @Volatile
    private var getterSeen = false

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * earthRadius * asin(min(1.0, sqrt(h)))
    }
}
