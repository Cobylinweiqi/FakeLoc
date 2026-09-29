package io.github.cobylinweiqi.fakeloc.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Mean Earth radius used for every local-flat approximation below. */
private const val EARTH_RADIUS_M = 6_378_137.0

/** Metres per degree of latitude. Constant enough for city-scale work. */
private const val METRES_PER_DEG_LAT = 111_320.0

/**
 * Spherical geometry helpers plus the walking-drift engine.
 *
 * Everything here is allocation-light and free of Android framework calls, so
 * it is safe to run on the hot path inside a hooked `Location.getLatitude()`.
 */
object Geo {

    /** Metres per degree of longitude at [latitude]. */
    fun metresPerDegLon(latitude: Double): Double =
        (METRES_PER_DEG_LAT * cos(Math.toRadians(latitude))).coerceAtLeast(1.0)

    /**
     * Converts a local east/north offset in metres into a lat/lng delta around
     * ([latitude], [longitude]).
     *
     * A flat-earth approximation is used deliberately: over the few hundred
     * metres we ever care about, its error is far below GPS noise, and it costs
     * two multiply-adds instead of a dozen trig calls.
     */
    fun offsetMetres(latitude: Double, longitude: Double, eastMetres: Double, northMetres: Double): Pair<Double, Double> {
        val lat = (latitude + northMetres / METRES_PER_DEG_LAT).coerceIn(-90.0, 90.0)
        val lng = wrapLongitude(longitude + eastMetres / metresPerDegLon(latitude))
        return lat to lng
    }

    /** Normalises [longitude] into [-180, 180). */
    fun wrapLongitude(longitude: Double): Double {
        var lon = (longitude + 180.0) % 360.0
        if (lon < 0) lon += 360.0
        return lon - 180.0
    }

    /** Great-circle distance in metres between two points. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    /**
     * A uniformly distributed random point inside a circle of [radiusMetres]
     * around the anchor. `sqrt(u)` is what makes it uniform by *area* — sampling
     * the radius linearly would clump points around the centre.
     */
    fun randomWithinRadius(latitude: Double, longitude: Double, radiusMetres: Double): Pair<Double, Double> {
        if (radiusMetres <= 0.0) return latitude to longitude
        val distance = radiusMetres * sqrt(Random.nextDouble())
        val bearing = 2 * Math.PI * Random.nextDouble()
        return offsetMetres(latitude, longitude, sin(bearing) * distance, cos(bearing) * distance)
    }

    /**
     * Formats a coordinate with the six decimals phone apps usually print.
     *
     * Pinned to [java.util.Locale.US] deliberately: the platform default would
     * render `39,908700` in much of Europe and South America, and that string is
     * fed straight back into the coordinate editor — where `toDoubleOrNull()`
     * only accepts a dot. A comma-locale device would show a coordinate it
     * could not re-parse.
     */
    fun format(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)

    // ---------------------------------------------------------------- parsing

    /**
     * A number that can plausibly be a coordinate. Requires at least one part of
     * the pair to carry a decimal point, which is what keeps `"2, 5"` or a date
     * from being swallowed as a location while still accepting real pairs.
     */
    private val PAIR_PATTERN = Regex(
        """(-?\d{1,3}(?:\.\d+)?)\s*[,;\uFF0C\u3001\s]\s*(-?\d{1,3}(?:\.\d+)?)"""
    )

