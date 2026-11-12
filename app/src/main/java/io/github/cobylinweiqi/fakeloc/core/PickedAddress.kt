package io.github.cobylinweiqi.fakeloc.core

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
    /** The street name, e.g. "邓高路" — what `getStreet()` answers. */
    val street: String = "",
    val adCode: String = "",
    val cityCode: String = "",
    /**
     * The place label the SDK recommended for this point, e.g. "腾讯滨海大厦".
     *
     * This is what a check-in screen prints as the place name; [line] is the
     * address printed under it. Blank when the reply carried no recommendation,
     * which the address hooks read as "leave `getName()` alone".
     */
    val placeName: String = "",
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
    addrStreet = address.street,
    addrAdCode = address.adCode,
    // Written straight through, *including* blank: the Tencent path has no
    // field in this namespace (see `readAddress`), so a blank here is the
    // correct state and also clears an administrative code that an earlier
    // build stored under this name.
    addrCityCode = address.cityCode,
    addrPlaceName = address.placeName,
    addrLine = address.line,
)

/**
 * Fills in the address of an anchor that already has its coordinates.
 *
 * Reverse geocoding answers asynchronously — the SDK posts to Tencent's server
 * and calls back hundreds of milliseconds later — so the anchor is always
 * positioned first and described second. This is the "described" half: the
 * coordinates are re-applied unchanged, and the address that arrived late is
 * attached to them.
 *
 * It also rewrites the stored history entry for this spot, which is the part
 * that stops the failure from coming back. A place recorded before its address
 * was known keeps an empty address forever, and re-selecting it from the home
 * screen calls [withAnchor] with that empty address — silently undoing the
 * repair. Measured 2026-11-12 on CCB's employee app: the anchor had been set
 * from the history, the check-in page reported 地理位置获取失败, and the config
 * showed every address field blank with a perfectly good pair of coordinates
 * beside them.
 *
 * A blank or partial [address] is ignored rather than written: writing
 * [PickedAddress.NONE] here would blank an address that is already good, and
 * every caller of this function is a lookup that may legitimately fail.
 */
fun SpoofConfig.withResolvedAddress(address: PickedAddress): SpoofConfig {
    if (!address.isResolved) return this
    val anchored = withAnchor(latitude, longitude, address)
    return anchored.copy(
        places = anchored.places.map {
            if (it.isSameSpot(latitude, longitude)) it.copy(address = address) else it
        },
    )
}

/**
 * Moves the anchor and **drops** the address that belonged to the old one.
 *
 * A stored address describes one specific anchor. Carrying it over to different
 * coordinates would have the module answer `getCity()` with a city the anchor is
 * not in — a lie that looks like a bug in the app being spoofed, and one nothing
 * in the UI would explain. Dropping it instead makes the address getters pass the
 * SDK's own value through, which is the same state a fresh install is in.
 *
 * Cheap to undo: the manager reverse-geocodes the new anchor as soon as it
 * settles (see `MainViewModel.scheduleAddress`), so the address comes back on its
 * own a moment later without the user having to do anything extra. Opening the
 * picker does the same thing by hand.
 */
fun SpoofConfig.moveTo(latitude: Double, longitude: Double): SpoofConfig = copy(
    latitude = latitude,
    longitude = longitude,
    addrCountry = "",
    addrProvince = "",
    addrCity = "",
    addrDistrict = "",
    addrTown = "",
    addrStreet = "",
    addrAdCode = "",
    addrCityCode = "",
    addrPlaceName = "",
    addrLine = "",
)
