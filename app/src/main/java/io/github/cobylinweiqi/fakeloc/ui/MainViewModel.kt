package io.github.cobylinweiqi.fakeloc.ui

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
import io.github.cobylinweiqi.fakeloc.App
import io.github.cobylinweiqi.fakeloc.R
import io.github.cobylinweiqi.fakeloc.core.Geo
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.PickedAddress
import io.github.cobylinweiqi.fakeloc.core.SavedPlace
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import io.github.cobylinweiqi.fakeloc.core.goToPlace
import io.github.cobylinweiqi.fakeloc.core.moveTo
import io.github.cobylinweiqi.fakeloc.core.recordingAnchor
import io.github.cobylinweiqi.fakeloc.core.togglePinned
import io.github.cobylinweiqi.fakeloc.core.withAnchor
import io.github.cobylinweiqi.fakeloc.core.withResolvedAddress
import io.github.cobylinweiqi.fakeloc.data.RemoteStore
import io.github.cobylinweiqi.fakeloc.mapsdk.MapSdkBootstrap
import io.github.cobylinweiqi.fakeloc.ui.map.tencentReverseGeocode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * What the address resolver is doing, for the one line under the coordinates.
 *
 * Four states rather than a boolean because the three ways an anchor can end up
 * without an address need three different things from the user, and collapsing
 * them is what made this failure mode read as "the module is broken" for an
 * entire afternoon:
 *
 *  - [RESOLVING] — a lookup is in flight; wait, there is nothing to fix.
 *  - [FAILED] — the key was sent and refused, or the network was down. The
 *    address is the user's to retry, and one tap does it.
 *  - [NO_KEY] — nothing was ever sent. No amount of retrying helps; the key is
 *    the missing piece and it lives in the settings page.
 *  - [SETTLED] — either the address is known, or the user turned address
 *    sync off and does not want a lookup at all.
 */
