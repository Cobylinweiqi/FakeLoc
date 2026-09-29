package io.github.cobylinweiqi.fakeloc.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.cobylinweiqi.fakeloc.R
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Settings, in two tabs: the basemap credential and the signal tuning.
 *
 * The two halves have nothing to do with each other and are touched on opposite
 * rhythms — a key is entered once and then never again, while the tuning switches
 * are the kind of thing a user flips while testing an app. Stacked into a single
 * scroll, the switches sat below three screens of key-registration prose, which
 * is why they used to live on the home screen instead. Tabs let both live here
 * without either burying the other.
 *
 * The map half:
 *
 * The picker draws with a third-party base map, and every one of them is gated
 * behind a key the platform binds to *this* package name and *this* signing
 * certificate. That binding is the whole reason this screen exists: when a key
 * stops working there is nothing in the code to fix, and the only way forward is
 * to register a new one — which means the user needs somewhere to put it, and
 * needs to know exactly which two strings the platform's console will ask for.
 *
 * So the two copy buttons in the middle are not a convenience, they are the
 * feature. A key registered against a SHA-1 that differs by one character is
 * indistinguishable from a key that was never registered: the SDK reports an
 * authorisation failure either way.
 *
 * **This build carries no credential of its own**, so the key is registered by
 * the user and the field on this page is the only source of it. There is no "use
 * the built-in one" escape hatch to offer, and offering a disabled field beside
 * one would only suggest otherwise.
 *
 * **There is one platform on this page, not three.** The provider chips that used
 * to sit here went with the two SDKs they switched to (2026-09-29): the key
 * field and the step list are now simply Tencent's, because that is the only
 * renderer the APK contains. The chips were removed rather than left with a
 * single option — a one-chip selector is a control that cannot do anything, and
 * it would imply a choice that no longer exists.
 *
 * Nothing in the map half reaches the hook side — the module forges positions and
 * needs no map key at all. Those fields only steer the picker's own rendering.
 * The tuning half is the exact opposite: every switch on it is read by the
 * module, which is why it is the one part of this page that changes what a
 * target app sees.
 */
@Composable
fun SettingsScreen(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read once per screen: the package name cannot change while we are the
    // running process, and reading signatures is not free.
    val context = LocalContext.current
    val fingerprint = remember { signingSha1(context) }
    val provider = config.mapProvider

    // Persisted by name, like the shell's screen switch and for the same reason:
    // a String round-trips through a Bundle on every API level without a
    // hand-written Saver, and this value only ever changes on a tab press.
    var tabName by rememberSaveable { mutableStateOf(SettingsTab.MAP.name) }
    val tab = SettingsTab.valueOf(tabName)

    // One scroll state per tab, both created unconditionally. A single shared one
    // would carry the map half's scroll offset into the tuning half, and a
    // `rememberScrollState()` inside the branch would be forgotten on every
    // switch — so the page would jump to the top each time either way.
    val mapScroll = rememberScrollState()
    val signalScroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_done),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_done))
            }
        }

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tabName = entry.name },
                    text = {
                        Text(
                            text = stringResource(entry.label),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(if (tab == SettingsTab.MAP) mapScroll else signalScroll)
                .padding(horizontal = ScreenGutter)
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tab) {
                SettingsTab.MAP -> {
                    MapServiceCard()
                    ProviderCard(config = config, onUpdate = onUpdate)
                    ConsoleFingerprintCard(packageName = context.packageName, sha1 = fingerprint)
                    HowToCard(provider = provider)
                    PrivacyNote()
                }

                SettingsTab.SIGNAL -> SignalTuningCard(config = config, onUpdate = onUpdate)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The two halves of this page.
 *
 * [label] is the tab caption. The tuning half reuses `section_advanced` — the
 * heading the controls carried as a card on the home screen — rather than
 * storing "Signal tuning" a second time under a new key.
 */
private enum class SettingsTab(val label: Int) {
    MAP(R.string.settings_tab_map),
    SIGNAL(R.string.section_advanced),
}

// ------------------------------------------------------------ map service

@Composable
private fun MapServiceCard() {
    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_map_service))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.settings_map_service_desc))
    }
}

// ------------------------------------------------------- provider and key

/**
 * The key field for the one platform this build can draw with.
 *
 * Kept as its own card rather than folded into [MapServiceCard] because the
 * section label, the state line and the restart hint are all about the credential
 * specifically, and merging them would bury the one thing the user came to this
 * page to paste.
 */
