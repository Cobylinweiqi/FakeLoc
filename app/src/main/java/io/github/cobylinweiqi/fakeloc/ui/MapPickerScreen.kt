package io.github.cobylinweiqi.fakeloc.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cobylinweiqi.fakeloc.R
import io.github.cobylinweiqi.fakeloc.core.Geo
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.PickedAddress
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import io.github.cobylinweiqi.fakeloc.mapsdk.MapSdkBootstrap
import io.github.cobylinweiqi.fakeloc.ui.map.MapCanvas
import io.github.cobylinweiqi.fakeloc.ui.map.PICKER_ZOOM
import io.github.cobylinweiqi.fakeloc.ui.map.SEARCH_ZOOM
import io.github.cobylinweiqi.fakeloc.ui.map.TencentPlaceHit
import io.github.cobylinweiqi.fakeloc.ui.map.tencentReverseGeocode
import io.github.cobylinweiqi.fakeloc.ui.map.tencentSuggestPlaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val GEOCODE_DEBOUNCE_MS = 450L
private const val SEARCH_DEBOUNCE_MS = 300L
private const val MAX_SEARCH_HITS = 8
private const val MIN_QUERY_LENGTH = 2

/**
 * Pick a spot on a real map.
 *
 * **The whole screen works in WGS-84**, which is what
 * [io.github.cobylinweiqi.fakeloc.core.SpoofConfig] stores and what `android.location` carries.
 * The platform's own datum is confined to its canvas — Tencent speaks GCJ-02 —
 * so there is exactly one conversion point per direction instead of one per
 * provider. Feeding a canvas the wrong datum puts the pin a few hundred metres
 * from where the user aimed while the map itself looks perfectly healthy, which
 * is why the footer says out loud which datum is being displayed.
 *
 * Two ways to aim, both live at once — drag the map so the pinned reticle lands
 * on the target, or search and let the camera fly there. The address under the
 * pin is resolved on a debounce, so a drag only fires one lookup once it settles.
 *
 * Address lookup goes through the **map SDK** first. `TencentSearch` is already
 * inside the bundled map SDK (no new dependency, no second key), it is handed
 * the same key the map draws with, and it is the only path that can answer
 * `adcode` / `city_code` — the two fields the hook side has had getters for
 * since 1.2.2 and no producer for since 1.5.0 removed the Baidu geocoder.
 * `android.location.Geocoder` stays as the fallback, for the devices where the
 * SDK lookup is refused; [tencentReverseGeocode] explains what it replaced and
 * why, [platformReverseGeocode] what it can still supply.
 */
