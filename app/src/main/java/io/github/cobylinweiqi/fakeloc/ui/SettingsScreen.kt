package io.github.cobylinweiqi.fakeloc.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.cobylinweiqi.fakeloc.R
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import io.github.cobylinweiqi.fakeloc.ui.map.TencentProbe
import io.github.cobylinweiqi.fakeloc.ui.map.tencentProbe
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Settings, in two tabs: the signal tuning and the basemap credential.
 *
 * The tuning half comes first and the page opens on it — see [SettingsTab].
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
    var tabName by rememberSaveable { mutableStateOf(SettingsTab.SIGNAL.name) }
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
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
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
                    HowToCard(provider = provider, packageName = context.packageName)
                    PrivacyNote()
                }

                SettingsTab.SIGNAL -> SignalTuningCard(config = config, onUpdate = onUpdate)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The two halves of this page, in the order they are worth opening.
 *
 * The tuning half is first, and it is the one the page opens on. It used to be
 * second, behind the map half — which put the switches a user flips while
 * testing an app behind the key they enter once and never touch again. A page
 * that opens on a credential form is a page that tells every user, every time,
 * that the important part is somewhere else.
 *
 * [label] is the tab caption. The tuning half reuses `section_advanced` — the
 * heading the controls carried as a card on the home screen — rather than
 * storing "Signal tuning" a second time under a new key.
 */
private enum class SettingsTab(val label: Int) {
    SIGNAL(R.string.section_advanced),
    MAP(R.string.settings_tab_map),
}

// ------------------------------------------------------------ map service

@Composable
private fun MapServiceCard() {
    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_map_service))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.settings_map_service_desc))
        DetailExpander(
            title = stringResource(R.string.settings_map_service),
            sections = listOf(DetailSection(0, R.string.settings_map_service_detail)),
        )
    }
}

/**
 * One titled block inside [DetailExpander]. A zero [heading] means "no title":
 * the map-service note is a single paragraph with nothing to break up.
 */
private data class DetailSection(val heading: Int, val body: Int)

/**
 * The four gates between an empty field and a working address lookup.
 *
 * Ordered by the order they are actually crossed, not by how often they fail.
 * Each one has a distinct status code, and the third is the one nobody finds on
 * their own: a key can be valid, enabled and correctly signed while every
 * request still comes back `113`, because quota is allocated per endpoint and
 * the default allocation is zero.
 */
private val TENCENT_HOWTO = listOf(
    DetailSection(R.string.howto_key_heading, R.string.howto_key_body),
    DetailSection(R.string.howto_sk_heading, R.string.howto_sk_body),
    DetailSection(R.string.howto_quota_heading, R.string.howto_quota_body),
    DetailSection(R.string.howto_status_heading, R.string.howto_status_body),
)

/**
 * A one-line summary plus the essay behind it.
 *
 * The paragraphs this replaces were product documentation sitting in the middle
 * of a settings page: four lines about what the basemap is and is not, three
 * about the scope, five registration steps. Read once, in order, on the day the
 * key is registered — and then in the way of the switches below them for the
 * rest of the app's life. A dialog keeps the same words available without
 * making every visit scroll past them.
 *
 * The body is a list of sections rather than one string because the key steps
 * outgrew a single paragraph: getting the credential, finding its signing
 * secret, and assigning quota are three different screens in the console, and
 * run together they read as one undifferentiated wall of steps. The text area
 * scrolls, since these four sections no longer fit above the buttons.
 */
