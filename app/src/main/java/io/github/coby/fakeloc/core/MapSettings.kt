package io.github.coby.fakeloc.core

/**
 * Which base map the picker draws with.
 *
 * **There is exactly one entry, and that is the point.** Earlier revisions shipped
 * all three renderers — Baidu, Amap and Tencent — so that a user whose key had
 * been revoked on one platform could fall back to another. That is a real
 * argument, and it lost to a much larger one: the three SDKs cost ~24 MB of
 * native libraries and map assets *on top of* anything the app itself needs, and
 * they were paying for it on every install to render one convenience screen.
 *
 * What made dropping them cheap is that they were never load-bearing. The module
 * half of this app — including the hooks that push fixes into a target app's own
 * Baidu/Amap/Tencent location SDK — reaches every one of those classes by
 * reflection through the *target's* ClassLoader. It links against none of them.
 * Removing a dependency here therefore costs a map renderer and nothing else.
 * Tencent is the one that stayed because it is by far the smallest of the three:
 * ~2 MB of native code against Baidu's 7.4 MB and Amap's 7.7 MB.
 *
 * The enum survives with one entry rather than collapsing into a constant
 * because its `id` is a field in the persisted config. Keeping the shape means
 * re-adding a provider later is a data-compatible change: an unknown or removed
 * id falls back to [DEFAULT] instead of throwing, so a config written by an
 * older build still loads.
 *
 * **The author registers no key.** Tencent's is entered on the settings page and
 * handed to the SDK at runtime. A key baked into a distributed APK only ever
 * works for the exact package and certificate it was registered against, and
 * once it stops working there is nothing in the code that can restore it.
 *
 * Tencent is pinned to the 5.9.0 line in the version catalog, and that pin is
 * load-bearing: the 6.x artifacts on Maven Central omit a class their own public
 * API is typed against. See the note in `gradle/libs.versions.toml`.
 */
enum class MapProvider(val id: String) {
    TENCENT("tencent"),
    ;

    companion object {
        val DEFAULT = TENCENT

        /** Unknown or missing ids fall back to [DEFAULT] rather than throwing. */
        fun from(id: String?): MapProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
