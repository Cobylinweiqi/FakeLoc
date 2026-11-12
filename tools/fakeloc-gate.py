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
    # The in-app target picker and the package enumerator behind it. The scope is
    # now LSPosed's alone, and these two were the second list that could disagree
    # with it — an empty one looked exactly like a module that does not work.
    JAVA + "ui/TargetAppsScreen.kt",
    JAVA + "data/InstalledApps.kt",
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
            ".apk", ".aab", ".zip", ".ttf", ".otf",
            # Icon design blobs the design tooling drops in the repository root.
            # Binary, and `read()` decodes as UTF-8, so leaving it in the scan
            # takes the whole gate down rather than failing one check.
            ".miora"}
# Files with no useful extension. `.DS_Store` is Finder bookkeeping, it appears
# the moment the repository is browsed in the GUI, and it is not UTF-8 — the
# extension filter above cannot see it because it has no extension at all.
SKIP_NAMES = {".DS_Store"}
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
        if name in SKIP_NAMES or os.path.splitext(name)[1].lower() in SKIP_EXT:
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

# ------------------------------------------------- credentials left in the tree
# The one credential this project asks a user to paste in is the Tencent key
# pair, and the mistake this guards against is a paste that lands somewhere
# committed — an example in the README, a comment recording what a debugging
# session turned up. Neither value has any reason to exist in the tree: the app
# reads both from the settings screen at runtime and keeps them in its own
# private preferences. The patterns below describe the *public shapes* of the
# two values (a key is six uppercase groups; a secret is 32 mixed-case
# alphanumerics) and carry no secret of their own.
#
# `keystore.properties` genuinely holds a 32-character secret of the same shape
# and is skipped anyway: it is ignored, so it cannot be committed, and the
# signing-material check above already owns it. `local.properties` is skipped
# for the same reason — it is ignored, and its `sdk.dir` is a path, not a
# credential.
SECRET_SHAPES = (
    ("key", re.compile(r"\b[A-Z0-9]{4,6}(?:-[A-Z0-9]{4,6}){5}\b")),
    ("secret", re.compile(r"(?=[A-Za-z0-9]{32}\b)"
                          r"(?=[^\s]*[A-Z])(?=[^\s]*[a-z])(?=[^\s]*\d)"
                          r"[A-Za-z0-9]{32}")),
)
SECRET_SKIP_NAMES = {"keystore.properties", "local.properties"}
secret_hits = []
for directory, dirnames, names in os.walk("."):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for name in names:
        if name in SKIP_NAMES or os.path.splitext(name)[1].lower() in SKIP_EXT:
            continue
        if name in SECRET_SKIP_NAMES:
            continue
        path = os.path.join(directory, name)
        try:
            text = read(path)
        # UnicodeDecodeError as well as OSError: a binary file that the suffix
        # filter did not anticipate must cost one skipped file, not the whole
        # gate. This check sits above every count assertion, so letting it throw
        # means nothing below runs and the run looks like a silent pass.
        except (OSError, UnicodeDecodeError):
            continue
        for label, pattern in SECRET_SHAPES:
            for found in pattern.finditer(text):
                secret_hits.append((path, label, found.group()[:8] + "…"))
print("credential-shaped strings in the tree: %d" % len(secret_hits))
for item in secret_hits:
    print("   ", item)