enum class AddressState {
    SETTLED,
    RESOLVING,
    FAILED,
    NO_KEY,
}

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

    private var lastLocationRequestMs = 0L

    private val _addressState = MutableStateFlow(AddressState.SETTLED)

    /** Drives the single line under the coordinates on the home screen. */
    val addressState: StateFlow<AddressState> = _addressState.asStateFlow()

    /** The lookup in flight, cancelled whenever the anchor moves again. */
    private var addressJob: Job? = null

    init {
        // A configuration can arrive without an address for a dozen reasons that
        // all predate this code — a hand-typed pair, a preset, a place recorded
        // from the history before anyone resolved its address. Starting here
        // means such a blob repairs itself the next time the manager is opened,
        // instead of needing the user to remember which screen fixes it.
        scheduleAddress()
    }

    // ------------------------------------------------------------ config

    /**
     * Applies [transform] to the config: the UI updates immediately, the persist
     * happens in the background. A failed remote write surfaces as a message
     * rather than being swallowed, because a silently unsaved toggle is
     * indistinguishable from a broken module.
     */
    fun update(transform: (SpoofConfig) -> SpoofConfig) {
        val before = _config.value
        val transformed = transform(before)
        if (transformed == before) return

        // The history is written here, at the one chokepoint every anchor change
        // passes through, rather than in each of the six paths that can move it.
        // A path added later would otherwise move the anchor without leaving a
        // trace — and a history that silently skips one route is worse than none,
        // because the user trusts it to hold the spot they just used.
        val next = if (
            transformed.latitude != before.latitude ||
            transformed.longitude != before.longitude
        ) {
            transformed.recordingAnchor()
        } else {
            transformed
        }
        _config.value = next

        // Same reasoning as the history write above: one chokepoint. Every path
        // that moves the anchor without an address — presets, paste, typed
        // coordinates, "use real GPS" — passes through here, so the resolver
        // does not have to be remembered at six call sites. A path that lost its
        // address and one that never had it look identical from here, and both
        // are repaired the same way.
        if (next.latitude != before.latitude || next.longitude != before.longitude) {
            scheduleAddress()
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (!store.save(next)) {
                _messages.tryEmit(R.string.service_unbound_title)
            }
        }
    }

    /** Re-selects a place from the history, address and all. */
    fun pickSavedPlace(place: SavedPlace) {
        update { it.goToPlace(place) }
        _messages.tryEmit(R.string.toast_place_applied)
    }

    /**
     * Pins or unpins a saved place.
     *
     * Separate from [pickSavedPlace] on purpose: pinning must not move the
     * anchor. Pinning the place you are standing on while comparing it with
     * another is a reasonable thing to do, and a star that also teleported the
     * map would make that impossible.
     */
    fun togglePinned(latitude: Double, longitude: Double) {
        update { it.togglePinned(latitude, longitude) }
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
     * Adopts a spot chosen on the Tencent map.
     *
     * The picker has already converted GCJ-02 to WGS-84, so nothing is transformed
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
        // The saved places survive a reset. "Reset" means the spoofing
        // parameters, not the user's own history — and a reset that silently
        // deleted the office they had pinned would be the last thing they ever
        // used this button for.
        _config.value = SpoofConfig(places = _config.value.places)
        viewModelScope.launch(Dispatchers.IO) {
            store.clearMirror()
            store.save(_config.value)
        }
    }

    /** Pulls the config back from the store, e.g. after the service (re)binds. */
    fun reload() {
        _config.value = store.load()
    }

    // ------------------------------------------------------------ address

    /**
     * Makes sure the anchor has an address, by reverse-geocoding it when it does
     * not.
     *
     * ## Why this is not left to the picker
     *
     * Only one of the six ways to set an anchor carries an address — the map
     * picker, through [applyMapPick]. The other five ([commitCoordinates],
     * [setCoordinates], [applyClipboard], [useRealLocation] and re-selecting a
     * history entry) call `moveTo`, which deliberately drops the old address
     * because it describes different coordinates. The address was therefore
     * blank in every configuration that did not end in a map pick, and a blank
     * address is not a cosmetic gap:
     *
     *  - the SDK getters answer nothing, so a target app that prints its own
     *    city keeps the one it cached from the last real fix;
     *  - CCB's employee app answers its check-in page from `getAddrStr()` and
     *    renders "地理位置获取失败" when it is empty — while the map, the
     *    coordinates and the injected fix are all perfectly correct (measured
     *    2026-11-12, config with every address field blank).
     *
     * A user who set the anchor by typing a pair or by tapping a preset has no
     * way to know that; the symptom points at everything except the cause.
     *
     * ## Debounce
     *
     * Every step of the coordinate editor goes through [update], so dragging a
     * slider or finishing a typed number would otherwise fire a request per
     * keystroke. The delay is cancelled and restarted, so only the value the
     * user settles on is looked up.
     */
    private fun scheduleAddress(delayMs: Long = ADDRESS_DEBOUNCE_MS) {
        val current = _config.value
        // Turned off means the getters should pass the SDK's own values through;
        // an address in the config would defeat that, so nothing is resolved.
        if (!current.syncAddress || current.addressKnown) {
            _addressState.value = AddressState.SETTLED
            return
        }
        val key = current.mapKeyFor(MapProvider.TENCENT)
        if (key == null) {
            _addressState.value = AddressState.NO_KEY
            return
        }

        val secret = current.mapSecretValue(MapProvider.TENCENT)
        val latitude = current.latitude
        val longitude = current.longitude

        addressJob?.cancel()
        _addressState.value = AddressState.RESOLVING
        addressJob = viewModelScope.launch {
            delay(delayMs)

            // Consent has to be recorded before the SDK will send anything, and
            // it is recorded per process by `MapSdkBootstrap`. The picker calls
            // it on open, which is why a lookup from that screen works and this
            // one did not: without it `TencentSearch` refuses with
            // `请确保隐私政策已取得用户同意` — status -1, no request on the wire,
            // and an address that never arrives (measured 2026-11-12, first run
            // of this resolver).
            val ready = MapSdkBootstrap.ensure(getApplication(), current)
            if (ready is MapSdkBootstrap.Status.Failed) {
                _addressState.value = AddressState.FAILED
                return@launch
            }

            val found = tencentReverseGeocode(
                getApplication(),
                key,
                secret,
                latitude,
                longitude,
            )

            // The user can move the anchor while the lookup is in flight, and a
            // late answer then describes a point that is no longer the anchor.
            // Writing it anyway would put one city's name on another city's
            // coordinates — worse than the blank being repaired.
            val now = _config.value
            if (now.latitude != latitude || now.longitude != longitude) return@launch

            if (found == null || !found.isResolved) {
                _addressState.value = AddressState.FAILED
                return@launch
            }
            update { it.withResolvedAddress(found) }
            _addressState.value = AddressState.SETTLED
        }
    }

    /**
     * Runs the lookup again on demand, from the line under the coordinates.
     *
     * No debounce: this is a tap, not a value settling.
     */
    fun retryAddress() = scheduleAddress(delayMs = 0)

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

    private companion object {
        const val LOCATION_THROTTLE_MS = 5_000L

        /**
         * How long the anchor has to sit still before it is looked up.
         *
         * Long enough to swallow a slider drag or a typed number (the editor
         * commits on every valid keystroke), short enough that the address is
         * there by the time the user has read the coordinates.
         */
        const val ADDRESS_DEBOUNCE_MS = 700L
    }
}