@Composable
private fun ProviderCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    val provider = config.mapProvider

    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_provider))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.settings_provider_desc))
        Spacer(Modifier.height(12.dp))

        // State the consequence rather than leaving it to be discovered on a grey
        // square where a map should be.
        val ready = config.mapKeyConfigured(provider)
        Text(
            text = stringResource(
                if (ready) R.string.settings_provider_ready else R.string.settings_provider_missing_key,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        Spacer(Modifier.height(12.dp))
        RowDivider()
        Spacer(Modifier.height(12.dp))

        KeyField(
            label = stringResource(R.string.settings_key_tencent),
            value = config.mapKeyValue(provider),
            onValueChange = { typed -> onUpdate { it.withMapKey(provider, typed) } },
        )

        Spacer(Modifier.height(14.dp))
        RowDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Body(stringResource(R.string.settings_restart_hint))
        }
    }
}

/**
 * The active platform's key field, with a status line that distinguishes
 * "nothing typed yet" from "typed and therefore plausibly usable".
 *
 * It deliberately does not claim the key *works*: only the platform's own server
 * can decide that, and a revoked key looks exactly like a live one until a
 * request is made.
 */
@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        trailingIcon = {
            Text(
                text = stringResource(
                    if (value.isBlank()) R.string.settings_key_empty else R.string.settings_key_filled,
                ),
                modifier = Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
    )
}

// -------------------------------------------------------------- fingerprint

/**
 * The two strings every one of these consoles will ask for.
 *
 * Worth its own card, with copy buttons: a key is bound to this package name and
 * this certificate, and a mismatch on either produces an authorisation error
 * that looks identical to "the key is wrong". Handing the user a value they can
 * copy — rather than one they must transcribe from a `keytool` dump — removes
 * the most common way this feature is set up incorrectly.
 */
@Composable
private fun ConsoleFingerprintCard(packageName: String, sha1: String?) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_console_title))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.settings_console_desc))
        Spacer(Modifier.height(12.dp))

        CopyableValue(
            label = stringResource(R.string.settings_package_label),
            value = packageName,
        )
        Spacer(Modifier.height(8.dp))
        CopyableValue(
            label = stringResource(R.string.settings_sha1_label),
            value = sha1 ?: stringResource(R.string.settings_sha1_unavailable),
            copyable = sha1 != null,
        )
    }
}