@Composable
private fun DetailExpander(
    title: String,
    sections: List<DetailSection>,
    packageName: String = "",
) {
    var open by remember { mutableStateOf(false) }

    TextButton(
        onClick = { open = true },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.action_learn_more),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) Spacer(Modifier.height(14.dp))
                        if (section.heading != 0) {
                            Text(
                                text = stringResource(section.heading),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = stringResource(section.body, packageName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            },
        )
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
    val context = LocalContext.current

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

        // Only meaningful for a key with 签名校验 switched on, and harmless
        // otherwise, so it is always visible rather than hidden behind a switch
        // the user would have to go and find first. The description says what
        // happens with it blank, because that is the state most people will
        // leave it in.
        Spacer(Modifier.height(12.dp))
        Body(stringResource(R.string.settings_sk_tencent_desc))
        Spacer(Modifier.height(8.dp))
        KeyField(
            label = stringResource(R.string.settings_sk_tencent),
            value = config.mapSecretValue(provider),
            onValueChange = { typed -> onUpdate { it.withMapSecret(provider, typed) } },
        )

        Spacer(Modifier.height(14.dp))
        RowDivider()
        Spacer(Modifier.height(12.dp))
        ConnectivityTest(config = config, provider = provider)

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
            Column(Modifier.weight(1f)) {
                Body(stringResource(R.string.settings_restart_hint))
                Spacer(Modifier.height(6.dp))
                // The SDK reads its key once, in a static initialiser, so this
                // is a real constraint rather than an implementation detail —
                // but making the user hunt for this app in the system settings
                // to force-stop it is the part that was not necessary.
                OutlinedButton(
                    onClick = { restartForNewKey(context) },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.settings_restart_now))
                }
            }
        }
    }
}

/**
 * The active platform's credential, masked once it holds anything.
 *
 * Masking is not decoration. This page is the one screen a user photographs
 * when asking for help — the review that produced v1.11.0 started from a
 * screenshot in which the SK was legible end to end — and a credential on
 * screen is a credential that has left the device. The value is still there,
 * one deliberate tap away, but showing it takes a press rather than a glance.
 *
 * While it is being edited it is a plain text field, because that is the only
 * control that can round-trip a paste, and pasting is how these arrive.
 *
 * It deliberately still does not claim the key *works*: only the platform's own
 * server can decide that, and a revoked key looks exactly like a live one until
 * a request is made — which is what the test above this field is for.
 */
@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    // Saved across rotation, like the tab: a half-pasted key must not be
    // collapsed back into a mask by an accidental rotation.
    var editing by rememberSaveable { mutableStateOf(value.isBlank()) }
    // Revealing the field without focus is the "dead control" this page keeps
    // being rewritten to avoid: a tap on 修改 would leave a text box that still
    // needs a second tap before anything can be typed.
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        // Guarded: the requester is attached by the time this coroutine runs,
        // but a nicety must not be able to take the settings page down.
        if (editing && value.isNotBlank()) runCatching { focus.requestFocus() }
    }

    if (!editing && value.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = maskCredential(value),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(onClick = { editing = true }) {
                Text(stringResource(R.string.action_edit))
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                // Only offered once there is something to collapse; on a blank
                // field it would be a button that hides an empty box.
                if (value.isNotBlank()) {
                    TextButton(onClick = { editing = false }) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            },
        )
    }
}

/**
 * First four characters, last four, fixed-width gap.
 *
 * The gap is a constant rather than a run of proportional bullets so that two
 * keys of different lengths cannot be told apart by the width of their mask.
 */
private fun maskCredential(value: String): String =
    if (value.length <= 8) {
        value.take(2) + "••••"
    } else {
        value.take(4) + "••••••••" + value.takeLast(4)
    }

// ------------------------------------------------------ connectivity test

/**
 * Fixed probe point: Tiananmen Square.
 *
 * Not the user's anchor, and not their current location. The question this
 * button answers is "does this credential work", and that must be answerable
 * before a location has been picked at all. Central Beijing is also dense with
 * addressable POIs, so a working key cannot fail here for lack of something to
 * resolve to.
 */
private const val PROBE_LATITUDE = 39.908700
private const val PROBE_LONGITUDE = 116.397500

/**
 * One real request against Tencent's reverse-geocoder, reported in words.
 *
 * The highest-value control on this page. A key that draws tiles and a key that
 * resolves addresses are gated **separately** — `Android SDK` versus
 * `WebServiceAPI` — and the four ways the second gate refuses (`111` unsigned,
 * `113` no quota, `199` product off, `190` bad key) are indistinguishable from
 * the picker, which can only say "no address". Naming them here turns a support
 * thread into a one-tap answer, and it is the only place in the app that can
 * truthfully use the word "ready".
 */
