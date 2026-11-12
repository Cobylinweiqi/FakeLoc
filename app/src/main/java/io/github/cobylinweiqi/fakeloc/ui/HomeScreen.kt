package io.github.cobylinweiqi.fakeloc.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.cobylinweiqi.fakeloc.R
import io.github.cobylinweiqi.fakeloc.core.Geo
import io.github.cobylinweiqi.fakeloc.core.SavedPlace
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import io.github.cobylinweiqi.fakeloc.core.moveTo
import io.github.cobylinweiqi.fakeloc.core.pinnedPlaces
import io.github.cobylinweiqi.fakeloc.core.recentPlaces
import kotlin.math.abs

/**
 * The home screen: everything a user touches in the common case.
 *
 * State is *not* held here — the screen is a pure function of [config] plus the
 * callbacks it is handed. The only local state is the two in-progress coordinate
 * text fields, re-synced from [config] whenever the anchor changes elsewhere
 * (preset tap, clipboard parse, "use real GPS"), plus the one dialog.
 *
 * The start/stop control lives **inside** the status card, not at the end of
 * the scroll. It used to sit below six cards, which meant the one action this
 * app exists for was off-screen on every launch — the screenshot that prompted
 * this rewrite showed a user staring at "Idle" with no visible way to change it.
 * Grouping it with the state it acts on also removes the question of which of
 * the two is authoritative.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    config: SpoofConfig,
    serviceBound: Boolean,
    addressState: AddressState,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
    onTogglePlaying: () -> Unit,
    onUseRealGps: () -> Unit,
    onPaste: (String?) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveCoordinates: (String, String) -> Unit,
    onPickSavedPlace: (SavedPlace) -> Unit,
    onTogglePin: (Double, Double) -> Unit,
    onRetryAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    // Pulsing halo while spoofing: 0.35 → 1.0, back and forth.
    val pulseTransition = rememberInfiniteTransition(label = "glow")
    val glow by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    var latitudeText by rememberSaveable { mutableStateOf("") }
    var longitudeText by rememberSaveable { mutableStateOf("") }
    var showModuleHelp by remember { mutableStateOf(false) }

    // Re-sync the editor whenever the anchor changes from the outside. Keyed on
    // the coordinates only, so tweaking a slider does not wipe an edit in
    // progress.
    LaunchedEffect(config.latitude, config.longitude) {
        latitudeText = Geo.format(config.latitude)
        longitudeText = Geo.format(config.longitude)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(BlockSpacing),
    ) {
        HeaderRow(onOpenSettings = onOpenSettings)

        StatusHeroCard(
            config = config,
            serviceBound = serviceBound,
            addressState = addressState,
            glow = if (config.playing) glow else 0f,
            onToggle = {
                // A press must never do nothing: with LSPosed's service bound it
                // flips the switch, and without it, it explains why flipping
                // would be a lie. Disabling the button instead would leave the
                // user with a dead control and no way to learn why.
                if (serviceBound) onTogglePlaying() else showModuleHelp = true
            },
            onExplain = { showModuleHelp = true },
            onOpenSettings = onOpenSettings,
            onRetryAddress = onRetryAddress,
        )

        MapPickCard(onOpenMap = onOpenMap)

        CoordinateEditorCard(
            config = config,
            latitudeText = latitudeText,
            longitudeText = longitudeText,
            onLatitudeChange = { latitudeText = it },
            onLongitudeChange = { longitudeText = it },
            onSave = { onSaveCoordinates(latitudeText, longitudeText) },
            onPaste = { onPaste(clipboard.getText()?.text) },
            onUseRealGps = onUseRealGps,
        )

        PlacesCard(
            current = config,
            onPick = onPickSavedPlace,
            onPickCity = { latitude, longitude ->
                // moveTo, not copy: a preset is a different city, so the address
                // resolved for the previous anchor no longer describes it.
                onUpdate { it.moveTo(latitude, longitude) }
            },
            onTogglePin = onTogglePin,
        )

        TargetsCard()

        Spacer(Modifier.height(28.dp))
    }

    if (showModuleHelp) {
        AlertDialog(
            onDismissRequest = { showModuleHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.module_help_title)) },
            text = {
                Text(
                    text = stringResource(R.string.module_help_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showModuleHelp = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            },
        )
    }
}

private val BlockSpacing = 6.dp

/** Anything below this is float noise, not an edit worth offering to save. */
private const val COORD_EPSILON = 1e-7

// ---------------------------------------------------------------- header

/**
 * App name plus the way into settings.
 *
 * The picker's map keys live behind this icon rather than in the card list
 * below: they are set up once and then never touched again, and a card that
 * exists only to be visited on the first run would push the things a user does
 * daily further down the screen.
 */
