package io.github.coby.fakeloc.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.coby.fakeloc.App
import io.github.coby.fakeloc.R
import io.github.coby.fakeloc.core.Geo
import io.github.coby.fakeloc.core.PickedAddress
import io.github.coby.fakeloc.core.SpoofConfig
import io.github.coby.fakeloc.core.moveTo
import io.github.coby.fakeloc.core.withAnchor
import io.github.coby.fakeloc.data.AppEntry
import io.github.coby.fakeloc.data.InstalledApps
import io.github.coby.fakeloc.data.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single source of truth for the manager UI.
 *
 * The config is held as a [MutableStateFlow] so Compose re-renders the instant a
 * switch flips, while the actual write to LSPosed's remote preferences happens
 * off the main thread — it is a synchronous `commit()` across a binder, and
 * running that on the UI thread would visibly stutter on a slider drag.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RemoteStore(application)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<SpoofConfig> = _config.asStateFlow()

    /** Transient messages, as string resource ids, for the snackbar. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** `true` while LSPosed's service is bound. */
    val serviceBound: StateFlow<Boolean> = App.services
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.serviceAvailable)

    private val _targetApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val targetApps: StateFlow<List<AppEntry>> = _targetApps.asStateFlow()

    private var lastLocationRequestMs = 0L

    // ------------------------------------------------------------ config

    /**
     * Applies [transform] to the config: the UI updates immediately, the persist
     * happens in the background. A failed remote write surfaces as a message
     * rather than being swallowed, because a silently unsaved toggle is
     * indistinguishable from a broken module.
     */
    fun update(transform: (SpoofConfig) -> SpoofConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next

        viewModelScope.launch(Dispatchers.IO) {
            if (!store.save(next)) {
                _messages.tryEmit(R.string.service_unbound_title)
            }
        }
    }

    fun togglePlaying() = update { it.copy(playing = !it.playing) }

    /**
     * Validates the two text fields and adopts them.
     *
     * Kept separate from [setCoordinates] because this is the explicit "保存"
     * action and deserves a confirmation, whereas presets, paste and "use real
     * GPS" already give their own feedback.
     */
    fun commitCoordinates(latitudeText: String, longitudeText: String) {
        val latitude = latitudeText.trim().toDoubleOrNull()
        val longitude = longitudeText.trim().toDoubleOrNull()
        if (latitude == null || longitude == null ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.moveTo(latitude, longitude) }
        _messages.tryEmit(R.string.toast_saved)
    }

    fun setCoordinates(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.moveTo(latitude, longitude) }
    }

    /**
     * Adopts a spot chosen on the Baidu map.
     *
     * The picker has already converted BD-09 to WGS-84, so nothing is transformed
     * here. This exists so the map flow gets its own confirmation, the way
     * presets and paste have theirs — the user just spent a minute aiming and
     * deserves to be told it stuck.
     *
     * This is also the **only** path that carries an address. The picker
     * reverse-geocodes the centre, and those strings have to travel with the
     * coordinates: the module answers the SDKs' `getCity()` with them, and an app
     * that shows a city it looked up by name needs the administrative spelling.
     */
    fun applyMapPick(latitude: Double, longitude: Double, address: PickedAddress) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.withAnchor(latitude, longitude, address) }
        _messages.tryEmit(R.string.toast_map_picked)
    }

    fun resetToDefaults() {
        _config.value = SpoofConfig()
        viewModelScope.launch(Dispatchers.IO) {
            store.clearMirror()
            store.save(_config.value)
        }
    }

    /** Pulls the config back from the store, e.g. after the service (re)binds. */
    fun reload() {
        _config.value = store.load()
    }

    // ------------------------------------------------------------ actions

    /**
     * Reads the clipboard and adopts whatever coordinate it contains. Accepts a
     * bare `"lat, lng"`, a shared map link, or a `geo:` URI — see
     * [Geo.parseCoordinates].
     */
    fun applyClipboard(text: String?) {
        val parsed = Geo.parseCoordinates(text)
        if (parsed == null) {
            _messages.tryEmit(R.string.toast_paste_failed)
            return
        }
        update { it.moveTo(parsed.first, parsed.second) }
        _messages.tryEmit(R.string.toast_paste_ok)
    }

    /**
     * Uses the device's own position as the anchor — useful for picking a spot
     * "a few hundred metres from here" without typing coordinates.
     *
     * Requires `ACCESS_FINE_LOCATION`; the caller is responsible for having
     * requested it, since a permission prompt can only be launched from an
     * Activity.
     */
    @SuppressLint("MissingPermission")
    fun useRealLocation() {
        val context = getApplication<Application>()

        val granted = ContextCompat.checkSelfPermission(
            context,
            "android.permission.ACCESS_FINE_LOCATION",
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            _messages.tryEmit(R.string.toast_location_denied)
            return
        }

        // Throttle: this starts a real fix on the radio, and a user tapping the
        // button repeatedly should not queue up several of them.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLocationRequestMs < LOCATION_THROTTLE_MS) return
        lastLocationRequestMs = now

        val manager = context.getSystemService(LocationManager::class.java) ?: run {
            _messages.tryEmit(R.string.toast_location_failed)
            return
        }

        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(context),
                ) { location: Location? ->
                    if (location == null) {
                        _messages.tryEmit(R.string.toast_location_failed)
                    } else {
                        update { it.moveTo(location.latitude, location.longitude) }
                    }
                }
            } else {
                // API 29 has no async one-shot API; the last known fix is the only
                // thing available without registering a listener.
                val last = manager.getLastKnownLocation(provider)
                if (last == null) {
                    _messages.tryEmit(R.string.toast_location_failed)
                } else {
                    update { it.moveTo(last.latitude, last.longitude) }
                }
            }
        }.onFailure {
            _messages.tryEmit(R.string.toast_location_failed)
        }
    }

    // ------------------------------------------------------- target apps

    fun loadTargetApps() {
        viewModelScope.launch {
            _targetApps.value = InstalledApps.load(
                getApplication(),
                keepPackages = _config.value.targetPackages,
            )
        }
    }

    fun toggleTargetApp(packageName: String) {
        update { config ->
            val next = config.targetPackages.toMutableSet()
            if (!next.remove(packageName)) next.add(packageName)
            config.copy(targetPackages = next)
        }
    }

    private companion object {
        const val LOCATION_THROTTLE_MS = 5_000L
    }
}