@Composable
fun MapPickerScreen(
    initialLatitude: Double,
    initialLongitude: Double,
    config: SpoofConfig,
    onPicked: (Double, Double, PickedAddress) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val provider = config.mapProvider
    val mapKey = config.mapKeyFor(provider)
    // Raw, blank included: an empty secret is a meaningful instruction to
    // Tencent's SDK ("this key does not sign"), not something to filter out.
    val mapSecret = config.mapSecretValue(provider)
    val configured = config.mapKeyConfigured(provider)

    // --------------------------------------------------------------- the SDK
    //
    // Deliberately `remember` and not `LaunchedEffect`. Amap's `MapView` draws
    // nothing at all unless `MapsInitializer.initialize` has already run, and an
    // effect fires *after* the frame that creates the view. Running during
    // composition puts the initialisation strictly before the canvas below.
    //
    // It is safe to do work here because `ensure` is idempotent and memoised on
    // the same keys: it starts a provider at most once per process, and the
    // `remember` means at most once per screen entry.
    val status = remember(provider, mapKey, configured) {
        if (configured) MapSdkBootstrap.ensure(context, config) else null
    }

    // A base map that could not build its view at all. Held as the throwable
    // rather than a boolean so the panel can show the SDK's own complaint: an
    // `UnsatisfiedLinkError` (no `.so` for this ABI) and an authorisation
    // refusal look nothing alike to whoever has to fix it, and "the map failed"
    // would not say which of the two happened.
    //
    // Keyed on provider and key so that changing either clears it — the failure
    // belonged to the view that has just been thrown away.
    val mapFailure = remember(provider, mapKey) { mutableStateOf<Throwable?>(null) }

    // ------------------------------------------------------------- the state

    // The centre, WGS-84. Starts at the stored anchor; the canvas reports back
    // after the opening camera move, so this is never stale for long.
    val center = remember { mutableStateOf(initialLatitude to initialLongitude) }

    // Where the camera should be. Separate from `center` because aiming at a
    // place and reporting a place are different directions: a search result
    // moves the camera, and only the callback that follows moves `center`.
    var targetLatitude by remember { mutableStateOf(initialLatitude) }
    var targetLongitude by remember { mutableStateOf(initialLongitude) }
    var targetZoom by remember { mutableStateOf(PICKER_ZOOM) }
    var moveNonce by remember { mutableIntStateOf(0) }

    fun aimAt(latitude: Double, longitude: Double, zoom: Float) {
        targetLatitude = latitude
        targetLongitude = longitude
        targetZoom = zoom
        moveNonce++
    }

    val address = remember { mutableStateOf<String?>(null) }

    // The structured form of `address`, and the only thing that ever reaches the
    // config. Kept beside the display string rather than parsed back out of it:
    // an app that shows a city looks the *name* up in its own table, and a
    // formatted line has lost the boundaries between province, city and district.
    val resolved = remember { mutableStateOf(PickedAddress.NONE) }
    val resolving = remember { mutableStateOf(false) }

    // Set when both lookup paths come up empty. Deliberately not folded into
    // `address == null`: "this key is not allowed to geocode" and "you have not
    // aimed at anything yet" need opposite responses from whoever is looking at
    // the screen, and an unresolved address is otherwise silent.
    val lookupFailed = remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    val hits = remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    val searchFailed = remember { mutableStateOf(false) }

    // ------------------------------------------------------------ geocoding

    // Re-resolve the address once the centre stops moving. Keying the effect on
    // the centre cancels the pending delay on every drag event, which *is* the
    // debounce — no timestamp bookkeeping needed.
    //
    // There used to be a second, structured answer racing this one: Baidu's SDK
    // geocoder ran first when Baidu was drawing, with a three-second deadline and
    // a `fallbackCenter` guard so a late reply could not overwrite the platform
    // geocoder's newer one. All of that went with the dependency. What it bought
    // was `addressDetail.province/city/district` and an `adcode` straight from
    // Baidu; the platform geocoder supplies the same three fields from
    // `adminArea` / `locality` / `subLocality`, which is all the hook side ever
    // reads them for — it answers the target SDK's `getCity()` / `getProvince()`
    // getters and looks the name up in the app's own table.
    val centerWgs = center.value

    // Read through `rememberUpdatedState` so that a key pasted while this screen
    // is open reaches the next lookup — the effect below is keyed on the centre
    // alone and would otherwise hold the key it started with.
    val lookupKey by rememberUpdatedState(mapKey)

    // The signing secret gets the same treatment, and for the same reason: it is
    // edited on the settings page rather than here, but this screen can already
    // be open when it changes.
    val lookupSecret by rememberUpdatedState(mapSecret)

    LaunchedEffect(centerWgs) {
        val (centerLat, centerLng) = centerWgs
        resolving.value = true
        // Cleared before the lookup, not after: a confirm pressed during the
        // debounce must store *nothing* rather than the previous centre's
        // address, which would pair the new coordinates with the old city.
        resolved.value = PickedAddress.NONE
        address.value = null
        lookupFailed.value = false
        delay(GEOCODE_DEBOUNCE_MS)

        // SDK first, platform second. The SDK path is the one that knows the
        // adcode and the city code, and the one that does not need the ROM to
        // have a working geocoder of its own; the platform path is tried
        // whenever it produced nothing at all, including the case where it
        // answered but had no administrative name to give.
        val viaSdk = tencentReverseGeocode(context, lookupKey, lookupSecret, centerLat, centerLng)
        val picked = if (viaSdk != null && viaSdk.isResolved) {
            viaSdk
        } else {
            platformReverseGeocode(context, centerLat, centerLng)
        }

        if (picked.isResolved) {
            resolved.value = picked
            address.value = picked.line.ifBlank { picked.city }
        } else {
            lookupFailed.value = true
        }
        resolving.value = false
    }

    // Search as you type, one request per pause in typing.
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.length < MIN_QUERY_LENGTH) {
            hits.value = emptyList()
            searchFailed.value = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)

        // Same order as the reverse lookup, for the same reason: the SDK answers
        // on devices where the platform geocoder has no backend. `null` means
        // the lookup failed and an empty list means it succeeded with no
        // matches — the panel says different things about the two, so they are
        // kept apart all the way there.
        val viaSdk = tencentSuggestPlaces(
            context,
            lookupKey,
            lookupSecret,
            keyword,
            centerWgs.first,
            centerWgs.second,
        )
        val found = viaSdk?.map { hit ->
            PlaceHit(
                label = hit.title,
                region = hit.region,
                latitude = hit.latitude,
                longitude = hit.longitude,
            )
        } ?: platformSearchPlaces(context, keyword)

        // A query that changed while this one was in flight must not get to paint
        // rows for a keyword the user has already replaced.
        if (query.trim() != keyword) return@LaunchedEffect
        hits.value = found.orEmpty()
        searchFailed.value = found == null
    }

    // ---------------------------------------------------------------- layout

    Column(modifier = modifier.fillMaxSize()) {
        MapPickerTopBar(provider = provider, onBack = onBack, onOpenSettings = onOpenSettings)

        Box(Modifier.weight(1f)) {
            // Three states, not two. A view that threw is not the same as a key
            // that is missing, and treating them alike is what let the first
            // version of this screen die silently: the map was "configured", so
            // the canvas was built, and the exception it threw out of the view
            // factory killed the process.
            val failure = mapFailure.value
            val drawable = configured && failure == null

            if (drawable) {
                MapCanvas(
                    provider = provider,
                    targetLatitude = targetLatitude,
                    targetLongitude = targetLongitude,
                    targetZoom = targetZoom,
                    moveNonce = moveNonce,
                    onCenterChanged = { latitude, longitude -> center.value = latitude to longitude },
                    onFailed = { mapFailure.value = it },
                    mapKey = mapKey,
                    modifier = Modifier.fillMaxSize(),
                )
                CenterPin()
            } else if (failure != null) {
                MapFailedPanel(
                    detail = failure.message ?: failure.javaClass.simpleName,
                    onOpenSettings = onOpenSettings,
                )
            } else {
                MissingKeyPanel(provider = provider, onOpenSettings = onOpenSettings)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                StatusBanner(status)
                if (drawable) {
                    SearchPanel(
                        query = query,
                        onQueryChange = { query = it },
                        hits = hits.value,
                        failed = searchFailed.value,
                        onPickHit = { hit ->
                            aimAt(hit.latitude, hit.longitude, SEARCH_ZOOM)
                            query = ""
                            hits.value = emptyList()
                        },
                        onClear = {
                            query = ""
                            hits.value = emptyList()
                            searchFailed.value = false
                        },
                    )
                }
            }
        }

        PickerFooter(
            latitude = centerWgs.first,
            longitude = centerWgs.second,
            address = address.value,
            resolving = resolving.value,
            failed = lookupFailed.value,
            enabled = configured,
            onRecentre = { aimAt(initialLatitude, initialLongitude, PICKER_ZOOM) },
            onConfirm = { onPicked(centerWgs.first, centerWgs.second, resolved.value) },
        )
    }
}

