package io.github.coby.fakeloc.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.coby.fakeloc.R
import io.github.coby.fakeloc.core.Geo
import io.github.coby.fakeloc.core.SpoofConfig
import io.github.coby.fakeloc.core.moveTo
import kotlin.math.abs

/**
 * The home screen: everything a user touches in the common case.
 *
 * State is *not* held here — the screen is a pure function of [config] plus the
 * callbacks it is handed. The only local state is the two in-progress coordinate
 * text fields, re-synced from [config] whenever the anchor changes elsewhere
 * (preset tap, clipboard parse, "use real GPS").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    config: SpoofConfig,
    serviceBound: Boolean,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
    onTogglePlaying: () -> Unit,
    onUseRealGps: () -> Unit,
    onPaste: (String?) -> Unit,
    onOpenTargets: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveCoordinates: (String, String) -> Unit,
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

        if (!serviceBound) {
            ServiceWarningCard()
        }

        StatusHeroCard(config = config, glow = if (config.playing) glow else 0f)

        QuickActionsRow(
            onUseRealGps = onUseRealGps,
            onPaste = { onPaste(clipboard.getText()?.text) },
        )

        MapPickCard(onOpenMap = onOpenMap)

        CoordinateEditorCard(
            latitudeText = latitudeText,
            longitudeText = longitudeText,
            onLatitudeChange = { latitudeText = it },
            onLongitudeChange = { longitudeText = it },
            onSave = { onSaveCoordinates(latitudeText, longitudeText) },
        )

        PresetsCard(
            current = config,
            // moveTo, not copy: a preset is a different city, so the address
            // resolved for the previous anchor no longer describes it.
            onPick = { latitude, longitude -> onUpdate { it.moveTo(latitude, longitude) } },
        )

        TargetsCard(
            config = config,
            onUpdate = onUpdate,
            onOpenTargets = onOpenTargets,
        )

        Spacer(Modifier.height(4.dp))
        PrimaryActionButton(playing = config.playing, onClick = onTogglePlaying)
        Spacer(Modifier.height(28.dp))
    }
}

private val BlockSpacing = 6.dp

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

// --------------------------------------------------------------- warning

@Composable
private fun ServiceWarningCard() {
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val onError = MaterialTheme.colorScheme.onErrorContainer

    FakeLocCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = onError,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.service_unbound_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = onError,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.service_unbound_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = onError.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------- hero

@Composable
private fun StatusHeroCard(config: SpoofConfig, glow: Float) {
    FakeLocCard(glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Column {
                    Text(
                        text = stringResource(
                            if (config.playing) R.string.state_running else R.string.state_stopped,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            if (config.playing) R.string.state_running_sub else R.string.state_stopped_sub,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusPill(
                text = stringResource(
                    if (config.playing) R.string.status_module_active else R.string.status_module_inactive,
                ),
                active = config.playing,
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
            val resolvedAddress = listOf(config.addrProvince, config.addrCity, config.addrDistrict)
                .filter { it.isNotBlank() }
                .joinToString("")
                .ifBlank { config.addrLine }

            Spacer(Modifier.height(6.dp))
            Text(
                text = if (resolvedAddress.isNotBlank()) {
                    resolvedAddress
                } else {
                    stringResource(R.string.addr_unresolved)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (resolvedAddress.isNotBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricChip(
                label = stringResource(R.string.adv_accuracy),
                value = if (config.useAccuracy) "${config.accuracy.toInt()} m" else "—",
            )
            MetricChip(
                label = stringResource(R.string.adv_altitude),
                value = if (config.useAltitude) "${config.altitude.toInt()} m" else "—",
            )
            MetricChip(
                label = stringResource(R.string.adv_drift),
                value = if (config.driftEnabled) "±${config.driftRadiusMeters.toInt()} m" else "—",
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    val isSet = value != "—"
    Column(
        modifier = Modifier
            .background(
                color = if (isSet) {
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
            color = if (isSet) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ---------------------------------------------------------- quick actions

@Composable
private fun QuickActionsRow(onUseRealGps: () -> Unit, onPaste: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onUseRealGps,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_use_real_gps))
        }
        OutlinedButton(
            onClick = onPaste,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_paste))
        }
    }
}

// ------------------------------------------------------------- map picker

/**
 * Entry point to the Baidu map picker.
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

@Composable
private fun CoordinateEditorCard(
    latitudeText: String,
    longitudeText: String,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val latitude = latitudeText.trim().toDoubleOrNull()
    val longitude = longitudeText.trim().toDoubleOrNull()
    val latitudeBad = latitude == null || latitude !in -90.0..90.0
    val longitudeBad = longitude == null || longitude !in -180.0..180.0
    val dirty = latitude != null && longitude != null &&
        (!latitudeBad && !longitudeBad)

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
                isError = latitudeBad,
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
                isError = longitudeBad,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) "" else stringResource(R.string.toast_invalid_coords),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onSave, enabled = dirty) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

// --------------------------------------------------------------- presets

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsCard(current: SpoofConfig, onPick: (Double, Double) -> Unit) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_presets))
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PRESETS.forEach { (nameRes, latitude, longitude) ->
                val selected = abs(current.latitude - latitude) < 1e-6 &&
                    abs(current.longitude - longitude) < 1e-6
                FilterChip(
                    selected = selected,
                    onClick = { onPick(latitude, longitude) },
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

// --------------------------------------------------------------- targets

@Composable
private fun TargetsCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
    onOpenTargets: () -> Unit,
) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_targets))
        Spacer(Modifier.height(6.dp))

        ToggleRow(
            title = stringResource(R.string.targets_scope_all),
            subtitle = stringResource(R.string.targets_scope_all_desc),
            checked = config.scopeAll,
            onCheckedChange = { enabled -> onUpdate { it.copy(scopeAll = enabled) } },
        )

        if (!config.scopeAll) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.targets_selected_count,
                        config.targetPackages.size,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenTargets) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_pick_targets))
                }
            }
            Text(
                text = stringResource(R.string.targets_also_pick_in_lsposed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------ main button

@Composable
private fun PrimaryActionButton(playing: Boolean, onClick: () -> Unit) {
    if (playing) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
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
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.action_start),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
