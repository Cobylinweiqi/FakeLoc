package io.github.coby.fakeloc.data

import android.content.Context
import android.content.SharedPreferences
import io.github.coby.fakeloc.App
import io.github.coby.fakeloc.core.ConfigCodec
import io.github.coby.fakeloc.core.Keys
import io.github.coby.fakeloc.core.SpoofConfig

/**
 * Reads and writes the shared [SpoofConfig].
 *
 * Writes go to **two** places on purpose:
 *
 *  1. the LSPosed *remote* preferences — the channel the module actually reads;
 *  2. a local preferences file — a mirror that survives the LSPosed service
 *     being unavailable.
 *
 * The mirror matters because the bound service is not always up (LSPosed not yet
 * started, module just installed, manager being updated). Without it the user
 * would open the app to a reset UI and could not even prepare a configuration
 * before enabling the module. Reads prefer the remote copy and fall back to the
 * mirror, so whichever is fresher wins in practice.
 *
 * On a fresh install the mirror is empty and the remote copy does not exist yet,
 * so the codec's defaults apply.
 */
class RemoteStore(context: Context) {

    private val local: SharedPreferences =
        context.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)

    /** `true` while the LSPosed service is bound and the remote copy is writable. */
    val serviceAvailable: Boolean
        get() = App.service != null

    private fun remote(): SharedPreferences? =
        runCatching { App.service?.getRemotePreferences(Keys.REMOTE_GROUP) }.getOrNull()

    /** Current configuration; never throws, never returns `null`. */
    fun load(): SpoofConfig {
        val remote = remote()?.getString(Keys.CONFIG, null)
        val mirror = local.getString(Keys.CONFIG, null)
        return ConfigCodec.decode(remote ?: mirror)
    }

    /**
     * Persists [config] locally and, when possible, remotely.
     *
     * @return `true` when the module will actually see the change. `false` means
     *   the update was only mirrored and the UI should tell the user to fix the
     *   LSPosed connection.
     */
    fun save(config: SpoofConfig): Boolean {
        val json = ConfigCodec.encode(config)
        local.edit().putString(Keys.CONFIG, json).apply()

        val prefs = remote() ?: return false
        // `commit()` rather than `apply()`: the module may read the value the
        // instant the user toggles the switch, and a background flush would race
        // it. Callers run this off the main thread.
        return runCatching { prefs.edit().putString(Keys.CONFIG, json).commit() }.getOrDefault(false)
    }

    /** Drops the mirror. Used by "reset" so the next load shows defaults. */
    fun clearMirror() {
        local.edit().clear().apply()
    }

    private companion object {
        const val LOCAL_FILE = "fakeloc_local"
    }
}