@Composable
private fun ConnectivityTest(config: SpoofConfig, provider: MapProvider) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<TencentProbe?>(null) }
    var running by remember { mutableStateOf(false) }

    val ready = config.mapKeyConfigured(provider)

    Column {
        OutlinedButton(
            enabled = ready && !running,
            onClick = {
                running = true
                result = null
                scope.launch {
                    result = tencentProbe(
                        context = context,
                        apiKey = config.mapKeyValue(provider),
                        secretKey = config.mapSecretValue(provider),
                        latitude = PROBE_LATITUDE,
                        longitude = PROBE_LONGITUDE,
                    )
                    running = false
                }
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (running) R.string.settings_probe_running else R.string.settings_probe_action,
                ),
            )
        }

        result?.let { probe ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = probeMessage(probe),
                style = MaterialTheme.typography.bodySmall,
                color = if (probe is TencentProbe.Ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * The status code, spelled out.
 *
 * Every branch here is a different fix, which is why they are separate strings
 * rather than one line with the number interpolated: `111` means "type the SK
 * you already have", `199` means "go tick a checkbox in the console", and
 * telling a user only the number sends them to a search engine instead.
 */
@Composable
private fun probeMessage(probe: TencentProbe): String = when (probe) {
    TencentProbe.Ok -> stringResource(R.string.settings_probe_ok)
    TencentProbe.NoKey -> stringResource(R.string.settings_probe_no_key)
    TencentProbe.Unreachable -> stringResource(R.string.settings_probe_unreachable)
    is TencentProbe.Rejected -> when (probe.status) {
        111 -> stringResource(R.string.settings_probe_111)
        110, 112 -> stringResource(R.string.settings_probe_112)
        113 -> stringResource(R.string.settings_probe_113)
        199 -> stringResource(R.string.settings_probe_199)
        120 -> stringResource(R.string.settings_probe_120)
        190, 301, 311 -> stringResource(R.string.settings_probe_190)
        else -> stringResource(R.string.settings_probe_other, probe.status)
    }
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
 * Registration steps for Tencent Location Services, behind one tap.
 *
 * These used to be printed in full on the page, on the argument that with one
 * list on screen there is nothing to hide it from. The flaw in that: they are
 * read once, while the page itself is opened every time a switch needs flipping
 * — so "nothing to hide it from" meant "in the way of everything below".
 *
 * The steps moved from one paragraph into four titled sections: the console
 * treats the key, its signing secret and the endpoint quota as three separate
 * screens, and flattening them made a step that is easy to skip (quota) look
 * like part of the step above it. The package name is passed in rather than
 * spelled out in the string, so it cannot drift from the one this build ships.
 */
@Composable
private fun HowToCard(provider: MapProvider, packageName: String) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.settings_howto))
        Spacer(Modifier.height(8.dp))
        Body(
            stringResource(
                R.string.settings_howto_desc,
                stringResource(providerLabel(provider)),
            ),
        )
        DetailExpander(
            title = stringResource(R.string.steps_tencent_title),
            sections = TENCENT_HOWTO,
            packageName = packageName,
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
 * Three blocks now, and the split is the point: the scene (what situation the
 * device claims to be in), the numbers behind it, and the four switches that
 * decide what a detector can see. Those are not peers. A slider changes a
 * decimal; one of those switches can silently turn spoofing off, and it used to
 * sit in the same list as "ground speed" with exactly the same weight.
 */
@Composable
private fun SignalTuningCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    SceneCard(config = config, onUpdate = onUpdate)
    ShapeCard(config = config, onUpdate = onUpdate)
    GuardsCard(config = config, onUpdate = onUpdate)
}

// ------------------------------------------------------------ scene presets

/**
 * Scene presets, because a hand-tuned set of numbers is itself a fingerprint.
 *
 * Accuracy, ground speed and drift are not three independent dials: 5 m with
 * 1.4 m/s is a person walking, 20 m with no movement is a phone on a desk
 * indoors, and a mixture in between describes a device that does not exist.
 * Asking the user to build a coherent set out of four sliders is asking them to
 * model GNSS error — so the three common situations are one tap each, and the
 * sliders stay, one card down, for what the presets do not cover.
 *
 * Altitude is deliberately **not** part of a preset. The plausible value
 * depends on the terrain of whichever city the anchor sits in, so any number
 * baked in here would be right in one city and wrong — visibly wrong, to anyone
 * comparing it with a digital elevation model — everywhere else. It keeps its
 * own switch in [ShapeCard].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    val active = SCENE_PRESETS.firstOrNull { it.matches(config) }

    FakeLocCard {
        SectionLabel(stringResource(R.string.section_scene))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.settings_signal_desc))
        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SCENE_PRESETS.forEach { preset ->
                FilterChip(
                    selected = preset === active,
                    onClick = { onUpdate { preset.applyTo(it) } },
                    label = { Text(stringResource(preset.label)) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        // A row of chips with nothing selected reads as "none of these is in
        // effect", which is wrong, rather than "you are on your own numbers",
        // which is right. One line names the actual state.
        if (active == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scene_custom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(10.dp))
        Body(stringResource(R.string.scene_altitude_note))
    }
}

// -------------------------------------------------------------- the numbers

/**
 * The raw dials behind the presets.
 *
 * Four controls, not eight: the detection switches moved into [GuardsCard].
 * The two groups answer different questions — "what does the fix look like"
 * versus "what is a detector able to see" — and stacking them meant the
 * switches that keep the whole thing from being caught sat below the fold,
 * visually identical to a drift radius.
 *
 * Each switch now disables its slider instead of removing it, and says in one
 * line what "off" means for that particular field. The four are not
 * interchangeable: with accuracy off the SDK answers whatever accuracy the
 * device reports, with altitude off it answers the real terrain height of
 * wherever the phone actually is, and with drift off the track is a single
 * frozen point. The old page said none of this, and a value that stops being
 * sent is precisely the kind of silence this project keeps getting caught by.
 */
@Composable
private fun ShapeCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_shape))
        Spacer(Modifier.height(10.dp))

        ToggleRow(
            title = stringResource(R.string.adv_accuracy),
            subtitle = stringResource(R.string.adv_accuracy_desc),
            checked = config.useAccuracy,
            onCheckedChange = { on -> onUpdate { it.copy(useAccuracy = on) } },
            icon = Icons.Rounded.Straighten,
        )
        SliderRow(
            title = "",
            value = config.accuracy,
            range = 1f..100f,
            unit = " m",
            decimals = 0,
            enabled = config.useAccuracy,
            onValueChange = { value -> onUpdate { it.copy(accuracy = value) } },
        )
        if (!config.useAccuracy) OffNote(stringResource(R.string.off_accuracy))

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_altitude),
            subtitle = stringResource(R.string.adv_altitude_desc),
            checked = config.useAltitude,
            onCheckedChange = { on -> onUpdate { it.copy(useAltitude = on) } },
            icon = Icons.Rounded.Terrain,
        )
        SliderRow(
            title = "",
            value = config.altitude.toFloat(),
            range = -50f..600f,
            unit = " m",
            decimals = 0,
            enabled = config.useAltitude,
            onValueChange = { value -> onUpdate { it.copy(altitude = value.toDouble()) } },
        )
        if (!config.useAltitude) OffNote(stringResource(R.string.off_altitude))

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_speed),
            subtitle = stringResource(R.string.adv_speed_desc),
            checked = config.useSpeed,
            onCheckedChange = { on -> onUpdate { it.copy(useSpeed = on) } },
            icon = Icons.Rounded.Speed,
        )
        SliderRow(
            title = "",
            value = config.speed,
            range = 0f..30f,
            unit = " m/s",
            enabled = config.useSpeed,
            onValueChange = { value -> onUpdate { it.copy(speed = value) } },
        )
        if (!config.useSpeed) OffNote(stringResource(R.string.off_speed))

        RowDivider(Modifier.padding(vertical = 8.dp))

        ToggleRow(
            title = stringResource(R.string.adv_drift),
            subtitle = stringResource(R.string.adv_drift_desc),
            checked = config.driftEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(driftEnabled = on) } },
            icon = Icons.Rounded.DirectionsWalk,
        )
        SliderRow(
            title = stringResource(R.string.adv_drift_radius),
            value = config.driftRadiusMeters.toFloat(),
            range = 5f..500f,
            unit = " m",
            decimals = 0,
            enabled = config.driftEnabled,
            onValueChange = { value -> onUpdate { it.copy(driftRadiusMeters = value.toDouble()) } },
        )
        SliderRow(
            title = stringResource(R.string.adv_drift_speed),
            value = config.driftSpeedMps,
            range = 0.5f..10f,
            unit = " m/s",
            enabled = config.driftEnabled,
            onValueChange = { value -> onUpdate { it.copy(driftSpeedMps = value) } },
        )
        if (!config.driftEnabled) OffNote(stringResource(R.string.off_drift))
    }
}

