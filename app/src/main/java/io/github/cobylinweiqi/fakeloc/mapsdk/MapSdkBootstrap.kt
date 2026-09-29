package io.github.cobylinweiqi.fakeloc.mapsdk

import android.content.Context
import android.util.Log
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.SpoofConfig
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/**
 * Brings up the map SDK the picker is about to draw with.
 *
 * Deliberately *not* done in `Application.onCreate`. The SDK reads its key once,
 * at initialisation, and the key does not exist until the config has been loaded
 * from LSPosed's remote preferences — which needs a service bind that can land
 * after the first frame. Initialising on launch would therefore bake in the
 * wrong key on every run where the user supplied their own.
 *
 * Every failure is contained and reported rather than thrown: the module half of
 * this app forges positions and has nothing to do with maps, and it must keep
 * working whether or not a base map can be drawn.
 *
 * The Baidu and Amap branches that used to live here are gone along with their
 * dependencies — see `core/MapSettings.kt` for why. What is left is the one
 * provider whose setup is genuinely a no-op: Tencent has no global entry point
 * at all, so [ensure] does little more than record what it started with.
 */
object MapSdkBootstrap {

    private const val TAG = "FakeLoc/MapSdk"

    /** What the SDK in this process was brought up with, if anything. */
    @Volatile
    private var startedProvider: MapProvider? = null

    @Volatile
    private var startedKey: String? = null

    /**
     * Starts [config]'s provider if it is not already running, and reports what
     * the picker should do next.
     *
     * Idempotent, and cheap on the repeated calls: `MapPickerScreen` calls this
     * every time it opens, and the SDKs are only touched when something actually
     * changed.
     *
     * [context] is still accepted, and still normalised, even though Tencent's
     * 5.9.0 consent call takes no context. It is not dead weight: the map view
     * Tencent builds is constructed with `context`, and an Activity there is a
     * leak the SDK will outlive. Every canvas gets its context from Compose's
     * `LocalContext`, so normalising in one place is the only way to be sure
     * none of them passes something the SDK may hold on to.
     */
    fun ensure(context: Context, config: SpoofConfig): Status {
        // **Application context, not the caller's.** Baidu 8.x used to reject
        // anything else in `setAgreePrivacy` with "context must be an
        // ApplicationContext", and the failure was a trap: the throw landed
        // inside this method's `runCatching`, so the only visible trace was a
        // `Failed` status, while the real damage appeared later and elsewhere —
        // the first `MapView` then died with "you have not supplyed the global
        // app context" *inside Compose's change application*, where an escaping
        // exception is not a grey map but a dead process. Baidu is gone from this
        // build (2026-09-29) but the normalisation stays: it costs nothing and no
        // caller can get it wrong again, whatever `LocalContext` happens to be.
        val appContext = context.applicationContext ?: context

        val provider = config.mapProvider
        val key = config.mapKeyFor(provider)

        if (startedProvider == provider && startedKey == key) return Status.Ready

        // A restart is the only thing that helps here: initialisation is a
        // one-shot per process, so a second call with a different key is accepted
        // and ignored by the vendor. Saying so beats a map that silently keeps
        // failing authorisation after the user has just pasted a fresh key.
        val restartNeeded = startedProvider != null &&
            (startedProvider != provider || startedKey != key)

        val failure = runCatching {
            when (provider) {
                MapProvider.TENCENT -> startTencent()
            }
        }.exceptionOrNull()

        if (failure == null) {
            startedProvider = provider
            startedKey = key
            // No "built-in" branch: this build ships no key for any provider, so
            // `key` is the user's own or nothing was started.
            Log.i(TAG, "${provider.id} map SDK ready (key=user)")
            return if (restartNeeded) Status.RestartNeeded else Status.Ready
        }

        Log.w(TAG, "${provider.id} map SDK failed to start", failure)
        return Status.Failed(failure.message ?: failure.javaClass.simpleName)
    }

    /**
     * Tencent is the one of the three that had no global entry point at all.
     *
     * Its consent flag is the whole of the per-process setup: 5.9.0 exposes
     * `setAgreePrivacy(boolean)` and nothing else — no `Context` overload, and no
     * `start`/`initialize` to call (6.x renamed the pair; the version catalog
     * explains why 6.x is not usable here). Everything else, the engine and its
     * native libraries included, comes up lazily when the first `MapView` is
     * constructed, which is why the key is passed there rather than here.
     *
     * Not calling this does not fail loudly: the SDK simply refuses to serve
     * tiles until consent is recorded, and the map stays blank.
     */
    private fun startTencent() {
        TencentMapInitializer.setAgreePrivacy(true)
    }

    /** What the picker should tell the user, if anything. */
    sealed interface Status {
        /** The selected provider is up. */
        data object Ready : Status

        /**
         * Up, but with a different key than the config now holds. The map will
         * keep using the old one until the process restarts.
         */
        data object RestartNeeded : Status

        /** Could not be started; [detail] is the SDK's own complaint. */
        data class Failed(val detail: String) : Status
    }
}