    /** Explicitly tagged pairs, e.g. `q=39.9,116.4`, `geo:39.9,116.4`, `@39.9,116.4`. */
    private val TAGGED_PATTERN = Regex(
        """(?:@|q=|ll=|sll=|daddr=|destination=|geo:|center=|lat=|latitude=)\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Best-effort extraction of a coordinate from arbitrary pasted text: a bare
     * `"39.9087, 116.3975"`, a Google Maps URL (`@`/`?q=`/`!3d` forms), an Amap
     * or Baidu share link, an `geo:` URI, a set of `?lat=&lng=` query params.
     *
     * Returns `null` when nothing valid is found, so the caller can tell the user
     * instead of silently dropping the paste.
     */
    fun parseCoordinates(text: String?): Pair<Double, Double>? {
        if (text.isNullOrBlank()) return null

        // 1. Explicitly tagged pairs win — they cannot be a coincidence.
        TAGGED_PATTERN.find(text)?.let { m ->
            toCoordinates(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

        // 2. Google's internal `!3d<lat>!4d<lng>` marker, which sits after the @ pair.
        Regex("""!3d(-?\d{1,3}\.\d+)!4d(-?\d{1,3}\.\d+)""").find(text)?.let { m ->
            toCoordinates(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

        // 3. Query parameters in any order.
        val lat = Regex("""[?&](?:lat|latitude)=(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
        val lng = Regex("""[?&](?:lng|lon|long|longitude)=(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
        if (lat != null && lng != null) {
            toCoordinates(lat.groupValues[1], lng.groupValues[1])?.let { return it }
        }

        // 4. Last resort: the first plausible decimal pair anywhere in the text.
        for (m in PAIR_PATTERN.findAll(text)) {
            toCoordinates(m.groupValues[1], m.groupValues[2])?.let { return it }
        }
        return null
    }

    private fun toCoordinates(rawLat: String, rawLng: String): Pair<Double, Double>? {
        val latIsDecimal = rawLat.contains('.')
        val lngIsDecimal = rawLng.contains('.')
        if (!latIsDecimal && !lngIsDecimal) return null

        val lat = rawLat.toDoubleOrNull() ?: return null
        val lng = rawLng.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0) return null
        if (lng !in -180.0..180.0) return null
        return lat to lng
    }

    // -------------------------------------------------------------- drift

    /** How often the drift may move the fix, in milliseconds. */
    private const val DRIFT_TICK_MS = 1_000L

    /**
     * Random-walk generator used to turn a static anchor into a believable
     * track.
     *
     * It walks in a local metric frame, pulls the point back toward the anchor
     * once it leaves [SpoofConfig.driftRadiusMeters], and re-seeds itself
     * whenever the user changes the anchor or the radius. Movement is capped at
     * [DRIFT_TICK_MS] granularity so a burst of hooked getters all observe the
     * same position instead of a stuttering jitter.
     *
     * One instance per hooked process; every method is synchronised because
     * location callbacks and getters arrive on arbitrary threads.
     */
    class DriftEngine {
        private var east = 0.0
        private var north = 0.0
        private var anchorLat = Double.NaN
        private var anchorLng = Double.NaN
        private var radius = Double.NaN
        private var lastTickMs = 0L
        private var cachedLat = Double.NaN
        private var cachedLng = Double.NaN

        @Synchronized
        fun position(config: SpoofConfig, nowMs: Long): Pair<Double, Double> {
            val reseed = anchorLat != config.latitude ||
                anchorLng != config.longitude ||
                radius != config.driftRadiusMeters

            if (reseed) {
                anchorLat = config.latitude
                anchorLng = config.longitude
                radius = config.driftRadiusMeters
                east = 0.0
                north = 0.0
                lastTickMs = nowMs
                cachedLat = config.latitude
                cachedLng = config.longitude
                return cachedLat to cachedLng
            }

            val elapsed = nowMs - lastTickMs
            if (elapsed < DRIFT_TICK_MS || radius <= 0.0) {
                return cachedLat to cachedLng
            }
            lastTickMs = nowMs

            // At most 2 s of travel per tick, so a stalled process does not
            // teleport when it wakes up.
            val seconds = (elapsed / 1000.0).coerceAtMost(2.0)
            val step = config.driftSpeedMps * seconds
            val heading = 2 * Math.PI * Random.nextDouble()
            east += sin(heading) * step
            north += cos(heading) * step

            // Spring the walker back inside the circle: a pure random walk drifts
            // away from the anchor, a clipped one pins itself to the edge.
            val distance = hypot(east, north)
            if (distance > radius) {
                val pull = 0.55 + 0.45 * Random.nextDouble()
                east *= (radius / distance) * pull
                north *= (radius / distance) * pull
            }

            val (lat, lng) = offsetMetres(anchorLat, anchorLng, east, north)
            cachedLat = lat
            cachedLng = lng
            return lat to lng
        }

        /** Drops the accumulated walk. Call when spoofing is switched off. */
        @Synchronized
        fun reset() {
            east = 0.0
            north = 0.0
            anchorLat = Double.NaN
            anchorLng = Double.NaN
            radius = Double.NaN
            lastTickMs = 0L
            cachedLat = Double.NaN
            cachedLng = Double.NaN
        }
    }
}
