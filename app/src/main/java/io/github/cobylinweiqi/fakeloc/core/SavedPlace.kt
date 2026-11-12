package io.github.cobylinweiqi.fakeloc.core

import kotlin.math.abs

/**
 * A spot the user has already been at, kept so they do not have to find it again.
 *
 * **The address travels with it.** A saved place that remembered only the
 * coordinates would be exactly the half-converted state this project keeps
 * running into: the anchor moves, the numbers on the home screen change, and an
 * app that reads its city from the map SDK keeps printing the real one. Storing
 * [PickedAddress] beside the pair is the only way "go back to the office" can
 * mean the same thing it meant the first time.
 *
 * [pinned] is the difference between the two lists on the home screen: pinned
 * places survive eviction, unpinned ones are a rolling history. They live in one
 * array rather than two so a place cannot exist in both and disagree with itself.
 */
data class SavedPlace(
    val latitude: Double,
    val longitude: Double,
    val address: PickedAddress = PickedAddress.NONE,
    val pinned: Boolean = false,
) {
    /**
     * What the row prints.
     *
     * Falls back through the three names a place can have: the POI label the
     * geocoder recommended, the formatted line, and finally the raw pair. A
     * hand-typed coordinate has no address at all, and a blank row would look
     * like a bug rather than like "this one was entered manually".
     */
    val label: String
        get() = address.placeName.ifBlank {
            address.line.ifBlank {
                "%.4f, %.4f".format(latitude, longitude)
            }
        }

    /** The line under [label], or blank when it would repeat the label. */
    val detail: String
        get() = listOf(address.province, address.city, address.district)
            .filter { it.isNotBlank() }
            .joinToString("")
            .takeIf { it.isNotBlank() && it != label }
            .orEmpty()

    /**
     * Whether [latitude] / [longitude] describe this same spot.
     *
     * Two hundred micro-degrees is roughly twenty metres — the scale at which a
     * re-pick is "the same place" rather than "a new one". Tighter and walking
     * across the road would build a new history entry each time; looser and the
     * list fills with the same building.
     */
    fun isSameSpot(latitude: Double, longitude: Double): Boolean =
        abs(this.latitude - latitude) < SPOT_EPSILON &&
            abs(this.longitude - longitude) < SPOT_EPSILON

    private companion object {
        const val SPOT_EPSILON = 2e-4
    }
}

/** How many unpinned entries the history keeps. */
const val RECENT_PLACES_LIMIT = 6

/** The anchor as it stands, address included. */
fun SpoofConfig.currentPlace(pinned: Boolean = false): SavedPlace = SavedPlace(
    latitude = latitude,
    longitude = longitude,
    address = PickedAddress(
        country = addrCountry,
        province = addrProvince,
        city = addrCity,
        district = addrDistrict,
        town = addrTown,
        street = addrStreet,
        adCode = addrAdCode,
        cityCode = addrCityCode,
        placeName = addrPlaceName,
        line = addrLine,
    ),
    pinned = pinned,
)

/**
 * Records the anchor as it now stands, newest first.
 *
 * Called from the one place every anchor change goes through, so presets, the
 * clipboard, "use real GPS" and the map picker all end up in the history without
 * each of them having to remember to. Moving to a spot that is already in the
 * list promotes it instead of adding a twin, and a spot that had been pinned
 * stays pinned when it is revisited.
 */
fun SpoofConfig.recordingAnchor(): SpoofConfig {
    val existing = places.firstOrNull { it.isSameSpot(latitude, longitude) }
    val incoming = currentPlace(pinned = existing?.pinned == true)
    val merged = listOf(incoming) + places.filterNot { it.isSameSpot(latitude, longitude) }

    // Pins are never evicted; the history is a fixed-length tail. Built by hand
    // rather than with `take` so the pins keep their positions in the list.
    val kept = ArrayList<SavedPlace>(merged.size)
    var recent = 0
    for (place in merged) {
        if (place.pinned) {
            kept += place
        } else if (recent < RECENT_PLACES_LIMIT) {
            kept += place
            recent++
        }
    }
    return copy(places = kept)
}

/** Moves the anchor to [place], address and all — the same path the picker uses. */
fun SpoofConfig.goToPlace(place: SavedPlace): SpoofConfig =
    withAnchor(place.latitude, place.longitude, place.address)

/**
 * Pins or unpins the place at [latitude] / [longitude].
 *
 * Keyed on the coordinates rather than on an index: a row's position changes
 * every time the list is re-sorted by a new pick, and an index captured by the
 * UI would then point at a different place.
 */
fun SpoofConfig.togglePinned(latitude: Double, longitude: Double): SpoofConfig = copy(
    places = places.map {
        if (it.isSameSpot(latitude, longitude)) it.copy(pinned = !it.pinned) else it
    },
)

/** The two lists the home screen shows, in stored order. */
val SpoofConfig.pinnedPlaces: List<SavedPlace>
    get() = places.filter { it.pinned }

val SpoofConfig.recentPlaces: List<SavedPlace>
    get() = places.filterNot { it.pinned }
