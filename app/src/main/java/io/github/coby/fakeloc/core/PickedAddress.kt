package io.github.coby.fakeloc.core

/**
 * The address the map picker resolved for the anchor.
 *
 * Kept as its own type rather than folded straight into [SpoofConfig] because the
 * two are resolved by different code and at different times: the coordinates come
 * from wherever the user aimed, the address only from the picker's reverse
 * geocoder. Naming it makes it obvious at the call site which one a change
 * carries — see [withAnchor] versus [moveTo].
 *
 * All parts default to blank, which means "not resolved". Blank is not the same
 * as empty-on-purpose: [SpoofConfig.addressFor] turns a blank part into "leave
 * that getter alone" rather than into an empty string, because an app handed an
 * empty city acts on it — measurably, by keeping whatever city it cached last.
 */
data class PickedAddress(
    val country: String = "",
    val province: String = "",
    /** The administrative name, e.g. "吉林市" — not "吉林". See [SpoofConfig.addrCity]. */
    val city: String = "",
    val district: String = "",
    val town: String = "",
    val adCode: String = "",
    val cityCode: String = "",
    /** The formatted one-line address, e.g. "吉林省吉林市船营区…". */
    val line: String = "",
) {
    /**
     * `true` once reverse geocoding produced something worth keeping.
     *
     * Neither the province nor the city alone is enough on a mainland China
     * address — but a municipality such as 北京市 reports its city and may leave
     * the province blank, so testing either one is the right check.
     */
    val isResolved: Boolean
        get() = city.isNotBlank() || province.isNotBlank()

    companion object {
        /** Nothing resolved yet: every part left alone. */
        val NONE = PickedAddress()
    }
}

/**
 * The anchor *and* its address, applied together.
 *
 * The one path that should use this is the map picker, which is the only place
 * that knows both. Everything else uses [moveTo].
 */
fun SpoofConfig.withAnchor(
    latitude: Double,
    longitude: Double,
    address: PickedAddress,
): SpoofConfig = copy(
    latitude = latitude,
    longitude = longitude,
    addrCountry = address.country,
    addrProvince = address.province,
    addrCity = address.city,
    addrDistrict = address.district,
    addrTown = address.town,
    addrAdCode = address.adCode,
    addrCityCode = address.cityCode,
    addrLine = address.line,
)

/**
 * Moves the anchor and **drops** the address that belonged to the old one.
 *
 * A stored address describes one specific anchor. Carrying it over to different
 * coordinates would have the module answer `getCity()` with a city the anchor is
 * not in — a lie that looks like a bug in the app being spoofed, and one nothing
 * in the UI would explain. Dropping it instead makes the address getters pass the
 * SDK's own value through, which is the same state a fresh install is in.
 *
 * Cheap to undo: the picker reverse-geocodes as soon as it opens, so re-aiming
 * through it restores the address without the user having to do anything extra.
 */
fun SpoofConfig.moveTo(latitude: Double, longitude: Double): SpoofConfig = copy(
    latitude = latitude,
    longitude = longitude,
    addrCountry = "",
    addrProvince = "",
    addrCity = "",
    addrDistrict = "",
    addrTown = "",
    addrAdCode = "",
    addrCityCode = "",
    addrLine = "",
)
