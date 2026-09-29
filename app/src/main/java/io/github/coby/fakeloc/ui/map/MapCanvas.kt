package io.github.coby.fakeloc.ui.map

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.coby.fakeloc.core.Coords
import io.github.coby.fakeloc.core.MapProvider

/** Zoom the picker opens at, and returns to when the user taps "recentre". */
internal const val PICKER_ZOOM = 16f

/** Closer zoom applied after picking a search result, so the target fills the view. */
internal const val SEARCH_ZOOM = 17f

/**
 * The picker's base map.
 *
 * The indirection through [MapProvider] outlived the three-provider switch it was
 * built for (see `core/MapSettings.kt`), and is kept on purpose: the provider is a
 * persisted config field, and this `when` is the one place that turns it into a
 * renderer. Re-adding a platform is an entry here plus a canvas, with no change to
 * the screen below.
 *
 * The canvas takes and returns **WGS-84**, because that is what the config stores
 * and what `android.location` carries. The platform's own datum is applied inside
 * its own canvas and nowhere else: Tencent speaks GCJ-02, and feeding a canvas the
 * wrong datum puts the pin a few hundred metres from where the user aimed, with
 * the map itself looking perfectly healthy while it happens. That is the reason
 * the footer says out loud which datum is on screen.
 *
 * The camera is driven by value, not by a handle: [targetLatitude] /
 * [targetLongitude] / [targetZoom] say *where* to look and [moveNonce] says
 * *do it again*. Two separate things make the nonce necessary — a tap on
 * "recentre" while the map already sits on the target would change nothing, and
 * Compose cannot tell that request apart from no request at all. Bumping the
 * nonce is the caller saying "move, even though the destination is the same".
 *
 * [onFailed] is not error handling for its own sake: a map SDK that will not
 * build its view must not be allowed to take the process with it. See
 * [buildMapView].
 */
@Composable
fun MapCanvas(
    provider: MapProvider,
    targetLatitude: Double,
    targetLongitude: Double,
    targetZoom: Float,
    moveNonce: Int,
    onCenterChanged: (Double, Double) -> Unit,
    onFailed: (Throwable) -> Unit,
    mapKey: String?,
    modifier: Modifier = Modifier,
) {
    when (provider) {
        MapProvider.TENCENT -> TencentCanvas(
            targetLatitude = targetLatitude,
            targetLongitude = targetLongitude,
            targetZoom = targetZoom,
            moveNonce = moveNonce,
            onCenterChanged = onCenterChanged,
            onFailed = onFailed,
            mapKey = mapKey,
            modifier = modifier,
        )
    }
}

/**
 * Runs a map SDK's view constructor, or reports why it threw.
 *
 * The reason this needs its own function is *where* the constructor runs: inside
 * Compose's change application, on the recomposer's dispatcher, where an
 * exception does not degrade anything — it is an uncaught coroutine exception and
 * the process is killed on the spot.
 *
 * Measured on the test device, 2026-09-29: `BDMapSDKException: you have not
 * supplyed the global app context info` thrown from `MapView.<init>` inside
 * `BaiduCanvas`'s factory SIGKILLed the app the instant the picker opened, with
 * nothing on screen to say why and no stack trace in the crash dialog that
 * pointed at the map. Baidu's canvas is no longer in this build, but the guard
 * stays and still wraps Tencent's constructor: the failure mode belongs to the
 * place the constructor runs, not to a particular vendor.
 *
 * `Throwable` rather than `Exception` on purpose: a missing `.so` surfaces as
 * `UnsatisfiedLinkError`, which is exactly the class of failure this guard is
 * for, and there is no such thing as a survivable error escaping into Compose's
 * change list.
 *
 * Returns `View` rather than `T` because the fallback cannot be a `T`: there is
 * no way to conjure a `MapView` that failed to construct, and lying about the
 * type would only move the failure into whoever trusts it next. Callers that
 * need the concrete type read it back off their own holder, and the release path
 * casts defensively — see the `onRelease` in the canvas.
 */
internal inline fun <T : View> buildMapView(
    context: Context,
    onFailed: (Throwable) -> Unit,
    block: () -> T,
): View = try {
    block()
} catch (failure: Throwable) {
    onFailed(failure)
    View(context)
}

/**
 * Holds the live `MapView` for the lifecycle observer and the camera effect.
 *
 * A plain object and not snapshot state, on purpose: this is read from outside
 * composition (a lifecycle callback, a `LaunchedEffect`), and a state read there
 * buys nothing while costing a recomposition on every write.
 *
 * Stays null when the view failed to build, which is what makes every
 * `holder.view?.` on the lifecycle and camera paths a no-op rather than a second
 * crash after the first one has already been reported.
 */
internal class MapViewHolder<T> {
    var view: T? = null
}

/** `WGS-84 → the datum this platform's SDK speaks`. */
internal fun toGcj02(latitude: Double, longitude: Double): Pair<Double, Double> =
    Coords.wgs84ToGcj02(latitude, longitude)

internal fun gcj02ToWgs84(latitude: Double, longitude: Double): Pair<Double, Double> =
    Coords.gcj02ToWgs84(latitude, longitude)
