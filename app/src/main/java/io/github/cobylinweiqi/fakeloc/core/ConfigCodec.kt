package io.github.cobylinweiqi.fakeloc.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON codec for [SpoofConfig].
 *
 * `org.json` is used on purpose: it lives in the boot classpath, so the exact
 * same decoder runs inside a target app's process without dragging Gson (and
 * its reflection) into every process we hook. Decoding is defensive — a
 * malformed or truncated blob falls back to defaults rather than throwing
 * inside `Location.getLatitude()`.
 */
object ConfigCodec {

    private const val VERSION = 1

    private const val K_VERSION = "v"
    private const val K_PLAYING = "playing"
    private const val K_LAT = "lat"
    private const val K_LNG = "lng"
    private const val K_USE_ACCURACY = "use_accuracy"
    private const val K_ACCURACY = "accuracy"
    private const val K_USE_ALTITUDE = "use_altitude"
    private const val K_ALTITUDE = "altitude"
    private const val K_USE_SPEED = "use_speed"
    private const val K_SPEED = "speed"
    private const val K_DRIFT = "drift"
    private const val K_DRIFT_RADIUS = "drift_radius"
    private const val K_DRIFT_SPEED = "drift_speed"
    private const val K_ANTI_MOCK = "anti_mock"
    private const val K_STRICT_SOURCES = "strict_sources"
    private const val K_MAP_SDK = "map_sdk_compat"
    private const val K_SYNC_ADDRESS = "sync_address"
    private const val K_ADDR_COUNTRY = "addr_country"
    private const val K_ADDR_PROVINCE = "addr_province"
    private const val K_ADDR_CITY = "addr_city"
    private const val K_ADDR_DISTRICT = "addr_district"
    private const val K_ADDR_TOWN = "addr_town"
    // Added with [SpoofConfig.addrStreet]: the check-in reply carries the street
    // beside the formatted address, and it is a different getter. A blob from an
    // earlier build has no such key and decodes to blank, which the hook reads
    // as "leave getStreet() alone" — no migration needed.
    private const val K_ADDR_STREET = "addr_street"
    private const val K_ADDR_ADCODE = "addr_adcode"
    private const val K_ADDR_CITYCODE = "addr_citycode"
    // Added in 1.9.0 with [SpoofConfig.addrPlaceName]. A blob written before it
    // simply has no key and decodes to the empty string, which the address hooks
    // read as "leave the name getter alone" — the pre-1.9.0 behaviour, byte for
    // byte, so no migration is needed.
    private const val K_ADDR_PLACE = "addr_place"
    private const val K_ADDR_LINE = "addr_line"
    private const val K_MAP_PROVIDER = "map_provider"
    // Removed in 1.4.1 along with the built-in key: `map_credential` is no longer
    // written, and a blob that still carries one from an older build simply has
    // it ignored. Dropping the reader is safe because the field it fed fed only
    // the picker's own credential switch, never the hook side.
    private const val K_MAP_AK_BAIDU = "map_ak_baidu"
    private const val K_MAP_AK_AMAP = "map_ak_amap"
    private const val K_MAP_AK_TENCENT = "map_ak_tencent"
    private const val K_MAP_SK_TENCENT = "map_sk_tencent"
    // Added with [SpoofConfig.places]. Deliberately an additive key with no
    // version bump: a blob written before it decodes to an empty history (the
    // home screen falls back to the built-in cities), and a blob written after
    // it is ignored by any older build, which is the same forward-compatibility
    // every other field here already relies on.
    private const val K_PLACES = "places"
    private const val K_PINNED = "pinned"
    // `scope_all` and `targets` are no longer written: the effective scope is
    // whatever LSPosed injected the module into, so there is nothing left for
    // the app to store. A blob from an older build still carries both keys and
    // they are simply ignored — an empty target list in particular must NOT be
    // read as "spoof nothing", which is the exact failure that made the switch
    // a trap in the first place.