/**
 * One line under a switched-off dial, saying what stops happening.
 *
 * Deliberately not the error red of [GuardSwitch]: those four can turn the
 * whole feature off, while these only stop sending one field. Muting a value is
 * a legitimate choice — the point is that the consequence is written down where
 * the switch is, not left to be inferred from a number that is no longer used.
 */
@Composable
private fun OffNote(text: String) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------- what can be seen

/**
 * The four switches that decide whether any of the above is believable.
 *
 * Separated from the sliders on purpose. Two of them can make the module look
 * like it is doing nothing — with `mapSdkCompat` off, an app that reads its fix
 * from a vendor SDK gets real coordinates while the home screen still shows the
 * anchor; with `syncAddress` off, the city name stays real while the map moves.
 * In a single list, those sat one row above a drift radius with the same visual
 * weight, and nothing on the page said that turning one off is a repair step
 * rather than a preference.
 *
 * Hence: an explicit consequence line under every switch once it is off, and a
 * confirmation before the two silent ones go off at all. `antiMock` and
 * `strictSources` get the line but no dialog — switching those off is a known
 * workaround (an app that cannot get a fix at all) and a dialog there would be
 * friction on the one path a user legitimately needs.
 */
@Composable
private fun GuardsCard(
    config: SpoofConfig,
    onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit,
) {
    FakeLocCard {
        SectionLabel(stringResource(R.string.section_guards))
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.guards_desc))
        Spacer(Modifier.height(12.dp))
        RowDivider()
        Spacer(Modifier.height(12.dp))

        GuardSwitch(
            title = stringResource(R.string.adv_anti_mock),
            subtitle = stringResource(R.string.adv_anti_mock_desc),
            warning = stringResource(R.string.guard_off_anti_mock),
            checked = config.antiMock,
            icon = Icons.Rounded.VisibilityOff,
            onCheckedChange = { on -> onUpdate { it.copy(antiMock = on) } },
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        GuardSwitch(
            title = stringResource(R.string.adv_strict_sources),
            subtitle = stringResource(R.string.adv_strict_sources_desc),
            warning = stringResource(R.string.guard_off_strict_sources),
            checked = config.strictSources,
            icon = Icons.Rounded.LocationOff,
            onCheckedChange = { on -> onUpdate { it.copy(strictSources = on) } },
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        GuardSwitch(
            title = stringResource(R.string.adv_map_sdk),
            subtitle = stringResource(R.string.adv_map_sdk_desc),
            warning = stringResource(R.string.guard_off_map_sdk),
            checked = config.mapSdkCompat,
            icon = Icons.Rounded.NearMe,
            confirmBody = stringResource(R.string.guard_confirm_map_sdk),
            onCheckedChange = { on -> onUpdate { it.copy(mapSdkCompat = on) } },
        )

        RowDivider(Modifier.padding(vertical = 8.dp))

        GuardSwitch(
            title = stringResource(R.string.adv_sync_address),
            subtitle = stringResource(R.string.adv_sync_address_desc),
            warning = stringResource(R.string.guard_off_sync_address),
            checked = config.syncAddress,
            icon = Icons.Rounded.LocationCity,
            confirmBody = stringResource(R.string.guard_confirm_sync_address),
            onCheckedChange = { on -> onUpdate { it.copy(syncAddress = on) } },
        )
    }
}