// ------------------------------------------------------------------ lookup

/**
 * One row of search results.
 *
 * Flattened away from `android.location.Address` on purpose: the `Address`
 * objects are large, carry an `extras` bundle each, and would keep the geocoder
 * alive through the snapshot state that holds them. This is the four fields the
 * panel actually renders.
 */
private data class PlaceHit(
    val label: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Forward-geocodes [query] with the **platform** geocoder.
 *
 * Chosen over Baidu's `SuggestionSearch` — which this screen used before it grew
 * a provider switch, and which is gone with the dependency — for one reason: it
 * is provider-agnostic and needs no key. That mattered when the picker could draw
 * with any of three platforms and it matters more now, because the whole point of
 * this build is that a map key is the only credential the user has to obtain, and
 * asking for a second one just to type into a search box would be a poor trade.
 *
 * Returns `null` when the lookup failed and an empty list when it succeeded with
 * no matches; the panel says different things about the two.
 */
private suspend fun platformSearchPlaces(
    context: Context,
    query: String,
): List<PlaceHit>? = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        @Suppress("DEPRECATION")
        val found = Geocoder(context, Locale.getDefault())
            .getFromLocationName(query, MAX_SEARCH_HITS)
            .orEmpty()

        found.mapNotNull { hit ->
            if (!hit.hasLatitude() || !hit.hasLongitude()) return@mapNotNull null
            val province = hit.adminArea.orEmpty()
            // `locality` is the city on a well-behaved build; a few ROMs leave it
            // empty and put the city in `subAdminArea` instead.
            val city = hit.locality.orEmpty().ifBlank { hit.subAdminArea.orEmpty() }
            val district = hit.subLocality.orEmpty()
            val line = hit.getAddressLine(0).orEmpty()
            PlaceHit(
                label = hit.featureName.orEmpty().ifBlank { line },
                region = listOf(province, city, district)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                latitude = hit.latitude,
                longitude = hit.longitude,
            )
        }
    }.getOrNull()
}