# ------------------------------------------------------- declaration counts
EXPECTED = {
    JAVA + "core/SpoofConfig.kt": {
        # v1.3.0: blankAddress replaced by syncAddress + 8 address fields.
        "val syncAddress: Boolean = true,": 1,
        "fun addressFor(getter: String): String?": 1,
        "val addrProvince: String = \"\",": 1,
        "val blankAddress": 0,
        # The per-process filter is gone: LSPosed's scope is the only list, so
        # "loaded into this process" already means "in scope". Both halves of the
        # old pair are asserted dead — a scope-all switch left behind would
        # silently re-narrow the module the day someone flipped it off.
        "fun appliesTo(packageName: String?): Boolean": 0,
        "val scopeAll": 0,
        "targetPackages": 0,
        "fun spoofs(): Boolean = playing": 1,
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
        # v1.7.1: Tencent's signing secret. Added because a key with 签名校验
        # switched on is refused with `status 111` until the request carries a
        # `sig`, and the gateway answers that way for *every* request — the base
        # map still draws, which is what made this look like an address problem
        # for so long.
        "val mapSkTencent: String = \"\",": 1,
        # v1.9.0: the place *label*, separate from the formatted address. Pinned
        # because it is the field the 企业微信 check-in chip reads through
        # `TencentLocation.getName()` — dropping it silently puts the address in
        # both the title and the subtitle.
        "val addrPlaceName: String = \"\",": 1,
        # v1.9.x: the city code the SDK getters answer with. Not derivable from
        # the reverse geocoder — that one returns the administrative division
        # code, a different namespace — so a fixed default is pinned here and a
        # configuration that carries one still wins.
        "private const val DEFAULT_CITY_CODE = \"194\"": 1,
        "\"getCityCode\" -> cityCode()": 1,
        # The guard that keeps an administrative division code out of this
        # field: `194` is three digits, `156350200` is not, and feeding the
        # latter to a check-in page makes it fail while the reply looks full.
        "private fun cityCode(): String": 1,
        "it.length in 1..4 && it.all(Char::isDigit)": 1,
        # The street getter: hooked but unanswerable before, which is why the
        # spoofed check-in reply carried no `street` while the real one did.
        "val addrStreet: String = \"\",": 1,
        "\"getStreet\" -> addrStreet": 1,
        "fun mapSecretValue(provider: MapProvider): String": 1,
        "fun withMapSecret(provider: MapProvider, value: String): SpoofConfig": 1,
        "fun mapKeyFor(provider: MapProvider): String?": 1,
        "fun mapKeyConfigured(provider: MapProvider): Boolean": 1,
        "fun mapKeyValue(provider: MapProvider): String": 1,
        "fun withMapKey(provider: MapProvider, value: String): SpoofConfig": 1,
        # v1.13.0: the saved-place history. One array with a `pinned` flag, not
        # two lists — two would let the same spot exist in both and disagree
        # about its address.
        "val places: List<SavedPlace> = emptyList(),": 1,
    },
    # v1.13.0 — the history. Its whole point is that the address travels with the
    # coordinates, so the recording path is asserted to build a `PickedAddress`
    # rather than to copy ten fields at the call site.
    JAVA + "core/SavedPlace.kt": {
        "data class SavedPlace(": 1,
        "val address: PickedAddress = PickedAddress.NONE,": 1,
        "fun SpoofConfig.currentPlace(pinned: Boolean = false): SavedPlace": 1,
        "fun SpoofConfig.recordingAnchor(): SpoofConfig": 1,
        "fun SpoofConfig.goToPlace(place: SavedPlace): SpoofConfig": 1,
        "fun SpoofConfig.togglePinned(latitude: Double, longitude: Double): SpoofConfig": 1,
        "const val RECENT_PLACES_LIMIT = 6": 1,
        # Pins are never evicted; only the history tail is trimmed.
        "if (place.pinned) {": 1,
        "val SpoofConfig.pinnedPlaces: List<SavedPlace>": 1,
        "val SpoofConfig.recentPlaces: List<SavedPlace>": 1,
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
        # v1.7.1: the signing secret has to be declared, written and read back,
        # like every other field this blob carries.
        'private const val K_MAP_SK_TENCENT = "map_sk_tencent"': 1,
        # v1.7.2: the scope keys are neither written nor read. Old blobs still
        # carry them; reading `targets` back is exactly what would resurrect the
        # empty-list-means-nothing-spoofed trap.
        'private const val K_SCOPE_ALL = "scope_all"': 0,
        'private const val K_TARGETS = "targets"': 0,
        "decodeTargets(": 0,
        "put(K_MAP_PROVIDER, config.mapProviderId)": 1,
        "put(K_MAP_CREDENTIAL, config.mapCredentialId)": 0,
        "put(K_MAP_AK_BAIDU, config.mapAkBaidu)": 1,
        "put(K_MAP_AK_AMAP, config.mapAkAmap)": 1,
        "put(K_MAP_AK_TENCENT, config.mapAkTencent)": 1,
        "put(K_MAP_SK_TENCENT, config.mapSkTencent)": 1,
        "mapProviderId = decodeText(json, K_MAP_PROVIDER, defaults.mapProviderId)": 1,
        "mapCredentialId = decodeText(": 0,
        "mapAkBaidu = decodeText(json, K_MAP_AK_BAIDU, defaults.mapAkBaidu)": 1,
        "mapAkAmap = decodeText(json, K_MAP_AK_AMAP, defaults.mapAkAmap)": 1,
        "mapAkTencent = decodeText(json, K_MAP_AK_TENCENT, defaults.mapAkTencent)": 1,
        "mapSkTencent = decodeText(json, K_MAP_SK_TENCENT, defaults.mapSkTencent)": 1,
        # v1.9.0: the place label, declared/written/read back like every other
        # address field. A blob from 1.7.1 has no key and decodes to "".
        'private const val K_ADDR_PLACE = "addr_place"': 1,
        "put(K_ADDR_PLACE, config.addrPlaceName)": 1,
        "addrPlaceName = decodeText(json, K_ADDR_PLACE, defaults.addrPlaceName)": 1,
        # v1.13.0: the history is written, declared and read back like every other
        # field, and both ends are capped — the hook side must never be able to
        # walk a list whose length came out of a blob.
        'private const val K_PLACES = "places"': 1,
        "put(K_PLACES, encodePlaces(config.places))": 1,
        "places = decodePlaces(json.optJSONArray(K_PLACES)),": 1,
        "private fun encodePlaces(places: List<SavedPlace>): JSONArray": 1,
        "private fun decodePlaces(array: JSONArray?): List<SavedPlace>": 1,
        "private const val MAX_PLACES = 32": 1,
        "if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue": 1,
    },
    JAVA + "core/PickedAddress.kt": {
        "data class PickedAddress(": 1,
        "fun SpoofConfig.withAnchor(": 1,
        "fun SpoofConfig.moveTo(": 1,
        # v1.9.0: carried by `withAnchor`, cleared by `moveTo` — both asserted
        # because an address part that survives a coordinate change is exactly
        # the half-converted state this field exists to prevent.
        "val placeName: String = \"\",": 1,
        "addrPlaceName = address.placeName,": 1,
        "addrPlaceName = \"\",": 1,
        # The city code is the one part the picker never learns from the reply,
        # so re-picking must not blank it out: asserted as a keep-if-blank.
        "addrCityCode = address.cityCode,": 1,
        # The street, carried and cleared on the same two paths as every other
        # address part.
        "val street: String = \"\",": 1,
        "addrStreet = address.street,": 1,
        "addrStreet = \"\",": 1,
    },
    JAVA + "ui/MainViewModel.kt": {
        "it.moveTo(": 5,
        "it.withAnchor(": 1,
        # v1.13.0: the history is written at this one chokepoint rather than in
        # each path that can move the anchor — a path added later would otherwise
        # move it without leaving a trace, and a history that skips a route is
        # worse than no history at all.
        "transformed.recordingAnchor()": 1,
        "fun pickSavedPlace(place: SavedPlace)": 1,
        "fun togglePinned(latitude: Double, longitude: Double)": 1,
        # v1.16.0: the address is resolved at the same chokepoint, for the same
        # reason. Five of the six ways to move the anchor drop the address, so
        # anything set outside the map picker reached the hooks with no city to
        # answer — and CCB's check-in page renders "地理位置获取失败" for exactly
        # that, with perfect coordinates underneath.
        "private fun scheduleAddress(delayMs: Long = ADDRESS_DEBOUNCE_MS)": 1,
        "it.withResolvedAddress(found)": 1,
        "fun retryAddress()": 1,
        # The in-flight answer must not be written over an anchor that moved
        # while it was travelling; that would put one city's name on another
        # city's coordinates.
        "if (now.latitude != latitude || now.longitude != longitude) return@launch": 1,
    },
    JAVA + "MainActivity.kt": {
        "viewModel.applyMapPick(latitude, longitude, address)": 1,
        # v1.7.2: three screens — the picker left with the in-app target list.
        # A restored Bundle can still name it, so the lookup has to be tolerant.
        "private enum class Screen { HOME, MAP, SETTINGS }": 1,
        "runCatching { Screen.valueOf(screenName) }": 1,
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
        # v1.7.0: the SDK path in front of both fallbacks. Asserted by call site
        # because the failure they guard against is a silent one — an address
        # that never resolves leaves the picker looking perfectly healthy.
        # v1.7.1 hands both of them the signing secret as well: a key with
        # signing switched on is refused with status 111 no matter how correct
        # the address logic is.
        "tencentReverseGeocode(context, lookupKey, lookupSecret, centerLat, centerLng)": 1,
        "tencentSuggestPlaces(": 1,
        "val lookupSecret by rememberUpdatedState(mapSecret)": 1,
        # Two lines together: `failed: Boolean,` on its own also matches the
        # search panel, and the pair pins the one added to PickerFooter — the
        # flag that has to be read against the `resolving` state beside it.
        "    resolving: Boolean,\n    failed: Boolean,": 1,
        # The confirm button must not be pressable while a lookup is in flight:
        # the address is cleared before each one, so confirming early stores an
        # anchor with no address at all.
        "enabled = enabled && !resolving,": 1,
        # v1.4.0: the SDK is brought up by value, and the camera is aimed by
        # value too — no imperative MapView handle on the screen any more.
        "MapSdkBootstrap.ensure(context, config)": 1,
        "fun aimAt(latitude: Double, longitude: Double, zoom: Float)": 1,
        "MapViewRef": 0,
        # v1.4.0: a view factory that throws must not take the process with it.
        # Three states: no key, failed, drawable.
        "val mapKey = config.mapKeyFor(provider)": 1,
        # v1.7.1: read raw — to the SDK a blank secret means "do not sign", so it
        # is a value to pass along rather than a missing one to filter out.
        "val mapSecret = config.mapSecretValue(provider)": 1,
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
    # v1.7.0: address lookup through the SDK that is already in the APK. The
    # declarations carry the weight here, and one of them is the whole point of
    # the file: without `newSearch` the api key lands in the signing-secret slot
    # — `TencentSearch(Context, String)` does that — and every request comes back
    # "请申请并填写开发者密钥" with nothing in the UI to say so. The two address
    # fields are pinned because they are what the hook side has been asking for
    # since 1.2.2 and getting nothing.
    # v1.7.1: the secret became a parameter and the two silent rejection paths
    # learned to log. Both changes come from the same afternoon — a key with
    # 签名校验 on answers `111` to *everything*, and the code that could have
    # said so discarded the status code instead.
    JAVA + "ui/map/TencentLookup.kt": {
        "internal data class TencentPlaceHit(": 1,
        "internal suspend fun tencentReverseGeocode(": 1,
        "internal suspend fun tencentSuggestPlaces(": 1,
        "private fun newSearch(context: Context, apiKey: String?, secretKey: String?): TencentSearch": 1,
        "private fun readAddress(response: Geo2AddressResultObject?): PickedAddress?": 1,
        # The assumption that this project would never meet a signing key, now
        # gone along with the constant.
        "private const val NO_SIGNING_SECRET": 0,
        "secretKey.orEmpty(),": 1,
        # The two rejection paths, asserted on the call: the whole point is that
        # these lines exist at all. Both used to `resume(null)` without a word.
        "Log.w(": 2,
        "private const val LOOKUP_TAG = \"FakeLoc/MapLookup\"": 1,
        "adCode = firstNonBlank(info?.adcode)": 1,
        "street = firstNonBlank(component?.street)": 1,
        # `city_code` here is the administrative division code, *not* what
        # `getCityCode()` speaks: reading it pinned a value CCB's check-in page
        # cannot look up (156430600 instead of its own 194). Asserted gone, and
        # the constant that answers for the field instead is asserted below.
        "cityCode = firstNonBlank(info?.city_code)": 0,
        "cityCode = \"\",": 1,
        # v1.7.2: the recommended place label. It is the *only* producer of
        # `addrPlaceName`, and therefore of what a check-in chip prints as the
        # place — losing the line would leave `getName()` answering the SDK's
        # real POI forever.
        "reverse.formatted_addresses?.recommend": 1,
        "placeName = placeName,": 1,
        "private fun firstNonBlank(vararg values: String?): String": 1,
        # v1.11.0: the status code stopped being discarded. One caller wants an
        # address and one wants the code, and both now go through the single
        # `geo2address` call site — two copies of the listener would drift, and
        # the copy that drifted would be the one whose log nobody read.
        "private suspend fun callGeo2Address(": 1,
        "internal suspend fun tencentProbe(": 1,
        "internal sealed interface TencentProbe": 1,
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
        #
        # v1.12.0: chips return, and the distinction is the point — v1.5.0's
        # chips switched between map vendors when only one vendor was left, so
        # they could not do anything. These pick a scene preset, and the numbers
        # behind them differ. Four occurrences: two imports, the call, and the
        # colour lookup that keeps the selected chip on the container colour.
        "private fun PickerRow(": 0,
        "FilterChip": 4,
        "FlowRow": 2,
        "ExperimentalLayoutApi": 2,
        "private fun keyLabel(": 0,
        "private fun stepsTitle(": 0,
        "private fun stepsBody(": 0,
        "R.string.settings_key_tencent": 1,
        "R.string.steps_tencent_title": 1,
        # v1.14.0: the five-line step list became four titled sections — the key,
        # its signing secret, the endpoint quota, and a status-code table. The
        # quota section is the reason this exists: that gate fails with its own
        # code (113), says nothing, and the default allocation is zero, so it is
        # the one a user cannot reason their way to. The old flat body is gone.
        "R.string.steps_tencent_body": 0,
        "private data class DetailSection(": 1,
        "private val TENCENT_HOWTO = listOf(": 1,
        "sections = TENCENT_HOWTO": 1,
        "packageName = packageName": 1,
        "R.string.howto_key_body": 1,
        "R.string.howto_sk_body": 1,
        "R.string.howto_quota_body": 1,
        "R.string.howto_status_body": 1,
        # Four sections no longer fit above the buttons, so the dialog body has
        # to scroll; without this the last section is simply unreachable.
        "modifier = Modifier.verticalScroll(rememberScrollState())": 1,
        # v1.4.0 removed the credential switch; v1.5.0 removed the provider one.
        "private fun CredentialCard(": 0,
        "MapCredential": 0,
        "builtinActive": 0,
        # The package name is an argument now, not a literal in the string: it
        # has to match the one the console is told, and a hard-coded copy would
        # survive a rename.
        "private fun HowToCard(provider: MapProvider, packageName: String)": 1,
        "private fun KeyField(": 1,
        "private fun Steps(": 0,
        # v1.11.0: both accessors are now read twice — once by the field that
        # binds them and once by the connectivity test, which needs the same two
        # values to make a real request. Still routed through the provider, so
        # the screen stays ignorant of which named field it is editing.
        "config.mapKeyValue(provider)": 2,
        "it.withMapKey(provider, typed)": 1,
        # v1.7.1: the signing secret gets its own field beside the key. Both are
        # still read and written through the provider, so the screen does not
        # have to know which named field it is editing.
        "config.mapSecretValue(provider)": 2,
        "it.withMapSecret(provider, typed)": 1,
        # v1.11.0: the one control on this page that can honestly say a key
        # *works*. `mapKeyConfigured` only ever answered "a string is present".
        "tencentProbe(": 1,
        "R.string.settings_probe_action": 1,
        "private fun ConnectivityTest(": 1,
        # Suffixed so the two cannot match each other: the label's resource name
        # is a prefix of the description's, and a bare count would see two of it.
        "R.string.settings_sk_tencent),": 1,
        "R.string.settings_sk_tencent_desc))": 1,
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
        # v1.12.0: eight toggles became four dials plus four guarded switches,
        # in three cards. The counts below are the move — four plain ToggleRow
        # calls for the dials, and one inside GuardSwitch, which is the only
        # place a detection switch can be rendered. Five, not eight, is what
        # proves the anti-detection group left this list.
        "ToggleRow(": 5,
        "SliderRow(": 5,
        "private fun SceneCard(": 1,
        "private fun ShapeCard(": 1,
        "private fun GuardsCard(": 1,
        "private fun GuardSwitch(": 1,
        # The preset table and the matcher that decides whether a chip is lit.
        # One data class, three rows, one `matches` — a new preset added without
        # a row would change the first number, and a chip lit on a config it no
        # longer describes would need `matches` to go missing.
        "private val SCENE_PRESETS = listOf(": 1,
        "ScenePreset(": 4,
        "fun matches(config: SpoofConfig): Boolean": 1,
        # Only the two that fail silently ask first. Both go through the same
        # dialog, so one AlertDialog is all this file should contain.
        # v1.13.0: two, and the second is a different kind. One is the guard
        # confirmation (the only switch that may ask twice); the other is
        # `DetailExpander`, which holds the paragraphs that used to be printed
        # inline. Counting them together is still the check that matters: a
        # third AlertDialog would mean a second place a switch can silently
        # change behaviour, and that is the thing this number is watching.
        "AlertDialog(": 2,
        "R.string.guard_confirm_map_sdk": 1,
        "R.string.guard_confirm_sync_address": 1,
        "import androidx.compose.material.icons.rounded.LocationCity": 1,
        "config.syncAddress": 1,
        "R.string.adv_sync_address)": 1,
        "R.string.adv_sync_address_desc)": 1,
        # v1.13.0: the tuning half is now the tab the page opens on — it is the
        # half people come back to, and the key is entered once. Asserted on the
        # initial value, because an enum order alone does not decide which tab a
        # user sees.
        "rememberSaveable { mutableStateOf(SettingsTab.SIGNAL.name) }": 1,
        "val signalScroll = rememberScrollState()": 1,
        # v1.13.0: each of the four dials is disabled with its switch instead of
        # being removed by it. Four `enabled = config.use…` arguments are the
        # move — one missing would leave a slider that looks live and is not.
        "enabled = config.useAccuracy,": 1,
        "enabled = config.useAltitude,": 1,
        "enabled = config.useSpeed,": 1,
        "enabled = config.driftEnabled,": 2,
        # The consequence of switching one off, written under the control.
        "private fun OffNote(": 1,
        "R.string.off_accuracy": 1,
        "R.string.off_altitude": 1,
        "R.string.off_speed": 1,
        "R.string.off_drift": 1,
        # The inline essays are behind a dialog now; the dialog is one composable
        # so the map half cannot grow a second, differently-behaved one.
        "private fun DetailExpander(": 1,
        "R.string.settings_map_service_detail": 1,
        "R.string.action_learn_more": 1,
    },
    JAVA + "xposed/HookState.kt": {
        # The `:suffix` stripping left with the target list it existed for: a
        # subprocess and its package can no longer disagree about being in scope.
        "private var basePackage: String? = null": 0,
        "private fun applies(cfg: SpoofConfig): Boolean = cfg.spoofs()": 1,
        "fun active(): Boolean = applies(config())": 1,
        "append(\"inScope=\").append(applies(cfg))": 1,
        # The two columns that described the removed switch and list.
        "append(\" scopeAll=\")": 0,
        "append(\" targets=\")": 0,
        "return cfg.strictSources && applies(cfg)": 1,
        "return cfg.mapSdkCompat && applies(cfg)": 1,
        "if (!applies(current)) {": 1,
        'append(" syncAddress=").append(cfg.syncAddress)': 1,
        'if (cfg.addrCity.isNotBlank()) append(" fakeCity=").append(cfg.addrCity)': 1,
        # v1.9.0: a `getLastKnownLocation` from hours ago no longer carries its
        # timestamp onto the fake fix — a check-in flow reads that as "no usable
        # fix yet". Both halves are pinned: the window, and the branch that
        # decides between preserving and re-stamping.
        "private const val FIX_FRESH_WINDOW_MS = 5_000L": 1,
        "val originalIsFresh = originalTime > 0L &&": 1,
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
        # v1.9.0: Tencent grew a per-flavor label, because the app's Java package
        # is not a constant. What is pinned here is the pair of package names —
        # `sapp` is 企业微信's, and dropping it is the silent regression this
        # release exists to fix — plus the POI path, which is a *second* copy of
        # the real position that the payload getters never reach.
        "installAddressReads(clazz, flavor.label, TENCENT_ADDRESS)": 1,
        "installAddressReads(clazz, \"tencent\", TENCENT_ADDRESS)": 0,
        "private class TencentFlavor(": 1,
        '"com.tencent.map.geolocation.sapp.TencentLocationManager"': 1,
        '"com.tencent.map.geolocation.sapp.TencentLocationListener"': 1,
        "private fun installTencentFlavor(loader: ClassLoader, flavor: TencentFlavor): Boolean {": 1,
        # The listener is recognised by method shape, not by type name: a
        # repackaged interface must not be able to switch the subscription path
        # off. The type lookup above is a diagnostic only.
        "private fun isTencentListener(value: Any): Boolean =": 1,
        # One pass per listener *class*: a check-in screen re-subscribes on every
        # refresh, and re-wrapping the same `Method` would grow the hook chain
        # without bound inside someone else's process.
        "private val tencentListeners: MutableSet<Class<*>> =": 1,
        "private fun armTencentPayloadClass(clazz: Class<*>, flavor: TencentFlavor) {": 1,
        "private fun installTencentPoiReads(clazz: Class<*>, flavor: TencentFlavor) {": 1,
        "private fun armTencentPoi(clazz: Class<*>, flavor: TencentFlavor) {": 1,
        "private fun installAmap(loader: ClassLoader): Boolean {": 1,
        "private fun installBaidu(loader: ClassLoader): Boolean {": 1,
        "private fun installTencent(loader: ClassLoader): Boolean {": 1,
        # v1.10.0 (加固包补丁): "armed" is a fact about one channel, not about a
        # loader. A single set filled on entry turned the one attempt that runs
        # before a hardened app has decrypted its dex into a permanent
        # "already done", and the app then had no channel at all while the log
        # said nothing.
        "fun install(classLoader: ClassLoader): Int {": 1,
        "private val amapArmed: MutableSet<ClassLoader> = newLoaderSet()": 1,
        "private val baiduArmed: MutableSet<ClassLoader> = newLoaderSet()": 1,
        "private val tencentArmed: MutableSet<ClassLoader> = newLoaderSet()": 1,
        "private fun newLoaderSet(): MutableSet<ClassLoader> =": 1,
        "private val missReported: MutableMap<ClassLoader, MutableSet<String>> =": 1,
        "private fun reportMissing(loader: ClassLoader, sdk: String, detail: String): Boolean {": 1,
        # The failure that motivated all of the above must stay *sayable*: one
        # line per loader and SDK, not one per retry.
        '"$sdk SDK not reachable from ${loader.javaClass.name} yet ($detail) — will retry"': 1,
        # v1.3.0: BDLocation(String) is a JSON ctor, not a provider one.
        "private class BaiduFactory(": 1,
        "val factory = BaiduFactory.of(locationClass)": 1,
        "private const val EMPTY_JSON = \"{}\"": 1,
        "newInstance(PROVIDER)": 1,
        # v1.10.0 (打卡修复) — rewriting the coordinates is not enough when the
        # SDK's own callback is a *failure*: `strictSources` has already cut
        # getAllCellInfo / getScanResults, so Baidu answers 167 (server could not
        # locate) or 505 (request refused) with our coordinates inside. An app
        # that branches on getLocType() reads the type and reports "location
        # failed". The type, not the numbers, is what has to be rescued.
        "private const val BD_TYPE_NETWORK = 161": 1,
        "private val locTypeGetter = HookKit.findMethod(locationClass, \"getLocType\")": 1,
        "} else if (!succeeded(locTypeOf(target))) {": 1,
        "call(locType, target, BD_TYPE_NETWORK)": 1,
        "private fun succeeded(type: Int?): Boolean =": 1,
        # v1.10.0 (H5 诊断补丁): 失败码救援必须也发生在 *读类型* 的那一步。
        # 只在 `apply()` 里救援等于赌 App 先读坐标 —— 而几乎每个 App 都是先读
        # locType 再决定要不要读坐标，于是救援永远不执行，屏幕上还是"获取失败"。
        'HookKit.hookAll(locationClass, "getLocType", TAG)': 1,
        "fun locTypeOf(target: Any): Int? =": 1,
        "private fun recordCallback(listener: Any, reported: Int?) {": 1,
        "type in 61..66 || type == 68 || type == BD_TYPE_NETWORK": 1,
        # The type must be read *before* the write, or the breadcrumb always
        # reports our own value and the diagnosis is lost.
        "val reported = writer.locTypeOf(payload)": 1,
        # Two call sites on purpose: the getter path (what the app reads) and the
        # callback path (what the app is handed). Both have to carry the type fix.
        "writer.apply(payload)": 2,
    },
    # v1.10.0 (加固包补丁) — the second attempt at arming the SDK channels. Its whole reason
    # to exist is the *timing*, so what is pinned is the framework hook that runs
    # after the packer has decrypted the dex, and the fact that the retries go
    # through the same entry point as the first attempt.
    JAVA + "xposed/hooks/LateArmHooks.kt": {
        "internal object LateArmHooks {": 1,
        "Instrumentation::class.java, \"callApplicationOnCreate\", TAG": 1,
        "private val RETRY_DELAYS_MS = longArrayOf(": 1,
        "runCatching { MapSdkHooks.install(loader) }": 1,
        "runCatching { include(application.classLoader) }": 1,
        "runCatching { include(application.baseContext?.classLoader) }": 1,
        # Reaching into hidden API was the alternative and is deliberately not
        # done: the framework hands us the Application, so there is nothing to
        # reflect into.
        "ActivityThread": 0,
        "getDeclaredMethod": 0,
        # The shell that forced this file is named in the KDoc as evidence, and
        # nowhere else — a hook on a packer's class would break every other app.
        "com.secneo.apkwrapper.AW": 1,
        "libDexHelper.so": 1,
    },
    # v1.10.0 (H5 诊断补丁) — 「位置获取失败」到底是我们的锅，还是页面根本没问过任何人，
    # 取决于三件事：页面加载了哪些命令类、Chromium 有没有被要过权限、最终回给页面的脚本
    # 长什么样。这几条断言把那个取证面钉住，免得有人"顺手清理"掉唯一能区分三者的一层。
    JAVA + "xposed/hooks/WebBridgeHooks.kt": {
        "internal object WebBridgeHooks {": 1,
        'HookKit.hookAll(ClassLoader::class.java, "loadClass", TAG)': 1,
        'HookKit.hookAll(WebView::class.java, "evaluateJavascript", TAG)': 1,
        '"onGeolocationPermissionsShowPrompt",': 1,
        'private val CMD_MARKERS = arrayOf(".ecpweb.cmd.")': 1,
        "private const val JS_BUDGET = 20": 1,
        # 只报告、不 hook 命令类：命令集随 App 版本整批换名，挂在不存在的名字上是死重量。
        "clazz.declaredMethods": 1,
        # 例外只开给两个入口（`doWork` / `startLocation`），且在类**第一次加载**时装 ——
        # 页面实例化命令后立刻调用，晚一步就错过失败的那一次。理由（2026-11-12）：
        # 异步回包 `PJF.shellNotify` 字段全对而页面仍报“获取失败”，`doWork` 的返回值
        # 是唯一还在视野外的通道。
        'HookKit.hookAll(clazz,': 2,
        # 第二处是修复，不只是探测：`doWork` 同步回 `{"result":"定位失败","success":false}` 时，
        # 页面胶水 `if (o.success) this.once(...)` 根本没注册回调，异步的成功回包发给空气。
        # 2026-11-12 用 CDP 实测确认，详见 WebBridgeHooks#repairLocationReply。
        "private const val LOCATION_COMMAND = \"getlocation\"": 1,
        "private fun repairLocationReply(clazz: Class<*>)": 1,
        "internal fun repairReply(result: Any?): Any?": 1,
        "private fun locationPayload(): Map<String, Any>?": 1,
        # 载荷必须是**对象**而不是 JSON 字符串：页面胶水把 `resp.result` 直接交给业务代码
        # （`options.success(resp.result)`），业务代码读 `e.latitude` / `e.address`。字符串
        # 里字字都对，属性一个都没有 —— 2026-11-12 用 CDP 在真页面上验证过两种形态。
        "fields[\"latitude\"] = lat": 1,
        # JS 注入方向不走 `addJavascriptInterface`（命令由 native 分发器派发），
        # 挂在那里既看不到调用也看不到回包。
        "addJavascriptInterface": 0,
    },
    JAVA + "xposed/hooks/DynamicLoaderHooks.kt": {
        # Both resolution points, because a packer may override `loadClass`
        # without delegating to `super` — and then the public method, where every
        # other app is caught, is never reached.
        'val hooked = watch("loadClass") + watch("findClass")': 1,
        "private fun watch(methodName: String): Int =": 1,
        '"com.amap.api.location.",': 1,
        '"com.baidu.location.",': 1,
        '"com.tencent.map.geolocation.",': 1,
    },
    JAVA + "ui/HomeScreen.kt": {
        "import androidx.compose.material.icons.rounded.LocationCity": 0,
        "config.syncAddress": 1,
        "R.string.adv_sync_address)": 0,
        "R.string.adv_sync_address_desc)": 0,
        # v1.16.0: the unresolved-address line became an action, so it split into
        # three by cause. The old single string is gone on purpose — it told the
        # user to open the map picker even when there was no key, which is a
        # button that cannot work.
        "R.string.addr_unresolved": 0,
        "R.string.addr_resolving": 1,
        "R.string.addr_retry": 1,
        "R.string.addr_no_key": 1,
        # Two declarations: the screen takes it and the status card takes it —
        # the line that needs it sits inside the card.
        "onRetryAddress: () -> Unit": 2,
        "WrongLocation": 0,
        "adv_blank_address": 0,
        # v1.6.0: the tuning card left for the settings page. v1.7.2: the
        # scope-all switch followed the target list out, so no ToggleRow is left
        # on this screen at all; `adv_` at 3 is the summary chips on the status
        # card — the numbers that prove the *controls* left while the read-only
        # summary did not.
        "SignalTuningCard": 0,
        "SliderRow(": 0,
        "ToggleRow(": 0,
        "R.string.adv_": 3,
        # What replaced the switch: a fixed sentence, plus no route to a picker.
        "private fun TargetsCard(": 1,
        "R.string.targets_fixed_desc": 1,
        "onOpenTargets": 0,
        "rounded.Apps": 0,
        # v1.11.0: the master switch moved out of the scroll and into the status
        # card, and the pill stopped conflating "idle" with "not connected".
        # Both are asserted because both were the defect: a start button below
        # six cards is a button nobody finds, and a pill driven by `playing`
        # accused a healthy install of being inactive.
        "private fun PrimaryActionButton(playing: Boolean, onClick: () -> Unit)": 1,
        "RowDivider()\n        Spacer(Modifier.height(14.dp))\n\n        PrimaryActionButton(": 1,
        "PillTone.WARN": 1,
        "R.string.status_module_ready": 1,
        "R.string.state_unbound_sub": 1,
        # The explanation dialog. Its three cases are the three ways LSPosed
        # silently fails to inject, and it is the only reason a pill is allowed
        # to say "not active" at all.
        "R.string.module_help_body": 1,
        "AlertDialog(": 1,
        # The editor's saved/unsaved/pending split.
        "R.string.coord_pending": 1,
        # v1.13.0: 最近 / 收藏 / 城市 in one card. The eight built-in cities
        # answered a question almost nobody asks; the places a user returns to
        # are their office and last week's check-in, which no hand-written list
        # can contain.
        "private fun PlacesCard(": 1,
        "private fun SavedPlaceRow(": 1,
        "private fun PlaceModeChip(": 1,
        "import androidx.compose.material.icons.rounded.Place": 1,
        # Suffixed so the chip label and the empty-state line cannot match each
        # other — one is a prefix of the other.
        "R.string.places_recent)": 1,
        "R.string.places_recent_empty": 1,
        # v1.13.0: the pair of equal-weight buttons above the map card is gone.
        # One filled the coordinate box, the other replaced the anchor — an input
        # and a state change, which should not look like siblings. Both now live
        # under the fields as text actions.
        "QuickActionsRow": 0,
        "R.string.action_paste)": 1,
        "R.string.action_use_real_gps)": 1,
        # v1.13.0: region first, then the POI label — the order a Chinese address
        # is written in. The label is trimmed against the region first, because a
        # recommended name usually opens with its own administrative name.
        "listOf(region, label)": 1,
        "it.startsWith(region)": 1,
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
        # v1.7.0 — the picker resolves addresses through the bundled map SDK
        # first, so that `adcode` / `city_code` have a producer again and the
        # lookup no longer depends on the ROM shipping a working geocoder.
        # v1.7.1 — the signing secret became a setting, because a key with
        # 签名校验 on answers `111` to every request and v1.7.0 had nowhere to
        # put the secret that would have satisfied it.
        # v1.9.0 — 企业微信's check-in path: the Tencent channel grew the `sapp`
        # package, the payload's place-name getter, its POI list, and a
        # freshness rule for `getLastKnownLocation`.
        # Numbered 1.9.0, not the PR's 1.7.2: 1.8.0 (the rename) had already
        # shipped on versionCode 18 by the time this landed.
        # v1.10.0 — two changes in one release: the in-app target list is gone
        # (the module acts on every process LSPosed injected it into), and PR #1's
        # `sapp` Tencent channel for 企业微信 check-in is in. Numbered past the
        # published 1.8.0 / 1.9.0 on purpose, so the version string sorts with the
        # code and the versionCode still upgrades over 1.9.0's 19.
        # v1.11.0 — manager UI only, no hook changed. Numbered past 1.10.0
        # because that build is already on the phone.
        # v1.12.0 — manager UI only again. Numbered past 1.11.0 because that
        # build exists as an artifact even though it was never installed.
        # v1.13.0 — the last UI round, and the only one that changes the
        # persisted schema (an additive `places` array, no version bump needed).
        # v1.14.0 — manager UI only: the key-registration steps are now four
        # titled sections covering the key, its signing secret, the per-endpoint
        # quota, and the status codes those three gates return.
        # v1.15.0 — manager UI only: the launcher icon was replaced with artwork
        # (two WebP layers per density instead of the two vector XMLs).
        # v1.16.0 — the anchor resolves its own address. First release since
        # 1.10.0 that changes hook behaviour: a reply that carries no address is
        # no longer repaired silently, and `getAddrStr` assembles the parts when
        # the picker received no formatted line.
        # v1.17.0 — manager UI only: the launcher artwork swapped to a pixel-art
        # master (tools/icon-master-pixel.png); both adaptive-icon layers are
        # re-derived by make-icons.py with the composition re-measured.
        "versionCode = 27": 1,
        'versionName = "1.17.0"': 1,
        "versionCode = 26": 0,
        'versionName = "1.16.0"': 0,
        "versionCode = 25": 0,
        'versionName = "1.15.0"': 0,
        "versionCode = 24": 0,
        'versionName = "1.14.0"': 0,
        "versionCode = 23": 0,
        'versionName = "1.13.0"': 0,
        "versionCode = 22": 0,
        'versionName = "1.12.0"': 0,
        "versionCode = 21": 0,
        'versionName = "1.11.0"': 0,
        "versionCode = 20": 0,
        'versionName = "1.10.0"': 0,
        "versionCode = 19": 0,
        'versionName = "1.9.0"': 0,
        # PR #1 shipped this as 1.7.2 / 18; it is folded into 1.10.0 instead, so
        # the old numbers must not survive anywhere in the tree.
        "versionCode = 18": 0,
        'versionName = "1.8.0"': 0,
        'versionName = "1.7.2"': 0,
        "versionCode = 17": 0,
        'versionName = "1.7.1"': 0,
        "versionCode = 16": 0,
        'versionName = "1.7.0"': 0,
        "versionCode = 15": 0,
        'versionName = "1.6.0"': 0,
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
    # Rewritten on 2026-09-30 as a short user-facing document; the ~1280-line
    # build log it replaced is still in git (5076c93). Every value below is
    # deliberately spelt out exactly once in the new text, which is what makes a
    # count meaningful here:
    #   * the package name the Tencent console asks for, plus the old one — an
    #     install under the old name is a different app and cannot be upgraded
    #     in place, so that line earns its keep;
    #   * the SHA-1 of the official Release key. A self-built APK signs with a
    #     different certificate, and the README now says so without naming the
    #     debug key at all, so that assertion is dropped rather than pinned to 0;
    #   * the repository URL — a stale one looks like a working link.
    "README.md": {
        # 1 as of 2026-11-12: the v1.8.0 rename note was removed when the
        # display name went back to FakeLoc, leaving the package-name line in
        # the install section as the only occurrence.
        "io.github.cobylinweiqi.fakeloc": 1,
        "com.amo.fakeloc": 1,
        "28a3a1d6f8a0d9b00af1a33a70586cc0c20a04b0": 1,
        # 2 as of 2026-09-30, up from 1: the closing line that invites a star
        # links the repository as well. Both links have to keep pointing here.
        "github.com/Cobylinweiqi/FakeLoc": 2,
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
                    "settings_key_tencent", "steps_tencent_title",
                    # v1.14.0: the four sections that replaced steps_tencent_body.
                    "howto_key_heading", "howto_key_body",
                    "howto_sk_heading", "howto_sk_body",
                    "howto_quota_heading", "howto_quota_body",
                    "howto_status_heading", "howto_status_body",
                    # v1.6.0: the two tab captions and the tuning intro. The tuning
                    # tab deliberately reuses section_advanced, so that key is
                    # asserted here too — it is now load-bearing in two places.
                    # v1.7.2: the sentence that replaced the scope switch.
                    "settings_tab_map", "settings_signal_desc", "section_advanced",
                    "targets_fixed_title", "targets_fixed_desc")
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
                    "steps_amap_title", "steps_amap_body",
                    # v1.7.2: the target picker's copy went with the picker. A
                    # leftover "Choose apps" button would still render — and lie.
                    "targets_scope_all", "targets_scope_all_desc",
                    "targets_selected_count", "targets_also_pick_in_lsposed",
                    "action_pick_targets", "targets_title",
                    "targets_search_hint", "targets_empty")
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
# which really do hook all three vendor SDKs inside the target app. v1.12.0's
# two consequence lines are exempt for the same reason — they name which SDKs
# stop being taken over, and that sentence is the point of the warning.
VENDOR_WORDING_EXEMPT = {"adv_map_sdk_desc", "adv_map_sdk",
                         "guard_off_map_sdk", "guard_confirm_map_sdk"}
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
    + len(secret_hits)
)
print("GATE: %s (%d failing checks)" % ("PASS" if failures == 0 else "FAIL", failures))
sys.exit(0 if failures == 0 else 1)