/**
 * A switch that states what it costs to turn it off.
 *
 * [confirmBody] doubles as "this one is risky enough to ask twice": it is both
 * the text of the dialog and the flag that the dialog exists, so a switch
 * cannot end up with a dialog and no explanation of what it is asking about.
 */
@Composable
private fun GuardSwitch(
    title: String,
    subtitle: String,
    warning: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
    confirmBody: String? = null,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ToggleRow(
            title = title,
            subtitle = subtitle,
            checked = checked,
            icon = icon,
            onCheckedChange = { want ->
                // Turning it back on is never worth a dialog: it is a repair,
                // not a risk.
                if (!want && confirmBody != null) confirming = true else onCheckedChange(want)
            },
        )
        if (!checked) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = warning,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirming && confirmBody != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.guard_confirm_title, title)) },
            text = {
                Text(text = confirmBody, style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onCheckedChange(false)
                    },
                ) {
                    Text(stringResource(R.string.guard_confirm_off))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------ preset values

/**
 * One coherent set of numbers, and the check that says whether it is in effect.
 *
 * [matches] compares every field the preset writes, so a user who took the
 * walking preset and then dragged speed to 20 m/s sees no chip lit and the
 * "custom combination" line instead — which is the truth, and better than a lit
 * chip that no longer describes the config.
 */
private data class ScenePreset(
    val label: Int,
    val accuracy: Float,
    val speed: Float,
    val driftRadius: Double,
    val driftSpeed: Float,
) {
    fun matches(config: SpoofConfig): Boolean =
        config.useAccuracy &&
            config.useSpeed &&
            config.driftEnabled &&
            abs(config.accuracy - accuracy) < 0.05f &&
            abs(config.speed - speed) < 0.05f &&
            abs(config.driftRadiusMeters - driftRadius) < 0.5 &&
            abs(config.driftSpeedMps - driftSpeed) < 0.05f

    fun applyTo(config: SpoofConfig): SpoofConfig = config.copy(
        useAccuracy = true,
        accuracy = accuracy,
        useSpeed = true,
        speed = speed,
        driftEnabled = true,
        driftRadiusMeters = driftRadius,
        driftSpeedMps = driftSpeed,
    )
}

/**
 * Walking, driving, and a phone left on a desk indoors.
 *
 * The numbers are not round for the sake of it: 5 m is a clear-sky consumer fix,
 * 20 m is what a receiver reports indoors where it is extrapolating from a
 * partial sky view, 1.4 m/s is average walking pace and 11.1 m/s is 40 km/h in
 * city traffic. Drift radius follows the same logic — 5 m is the jitter of a
 * stationary phone, 40 m is a walk that crosses a street, 120 m is a car moving
 * through a junction.
 */
private val SCENE_PRESETS = listOf(
    ScenePreset(
        label = R.string.scene_walking,
        accuracy = 5f,
        speed = 1.4f,
        driftRadius = 40.0,
        driftSpeed = 1.4f,
    ),
    ScenePreset(
        label = R.string.scene_driving,
        accuracy = 8f,
        speed = 11.1f,
        driftRadius = 120.0,
        driftSpeed = 11.1f,
    ),
    ScenePreset(
        label = R.string.scene_indoor,
        accuracy = 20f,
        speed = 0f,
        driftRadius = 5.0,
        driftSpeed = 0.3f,
    ),
)

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

// ------------------------------------------------------------- restart

/** How long the system gets to tear this process down before the relaunch. */
private const val RESTART_DELAY_MS = 150L

/** Request code for the relaunch intent; any fixed value works. */
private const val RESTART_REQUEST_CODE = 1001

/**
 * Cold-restarts the app so the map SDK re-reads its key.
 *
 * The SDK reads the key once, in a static initialiser, so a changed key is
 * invisible to the process that is running — that part is not fixable from
 * here. What *is* fixable is making the user go to the system settings, find
 * this app, force-stop it and relaunch it. Scheduling the relaunch through
 * `AlarmManager` and finishing this task is the standard way: launching the
 * activity inline would have it torn down together with the process that
 * scheduled it.
 *
 * A no-op unless we have an activity and an alarm service. Killing the process
 * without a scheduled relaunch would leave the user staring at a closed app
 * with no idea whether the key was saved — worse than the hint it replaces.
 */
private fun restartForNewKey(context: Context) {
    val activity = context as? Activity ?: return
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val alarm = context.getSystemService(AlarmManager::class.java) ?: return

    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pending = PendingIntent.getActivity(
        context,
        RESTART_REQUEST_CODE,
        launch,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
    )
    alarm.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
    activity.finishAffinity()
    exitProcess(0)
}