@Composable
private fun CopyableValue(label: String, value: String, copyable: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            enabled = copyable,
            onClick = {
                clipboard.setText(AnnotatedString(value))
                copied = true
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(
                    if (copied) R.string.settings_copied else R.string.settings_copy,
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ how to

/**
 * Registration steps for Tencent Location Services.
 *
 * Expanded rather than collapsed behind a disclosure triangle. Collapsing made
 * sense when three sets of instructions were stacked here; with one list on
 * screen there is nothing to hide it from, and the steps are the reason most
 * people opened this page in the first place.
 *
 * The string is addressed directly rather than through a `stepsBody(provider)`
 * lookup. That indirection existed to pick one of three lists and became a
 * function that ignored its argument and always returned the same resource.
 */
@Composable
private fun HowToCard(provider: MapProvider) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_howto))
        Spacer(Modifier.height(8.dp))
        Body(
            stringResource(
                R.string.settings_howto_desc,
                stringResource(providerLabel(provider)),
            ),
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.steps_tencent_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.steps_tencent_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        text = stringResource(R.string.settings_privacy_note),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

// -------------------------------------------------------- signal tuning

/**
 * Everything that shapes the forged position, moved here from the home screen.
 *
 * As a card there it put eight switches and four sliders between the coordinate
 * editor and the start button — the two things a user actually alternates
 * between. The home screen still reports accuracy, altitude and drift as chips on
 * the status card, so the current tuning stays visible at a glance without the
 * controls that set it.
 *
 * No section heading inside the card: the tab above is already named for it, and
 * "Signal tuning" twice on one screen is noise.
 */
@Composable
private fun SignalTuningCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    FakeLocCard {
        Body(stringResource(R.string.settings_signal_desc))

        Spacer(Modifier.height(14.dp))
        RowDivider()
        Spacer(Modifier.height(12.dp))

        ToggleRow(
            title = stringResource(R.string.adv_accuracy),
            subtitle = stringResource(R.string.adv_accuracy_desc),
            checked = config.useAccuracy,
            onCheckedChange = { on -> onUpdate { it.copy(useAccuracy = on) } },
            icon = Icons.Rounded.Straighten,
        )
        if (config.useAccuracy) {
            SliderRow(
                title = "",
                value = config.accuracy,
                range = 1f..100f,
                unit = " m",
                decimals = 0,
                onValueChange = { value -> onUpdate { it.copy(accuracy = value) } },
            )
        }

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_altitude),
            subtitle = stringResource(R.string.adv_altitude_desc),
            checked = config.useAltitude,
            onCheckedChange = { on -> onUpdate { it.copy(useAltitude = on) } },
            icon = Icons.Rounded.Terrain,
        )
        if (config.useAltitude) {
            SliderRow(
                title = "",
                value = config.altitude.toFloat(),
                range = -50f..600f,
                unit = " m",
                decimals = 0,
                onValueChange = { value -> onUpdate { it.copy(altitude = value.toDouble()) } },
            )
        }

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_speed),
            subtitle = stringResource(R.string.adv_speed_desc),
            checked = config.useSpeed,
            onCheckedChange = { on -> onUpdate { it.copy(useSpeed = on) } },
            icon = Icons.Rounded.Speed,
        )
        if (config.useSpeed) {
            SliderRow(
                title = "",
                value = config.speed,
                range = 0f..30f,
                unit = " m/s",
                onValueChange = { value -> onUpdate { it.copy(speed = value) } },
            )
        }

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_drift),
            subtitle = stringResource(R.string.adv_drift_desc),
            checked = config.driftEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(driftEnabled = on) } },
            icon = Icons.Rounded.DirectionsWalk,
        )
        if (config.driftEnabled) {
            SliderRow(
                title = stringResource(R.string.adv_drift_radius),
                value = config.driftRadiusMeters.toFloat(),
                range = 5f..500f,
                unit = " m",
                decimals = 0,
                onValueChange = { value -> onUpdate { it.copy(driftRadiusMeters = value.toDouble()) } },
            )
            SliderRow(
                title = stringResource(R.string.adv_drift_speed),
                value = config.driftSpeedMps,
                range = 0.5f..10f,
                unit = " m/s",
                onValueChange = { value -> onUpdate { it.copy(driftSpeedMps = value) } },
            )
        }

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_anti_mock),
            subtitle = stringResource(R.string.adv_anti_mock_desc),
            checked = config.antiMock,
            onCheckedChange = { on -> onUpdate { it.copy(antiMock = on) } },
            icon = Icons.Rounded.VisibilityOff,
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_strict_sources),
            subtitle = stringResource(R.string.adv_strict_sources_desc),
            checked = config.strictSources,
            onCheckedChange = { on -> onUpdate { it.copy(strictSources = on) } },
            icon = Icons.Rounded.LocationOff,
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_map_sdk),
            subtitle = stringResource(R.string.adv_map_sdk_desc),
            checked = config.mapSdkCompat,
            onCheckedChange = { on -> onUpdate { it.copy(mapSdkCompat = on) } },
            icon = Icons.Rounded.NearMe,
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_sync_address),
            subtitle = stringResource(R.string.adv_sync_address_desc),
            checked = config.syncAddress,
            onCheckedChange = { on -> onUpdate { it.copy(syncAddress = on) } },
            icon = Icons.Rounded.LocationCity,
        )
    }
}

// ----------------------------------------------------------------- shared

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Display name for a platform, shared with the picker's own header.
 *
 * Still a lookup and not a constant even though there is one entry: the picker's
 * header labels itself with this, and the day a second renderer comes back it is
 * the one place that has to learn about it.
 */
internal fun providerLabel(provider: MapProvider): Int = when (provider) {
    MapProvider.TENCENT -> R.string.provider_tencent
}

/**
 * The app's own signing certificate, as the consoles want it: uppercase hex,
 * colon-separated.
 *
 * Null when the platform refuses to hand it over. That is worth surfacing as
 * "could not read" rather than as an empty string, because an empty field in a
 * console means "no restriction" to some of these platforms and "matches
 * nothing" to others.
 */
private fun signingSha1(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )
    val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-1")
        .digest(certificate.toByteArray())
        .joinToString(":") { byte -> String.format(Locale.US, "%02X", byte) }
}.getOrNull()
