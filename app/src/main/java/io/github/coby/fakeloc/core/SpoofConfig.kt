package io.github.coby.fakeloc.core

/**
 * The whole spoofing state, shared between the manager UI and the hooks.
 *
 * It is deliberately a single object serialised into a single preference key:
 * the hook side then needs one change listener and one cached decode instead of
 * a dozen independent keys, and adding a field never means touching two code
 * paths that could drift apart.
 *
 * Defaults are sensible for a first run: spoofing off, Tiananmen Square as the
 * anchor (Beijing, 39.9087 / 116.3975), a plausible 5 m accuracy, mock traces
 * hidden.
 */
data class SpoofConfig(
    /** Master switch. Everything is a no-op while this is false. */
    val playing: Boolean = false,

    /** Anchor point, in decimal degrees. */
    val latitude: Double = 39.908700,
    val longitude: Double = 116.397500,

    val useAccuracy: Boolean = true,
    /** Horizontal accuracy in metres. */
    val accuracy: Float = 5.0f,

    val useAltitude: Boolean = false,
    /** Metres above the WGS-84 ellipsoid. */
    val altitude: Double = 44.0,

    val useSpeed: Boolean = false,
    /** Ground speed in metres per second. */
    val speed: Float = 1.4f,

    /** Random-walk around the anchor so a recorded track does not look frozen. */
    val driftEnabled: Boolean = false,
    val driftRadiusMeters: Double = 40.0,
    val driftSpeedMps: Float = 1.4f,

    /**
     * Neutralise every mock-location signal a detector can read:
     * `Location.isFromMockProvider()`, `Location.isMock()`, the
     * `mock_location` secure setting and the `OP_MOCK_LOCATION` app-op.
     */
    val antiMock: Boolean = true,

    /**
     * Deny the target process every raw source it could use to work out a real
     * position by itself: WiFi scan results, cell information, and raw GNSS
     * (NMEA sentences, measurements, navigation messages).
     *
     * This exists because rewriting the platform's fixes is not always enough.
     * A location SDK with its own engine — Amap's, for instance — can ignore what
     * `LocationManager` hands it and either solve its own position from raw GNSS
     * or send a WiFi/cell fingerprint to its own server and take the coordinates
     * that come back. Neither can be rewritten from inside the app, and the
     * symptom is a map that jumps to the injected position and then drifts back
     * to the real one seconds later.
     *
     * On by default: without it the module only wins against apps that read the
     * platform's location. Turn it off if an app starts reporting that it cannot
     * get a fix at all.
     */
    val strictSources: Boolean = true,

    /**
     * Hook the map and location SDKs inside the target app.
     *
     * Covers Amap, Baidu and Tencent — the three whose SDKs carry their own
     * positioning engine and publish results through their own classes rather
     * than through `android.location`. [strictSources] blocks the *inputs* such an
     * engine would use; this one takes over its *output*, which is the only thing
     * that helps when the SDK has already computed a fix before the inputs were
     * cut, or when it caches one.
     *
     * On by default: it costs nothing in an app that uses none of the three,
     * because every channel is resolved by name and skipped when absent.
     *
     * Turn it off for the rare app that breaks on it — a mapping library that
     * relies on `getLatitude()` returning what it wrote a moment ago will see a
     * different value, and a handful of callers assume otherwise.
     */
    val mapSdkCompat: Boolean = true,

    /**
     * Answer the map SDKs' *address* getters with the anchor's own address.
     *
     * A rewritten coordinate rewrites a number, and nothing else. The city,
     * province, street and POI the same payload carries are **strings the SDK
     * computed from the real fix** — by asking its own server, not by consulting
     * anything on the device — so no part of the coordinate path touches them. An
     * app that maps the coordinates it is handed but prints the SDK's own
     * `getCity()` ends up half converted: a fake map with a real city in the
     * header.
     *
     * On by default, because answering *nothing* turned out to be worse than
     * answering wrongly. Measured on CCB Life (2026-09-29): with the address left
     * empty the app kept the city it had cached from the last real fix, so the
     * header never moved no matter which anchor was set — a symptom identical to
     * the module doing nothing at all, and much harder to diagnose.
     *
     * Turn it off for an app that misbehaves on a rewritten address; the getters
     * then pass through whatever the SDK produced, exactly as before this existed.
     */
    val syncAddress: Boolean = true,

    /**
     * The anchor's address, resolved once by the picker's reverse geocoder and
     * kept here for the hook side to hand back.
     *
     * Split into parts rather than stored as one formatted line, and that is not
     * cosmetic: apps routinely turn the city *name* into a city *code* through
     * their own lookup table, so `getCity()` has to answer the exact
     * administrative name — "吉林市", not "吉林" — or the lookup silently finds
     * nothing and the whole update path is skipped. CCB Life's home page does
     * exactly that (`position_city_code = lookup(cityName).code`).
     *
     * Blank means "nothing configured for this part — leave that getter alone",
     * which is what a config written before the first pick looks like.
     */
    val addrCountry: String = "",
    val addrProvince: String = "",
    val addrCity: String = "",
    val addrDistrict: String = "",
    val addrTown: String = "",
    val addrAdCode: String = "",
    val addrCityCode: String = "",
    /** The formatted one-line address, for `getAddrStr()` / `getAddress()`. */
    val addrLine: String = "",

    /**
     * How the picker's map is drawn.
     *
     * These are the manager UI's own settings — the hook side never reads them,
     * because the module does not draw maps and needs no key for anything it
     * does. They live here anyway so that there is exactly one stored object,
     * one decoder and one change notification rather than a second, parallel
     * preference store that would have to be kept in step.
     *
     * Stored as an id rather than an enum ordinal; see [MapProvider] for why,
     * and for what happens to a value written by a build that did not know
     * about it.
     */
    val mapProviderId: String = MapProvider.DEFAULT.id,

    /**
     * The user's own keys, one slot per platform.
     *
     * Blank means "not configured". There is no built-in key to fall back on —
     * this build deliberately carries no credential at all — so a blank field
     * means the map cannot be drawn, and the picker says so instead of showing an
     * empty grey square.
     *
     * **Baidu's and Amap's slots are still here although neither renderer is**
     * (v1.5.0, 2026-09-29). They are part of every config blob this app has ever
     * written, and keeping the schema stable is what makes removing a provider a
     * code change rather than a migration — the same reason [mapProviderId]
     * survives. An unknown id decodes to [MapProvider.DEFAULT]; the two orphaned
     * keys simply sit there unread.
     */
    val mapAkBaidu: String = "",
    val mapAkAmap: String = "",
    val mapAkTencent: String = "",

    /**
     * When true, *every* process in the LSPosed scope is spoofed and
     * [targetPackages] is ignored. Useful when you would rather curate the
     * scope in LSPosed itself.
     */
    val scopeAll: Boolean = false,

    /** Package names that receive the fake fix when [scopeAll] is false. */
    val targetPackages: Set<String> = emptySet(),
) {
    /** `true` when latitude/longitude are inside WGS-84 bounds. */
    val coordinatesValid: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0

    /**
     * Whether the process identified by [packageName] should be spoofed right
     * now. The hook side calls this on every intercepted location read, so it
     * must stay allocation-free and cheap — it does.
     *
     * The comparison is against whole process names, exactly as the framework
     * reports them: `com.example.app:remote` is not
     * `com.example.app`. Widening a subprocess to its parent would need a
     * substring per call, so that lives in `HookState`, once per process.
     */
    fun appliesTo(packageName: String?): Boolean {
        if (!playing) return false
        if (scopeAll) return true
        return packageName != null && targetPackages.contains(packageName)
    }

    /**
     * What a given SDK address getter should answer, or `null` to leave it be.
     *
     * The three SDKs name the same concepts slightly differently — `getAddrStr`
     * (Baidu) vs `getAddress` (Amap, Tencent), `getNation` (Tencent) vs
     * `getCountry` — so the mapping lives in one place instead of in each
     * channel, where the three copies would drift.
     *
     * `null` means "no value configured" and the caller turns it into "proceed as
     * before". It must never become an empty string: an empty city is a value the
     * app will act on, and the one we measured acts on it by keeping its previous
     * city forever.
     *
     * `getName()` is deliberately absent — on Tencent that is the POI name, not
     * the address, and substituting the address there would put a street name
     * where the app expects a shop.
     */
    fun addressFor(getter: String): String? {
        val value = when (getter) {
            "getCountry", "getNation" -> addrCountry
            "getProvince" -> addrProvince
            "getCity" -> addrCity
            "getDistrict" -> addrDistrict
            "getTown", "getTownship" -> addrTown
            "getAdCode", "getAdcode" -> addrAdCode
            "getCityCode" -> addrCityCode
            "getAddrStr", "getAddress" -> addrLine
            else -> return null
        }
        return value.takeIf { it.isNotBlank() }
    }

    /** Human-readable one-liner used by the manager UI header. */
    fun summary(): String =
        "%.6f, %.6f".format(latitude, longitude)

    /** The platform the picker draws with. */
    val mapProvider: MapProvider
        get() = MapProvider.from(mapProviderId)

    /**
     * [provider]'s key field verbatim, blank included.
     *
     * This is the value a text field binds to, and a text field cannot tell
     * `null` from the empty string while the user is halfway through pasting
     * one — hence a raw `String` here and a nullable accessor for callers that
     * only care whether there is something to use.
     */
    fun mapKeyValue(provider: MapProvider): String = when (provider) {
        MapProvider.TENCENT -> mapAkTencent
    }

    /**
     * [this] with [provider]'s key field replaced by [value].
     *
     * Routed through the provider rather than written to a named field so the
     * settings page does not have to know which field it is editing. That mattered
     * when the page switched between three; it is kept now because the alternative
     * is a screen hard-coding `mapAkTencent` in two places, and because the day a
     * second renderer returns is the day this `when` is the only code that has to
     * learn about it.
     */
    fun withMapKey(provider: MapProvider, value: String): SpoofConfig = when (provider) {
        MapProvider.TENCENT -> copy(mapAkTencent = value)
    }

    /**
     * The key to hand [provider]'s SDK, or `null` when there is nothing to hand
     * it.
     *
     * `null` and "the empty string" are not interchangeable to these SDKs: an
     * empty key reads as *a key was configured, and it is empty*, which turns a
     * clear "not set up yet" into an authentication failure. So a blank field has
     * to become `null` here and the caller has to skip the key setter entirely —
     * see the guard in `TencentCanvas`.
     *
     * There is no second source to fall back to: this build carries no credential
     * and no manifest entry for one, so this is the only key path there is.
     */
    fun mapKeyFor(provider: MapProvider): String? =
        mapKeyValue(provider).takeIf { it.isNotBlank() }

    /**
     * Whether [provider] can be drawn at all.
     *
     * Deliberately answers "is there a key", not "does that key work" — the
     * second question can only be answered by the platform's server, and a
     * revoked key looks exactly like a working one until a request is made.
     */
    fun mapKeyConfigured(provider: MapProvider): Boolean = mapKeyFor(provider) != null
}

/** Preference keys. Both sides import these — never inline the literals. */
object Keys {
    /**
     * The LSPosed *remote* preference group. The module reads it through
     * `XposedModule.getRemotePreferences`, the UI writes it through
     * `XposedService.getRemotePreferences`. This is the API-101 replacement for
     * the old `XSharedPreferences` + `MODE_WORLD_READABLE` trick, and it works
     * across processes and across users without root.
     */
    const val REMOTE_GROUP = "settings"

    /** Single JSON blob holding the serialised [SpoofConfig]. */
    const val CONFIG = "config"
}

/** Both default presets shown as chips on the home screen. */
data class Coordinate(val nameRes: Int, val latitude: Double, val longitude: Double)