@Composable
private fun HeaderRow(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------- hero

/**
 * State, coordinates and the master switch in one block.
 *
 * Three states, not two. `playing` is the user's intent; `serviceBound` is
 * whether that intent can reach anything at all. Folding both into one boolean
 * produced the sentence "module not active" beside "Idle" on a perfectly
 * healthy install — and, worse, the reverse: with the service unbound and the
 * switch on, the old screen claimed target apps were receiving coordinates when
 * nothing was hooked.
 */
@Composable
private fun StatusHeroCard(
    config: SpoofConfig,
    serviceBound: Boolean,
    addressState: AddressState,
    glow: Float,
    onToggle: () -> Unit,
    onExplain: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryAddress: () -> Unit,
) {
    val subtitle = when {
        !serviceBound -> R.string.state_unbound_sub
        config.playing -> R.string.state_running_sub
        else -> R.string.state_ready_sub
    }
    val pillText = when {
        !serviceBound -> R.string.status_module_inactive
        config.playing -> R.string.status_module_active
        else -> R.string.status_module_ready
    }
    val pillTone = when {
        !serviceBound -> PillTone.WARN
        config.playing -> PillTone.ACTIVE
        else -> PillTone.READY
    }

    FakeLocCard(glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (config.playing) R.string.state_running else R.string.state_stopped,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (serviceBound) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = stringResource(pillText),
                tone = pillTone,
                onClick = onExplain,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "${Geo.format(config.latitude)}, ${Geo.format(config.longitude)}",
            style = CoordinateTextStyle,
            color = MaterialTheme.colorScheme.primary,
        )

        // Only shown when it can matter. The address is resolved from the map
        // picker and stored, so the coordinates alone do not tell the user whether
        // an app that prints the SDK's own city name will follow — this line does,
        // and says what to do when it will not.
        if (config.mapSdkCompat && config.syncAddress) {
            val region = listOf(config.addrProvince, config.addrCity, config.addrDistrict)
                .filter { it.isNotBlank() }
                .joinToString("")

            // Administrative address first, POI label second: that is the order a
            // Chinese address is written in, and the reverse ("软件园观日路38号楼 ·
            // 福建省厦门市思明区") reads like a sentence with its two halves
            // swapped. The label still belongs on the line — it is the only string
            // that says which POI the fake fix will be recorded as
            // (`TencentLocation.getName()`) — but as the detail, not the heading.
            //
            // A recommended label usually already opens with its own region
            // ("厦门市思明区软件园…"), so it is trimmed against [region] first:
            // printing the region twice would be worse than printing it late.
            val label = config.addrPlaceName
                .let { if (region.isNotEmpty() && it.startsWith(region)) it.removePrefix(region) else it }
                .trimStart('·', '，', ',', ' ')

            val resolvedAddress = listOf(region, label)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { config.addrLine }

            Spacer(Modifier.height(6.dp))
            if (resolvedAddress.isNotBlank()) {
                Text(
                    text = resolvedAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // An unresolved address is the one line on this card that
                // describes a defect rather than a setting, so it carries an
                // action instead of just a sentence. Which action depends on
                // *why* it is unresolved: a failed lookup is one tap away from
                // being retried, a missing key is not — asking again would send
                // the same nothing.
                val hintText = when (addressState) {
                    AddressState.RESOLVING -> R.string.addr_resolving
                    AddressState.NO_KEY -> R.string.addr_no_key
                    AddressState.SETTLED, AddressState.FAILED -> R.string.addr_retry
                }
                val fix: (() -> Unit)? = when (addressState) {
                    AddressState.RESOLVING -> null
                    AddressState.NO_KEY -> onOpenSettings
                    AddressState.SETTLED, AddressState.FAILED -> onRetryAddress
                }
                val tint = if (addressState == AddressState.RESOLVING) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.error
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (fix != null) Modifier.clickable(onClick = fix) else Modifier)
                        .padding(vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(hintText),
                        style = MaterialTheme.typography.bodySmall,
                        color = tint,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // De-emphasised while idle: these numbers describe a configuration, not
        // an effect, and a chip that looks identical in both states reads as
        // "this is what apps currently see".
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricChip(
                label = stringResource(R.string.adv_accuracy),
                value = if (config.useAccuracy) "${config.accuracy.toInt()} m" else "—",
                active = config.playing,
            )
            MetricChip(
                label = stringResource(R.string.adv_altitude),
                value = if (config.useAltitude) "${config.altitude.toInt()} m" else "—",
                active = config.playing,
            )
            MetricChip(
                label = stringResource(R.string.adv_drift),
                value = if (config.driftEnabled) "±${config.driftRadiusMeters.toInt()} m" else "—",
                active = config.playing,
            )
        }

        Spacer(Modifier.height(16.dp))
        RowDivider()
        Spacer(Modifier.height(14.dp))

        PrimaryActionButton(playing = config.playing, onClick = onToggle)
    }
}

@Composable
private fun MetricChip(label: String, value: String, active: Boolean) {
    val isSet = value != "—"
    val emphasized = isSet && active
    Column(
        modifier = Modifier
            .background(
                color = if (emphasized) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ------------------------------------------------------------- map picker

/**
 * Entry point to the map picker, which draws on the Tencent basemap.
 *
 * Sits above the coordinate editor because it is the path most people will take:
 * aiming at a spot on a map beats typing six decimal places by hand. The manual
 * box stays for the cases a map cannot serve — a coordinate pasted in from
 * somewhere else, or a spot outside China where there is nothing to search for.
 */
@Composable
private fun MapPickCard(onOpenMap: () -> Unit) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_map))
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onOpenMap,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Map,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_open_map))
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.map_open_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------ coordinate editor

/**
 * The manual coordinate box, its two input helpers, and its "is this saved"
 * state.
 *
 * The old version enabled Save whenever the two fields parsed, which made it
 * impossible to tell "I have unsaved edits" from "everything here is already
 * stored" — and a user who typed a new longitude, got distracted, and came back
 * had no way to see that the number on screen was not the one in effect. The
 * button now fills in only while there is something pending, and the label
 * beside it names the state.
 *
 * 粘贴 and 使用真实定位 live here, under the fields they fill, instead of in a
 * pair of buttons above the map card. They were sitting beside each other as
 * equals, which they are not: one fills the box from the clipboard, the other
 * replaces the anchor with a real fix — an input and a *state change* that the
 * target apps will start receiving coordinates from. Under the fields, as
 * secondary text actions, each reads as what it does.
 */
@Composable
private fun CoordinateEditorCard(
    config: SpoofConfig,
    latitudeText: String,
    longitudeText: String,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onSave: () -> Unit,
    onPaste: () -> Unit,
    onUseRealGps: () -> Unit,
) {
    val latitude = latitudeText.trim().toDoubleOrNull()
    val longitude = longitudeText.trim().toDoubleOrNull()
    val latitudeBad = latitude == null || latitude !in -90.0..90.0
    val longitudeBad = longitude == null || longitude !in -180.0..180.0
    val parsable = !latitudeBad && !longitudeBad
    val typed = latitudeText.isNotBlank() || longitudeText.isNotBlank()

    // Two separate questions: can this be stored at all, and does it differ
    // from what is stored. Only the second one should make the button shout.
    // `parsable` is the last conjunct, so an out-of-range number still shows the
    // range error rather than offering to save it.
    val pending = latitude != null && longitude != null &&
        (abs(latitude - config.latitude) > COORD_EPSILON ||
            abs(longitude - config.longitude) > COORD_EPSILON) &&
        parsable

    FakeLocCard {
        SectionLabel(stringResource(R.string.label_latitude) + " / " + stringResource(R.string.label_longitude))

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = latitudeText,
                onValueChange = onLatitudeChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.label_latitude)) },
                singleLine = true,
                isError = latitudeBad && latitudeText.isNotBlank(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = longitudeText,
                onValueChange = onLongitudeChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.label_longitude)) },
                singleLine = true,
                isError = longitudeBad && longitudeText.isNotBlank(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
        }

        Spacer(Modifier.height(4.dp))

        // Secondary actions, left-aligned and small: they feed the fields above
        // rather than competing with 保存 for the eye.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onPaste, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_paste), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onUseRealGps, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_use_real_gps), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    typed && !parsable -> stringResource(R.string.coord_invalid_short)
                    pending -> stringResource(R.string.coord_pending)
                    else -> stringResource(R.string.coord_saved)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    typed && !parsable -> MaterialTheme.colorScheme.error
                    pending -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (pending) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_save))
                }
            } else {
                TextButton(
                    onClick = onSave,
                    enabled = false,
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

// ---------------------------------------------------------- saved places

private val PRESETS = listOf(
    Triple(R.string.preset_beijing, 39.908700, 116.397500),
    Triple(R.string.preset_shanghai, 31.230400, 121.473700),
    Triple(R.string.preset_guangzhou, 23.129100, 113.264400),
    Triple(R.string.preset_shenzhen, 22.543100, 114.057900),
    Triple(R.string.preset_xiamen, 24.479800, 118.089400),
    Triple(R.string.preset_hangzhou, 30.274100, 120.155100),
    Triple(R.string.preset_chengdu, 30.572800, 104.066500),
    Triple(R.string.preset_xian, 34.341600, 108.939800),
)

/** The three lists behind one card. */
private enum class PlaceMode { RECENT, PINNED, CITY }

/**
 * 最近 / 收藏 / 城市, in one card.
 *
 * The eight built-in cities were the whole of this card, and they answer the one
 * question almost nobody asks: "put me in Beijing". The places a user actually
 * returns to are their office, a client site, the spot they checked in from last
 * week — none of which any hand-written list can contain. Every anchor change is
 * already recorded by the view model, so this card only has to show it.
 *
 * The star is deliberately a separate control from the row: tapping the row goes
 * there, tapping the star keeps it. Pinning the place you are comparing against
 * must not move the anchor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacesCard(
    current: SpoofConfig,
    onPick: (SavedPlace) -> Unit,
    onPickCity: (Double, Double) -> Unit,
    onTogglePin: (Double, Double) -> Unit,
) {
    val recents = current.recentPlaces
    val pinned = current.pinnedPlaces

    // Opens on the list that has something in it. A card that greets a new user
    // with "nothing here yet" teaches them the feature does not work.
    var modeName by rememberSaveable {
        mutableStateOf(if (recents.isEmpty()) PlaceMode.CITY.name else PlaceMode.RECENT.name)
    }
    val mode = runCatching { PlaceMode.valueOf(modeName) }.getOrDefault(PlaceMode.CITY)

    FakeLocCard {
        SectionLabel(stringResource(R.string.section_presets))
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaceModeChip(
                label = stringResource(R.string.places_recent),
                count = recents.size,
                selected = mode == PlaceMode.RECENT,
                onClick = { modeName = PlaceMode.RECENT.name },
            )
            PlaceModeChip(
                label = stringResource(R.string.places_pinned),
                count = pinned.size,
                selected = mode == PlaceMode.PINNED,
                onClick = { modeName = PlaceMode.PINNED.name },
            )
            PlaceModeChip(
                label = stringResource(R.string.places_cities),
                count = null,
                selected = mode == PlaceMode.CITY,
                onClick = { modeName = PlaceMode.CITY.name },
            )
        }

        Spacer(Modifier.height(6.dp))

        when (mode) {
            PlaceMode.RECENT ->
                if (recents.isEmpty()) {
                    PlaceHint(stringResource(R.string.places_recent_empty))
                } else {
                    recents.forEach { place ->
                        SavedPlaceRow(place, current, onPick, onTogglePin)
                    }
                }

            PlaceMode.PINNED ->
                if (pinned.isEmpty()) {
                    PlaceHint(stringResource(R.string.places_pinned_empty))
                } else {
                    pinned.forEach { place ->
                        SavedPlaceRow(place, current, onPick, onTogglePin)
                    }
                }

            PlaceMode.CITY -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PRESETS.forEach { (nameRes, latitude, longitude) ->
                    val selected = abs(current.latitude - latitude) < 1e-6 &&
                        abs(current.longitude - longitude) < 1e-6
                    FilterChip(
                        selected = selected,
                        onClick = { onPickCity(latitude, longitude) },
                        label = { Text(stringResource(nameRes)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceModeChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(if (count == null) label else "$label $count")
        },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * One saved place: label, administrative line, star.
 *
 * The row is highlighted while the anchor sits on it — without that, a list of
 * places and a coordinate readout elsewhere on screen leave the user to compare
 * six decimal places by eye to find out where they are.
 */
@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    current: SpoofConfig,
    onPick: (SavedPlace) -> Unit,
    onTogglePin: (Double, Double) -> Unit,
) {
    val here = abs(current.latitude - place.latitude) < 1e-6 &&
        abs(current.longitude - place.longitude) < 1e-6

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(place) }
            .padding(start = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Place,
            contentDescription = null,
            tint = if (here) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = place.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (here) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (place.detail.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = place.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onTogglePin(place.latitude, place.longitude) }) {
            Icon(
                imageVector = if (place.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (place.pinned) R.string.places_unpin else R.string.places_pin,
                ),
                tint = if (place.pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PlaceHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

// --------------------------------------------------------------- targets
//
// Read-only on purpose. The scope used to be a second, narrower list chosen in
// this app, which only ever intersected with LSPosed's own scope — and an empty
// or switched-off list looked exactly like a module that does not work. One
// list, in LSPosed, is the whole answer; this card just says so.

@Composable
private fun TargetsCard() {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_targets))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.targets_fixed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.targets_fixed_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------ main button

/**
 * The one action, 52 dp tall and inside the status card.
 *
 * Outlined-with-error-texting while running rather than a filled red button:
 * stopping is the undo, not the goal, and a large saturated button in the same
 * position as "start" trains a double-tap that turns spoofing back on.
 */
@Composable
private fun PrimaryActionButton(playing: Boolean, onClick: () -> Unit) {
    if (playing) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(
                text = stringResource(R.string.action_stop),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = stringResource(R.string.action_start),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
