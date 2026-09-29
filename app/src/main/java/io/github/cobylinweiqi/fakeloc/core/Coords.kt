package io.github.cobylinweiqi.fakeloc.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Datum shifts between the three coordinate systems a Chinese phone meets.
 *
 * - **WGS-84** — what `android.location.Location` carries and what the GNSS chip
 *   actually reports. This is the system [SpoofConfig] stores and the hooks
 *   inject.
 * - **GCJ-02** — the obfuscated datum mainland China mandates for public maps.
 * - **BD-09** — Baidu's own further skew on top of GCJ-02. Everything the Baidu
 *   Map SDK hands back lives here: search hits, reverse-geocoded addresses and
 *   the map centre you dragged the pin over.
 *
 * So a spot picked on the Baidu map arrives as BD-09, and writing that straight
 * into a `Location` would put the target roughly half a kilometre from where the
 * user tapped. [bd09ToWgs84] closes exactly that gap.
 *
 * Outside mainland China the three systems coincide and every method returns its
 * input untouched. The maths is the well-known public-domain GCJ-02 algorithm;
 * accuracy is good to about a metre, far below GPS noise.
 */
object Coords {

    /** Semi-major axis of the Krasovsky 1940 ellipsoid, in metres. */
    private const val AXIS = 6378245.0

    /** Squared eccentricity of that ellipsoid. */
    private const val ECCENTRICITY_SQ = 0.00669342162296594323

    /** BD-09's fixed rotation constant, π · 3000 / 180. */
    private const val BD_FACTOR = Math.PI * 3000.0 / 180.0

    /** How many refinement passes [gcj02ToWgs84] runs. Three is ample. */
    private const val INVERSE_ITERATIONS = 3

    /**
     * The anchor, converted into the datum [datum] asks for.
     *
     * The config stores one WGS-84 point and every consumer wants it in its own
     * terms. The platform wants WGS-84 back; Amap's and Tencent's SDKs report
     * GCJ-02; Baidu's reports BD-09. Handing an SDK the WGS-84 value it never
     * produces would land the result a few hundred metres from the picked point.
     */
    fun fromWgs84(latitude: Double, longitude: Double, datum: Datum): Pair<Double, Double> =
        when (datum) {
            Datum.WGS84 -> latitude to longitude
            Datum.GCJ02 -> wgs84ToGcj02(latitude, longitude)
            Datum.BD09 -> wgs84ToBd09(latitude, longitude)
        }

    // ------------------------------------------------------------ public API

    /** Baidu BD-09 → WGS-84. The conversion the map picker needs. */
    fun bd09ToWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        val (gcjLat, gcjLng) = bd09ToGcj02(latitude, longitude)
        return gcj02ToWgs84(gcjLat, gcjLng)
    }

    /** WGS-84 → Baidu BD-09. Used to drop the current anchor onto the map. */
    fun wgs84ToBd09(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        val (gcjLat, gcjLng) = wgs84ToGcj02(latitude, longitude)
        return gcj02ToBd09(gcjLat, gcjLng)
    }

    /** WGS-84 → GCJ-02. */
    fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        if (outOfChina(latitude, longitude)) return latitude to longitude

        var dLat = distortLatitude(longitude - 105.0, latitude - 35.0)
        var dLng = distortLongitude(longitude - 105.0, latitude - 35.0)

        val radLat = latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - ECCENTRICITY_SQ * magic * magic
        val sqrtMagic = sqrt(magic)

        dLat = dLat * 180.0 / (AXIS * (1 - ECCENTRICITY_SQ) / (magic * sqrtMagic) * Math.PI)
        dLng = dLng * 180.0 / (AXIS / sqrtMagic * cos(radLat) * Math.PI)

        return (latitude + dLat) to (longitude + dLng)
    }

    /**
     * GCJ-02 → WGS-84.
     *
     * There is no closed form for this direction — the forward transform is not
     * analytically invertible. A fixed-point refinement converges in two passes
     * and is exact to well under a centimetre; the naive "subtract the offset"
     * shortcut used by a lot of sample code leaves a few metres of error that
     * compounds when the result is converted back.
     */
    fun gcj02ToWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        if (outOfChina(latitude, longitude)) return latitude to longitude

        var guessLat = latitude
        var guessLng = longitude
        repeat(INVERSE_ITERATIONS) {
            val (forwardLat, forwardLng) = wgs84ToGcj02(guessLat, guessLng)
            guessLat += latitude - forwardLat
            guessLng += longitude - forwardLng
        }
        return guessLat to guessLng
    }

    /** GCJ-02 → Baidu BD-09. */
    fun gcj02ToBd09(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        if (outOfChina(latitude, longitude)) return latitude to longitude

        val z = sqrt(longitude * longitude + latitude * latitude) +
            0.00002 * sin(latitude * BD_FACTOR)
        val theta = atan2(latitude, longitude) + 0.000003 * cos(longitude * BD_FACTOR)

        val bdLat = z * sin(theta) + 0.006
        val bdLng = z * cos(theta) + 0.0065
        return bdLat to bdLng
    }

    /** Baidu BD-09 → GCJ-02. */
    fun bd09ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (!isFinite(latitude, longitude)) return latitude to longitude
        if (outOfChina(latitude, longitude)) return latitude to longitude

        val x = longitude - 0.0065
        val y = latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * BD_FACTOR)
        val theta = atan2(y, x) - 0.000003 * cos(x * BD_FACTOR)

        return (z * sin(theta)) to (z * cos(theta))
    }

    /**
     * `true` when the pair sits outside the bounding box where the GCJ-02 skew
     * applies. Inside it, a "WGS-84" fix from domestic hardware is already
     * offset, and the map SDKs are consistent with each other.
     */
    fun outOfChina(latitude: Double, longitude: Double): Boolean =
        longitude < 72.004 || longitude > 137.8347 ||
            latitude < 0.8293 || latitude > 55.8271

    // -------------------------------------------------------------- internals

    private fun isFinite(latitude: Double, longitude: Double): Boolean =
        !latitude.isNaN() && !longitude.isNaN() &&
            !latitude.isInfinite() && !longitude.isInfinite()

    private fun distortLatitude(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun distortLongitude(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}

/**
 * Whose idea of a coordinate a given channel speaks.
 *
 * Only the four map/location SDK channels need this; everything that reaches the
 * app through `android.location` wants [WGS84], because that is what the platform
 * itself uses.
 */
enum class Datum {
    /** What `android.location.Location` carries and what the config stores. */
    WGS84,

    /** The datum mainland China mandates for public maps; Amap's and Tencent's default. */
    GCJ02,

    /** Baidu's further skew on top of GCJ-02; what its location SDK reports. */
    BD09,
}