/**
 * Reverse-geocodes a WGS-84 point with the **platform** geocoder.
 *
 * This is the **fallback** behind [tencentReverseGeocode], not the primary path,
 * and the ordering is not a preference: on a mainland ROM `Geocoder.isPresent()`
 * can be true while every lookup answers with an empty list and no error, which
 * reads from the outside as a module that does nothing. It is kept because it
 * needs no key at all and costs nothing to try second.
 *
 * Its field mapping is Android's: `adminArea` is the province, `locality` the
 * city, `subLocality` the district. The two things it cannot supply — `adcode`
 * and `city_code` — are the ones the SDK path adds.
 *
 * Returns [PickedAddress.NONE] when the platform has no geocoder (some builds
 * ship none) or the lookup fails; the caller then keeps reporting "unresolved"
 * rather than storing a guess.
 */
private suspend fun platformReverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): PickedAddress = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching PickedAddress.NONE
        @Suppress("DEPRECATION")
        val found = Geocoder(context, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?: return@runCatching PickedAddress.NONE

        val province = found.adminArea.orEmpty()
        // `locality` is the city on a well-behaved build; a few ROMs leave it
        // empty and put the city in `subAdminArea` instead.
        val city = found.locality.orEmpty().ifBlank { found.subAdminArea.orEmpty() }
        val district = found.subLocality.orEmpty()
        val street = found.thoroughfare.orEmpty()

        if (province.isBlank() && city.isBlank() && district.isBlank()) {
            return@runCatching PickedAddress.NONE
        }
        PickedAddress(
            country = found.countryName.orEmpty(),
            province = province,
            city = city,
            district = district,
            line = listOf(province, city, district, street)
                .filter { it.isNotBlank() }
                .joinToString(""),
        )
    }.getOrDefault(PickedAddress.NONE)
}

// ------------------------------------------------------------------- chrome

