package io.github.cobylinweiqi.fakeloc.ui.map

import android.content.Context
import android.util.Log
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.HttpResponseListener
// `object` is a Kotlin **hard keyword** and this vendor's package has it as a
// segment, so each of these imports needs it escaped. Without the backticks the
// import is a *syntax* error — "Qualified name must be a '.'-separated
// identifier" — and every use of the class then reports "Unresolved reference",
// which reads as a missing dependency and sends you looking at the gradle cache
// instead of at the quotes (hit on 2026-09-30; the whole package is fine, only
// the spelling is). `TencentSearch` and `httpresponse` import normally — they
// have no keyword in the path — which is what makes the failure look partial
// and confusing rather than uniform.
import com.tencent.lbssearch.`object`.param.CoordTypeEnum
import com.tencent.lbssearch.`object`.param.Geo2AddressParam
import com.tencent.lbssearch.`object`.param.SuggestionParam
import com.tencent.lbssearch.`object`.result.Geo2AddressResultObject
import com.tencent.lbssearch.`object`.result.SuggestionResultObject
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import io.github.cobylinweiqi.fakeloc.core.PickedAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Address lookup through the map SDK that is already in the APK.
 *
 * ## Why this exists at all
 *
 * The picker used to resolve addresses with `android.location.Geocoder` alone,
 * and that turned out to be the weakest link in the address path: on a mainland
 * ROM the platform geocoder either has no backend at all or needs Google Play
 * services to reach one, so `getFromLocation` answers with an empty list and
 * **no error**. The visible result is a picker that draws tiles perfectly but
 * stores no city, and a target app whose header keeps the city it cached —
 * "the coordinates changed and the city name did not", reported again on
 * 2026-09-30 against 1.6.0.
 *
 * It also could not have supplied `adcode` / `city_code` even when it worked,
 * and those two have been dead weight since v1.5.0: `PickedAddress` declares
 * them, `withAnchor` writes them, `SpoofConfig.addressFor` reads them, and the
 * three SDK address-getter lists all include `getCityCode` / `getAdCode` — but
 * nothing ever produced a value. This file is what produces one.
 *
 * ## What it costs
 *
 * Nothing. `com.tencent.lbssearch.TencentSearch` and its result models are
 * already inside `tencent-map-vector-sdk`, the one SDK this build ships, and
 * `proguard-rules.pro` already keeps `com.tencent.**` whole because the map
 * resolves its own classes by name. No new dependency, no new key: the picker
 * hands over the same key it draws the map with.
 *
 * ## The one thing that is easy to get wrong
 *
 * **Use the three-argument constructor.** `TencentSearch(Context, String)` does
 * *not* take an API key — it forwards to `(context, null, arg)`, so the string
 * lands in `mSecretKey` and `mApiKey` stays null. `doHttpGet` then falls back to
 * `Util.getMetaKey(context, "TencentMapSDK")` (a manifest entry this project
 * deliberately does not have) and finally answers every request with
 * `onFailure(-1, "请申请并填写开发者密钥")` — silently, which is exactly the
 * failure shape this whole file is meant to remove. Read off the bytecode of
 * `tencent-map-vector-sdk:5.9.0` on 2026-09-30, not from sample code.
 *
 * The third argument is the signing secret, and it is not optional in the way
 * the parameter list suggests. A key with 签名校验 switched on makes Tencent's
 * gateway refuse every unsigned request with `status 111 签名验证失败`, and that
 * is a perfectly ordinary way for a key to be configured — it is how the key
 * this file was first debugged against was set up (2026-09-30). The chain is:
 * `TencentSearch` keeps the secret, hands it to `HttpProvider.get`, which puts
 * it on a `NetRequest`; `NetRequestBuilder.doGet` → `onRequestStart` installs
 * `com.tencent.mapsdk.internal.mm`, whose `onRequest` computes
 * `MD5(canonical query + secret)` and appends it as `sig`. `mm` returns
 * immediately when the secret is empty, so `""` sends the request *unsigned*
 * rather than sending a bogus signature. That makes the empty string the right
 * value for a key that does not sign — and a silently broken value for one that
 * does. Read off the same bytecode, same date.
 *
 * ## Datum
 *
 * The picker works in WGS-84 throughout, and this file keeps that promise: the
 * point goes out as GCJ-02 (the SDK's own datum, declared as such) and comes
 * back converted, so callers never see a second coordinate system.
 */

/**
 * Where a rejected lookup shows up.
 *
 * This is a logcat tag rather than a LSPosed one on purpose: the picker runs in
 * the module's own process, and the manager only collects log lines from hooked
 * processes. `adb logcat -s FakeLoc/MapLookup` is the reader.
 */
private const val LOOKUP_TAG = "FakeLoc/MapLookup"

/** How long a lookup may take before the caller falls back to its own path. */
private const val LOOKUP_TIMEOUT_MS = 6_000L

/** Rows asked of the suggestion endpoint; the panel shows [MAX_SEARCH_HITS]. */
private const val SUGGESTION_LIMIT = 8

/** "Any region" for the suggestion endpoint. */
private const val REGION_ANY = ""

/**
 * One suggestion row, already converted back to WGS-84.
 *
 * Flattened off `SuggestionResultObject.SuggestionData` for the same reason the
 * screen flattens `android.location.Address`: the SDK's row carries an id, a
 * category, a POI type and a distance that nothing renders, and holding it would
 * keep the response object alive through Compose state.
 */
internal data class TencentPlaceHit(
    val title: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Reverse-geocodes a WGS-84 point through the map SDK.
 *
 * Returns `null` when the lookup could not be answered at all — no key, no
 * network, the SDK refused, the server rejected the signature, or
 * [LOOKUP_TIMEOUT_MS] elapsed — so the caller can fall back to its own path. A
 * non-null result means the SDK answered; it may still be [PickedAddress.NONE]
 * in the (unlikely) case of a reply that carries no administrative name.
 *
 * A rejection is logged under [LOOKUP_TAG] with its status code, because "which
 * gate refused this" is the first question every time this breaks and the SDK
 * otherwise says nothing.
 */
internal suspend fun tencentReverseGeocode(
    context: Context,
    apiKey: String?,
    secretKey: String?,
    latitude: Double,
    longitude: Double,
): PickedAddress? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
    suspendCancellableCoroutine { cont ->
        val (gcjLat, gcjLng) = toGcj02(latitude, longitude)
        val param = Geo2AddressParam(LatLng(gcjLat, gcjLng))
            // The point handed over is already GCJ-02, so the request must not
            // be told to convert it again.
            // The one spelling the SDK publishes for this, and it is flagged
            // deprecated in the aar with no replacement named. `getPoi` below
            // has a non-deprecated twin and uses it; this one does not.
            .coord_type(CoordTypeEnum.DEFAULT)
            // Administrative names only. With POIs on, the reply's province /
            // city / district can be narrowed to the POI's rather than the
            // point's, which is the opposite of what the header needs.
            .getPoi(false)

        val search = runCatching { newSearch(context, apiKey, secretKey) }.getOrNull()
        if (search == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            search.geo2address(
                param,
                object : HttpResponseListener<Geo2AddressResultObject> {
                    override fun onSuccess(
                        statusCode: Int,
                        response: Geo2AddressResultObject?,
                    ) {
                        // The continuation is already dead if the timeout won.
                        // Resuming it then is not a no-op — it throws.
                        if (cont.isActive) cont.resume(readAddress(response))
                    }

                    override fun onFailure(
                        statusCode: Int,
                        responseBody: String?,
                        error: Throwable?,
                    ) {
                        // Printing nothing here cost a whole diagnosis round on
                        // 2026-09-30. That key has signing switched on, so every
                        // request came back `111 签名验证失败`, and the picker
                        // could only say it had no address — which reads exactly
                        // like a key that was never filled in. The status is the
                        // entire diagnosis; it has to reach the log.
                        Log.w(
                            LOOKUP_TAG,
                            "reverse geocode rejected: status=$statusCode " +
                                "body=$responseBody err=${error?.message}",
                        )
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (t: Throwable) {
            // `checkParams` and the network layer can both throw synchronously.
            // Letting that escape would take down the picker for a lookup that
            // is supposed to be optional.
            if (cont.isActive) cont.resume(null)
        }
    }
}

/**
 * Keyword suggestions for the search box, through the same SDK and key.
 *
 * The platform geocoder is the fallback here for the same reason it is one for
 * [tencentReverseGeocode]: a search box that answers nothing is indistinguishable
 * from a place that does not exist.
 *
 * Returns `null` when the lookup failed, and an empty list when it succeeded
 * with no matches — the panel says different things about the two.
 */
internal suspend fun tencentSuggestPlaces(
    context: Context,
    apiKey: String?,
    secretKey: String?,
    query: String,
    nearLatitude: Double,
    nearLongitude: Double,
): List<TencentPlaceHit>? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
    suspendCancellableCoroutine { cont ->
        val (gcjLat, gcjLng) = toGcj02(nearLatitude, nearLongitude)
        val param = SuggestionParam(query, REGION_ANY)
            // Nudges ranking towards what is on screen; the endpoint still
            // answers for the whole country.
            .location(LatLng(gcjLat, gcjLng))
            .pageSize(SUGGESTION_LIMIT)

        val search = runCatching { newSearch(context, apiKey, secretKey) }.getOrNull()
        if (search == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            search.suggestion(
                param,
                object : HttpResponseListener<SuggestionResultObject> {
                    override fun onSuccess(
                        statusCode: Int,
                        response: SuggestionResultObject?,
                    ) {
                        if (!cont.isActive) return
                        val rows = response?.data.orEmpty().mapNotNull { row ->
                            // A row without a coordinate cannot move the camera,
                            // which is the only thing a suggestion is for here.
                            val point = row.latLng ?: return@mapNotNull null
                            val (lat, lng) = gcj02ToWgs84(point.latitude, point.longitude)
                            TencentPlaceHit(
                                title = row.title.orEmpty().ifBlank { row.address.orEmpty() },
                                region = listOfNotNull(row.province, row.city, row.district)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                latitude = lat,
                                longitude = lng,
                            )
                        }
                        cont.resume(rows.ifEmpty { null })
                    }

                    override fun onFailure(
                        statusCode: Int,
                        responseBody: String?,
                        error: Throwable?,
                    ) {
                        // Same reason as the reverse-geocode listener: a silent
                        // failure here reads as "no such place" rather than
                        // "the request was refused".
                        Log.w(
                            LOOKUP_TAG,
                            "place suggestion rejected: status=$statusCode " +
                                "body=$responseBody err=${error?.message}",
                        )
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }
}

/**
 * Builds the client with the key in the **api-key** slot and the secret in the
 * **signing-secret** slot.
 *
 * Two arguments would compile and be wrong — see the file header. The
 * `orEmpty()` calls are not defensive noise either: a blank *key* has to reach
 * the SDK so it can answer with its own complaint ("请申请并填写开发者密钥")
 * rather than failing somewhere less legible, and a blank *secret* is a
 * meaningful instruction — "this key does not sign" — that must survive the trip.
 */
private fun newSearch(context: Context, apiKey: String?, secretKey: String?): TencentSearch =
    TencentSearch(
        // Application context: the client keeps whatever it is handed, and an
        // Activity here outlives the screen.
        context.applicationContext ?: context,
        apiKey.orEmpty(),
        secretKey.orEmpty(),
    )

/**
 * Maps the SDK's reply onto [PickedAddress].
 *
 * `address_component` carries the three administrative names; `ad_info` carries
 * the same three *plus* `adcode` and `city_code`, which is the pair nothing else
 * can supply. The component is preferred and the ad-info used to fill gaps
 * because some replies populate one and leave the other empty.
 */
private fun readAddress(response: Geo2AddressResultObject?): PickedAddress? {
    val reverse = response?.result ?: return null
    val component = reverse.address_component
    val info = reverse.ad_info

    // `firstNonBlank` instead of a chain of `?.takeIf { }.?:` — that chain is
    // the obvious way to write this and it does not compile: with the Java
    // fields typed as platform types, Kotlin 2.2 cannot infer the type of the
    // elvis operands and reports "actual type is 'String', but 'K (of fun <K>
    // ELVIS_CALL)' was expected" (measured 2026-09-30). The helper is also what
    // the two objects want anyway — they carry the same three names and a reply
    // tends to populate one of them and leave the other empty.
    val province = firstNonBlank(component?.province, info?.province)
    val city = firstNonBlank(component?.city, info?.city)
    val district = firstNonBlank(component?.district, info?.district)

    // Same test `PickedAddress.isResolved` uses: a municipality reports a city
    // and may leave the province blank, so either one is enough.
    if (province.isBlank() && city.isBlank()) return PickedAddress.NONE

    val joined = listOf(province, city, district)
        .filter { it.isNotBlank() }
        .joinToString("")

    return PickedAddress(
        country = firstNonBlank(component?.nation, info?.nation),
        province = province,
        city = city,
        district = district,
        // No town level: `AddressComponent` stops at district + street. Left
        // blank, which `addressFor` reads as "leave that getter alone".
        town = "",
        // The two fields v1.5.0 dropped on the floor, recovered.
        adCode = firstNonBlank(info?.adcode),
        cityCode = firstNonBlank(info?.city_code),
        line = reverse.address.orEmpty().ifBlank { joined },
    )
}

/** The first argument that carries text, or `""` when none of them does. */
private fun firstNonBlank(vararg values: String?): String {
    for (value in values) {
        if (!value.isNullOrBlank()) return value
    }
    return ""
}
