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
    private const val K_ADDR_ADCODE = "addr_adcode"
    private const val K_ADDR_CITYCODE = "addr_citycode"
    private const val K_ADDR_LINE = "addr_line"
    private const val K_MAP_PROVIDER = "map_provider"
    // Removed in 1.4.1 along with the built-in key: `map_credential` is no longer
    // written, and a blob that still carries one from an older build simply has
    // it ignored. Dropping the reader is safe because the field it fed fed only
    // the picker's own credential switch, never the hook side.
    private const val K_MAP_AK_BAIDU = "map_ak_baidu"
    private const val K_MAP_AK_AMAP = "map_ak_amap"
    private const val K_MAP_AK_TENCENT = "map_ak_tencent"
    private const val K_SCOPE_ALL = "scope_all"
    private const val K_TARGETS = "targets"

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
        put(K_ADDR_ADCODE, config.addrAdCode)
        put(K_ADDR_CITYCODE, config.addrCityCode)
        put(K_ADDR_LINE, config.addrLine)
        put(K_MAP_PROVIDER, config.mapProviderId)
        put(K_MAP_AK_BAIDU, config.mapAkBaidu)
        put(K_MAP_AK_AMAP, config.mapAkAmap)
        put(K_MAP_AK_TENCENT, config.mapAkTencent)
        put(K_SCOPE_ALL, config.scopeAll)
        put(K_TARGETS, JSONArray(config.targetPackages.sorted()))
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
            addrAdCode = decodeText(json, K_ADDR_ADCODE, defaults.addrAdCode),
            addrCityCode = decodeText(json, K_ADDR_CITYCODE, defaults.addrCityCode),
            addrLine = decodeText(json, K_ADDR_LINE, defaults.addrLine),
            mapProviderId = decodeText(json, K_MAP_PROVIDER, defaults.mapProviderId),
            mapAkBaidu = decodeText(json, K_MAP_AK_BAIDU, defaults.mapAkBaidu),
            mapAkAmap = decodeText(json, K_MAP_AK_AMAP, defaults.mapAkAmap),
            mapAkTencent = decodeText(json, K_MAP_AK_TENCENT, defaults.mapAkTencent),
            scopeAll = json.optBoolean(K_SCOPE_ALL, defaults.scopeAll),
            targetPackages = decodeTargets(json.optJSONArray(K_TARGETS)),
        )
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

    private fun decodeTargets(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val pkg = array.optString(i, "")
            if (pkg.isNotBlank()) out.add(pkg)
        }
        return out
    }
}