@Composable
private fun MapPickerTopBar(
    provider: MapProvider,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
            )
        }
        Text(
            text = stringResource(R.string.map_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        // Which platform is drawing, right where the user is looking at it: the
        // three look similar enough that "why is my key not being used" otherwise
        // turns into a hunt through the settings page.
        Text(
            text = stringResource(providerLabel(provider)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The fixed reticle at the centre of the map. */
@Composable
private fun CenterPin() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Ground dot, so the pin reads as standing on a spot rather than floating.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
        )
        Icon(
            imageVector = Icons.Rounded.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                // The Place glyph's tip sits on the bottom edge of its box, so
                // lifting by half the size lands that tip exactly on the centre —
                // which is the coordinate we report.
                .offset(y = (-20).dp)
                .size(40.dp),
        )
    }
}

/**
 * Says what the SDK did, when it is not a plain success.
 *
 * Both messages are about the *process*, not about this screen: every one of
 * these SDKs reads its key once and a second key is ignored, so a provider or key
 * change can only be honoured by a restart. Silence here would leave the user
 * staring at a map that keeps failing authorisation for a key they just pasted.
 */
@Composable
private fun StatusBanner(status: MapSdkBootstrap.Status?) {
    val (text, container, onContainer) = when (status) {
        is MapSdkBootstrap.Status.Failed -> Triple(
            stringResource(R.string.map_sdk_failed, status.detail),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )

        MapSdkBootstrap.Status.RestartNeeded -> Triple(
            stringResource(R.string.map_sdk_restart),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        else -> return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = container,
        shadowElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    hits: List<PlaceHit>,
    failed: Boolean,
    onPickHit: (PlaceHit) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(2.dp),
                placeholder = { Text(stringResource(R.string.map_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = stringResource(R.string.cd_clear),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }

        when {
            hits.isNotEmpty() -> {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp,
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 264.dp)) {
                        items(hits) { hit ->
                            SearchHitRow(hit = hit, onClick = { onPickHit(hit) })
                        }
                    }
                }
            }

            failed -> {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(R.string.map_search_failed),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: PlaceHit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = hit.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hit.region.isNotEmpty()) {
                Text(
                    text = hit.region,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown instead of the map when the selected provider has no credential.
 *
 * A blank map with a live pin would be worse than this: dragging it would look
 * like it worked, and the coordinates it reported would come from a camera that
 * was never actually rendering the place the user thought they were aiming at.
 * The button is here rather than only on the home screen because this is where
 * the problem becomes visible.
 */
@Composable
private fun MissingKeyPanel(
    provider: MapProvider,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenGutter),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 4.dp,
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(
                            R.string.map_needs_key_title,
                            stringResource(providerLabel(provider)),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.map_needs_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.88f),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.map_open_settings))
                    }
                }
            }
        }
    }
}

/**
 * Shown when a provider's SDK refused to build its view at all.
 *
 * This exists because the alternative is what actually happened: the exception
 * escaped the view factory into Compose's change application, and the process
 * was SIGKILLed the moment the picker opened — no dialog, no frame, and nothing
 * on screen that pointed at the map rather than at the app being broken.
 *
 * Kept separate from [MissingKeyPanel] because the two call for opposite
 * responses. A missing key is fixed by typing one. A view that threw usually is
 * not: the key may be perfectly good and the problem may be the ABI, the privacy
 * consent, or the SDK's own state, so this panel reports the SDK's complaint
 * verbatim and sends the user to the one place that can change any of it.
 */
@Composable
private fun MapFailedPanel(
    detail: String,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenGutter),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 4.dp,
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.map_sdk_failed, detail),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.map_failed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.88f),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.map_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerFooter(
    latitude: Double,
    longitude: Double,
    address: String?,
    resolving: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onRecentre: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        resolving -> stringResource(R.string.map_resolving)
                        // Named cause before generic symptom. On the SDK path an
                        // unresolved address almost always means the key is not
                        // authorised for reverse geocoding (or the network is
                        // gone), and "unknown address" gives the user nothing to
                        // act on — which is how the same failure went unnoticed
                        // for a release.
                        address.isNullOrBlank() && failed ->
                            stringResource(R.string.map_address_failed)
                        address.isNullOrBlank() -> stringResource(R.string.map_address_unknown)
                        else -> address.orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (address.isNullOrBlank() && !resolving) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRecentre, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.map_recenter))
                }
            }

            Spacer(Modifier.height(6.dp))

            // WGS-84: the value that is actually stored and injected, matching
            // the home screen's coordinate box digit for digit.
            Text(
                text = "${Geo.format(latitude)}, ${Geo.format(longitude)}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.map_center_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.map_datum_note),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onConfirm,
                // **Not while a lookup is in flight.** The address is cleared
                // before each lookup and only filled in once the reply lands, so
                // confirming during the debounce stores an anchor that carries no
                // address at all — coordinates that move while the city name
                // stays put, which is the exact symptom this path exists to
                // remove. Waiting costs one debounce interval; the alternative
                // silently throws the address away.
                enabled = enabled && !resolving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_use_this),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