    fun encode(config: SpoofConfig): String = JSONObject().apply {
        put(K_VERSION, VERSION)
        put(K_PLAYING, config.playing)
        put(K_LAT, config.latitude)
        put(K_LNG, config.longitude)
        put(K_USE_ACCURACY, config.useAccuracy)
        put(K_ACCURACY, config.accuracy.toDouble())
        put(K_USE_ALTITUDE, config.useAltitude)
        put(K_ALTITUDE, config.altitude)
        put(K_USE_SPEED, config.useSpeed)
        put(K_SPEED, config.speed.toDouble())
        put(K_DRIFT, config.driftEnabled)
        put(K_DRIFT_RADIUS, config.driftRadiusMeters)
        put(K_DRIFT_SPEED, config.driftSpeedMps.toDouble())
        put(K_ANTI_MOCK, config.antiMock)
        put(K_STRICT_SOURCES, config.strictSources)
        put(K_MAP_SDK, config.mapSdkCompat)
        put(K_SYNC_ADDRESS, config.syncAddress)
        put(K_ADDR_COUNTRY, config.addrCountry)
        put(K_ADDR_PROVINCE, config.addrProvince)
        put(K_ADDR_CITY, config.addrCity)
        put(K_ADDR_DISTRICT, config.addrDistrict)
        put(K_ADDR_TOWN, config.addrTown)
        put(K_ADDR_STREET, config.addrStreet)
        put(K_ADDR_ADCODE, config.addrAdCode)
        put(K_ADDR_CITYCODE, config.addrCityCode)
        put(K_ADDR_PLACE, config.addrPlaceName)
        put(K_ADDR_LINE, config.addrLine)
        put(K_MAP_PROVIDER, config.mapProviderId)
        put(K_MAP_AK_BAIDU, config.mapAkBaidu)
        put(K_MAP_AK_AMAP, config.mapAkAmap)
        put(K_MAP_AK_TENCENT, config.mapAkTencent)
        put(K_MAP_SK_TENCENT, config.mapSkTencent)
        put(K_PLACES, encodePlaces(config.places))
    }.toString()

    /**
     * Never throws. Any missing, wrong-typed or out-of-range field falls back to
     * the [SpoofConfig] default for that field, so a partially written blob (a
     * crash mid-write) still yields a usable config.
     *
     * A blob written by an older build simply has no key for a field added since,
     * and takes that field's default — which is why every new switch has to
     * default to "off, nothing changes", not to "on, it now behaves differently".
     */
    fun decode(raw: String?): SpoofConfig {
        if (raw.isNullOrBlank()) return SpoofConfig()
        val defaults = SpoofConfig()
        val json = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return defaults
        }

