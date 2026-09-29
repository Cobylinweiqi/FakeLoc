package io.github.coby.fakeloc.xposed

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import io.github.coby.fakeloc.core.ConfigCodec
import io.github.coby.fakeloc.core.Coords
import io.github.coby.fakeloc.core.Datum
import io.github.coby.fakeloc.core.Geo
import io.github.coby.fakeloc.core.Keys
import io.github.coby.fakeloc.core.SpoofConfig
import java.util.Locale

/**
 * Everything the hooks need to know, resolved inside the *hooked* process.
 *
 * Lives in the target app's address space, so it must be a cheap, thread-safe
 * read path: [config] hands back an immutable snapshot decoded once, not a fresh
 * parse per `Location.getLatitude()` call. Updates arrive two ways, on purpose:
 *
 *  1. an `OnSharedPreferenceChangeListener` on the LSPosed remote preferences,
 *     which is the documented push channel, and
 *  2. a slow polling fallback ([RELOAD_INTERVAL_MS]), in case a manager build
 *     fails to deliver the change notification. Without it a stale snapshot
 *     would silently pin the module to old coordinates.
 *
 * The listener is held in a field rather than created inline: `SharedPreferences`
 * only keeps a weak reference to listeners, so a local one would be collected
 * and stop firing.
 */
internal object HookState {

    private const val TAG = "State"
    private const val RELOAD_INTERVAL_MS = 2_000L

    /**
     * Set to `true` to trace every interception through the LSPosed log. Off by
     * default — a hooked `getLatitude()` fires thousands of times a second and
     * would drown the log.
     */
    @Volatile
    var verbose: Boolean = false

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var current: SpoofConfig = SpoofConfig()

    @Volatile
    private var lastReloadMs: Long = 0L

    /** Package name of the process we are loaded into, exactly as reported. */
    @Volatile
    private var processPackage: String? = null

    /**
     * [processPackage] with its `:suffix` stripped, or `null` when there is none.
     *
     * The framework announces a *process*, so a subprocess arrives as
     * `com.example.app:remote` — while a target-list entry names the package.
     * Compared directly, the two never match, so every subprocess of a targeted
     * app was judged out of scope and spoofed nothing, with the module loaded and
     * every hook armed. Stripped once here rather than per call: [applies] runs on
     * every intercepted location read.
     */
    @Volatile
    private var basePackage: String? = null

