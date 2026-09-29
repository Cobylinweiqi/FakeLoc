#!/usr/bin/env python3
"""FakeLoc static gate.

Four mechanisms, each of which has caught a real defect in this repo:

  1. bracket balance **after** removing comments and literals — a naive count
     reports two false positives from KDoc text in Geo.kt;
  2. declaration counts — the Edit tool on this machine sometimes applies an
     insertion twice, and a doubled `val`/`fun` shows up as a compile error or,
     worse, as a silently duplicated resource entry;
  3. `R.string` references resolved against both locales, plus a real duplicate
     key check (a set comparison hides a doubled entry — the resource merger
     does not);
  4. package declaration vs directory layout.

Plus an explicit "must be gone" half. A removal that only lands on one side is
the failure shape this file keeps meeting: the code compiles, the APK builds, and
a Baidu import survives in a corner nobody re-reads. Counting *absences* by the
same means as presences is the only reliable way to see it.

Counts are asserted, not compared with `set`/`in`: a set comparison over
resource keys silently swallows a key that was inserted twice, which is exactly
the failure mode this file exists to catch.

Run it from the repository root, where the relative paths below resolve:

    python3 tools/fakeloc-gate.py

Exit code is 0 only when every counter matches. It is deliberately dependency-free
(stdlib only, Python 3.9+) so it can run before Gradle is even reachable.
"""
import io
import os
import re
import sys
from collections import Counter

ROOT = "app/src/main/java"
PKG_PREFIX = "app/src/main/java/io/github/cobylinweiqi/fakeloc/"
JAVA = PKG_PREFIX
RES = "app/src/main/res/"

# v1.5.0 — the two renderers dropped to get the APK under 10 MB. Their sources
# are deleted, not commented out, so the strongest assertion is that the files
# are not there at all.
DELETED_FILES = [
    JAVA + "ui/map/BaiduCanvas.kt",
    JAVA + "ui/map/AmapCanvas.kt",
]


def read(path):
    return io.open(path, encoding="utf-8").read()


