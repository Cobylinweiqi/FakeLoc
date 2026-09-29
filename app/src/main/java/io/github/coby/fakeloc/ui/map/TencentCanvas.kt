package io.github.coby.fakeloc.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition
import com.tencent.tencentmap.mapsdk.maps.model.LatLng

/**
 * Tencent's base map.
 *
 * The one renderer this build ships, and the reason it is this one rather than
 * Baidu or Amap: it is the smallest by a wide margin — ~2 MB of native code
 * against Baidu's 7.4 MB and Amap's 7.7 MB — and the other two took ~6 MB of
 * tile assets with them when they were dropped on 2026-09-29.
 *
 * Its key is **not** global. Baidu read its own out of the manifest and Amap took
 * one through `MapsInitializer`, but Tencent wants it per map view, handed over
 * in [TencentMapOptions] at construction. That is why [mapKey] is a parameter
 * here, and why the `AndroidView` below sits inside a [key] on it: a view built
 * without a key keeps rendering unauthenticated for as long as it lives, so
 * pasting a key has to build a new one rather than re-use the old.
 *
 * Tencent speaks **GCJ-02**, as Amap did.
 *
 * Pinned to the 5.9.0 artifact line by the version catalog, and that pin is not
 * cosmetic — see the note there. This file compiles against 5.9.0's API, which is
 * also a different shape from 6.x's: privacy consent is
 * `TencentMapInitializer.setAgreePrivacy(boolean)` with no `Context` and no
 * separate `start` call. `MapSdkBootstrap` performs it.
 */
@Composable
internal fun TencentCanvas(
    targetLatitude: Double,
    targetLongitude: Double,
    targetZoom: Float,
    moveNonce: Int,
    onCenterChanged: (Double, Double) -> Unit,
    onFailed: (Throwable) -> Unit,
    mapKey: String?,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Rebuilt from scratch when the key changes; see the note above.
    key(mapKey) {
        val holder = remember { MapViewHolder<MapView>() }
        val center by rememberUpdatedState(onCenterChanged)
        val fail by rememberUpdatedState(onFailed)

        val targetGcj02 = remember(targetLatitude, targetLongitude) {
            val (lat, lng) = toGcj02(targetLatitude, targetLongitude)
            LatLng(lat, lng)
        }

        // Tencent's own docs drive the view through all five callbacks rather
        // than the resume/pause pair the other two need; onStart in particular
        // is where its engine begins fetching tiles.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> holder.view?.onStart()
                    Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                    Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                    Lifecycle.Event.ON_STOP -> holder.view?.onStop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // No-op on the first run; the factory does the opening camera move.
        LaunchedEffect(targetGcj02, targetZoom, moveNonce) {
            holder.view?.map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(targetGcj02, targetZoom),
            )
        }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                buildMapView(context, fail) {
                    val options = TencentMapOptions()
                    if (!mapKey.isNullOrBlank()) options.mapKey = mapKey
                    MapView(context, options).also { view ->
                        holder.view = view
                        view.map?.apply {
                            setOnCameraChangeListener(
                                object : TencentMap.OnCameraChangeListener {
                                    override fun onCameraChange(position: CameraPosition?) {
                                        position?.target?.let { target ->
                                            val (lat, lng) =
                                                gcj02ToWgs84(target.latitude, target.longitude)
                                            center(lat, lng)
                                        }
                                    }

                                    override fun onCameraChangeFinished(position: CameraPosition?) {
                                        position?.target?.let { target ->
                                            val (lat, lng) =
                                                gcj02ToWgs84(target.latitude, target.longitude)
                                            center(lat, lng)
                                        }
                                    }
                                },
                            )
                            animateCamera(
                                CameraUpdateFactory.newLatLngZoom(targetGcj02, targetZoom),
                            )
                        }
                        // Matches the resume/pause pair the observer forwards, and
                        // catches up when the screen opens into an already-resumed
                        // activity — the observer sees no event in that case.
                        view.onStart()
                        view.onResume()
                    }
                }
            },
            onRelease = { view ->
                (view as? MapView)?.let { map ->
                    runCatching {
                        map.onPause()
                        map.onStop()
                        map.onDestroy()
                    }
                }
                if (holder.view === view) holder.view = null
            },
        )
    }
}