    private val driftEngine = Geo.DriftEngine()

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Keys.CONFIG) {
                reload()
                if (verbose) HookKit.info(TAG, "config reloaded after remote change")
            }
        }

    /** Called once per process from `onPackageLoaded`. Safe to call repeatedly. */
    @Synchronized
    fun init(sharedPreferences: SharedPreferences, packageName: String?) {
        processPackage = packageName
        basePackage = packageName?.let { name ->
            val colon = name.indexOf(':')
            // A subprocess is `pkg:tag`; a target-list entry names the package.
            if (colon > 0) name.substring(0, colon) else null
        }
        preferences = sharedPreferences
        runCatching {
            sharedPreferences.registerOnSharedPreferenceChangeListener(changeListener)
        }.onFailure { HookKit.error(TAG, "cannot register prefs listener", it) }
        reload()
        HookKit.info(TAG, "state ready for $packageName")
        HookKit.info(TAG, selfCheck())
        if (!applies(current)) {
            HookKit.warn(TAG, "this process is NOT in the target list — nothing will be spoofed here")
        }
    }

    /** Re-decodes the snapshot from the remote preferences. */
    fun reload() {
        current = ConfigCodec.decode(preferences?.getString(Keys.CONFIG, null))
        lastReloadMs = SystemClock.elapsedRealtime()
    }

    /**
     * One-line summary of the switches that decide whether anything happens here.
     *
     * Written at startup because those values are invisible everywhere else: a
     * map-SDK channel can arm perfectly and still rewrite nothing, because
     * `mapSdkCompat` is off or this package is not in the target list — and in the
     * log that is indistinguishable from a channel that never armed at all. This
     * line is what separates "the hook is not installed" from "the hook is
     * installed but not allowed to fire".
     *
     * The anchor is printed on purpose: it is the coordinate the user picked, not
     * the device's real position, so the line stays safe to paste into a report.
     */
    fun selfCheck(): String = buildString {
        val cfg = current
        append("inScope=").append(applies(cfg))
        append(" playing=").append(cfg.playing)
        append(" mapSdkCompat=").append(cfg.mapSdkCompat)
        append(" strictSources=").append(cfg.strictSources)
        append(" antiMock=").append(cfg.antiMock)
        append(" scopeAll=").append(cfg.scopeAll)
        append(" syncAddress=").append(cfg.syncAddress)
        append(" targets=").append(cfg.targetPackages.size)
        append(" anchor=")
        append(String.format(Locale.US, "%.6f,%.6f", cfg.latitude, cfg.longitude))
        // Worth a column of its own. The address the SDK getters answer with is
        // resolved by the picker and stored separately from the coordinates, so a
        // "the city did not change" report splits in two here: nothing was ever
        // resolved (`fakeCity` absent — the picker was never opened since the
        // upgrade), or something was and the app ignored it.
        if (cfg.addrCity.isNotBlank()) append(" fakeCity=").append(cfg.addrCity)
    }

    /** Immutable snapshot of the active configuration. */
    fun config(): SpoofConfig {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReloadMs >= RELOAD_INTERVAL_MS) {
            reload()
        }
        return current
    }

    /**
     * Whether this process is one the module should act on.
     *
     * Two lookups rather than one, because a target-list entry names a *package*
     * while the framework announces *processes*: an entry for `com.example.app`
     * has to cover `com.example.app:remote` too. The suffix is stripped once, at
     * [init], which keeps this allocation-free on a path an app can hit hundreds
     * of times a second.
     */
    private fun applies(cfg: SpoofConfig): Boolean {
        if (cfg.appliesTo(processPackage)) return true
        val base = basePackage ?: return false
        return cfg.appliesTo(base)
    }

    /** `true` when this process should receive a fake fix right now. */
    fun active(): Boolean = applies(config())

    /** `true` when mock-location traces should be scrubbed. */
    fun antiMockEngaged(): Boolean = config().let { it.antiMock && it.playing }

    /**
     * `true` when the raw positioning sources (WiFi scans, cell information,
     * NMEA / GNSS measurements) must be denied to this process.
     *
     * Deliberately *not* tied to `antiMock`: hiding mock traces and preventing an
     * app from computing a real fix are different jobs, and the second one is what
     * stops a map app from drifting off the injected position a few seconds in.
     * What it is tied to is [SpoofConfig.appliesTo], so only a process that is
     * being spoofed loses those inputs — the rest of the device keeps working.
     */
    fun sourceLockEngaged(): Boolean {
        val cfg = config()
        return cfg.strictSources && applies(cfg)
    }

    /**
     * `true` when the map/location SDK channels should take over this process.
     *
     * Separate from [sourceLockEngaged] because the two attack opposite ends of
     * the same problem and are useful independently: the source lock starves an
     * SDK's engine of input, while this one commandeers what the SDK publishes.
     * An app that still worked before [strictSources] existed only needs the
     * second; one whose SDK caches a fix needs the first.
     */
    fun mapSdkEngaged(): Boolean {
        val cfg = config()
        return cfg.mapSdkCompat && applies(cfg)
    }

    /** Package this module instance is attached to, as reported by the framework. */
    fun packageName(): String? = processPackage

    // ------------------------------------------------------------- position

    /**
     * The coordinate to report: the anchor, or a point wandering around it when
     * drift is enabled.
     */
    fun position(): Pair<Double, Double> {
        val cfg = config()
        if (!cfg.driftEnabled || cfg.driftRadiusMeters <= 0.0) {
            driftEngine.reset()
            return cfg.latitude to cfg.longitude
        }
        return driftEngine.position(cfg, SystemClock.elapsedRealtime())
    }

    /** Latitude only — the hot path for `Location.getLatitude()`. */
    fun latitude(): Double = position().first

    /** Longitude only. */
    fun longitude(): Double = position().second

    /**
     * The anchor expressed in the datum a given SDK channel speaks.
     *
     * Converted on every call rather than cached: drift moves the point between
     * calls, so a cached conversion would pin a drifting track to a fixed
     * offset — the one thing that would give the drift away.
     */
    fun positionIn(datum: Datum): Pair<Double, Double> {
        if (datum == Datum.WGS84) return position()
        val (lat, lng) = position()
        return Coords.fromWgs84(lat, lng, datum)
    }

    // ------------------------------------------------------------- location

    /** Location providers the platform actually defines. */
    private val KNOWN_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    )

    /**
     * Builds the fake [Location] handed back to the hooked app.
     *
     * When [original] is supplied its timing metadata is carried over, because
     * apps routinely reject or discard a fix whose `time` /
     * `elapsedRealtimeNanos` look stale or impossible. The provider string is
     * likewise preserved when it is a real one — an app that asked the `gps`
     * provider and got a `fused` fix back will usually notice.
     */
    fun buildLocation(original: Location?, provider: String? = null): Location {
        val resolvedProvider = when {
            provider != null && provider in KNOWN_PROVIDERS -> provider
            original != null && original.provider in KNOWN_PROVIDERS -> original.provider
            else -> LocationManager.GPS_PROVIDER
        }

        val fake = Location(resolvedProvider)

        if (original != null) {
            fake.time = original.time
            fake.elapsedRealtimeNanos = original.elapsedRealtimeNanos
            fake.bearing = original.bearing
            fake.bearingAccuracyDegrees = original.bearingAccuracyDegrees
        } else {
            stampTiming(fake)
        }

        applyTo(fake)
        return fake
    }

    /**
     * Dates [location] the way a real chipset would.
     *
     * Backdated slightly: a fix dated "now" but never refreshed is itself a mild
     * tell, and a small lag is what real hardware reports.
     *
     * Shared with the map-SDK channels — they build their payloads from scratch
     * through a one-argument constructor, so without this every synthetic fix
     * would carry whatever timestamp the object was born with.
     */
    fun stampTiming(location: Location) {
        location.time = System.currentTimeMillis() - FIX_LAG_MS
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - FIX_LAG_NANOS
    }

    /**
     * Overwrites the coordinates carried by [location] *in place*.
     *
     * Preferred over [buildLocation] whenever the object is already owned by
     * someone else — a framework service handing a `LocationResult` to a batch of
     * listeners, for instance. Mutating in place keeps object identity and every
     * metadata field intact, which avoids breaking callers that key off either.
     *
     * [datum] exists for the map-SDK channels, whose payloads report GCJ-02 or
     * BD-09 rather than the platform's WGS-84. The write goes through the
     * *virtual* setter, which matters: `AMapLocation` keeps its own fields and
     * overrides `setLatitude`/`setAltitude`/`setSpeed` precisely so that writing
     * through a `Location` reference lands where its own getters read from.
     */
    fun applyTo(location: Location, datum: Datum = Datum.WGS84) {
        val cfg = config()
        val (lat, lng) = positionIn(datum)

        location.latitude = lat
        location.longitude = lng

        if (cfg.useAccuracy) {
            location.accuracy = cfg.accuracy
            // A fix that carries a horizontal accuracy but no vertical one is a
            // quiet tell on API 26+ — real hardware reports both, and "claims to
            // be a GPS fix but has only half its accuracy fields" is exactly the
            // shape a synthetic fix falls into. Derived from the horizontal value
            // rather than configured separately: the ratio between the two is
            // roughly constant on real hardware, so a second field in the UI
            // would only give the user another way to make the fix look wrong.
            location.verticalAccuracyMeters = cfg.accuracy * VERTICAL_ACCURACY_RATIO
        }
        if (cfg.useAltitude) {
            location.altitude = cfg.altitude
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                location.mslAltitudeMeters = cfg.altitude
                location.mslAltitudeAccuracyMeters = MSL_ALTITUDE_ACCURACY
            }
        }
        if (cfg.useSpeed) {
            location.speed = cfg.speed
            location.speedAccuracyMetersPerSecond = SPEED_ACCURACY
        }

        if (cfg.antiMock) {
            MockTrace.scrub(location)
        }
    }

    /** Returns [candidate] when it is already a plausible provider name. */
    fun normaliseProvider(candidate: String?): String =
        when (candidate) {
            null -> LocationManager.GPS_PROVIDER
            in KNOWN_PROVIDERS -> candidate
            else -> LocationManager.GPS_PROVIDER
        }

    /** Accuracy attached to the synthetic altitude/speed values, in their units. */
    private const val SPEED_ACCURACY = 0.6f

    /** Vertical accuracy, as a multiple of the horizontal one. */
    private const val VERTICAL_ACCURACY_RATIO = 1.6f
    private const val MSL_ALTITUDE_ACCURACY = 3.0f

    /** How far behind "now" a synthetic fix is dated. */
    private const val FIX_LAG_MS = 250L
    private const val FIX_LAG_NANOS = 250_000_000L
}