        return SpoofConfig(
            playing = json.optBoolean(K_PLAYING, defaults.playing),
            latitude = json.optDouble(K_LAT, defaults.latitude).takeIf { it.isFinite() } ?: defaults.latitude,
            longitude = json.optDouble(K_LNG, defaults.longitude).takeIf { it.isFinite() } ?: defaults.longitude,
            useAccuracy = json.optBoolean(K_USE_ACCURACY, defaults.useAccuracy),
            accuracy = json.optDouble(K_ACCURACY, defaults.accuracy.toDouble()).toFloat().coerceAtLeast(0f),
            useAltitude = json.optBoolean(K_USE_ALTITUDE, defaults.useAltitude),
            altitude = json.optDouble(K_ALTITUDE, defaults.altitude).takeIf { it.isFinite() } ?: defaults.altitude,
            useSpeed = json.optBoolean(K_USE_SPEED, defaults.useSpeed),
            speed = json.optDouble(K_SPEED, defaults.speed.toDouble()).toFloat().coerceAtLeast(0f),
            driftEnabled = json.optBoolean(K_DRIFT, defaults.driftEnabled),
            driftRadiusMeters = json.optDouble(K_DRIFT_RADIUS, defaults.driftRadiusMeters)
                .takeIf { it.isFinite() }?.coerceIn(0.0, 5_000.0) ?: defaults.driftRadiusMeters,
            driftSpeedMps = json.optDouble(K_DRIFT_SPEED, defaults.driftSpeedMps.toDouble())
                .toFloat().coerceIn(0f, 30f),
            antiMock = json.optBoolean(K_ANTI_MOCK, defaults.antiMock),
            strictSources = json.optBoolean(K_STRICT_SOURCES, defaults.strictSources),
            mapSdkCompat = json.optBoolean(K_MAP_SDK, defaults.mapSdkCompat),
            syncAddress = json.optBoolean(K_SYNC_ADDRESS, defaults.syncAddress),
            addrCountry = decodeText(json, K_ADDR_COUNTRY, defaults.addrCountry),
            addrProvince = decodeText(json, K_ADDR_PROVINCE, defaults.addrProvince),
            addrCity = decodeText(json, K_ADDR_CITY, defaults.addrCity),
            addrDistrict = decodeText(json, K_ADDR_DISTRICT, defaults.addrDistrict),
            addrTown = decodeText(json, K_ADDR_TOWN, defaults.addrTown),
            addrStreet = decodeText(json, K_ADDR_STREET, defaults.addrStreet),
            addrAdCode = decodeText(json, K_ADDR_ADCODE, defaults.addrAdCode),
            addrCityCode = decodeText(json, K_ADDR_CITYCODE, defaults.addrCityCode),
            addrPlaceName = decodeText(json, K_ADDR_PLACE, defaults.addrPlaceName),
            addrLine = decodeText(json, K_ADDR_LINE, defaults.addrLine),
            mapProviderId = decodeText(json, K_MAP_PROVIDER, defaults.mapProviderId),
            mapAkBaidu = decodeText(json, K_MAP_AK_BAIDU, defaults.mapAkBaidu),
            mapAkAmap = decodeText(json, K_MAP_AK_AMAP, defaults.mapAkAmap),
            mapAkTencent = decodeText(json, K_MAP_AK_TENCENT, defaults.mapAkTencent),
            mapSkTencent = decodeText(json, K_MAP_SK_TENCENT, defaults.mapSkTencent),
            places = decodePlaces(json.optJSONArray(K_PLACES)),
        )
    }

    /**
     * The history, as an array of flat objects.
     *
     * Flat rather than nested under an `"address"` key: the same eleven address
     * keys are already spelled out at the top level, and a second nested shape
     * would mean two decoders for one concept. Capped so a corrupt or hostile
     * blob cannot make the hook side walk a long list on every getter call.
     */
    private fun encodePlaces(places: List<SavedPlace>): JSONArray = JSONArray().apply {
        places.take(MAX_PLACES).forEach { place ->
            put(
                JSONObject().apply {
                    put(K_LAT, place.latitude)
                    put(K_LNG, place.longitude)
                    put(K_PINNED, place.pinned)
                    put(K_ADDR_COUNTRY, place.address.country)
                    put(K_ADDR_PROVINCE, place.address.province)
                    put(K_ADDR_CITY, place.address.city)
                    put(K_ADDR_DISTRICT, place.address.district)
                    put(K_ADDR_TOWN, place.address.town)
                    put(K_ADDR_STREET, place.address.street)
                    put(K_ADDR_ADCODE, place.address.adCode)
                    put(K_ADDR_CITYCODE, place.address.cityCode)
                    put(K_ADDR_PLACE, place.address.placeName)
                    put(K_ADDR_LINE, place.address.line)
                },
            )
        }
    }

    /**
     * Reads the history back, dropping anything unusable.
     *
     * A row with a non-finite or out-of-range coordinate is skipped rather than
     * clamped: a clamped row would silently move the user's saved office to
     * 90°N, and a stored place is only useful if it is exactly where they left
     * it.
     */
    private fun decodePlaces(array: JSONArray?): List<SavedPlace> {
        if (array == null) return emptyList()
        val count = minOf(array.length(), MAX_PLACES)
        val places = ArrayList<SavedPlace>(count)
        for (index in 0 until count) {
            val row = array.optJSONObject(index) ?: continue
            val latitude = row.optDouble(K_LAT, Double.NaN)
            val longitude = row.optDouble(K_LNG, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
            places += SavedPlace(
                latitude = latitude,
                longitude = longitude,
                address = PickedAddress(
                    country = decodeText(row, K_ADDR_COUNTRY, ""),
                    province = decodeText(row, K_ADDR_PROVINCE, ""),
                    city = decodeText(row, K_ADDR_CITY, ""),
                    district = decodeText(row, K_ADDR_DISTRICT, ""),
                    town = decodeText(row, K_ADDR_TOWN, ""),
                    street = decodeText(row, K_ADDR_STREET, ""),
                    adCode = decodeText(row, K_ADDR_ADCODE, ""),
                    cityCode = decodeText(row, K_ADDR_CITYCODE, ""),
                    placeName = decodeText(row, K_ADDR_PLACE, ""),
                    line = decodeText(row, K_ADDR_LINE, ""),
                ),
                pinned = row.optBoolean(K_PINNED, false),
            )
        }
        return places
    }

    /**
     * Reads one free-text field.
     *
     * Trims, and treats a literal `"null"` as absent: `JSONObject.optString`
     * stringifies a JSON null into the four characters `null`, which would then
     * reach the user as a city literally named "null" — and, worse, count as a
     * *non-blank* value that the address hooks would hand straight to an app.
     *
     * Length-capped because these come back out of getters an app may call in a
     * loop, and the blob is written by a UI, not by a machine.
     */
    private fun decodeText(json: JSONObject, key: String, fallback: String): String {
        val value = json.optString(key, "").trim()
        if (value.isEmpty() || value == "null") return fallback
        return value.take(MAX_TEXT)
    }

    /** Upper bound on any single stored text field. */
    private const val MAX_TEXT = 120

    /** Upper bound on the saved-place list, whatever a blob claims. */
    private const val MAX_PLACES = 32
}