def strip_code(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == '"' or c == "'":
            if src[max(0, i - 3):i] == '"""':
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            quote = c
            i += 1
            while i < n and src[i] != quote:
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


files = []
for directory, _, names in os.walk(ROOT):
    for name in names:
        if name.endswith(".kt"):
            files.append(os.path.join(directory, name))
files.sort()
whole = {p: read(p) for p in files}

lines = 0
unbalanced = []
for path, source in whole.items():
    lines += len(source.splitlines())
    stripped = strip_code(source)
    for open_char, close_char in (("{", "}"), ("(", ")"), ("[", "]")):
        a, b = stripped.count(open_char), stripped.count(close_char)
        if a != b:
            unbalanced.append((path.replace(PKG_PREFIX, ""), open_char, a, b))

print("kt files: %d, lines: %d" % (len(files), lines))
print("unbalanced brackets (comments/literals stripped): %d" % len(unbalanced))
for item in unbalanced:
    print("   ", item)

# ------------------------------------------------------------- a removal is a
# removal: the deleted renderers, and every trace of their SDKs anywhere in the
# sources. An import is the shape that survives — it compiles nowhere but reads
# fine, and it is what makes a later "just delete the dependency" step fail.
vanished = []
for path in DELETED_FILES:
    if os.path.exists(path):
        vanished.append(("file still present", path))

for path, source in whole.items():
    for needle in ("import com.baidu", "import com.amap", "import com.autonavi",
                   "baidu.mapapi", "amap.api.maps", "BaiduMapSDK", "libBaiduMapSDK",
                   "libAMapSDK", "AMapSDK_MAP"):
        if needle in source:
            vanished.append((path.replace(PKG_PREFIX, ""), needle))
if os.path.exists("app/src/main/AndroidManifest.xml"):
    # Comments stripped, exactly as for Kotlin: the manifest carries a note
    # explaining *why* there is no `com.baidu.lbsapi.API_KEY` entry, and a check
    # that trips on the explanation of an absence is worse than no check.
    manifest = re.sub(r"<!--.*?-->", "", read("app/src/main/AndroidManifest.xml"), flags=re.S)
    for needle in ("com.baidu", "com.amap", "MAP_AK", "API_KEY", "lbsapi"):
        if needle in manifest:
            vanished.append(("AndroidManifest.xml", needle))
print("traces of the removed SDKs: %d" % len(vanished))
for item in vanished:
    print("   ", item)

# ------------------------------------------------------------- the package
# name is the one thing LSPosed keys on. The scope list, this module's row in
# `/data/adb/lspd/config/modules_config.db`, and the Xposed-Modules-Repo
# submission all match on it, and a rename that stops halfway compiles fine,
# installs fine, and then shows up as a module nobody can look up. v1.6.0 moved
# it off `com.amo.fakeloc` — `amo.com` is not a domain the author owns, and the
# module repo verifies exactly that before listing a package — onto
# `io.github.cobylinweiqi.fakeloc`, the `io.github.<GitHub 用户名>` form, which
# needs no domain at all. All three names are asserted dead: the intermediate
# `io.github.coby.fakeloc` was never published under, so nothing has any reason
# to name it, and a half-done second rename is the exact failure this catches.
STALE_PACKAGE = ("com.amo.fakeloc", "com/amo/fakeloc",
                 "io.github.coby.fakeloc", "io/github/coby/fakeloc")
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", ".idea", ".workbuddy"}
SKIP_EXT = {".jar", ".png", ".webp", ".jpg", ".jpeg", ".so", ".dex",
            ".apk", ".aab", ".zip", ".ttf", ".otf"}
stale_package = []
SELF = os.path.abspath(__file__)
# The README is exempt from the broad scan because it legitimately names the old
# package twice over: it records builds that were made under it, and it is where
# the rename is documented. Without the exemption this check would fail on its
# own documentation. The README is pinned by exact count in EXPECTED instead, so
# a stray new reference still changes a number and trips.
STALE_EXEMPT = {SELF, os.path.abspath("README.md")}
for directory, dirnames, names in os.walk("."):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for name in names:
        if os.path.splitext(name)[1].lower() in SKIP_EXT:
            continue
        path = os.path.join(directory, name)
        # This file has to spell the old package name out — that is what the
        # check is. Scanning it would mean the gate always fails on itself.
        if os.path.abspath(path) in STALE_EXEMPT:
            continue
        try:
            source = open(path, encoding="utf-8").read()
        except (UnicodeDecodeError, PermissionError):
            continue
        for needle in STALE_PACKAGE:
            if needle in source:
                stale_package.append((path, needle))
print("stale package-name references: %d" % len(stale_package))
for item in stale_package:
    print("   ", item)

# ---------------------------------------------------------- signing material
# The release key must never be in the tree, and a leaked one is not a leak like
# any other: Android refuses to update a package whose signature differs, so
# whoever holds the key can sign an update this app will accept. The key itself
# lives in the developer's home directory where the gate cannot see it, so what
# is checked is the two things that are: no key file inside the repository, and
# the ignore rules that keep one out. `keystore.properties` is expected to be
# present locally and is therefore not flagged — only the ignore rule for it is.
SIGNING_EXTS = (".jks", ".keystore")
signing_leaks = []
for directory, dirnames, names in os.walk("."):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for name in names:
        if name.lower().endswith(SIGNING_EXTS):
            signing_leaks.append(os.path.join(directory, name))
print("signing material inside the repository: %d" % len(signing_leaks))
for item in signing_leaks:
    print("   ", item)

# ------------------------------------------------------- declaration counts
EXPECTED = {
    JAVA + "core/SpoofConfig.kt": {
        # v1.3.0: blankAddress replaced by syncAddress + 8 address fields.
        "val syncAddress: Boolean = true,": 1,
        "fun addressFor(getter: String): String?": 1,
        "val addrProvince: String = \"\",": 1,
        "val blankAddress": 0,
        "fun appliesTo(packageName: String?): Boolean": 1,
        # v1.4.0: the map-picker provider choice and the user's own keys.
        # v1.5.0: the provider field survives even though one entry remains — it
        # is part of the persisted blob, and dropping it would make re-adding a
        # provider a migration instead of a code change.
        "val mapProviderId: String = MapProvider.DEFAULT.id,": 1,
        # v1.4.1: the built-in/own switch and the key it selected are gone
        # with the built-in key itself; every provider is user-registered.
        "val mapCredentialId": 0,
        "fun customMapKey(": 0,
        # Baidu's and Amap's key slots stay in the schema for the same reason
        # mapProviderId does: they are already in every blob ever written.
        "val mapAkBaidu: String = \"\",": 1,
        "val mapAkAmap: String = \"\",": 1,
        "val mapAkTencent: String = \"\",": 1,
        "fun mapKeyFor(provider: MapProvider): String?": 1,
        "fun mapKeyConfigured(provider: MapProvider): Boolean": 1,
        "fun mapKeyValue(provider: MapProvider): String": 1,
        "fun withMapKey(provider: MapProvider, value: String): SpoofConfig": 1,
    },
    JAVA + "core/MapSettings.kt": {
        "enum class MapProvider(val id: String) {": 1,
        # v1.4.1: MapCredential deleted — there is no second key source.
        "MapCredential": 0,
        # v1.5.0: one entry. Anything else is a dependency this APK cannot pay for.
        "TENCENT(\"tencent\")": 1,
        "BAIDU": 0,
        "AMAP": 0,
        "val DEFAULT = TENCENT": 1,
        "val DEFAULT = BAIDU": 0,
        "val DEFAULT = BUILTIN": 0,
    },
    JAVA + "core/ConfigCodec.kt": {
        'private const val K_SYNC_ADDRESS = "sync_address"': 1,
        "put(K_SYNC_ADDRESS, config.syncAddress)": 1,
        "syncAddress = json.optBoolean(K_SYNC_ADDRESS": 1,
        "private fun decodeText(json: JSONObject, key: String, fallback: String): String": 1,
        "K_BLANK_ADDRESS": 0,
        # v1.4.0 additions; each must be written, declared and read back.
        'private const val K_MAP_PROVIDER = "map_provider"': 1,
        # v1.4.1: `map_credential` is no longer encoded or decoded. An old
        # blob that still carries one is simply ignored.
        'private const val K_MAP_CREDENTIAL = "map_credential"': 0,
        'private const val K_MAP_AK_BAIDU = "map_ak_baidu"': 1,
        'private const val K_MAP_AK_AMAP = "map_ak_amap"': 1,
        'private const val K_MAP_AK_TENCENT = "map_ak_tencent"': 1,
        "put(K_MAP_PROVIDER, config.mapProviderId)": 1,
        "put(K_MAP_CREDENTIAL, config.mapCredentialId)": 0,
        "put(K_MAP_AK_BAIDU, config.mapAkBaidu)": 1,
        "put(K_MAP_AK_AMAP, config.mapAkAmap)": 1,
        "put(K_MAP_AK_TENCENT, config.mapAkTencent)": 1,
        "mapProviderId = decodeText(json, K_MAP_PROVIDER, defaults.mapProviderId)": 1,
        "mapCredentialId = decodeText(": 0,
        "mapAkBaidu = decodeText(json, K_MAP_AK_BAIDU, defaults.mapAkBaidu)": 1,
        "mapAkAmap = decodeText(json, K_MAP_AK_AMAP, defaults.mapAkAmap)": 1,
        "mapAkTencent = decodeText(json, K_MAP_AK_TENCENT, defaults.mapAkTencent)": 1,
    },
    JAVA + "core/PickedAddress.kt": {
        "data class PickedAddress(": 1,
        "fun SpoofConfig.withAnchor(": 1,
        "fun SpoofConfig.moveTo(": 1,
    },
    JAVA + "ui/MainViewModel.kt": {
        "it.moveTo(": 5,
        "it.withAnchor(": 1,
    },
    JAVA + "MainActivity.kt": {
        "viewModel.applyMapPick(latitude, longitude, address)": 1,
    },
    JAVA + "ui/MapPickerScreen.kt": {
        "onPicked: (Double, Double, PickedAddress) -> Unit": 1,
        # v1.4.0: the screen reports the WGS-84 centre, not a BD-09 pair.
        "onPicked(centerWgs.first, centerWgs.second, resolved.value)": 1,
        # v1.3.1: the SDK geocode can be refused outright — fall back to the
        # platform one instead of spinning for ever.
        # v1.5.0: with Baidu's geocoder gone this is the only path, so the
        # deadline/polling pair it guarded is gone too.
        "private suspend fun platformReverseGeocode(": 1,
        "GEOCODE_SDK_TIMEOUT_MS": 0,
        "GEOCODE_POLL_MS": 0,
        "baiduGeoCoder": 0,
        # Declaration and use, not the KDoc paragraph that explains why the
        # race guard it backed is gone. Prose is allowed to keep the history.
        "val fallbackCenter": 0,
        "fallbackCenter.value": 0,
        "import android.location.Geocoder": 1,
        # v1.4.0: provider-agnostic search, so the Baidu-only suggester is gone.
        "private suspend fun platformSearchPlaces(": 1,
        # v1.4.0: the SDK is brought up by value, and the camera is aimed by
        # value too — no imperative MapView handle on the screen any more.
        "MapSdkBootstrap.ensure(context, config)": 1,
        "fun aimAt(latitude: Double, longitude: Double, zoom: Float)": 1,
        "MapViewRef": 0,
        # v1.4.0: a view factory that throws must not take the process with it.
        # Three states: no key, failed, drawable.
        "val mapKey = config.mapKeyFor(provider)": 1,
        "val mapFailure = remember(provider, mapKey)": 1,
        "onFailed = { mapFailure.value = it }": 1,
        "private fun MapFailedPanel(": 1,
        "val drawable = configured && failure == null": 1,
    },
    JAVA + "ui/map/MapCanvas.kt": {
        "fun MapCanvas(": 1,
        "internal const val PICKER_ZOOM = 16f": 1,
        "internal const val SEARCH_ZOOM = 17f": 1,
        "internal class MapViewHolder<T> {": 1,
        # Wraps the SDK view constructor: it runs inside Compose's change
        # application, where an escaping throw kills the process.
        "internal inline fun <T : View> buildMapView(": 1,
        "onFailed: (Throwable) -> Unit,": 2,
        "MapProvider.TENCENT -> TencentCanvas(": 1,
        # One dispatcher, one renderer; the BD-09 helpers went with Baidu.
        "internal fun toGcj02(": 1,
        "internal fun gcj02ToWgs84(": 1,
        "toBd09": 0,
        "bd09ToWgs84": 0,
        # Call sites, not prose: the KDoc above still names BaiduCanvas when it
        # explains the crash the guard was written for, and that history is worth
        # more than a cleaner grep.
        "BaiduCanvas(": 0,
        "AmapCanvas(": 0,
    },
    JAVA + "ui/map/TencentCanvas.kt": {
        "internal fun TencentCanvas(": 1,
        # Tencent's key is per view; a rebuild is the only way to apply a new one.
        "key(mapKey)": 1,
        "options.mapKey = mapKey": 1,
        "gcj02ToWgs84(target.latitude, target.longitude)": 2,
        "onFailed: (Throwable) -> Unit,": 1,
        "buildMapView(context, fail) {": 1,
        "(view as? MapView)?.let { map ->": 1,
    },
    JAVA + "mapsdk/MapSdkBootstrap.kt": {
        "private fun startTencent()": 1,
        "TencentMapInitializer.setAgreePrivacy(true)": 1,
        # v1.5.0: the Baidu and Amap branches are gone, and with them the
        # `setApiKey` guard that existed because an empty string is worse than
        # no call at all. If a provider with a global setApiKey ever returns,
        # that guard has to come back with it.
        "startBaidu": 0,
        "startAmap": 0,
        "SDKInitializer": 0,
        "MapsInitializer": 0,
        "TencentMapOptions": 0,
        "key=${if (key == null) \"built-in\" else \"custom\"}": 0,
    },
    JAVA + "ui/SettingsScreen.kt": {
        "fun SettingsScreen(": 1,
        # Read from the picker's top bar too, so it is no longer file-private.
        "internal fun providerLabel(provider: MapProvider): Int": 1,
        # v1.5.0: the chips, their generic row, and the three per-platform
        # resource lookups all ignored their argument once one entry was left.
        # A function that always returns the same constant is a lie about there
        # being a choice, so they are gone and the strings are addressed directly.
        "private fun PickerRow(": 0,
        "FilterChip": 0,
        "FlowRow": 0,
        "ExperimentalLayoutApi": 0,
        "private fun keyLabel(": 0,
        "private fun stepsTitle(": 0,
        "private fun stepsBody(": 0,
        "R.string.settings_key_tencent": 1,
        "R.string.steps_tencent_title": 1,
        "R.string.steps_tencent_body": 1,
        # v1.4.0 removed the credential switch; v1.5.0 removed the provider one.
        "private fun CredentialCard(": 0,
        "MapCredential": 0,
        "builtinActive": 0,
        "private fun HowToCard(provider: MapProvider)": 1,
        "private fun KeyField(": 1,
        "private fun Steps(": 0,
        "config.mapKeyValue(provider)": 1,
        "it.withMapKey(provider, typed)": 1,
        # v1.6.0: the tuning half of this page arrived from the home screen. The
        # counters below are the mirror image of the ones asserted to be zero in
        # HomeScreen.kt — a move that only lands on the destination side leaves
        # two live copies, and one that only lands on the source side leaves none.
        "TabRow(": 1,
        "private enum class SettingsTab(": 1,
        "R.string.settings_tab_map": 1,
        "R.string.section_advanced": 1,
        "R.string.settings_signal_desc": 1,
        "private fun SignalTuningCard(": 1,
        "ToggleRow(": 8,
        "SliderRow(": 5,
        "import androidx.compose.material.icons.rounded.LocationCity": 1,
        "config.syncAddress": 1,
        "R.string.adv_sync_address)": 1,
        "R.string.adv_sync_address_desc)": 1,
        # A missing Saver on this state would drop the tab on rotate; the two
        # scroll states are what keep each tab's offset across a switch.
        "rememberSaveable { mutableStateOf(SettingsTab.MAP.name) }": 1,
        "val signalScroll = rememberScrollState()": 1,
    },
    JAVA + "xposed/HookState.kt": {
        "private var basePackage: String? = null": 1,
        "private fun applies(cfg: SpoofConfig): Boolean {": 1,
        "fun active(): Boolean = applies(config())": 1,
        "append(\"inScope=\").append(applies(cfg))": 1,
        "return cfg.strictSources && applies(cfg)": 1,
        "return cfg.mapSdkCompat && applies(cfg)": 1,
        "if (!applies(current)) {": 1,
        'append(" syncAddress=").append(cfg.syncAddress)': 1,
        'if (cfg.addrCity.isNotBlank()) append(" fakeCity=").append(cfg.addrCity)': 1,
    },
    # The hook half must be untouched by any of this. It forges positions inside
    # the target app's own SDK classes and links against none of them, which is
    # the premise that made dropping two renderers cheap — so it is asserted.
    JAVA + "xposed/hooks/MapSdkHooks.kt": {
        "private fun observeAddress(": 1,
        # v1.3.2: null originals must be rewritten too — that is the whole point.
        "val answer = if (cfg.syncAddress && (original == null || original is String)) {": 1,
        "return answer ?: original": 1,
        "original !is String) return original": 0,
        "return cfg.addressFor(name) ?: original": 0,
        "private fun installAddressReads(": 1,
        "private val AMAP_ADDRESS = arrayOf(": 1,
        "private val BAIDU_ADDRESS = arrayOf(": 1,
        "private val TENCENT_ADDRESS = arrayOf(": 1,
        "installAddressReads(locationClass, \"amap\", AMAP_ADDRESS)": 1,
        "installAddressReads(locationClass, \"baidu\", BAIDU_ADDRESS)": 1,
        "installAddressReads(clazz, \"tencent\", TENCENT_ADDRESS)": 1,
        "private fun installAmap(loader: ClassLoader) {": 1,
        "private fun installBaidu(loader: ClassLoader) {": 1,
        "private fun installTencent(loader: ClassLoader) {": 1,
        # v1.3.0: BDLocation(String) is a JSON ctor, not a provider one.
        "private class BaiduFactory(": 1,
        "val factory = BaiduFactory.of(locationClass)": 1,
        "private const val EMPTY_JSON = \"{}\"": 1,
        "newInstance(PROVIDER)": 1,
    },
    JAVA + "ui/HomeScreen.kt": {
        "import androidx.compose.material.icons.rounded.LocationCity": 0,
        "config.syncAddress": 1,
        "R.string.adv_sync_address)": 0,
        "R.string.adv_sync_address_desc)": 0,
        "R.string.addr_unresolved": 1,
        "WrongLocation": 0,
        "adv_blank_address": 0,
        # v1.6.0: the tuning card left for the settings page. `ToggleRow(` at 1 is
        # the scope-all switch that stayed, and `adv_` at 3 is the summary chips on
        # the status card — the numbers that prove the *controls* left while the
        # read-only summary did not.
        "SignalTuningCard": 0,
        "SliderRow(": 0,
        "ToggleRow(": 1,
        "R.string.adv_": 3,
    },
    "app/src/main/AndroidManifest.xml": {
        # v1.4.1: the SDK gets its key from `setApiKey`, not from a meta-data
        # entry — and no key is substituted in at build time.
        'android:name="com.baidu.lbsapi.API_KEY"': 0,
        "${BAIDU_MAP_AK}": 0,
        'android:name=".MainActivity"': 1,
    },
    "app/build.gradle.kts": {
        # The application ID is what LSPosed keys everything on — the scope
        # list, this module's config blob, and the module-repo submission.
        # v1.6.0 renamed it twice before release: off com.amo.fakeloc (amo.com
        # is not a domain the author owns, and Xposed-Modules-Repo verifies that
        # before it will list a package), then onto the account it is actually
        # published from. `namespace` moves with it or the entry point named in
        # java_init.list stops resolving.
        'namespace = "io.github.cobylinweiqi.fakeloc"': 1,
        'applicationId = "io.github.cobylinweiqi.fakeloc"': 1,
        'namespace = "io.github.coby.fakeloc"': 0,
        'applicationId = "io.github.coby.fakeloc"': 0,
        'namespace = "com.amo.fakeloc"': 0,
        'applicationId = "com.amo.fakeloc"': 0,
        # Release signing: real key when `keystore.properties` is present, debug
        # key otherwise. Asserted because a silent fall back to the debug key
        # still builds and still installs — it just produces an APK that cannot
        # upgrade an existing release-signed install, which is a failure only
        # ever seen on someone else's device.
        "import java.util.Properties": 1,
        'val signingProps = rootProject.file("keystore.properties")': 1,
        "storeFile = file(props.getProperty(\"storeFile\"))": 1,
        'signingConfig = releaseSigning ?: signingConfigs.getByName("debug")': 1,
        "Sign release with the debug key so": 0,
        "versionCode = 15": 1,
        'versionName = "1.6.0"': 1,
        "versionCode = 14": 0,
        'versionName = "1.5.1"': 0,
        "versionCode = 13": 0,
        'versionName = "1.5.0"': 0,
        'versionName = "1.4.1"': 0,
        "versionCode = 12": 0,
        'versionName = "1.4.0"': 0,
        # v1.5.0: R8 on. It is what makes the Compose/androidx half of the dex
        # shrinkable; with the two SDKs that had to be kept whole removed, there
        # is nothing left that forbids it.
        "isMinifyEnabled = true": 1,
        "isMinifyEnabled = false": 0,
        # Still off: our `res/` is 31 KB and `resources.arsc` 479 KB, so there
        # is nothing to win and a vendor SDK that may look up resources by name
        # to lose.
        "isShrinkResources = false": 1,
        # v1.5.0: the two dependencies and everything that referenced them.
        "implementation(libs.baidumap": 0,
        "implementation(libs.amap": 0,
        "implementation(libs.tencent.map.vector)": 1,
        'manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk': 0,
        "val baiduMapAk: String": 0,
        # v1.4.0 size work: dex compressed, 32-bit .so set dropped.
        # AGP 8.7.3 spells it `packaging.dex.useLegacyPackaging`. The
        # top-level form does not exist on the `packaging` block and
        # fails script compilation with "Unresolved reference".
        "useLegacyPackaging = true": 2,
        "dexUseLegacyPackaging = true": 0,
        'abiFilters += listOf("arm64-v8a")': 1,
        'abiFilters += listOf("arm64-v8a", "armeabi-v7a")': 0,
    },
    "gradle/libs.versions.toml": {
        "baidumap-map =": 0,
        "baidumap-search =": 0,
        "baidumap-util =": 0,
        "amap-map =": 0,
        'baiduMap = "8.2.0"': 0,
        'amap = "10.0.600"': 0,
        'tencentMap = "5.9.0"': 1,
        "tencent-map-vector =": 1,
    },
    "app/proguard-rules.pro": {
        # The keep rules are the whole reason R8 can be on: anything the SDK
        # resolves by name has to survive by name.
        "-keep class com.tencent.** { *; }": 1,
        "-keep class com.qq.** { *; }": 1,
        "-keepclasseswithmembernames class * {": 1,
        "-keep class com.baidu.** { *; }": 0,
        "-keep class vi.com.** { *; }": 0,
        # The entry point named in META-INF/xposed/java_init.list. Losing it
        # turns this from a module into a plain app with no error anywhere.
        "-keep class io.github.cobylinweiqi.fakeloc.xposed.ModuleEntry { *; }": 1,
        "-keep class io.github.cobylinweiqi.fakeloc.xposed.** { *; }": 1,
        "-keep class io.github.cobylinweiqi.fakeloc.core.SpoofConfig { *; }": 1,
    },
    # The three files that make LSPosed treat this APK as a module at all. None
    # of them is checked by the compiler, and every failure mode here is silent:
    # a stale entry point loads as nothing, a missing module.prop means the app
    # simply never appears under Modules.
    "app/src/main/resources/META-INF/xposed/java_init.list": {
        "io.github.cobylinweiqi.fakeloc.xposed.ModuleEntry": 1,
        "io.github.coby.fakeloc.xposed.ModuleEntry": 0,
        "com.amo.fakeloc.xposed.ModuleEntry": 0,
    },
    "app/src/main/resources/META-INF/xposed/module.prop": {
        "minApiVersion=101": 1,
        "targetApiVersion=101": 1,
        "staticScope=false": 1,
    },
    # Exempt from the broad stale-package scan (see above) and pinned here
    # instead. The two positive assertions are the ones a reader would act on:
    # the package name the Tencent console wants, and the fact that the rename
    # is documented at all.
    "README.md": {
        "| 包名 | `io.github.cobylinweiqi.fakeloc` |": 1,
        "1.6.0 的应用 ID 变更：`com.amo.fakeloc` → `io.github.cobylinweiqi.fakeloc`": 1,
        # Where to get the APK, in two places. A stale repo URL sends readers to
        # somebody else's repository, which looks like a working link.
        "github.com/Cobylinweiqi/FakeLoc": 2,
        "com.amo.fakeloc": 6,
        # The two SHA-1s a reader has to act on are pinned by count. The release
        # key's is what goes into the map vendor's console for an official build;
        # the debug one is only correct for a self-compiled APK, and it also
        # appears in entries that record builds made before the key existed.
        "28a3a1d6f8a0d9b00af1a33a70586cc0c20a04b0": 3,
        "cce419e399302d402f2378a57b3c7985e98b66e2": 3,
    },
    # Paired with the signing-material scan above: that one catches a key file
    # that is actually there, this one catches the ignore rules that keep one
    # out. Losing either rule is invisible until the day it matters.
    ".gitignore": {
        "keystore.properties": 1,
        "*.jks": 1,
        ".workbuddy/": 1,
    },
}
wrong_counts = []
for path, expectations in EXPECTED.items():
    source = whole.get(path)
    if source is None:
        source = read(path)
    for needle, want in expectations.items():
        got = source.count(needle)
        if got != want:
            wrong_counts.append((path.replace(PKG_PREFIX, ""), needle, want, got))
print("declaration count mismatches: %d" % len(wrong_counts))
for item in wrong_counts:
    print("   ", item)

# ------------------------------------------------------------------ strings
refs = set()
for source in whole.values():
    refs |= set(re.findall(r"R\.string\.(\w+)", source))


def string_report(path):
    text = read(path)
    keys = re.findall(r'<string name="([^"]+)"', text)
    duplicates = sorted(k for k, v in Counter(keys).items() if v > 1)
    return keys, duplicates


en_keys, en_dups = string_report(RES + "values/strings.xml")
zh_keys, zh_dups = string_report(RES + "values-zh/strings.xml")
en, zh = set(en_keys), set(zh_keys)
print("R.string references: %d, undefined: %s" % (len(refs), sorted(refs - en)))
print("strings en: %d zh: %d, symmetric difference: %s" % (len(en), len(zh), sorted(en ^ zh)))
print("duplicate keys: en=%s zh=%s" % (en_dups or "none", zh_dups or "none"))

# Strings the layout still expects, asserted by name: a rename that only lands
# on one side is exactly the failure the set comparison above hides.
missing_named = [
    key for key in ("map_needs_key_title", "map_needs_key_desc", "map_open_settings",
                    "map_sdk_failed", "map_sdk_restart", "cd_settings",
                    "map_failed_desc", "map_datum_note", "settings_provider_desc",
                    "settings_howto_desc", "provider_tencent",
                    "settings_key_tencent", "steps_tencent_title", "steps_tencent_body",
                    # v1.6.0: the two tab captions and the tuning intro. The tuning
                    # tab deliberately reuses section_advanced, so that key is
                    # asserted here too — it is now load-bearing in two places.
                    "settings_tab_map", "settings_signal_desc", "section_advanced")
    if key not in en or key not in zh
]
print("named strings missing: %s" % (missing_named or "none"))

# v1.4.1 removed the credential switch; v1.5.0 removed two of the three
# platforms. A key that only survives in one locale would be caught by the
# symmetry check above; a key that survives in *both* is invisible to it, and
# that is the shape a half-finished removal takes.
dead_strings = [
    key for key in ("settings_credential", "settings_credential_desc",
                    "settings_credential_builtin", "settings_credential_custom",
                    "settings_key_builtin_active",
                    "provider_baidu", "provider_amap",
                    "settings_key_baidu", "settings_key_amap",
                    "steps_baidu_title", "steps_baidu_body",
                    "steps_amap_title", "steps_amap_body")
    if key in en or key in zh
]
print("strings that should be gone: %s" % (dead_strings or "none"))

# -------------------------------------------------------------- package/dir
mismatched = []
for path, source in whole.items():
    declared = re.search(r"^package\s+([\w.]+)", source, re.M).group(1)
    actual = os.path.dirname(path).replace("app/src/main/java/", "").replace("/", ".")
    if declared != actual:
        mismatched.append((path, declared, actual))
print("package/directory mismatches: %d" % len(mismatched))
for item in mismatched:
    print("   ", item)

# --------------------------------------------------- vendor wording (v1.5.1)
# The picker ships exactly one basemap. Copy that still offers to "switch
# provider", or names a console other than Tencent's, is now *false advice* —
# worse than a missing string, because the user acts on it and fails.
# adv_map_sdk_desc is exempt on purpose: it describes the injection channels,
# which really do hook all three vendor SDKs inside the target app.
VENDOR_WORDING_EXEMPT = {"adv_map_sdk_desc", "adv_map_sdk"}
STALE_NEEDLES = ("百度", "Baidu", "高德", "Amap", "三家",
                 "none of the three", "Another provider", "Each platform")
stale_wording = []
for keys_file, lang in ((RES + "values/strings.xml", "en"),
                        (RES + "values-zh/strings.xml", "zh")):
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', read(keys_file), re.S):
        key, body = m.group(1), m.group(2)
        if key in VENDOR_WORDING_EXEMPT:
            continue
        for needle in STALE_NEEDLES:
            if needle in body:
                stale_wording.append((lang, key, needle))
print("stale vendor wording: %s" % (stale_wording or "none"))

# The picker entry point must not promise a vendor by name.
promised_vendor = [
    (lang, body)
    for keys_file, lang in ((RES + "values/strings.xml", "en"),
                            (RES + "values-zh/strings.xml", "zh"))
    for body in [re.search(r'<string name="action_open_map">(.*?)</string>',
                           read(keys_file), re.S).group(1)]
    if any(n in body for n in ("百度", "Baidu", "高德", "Amap", "腾讯", "Tencent"))
]
print("action_open_map naming a vendor: %s" % (promised_vendor or "none"))

# ------------------------------------------------------------------- verdict
undefined = refs - en
asymmetric = en ^ zh
failures = (
    len(unbalanced)
    + len(vanished)
    + len(wrong_counts)
    + len(undefined)
    + len(asymmetric)
    + len(en_dups)
    + len(zh_dups)
    + len(missing_named)
    + len(dead_strings)
    + len(stale_wording)
    + len(promised_vendor)
    + len(mismatched)
    + len(stale_package)
    + len(signing_leaks)
)
print("GATE: %s (%d failing checks)" % ("PASS" if failures == 0 else "FAIL", failures))
sys.exit(0 if failures == 0 else 1)
