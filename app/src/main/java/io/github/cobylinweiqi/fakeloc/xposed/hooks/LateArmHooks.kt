package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.app.Application
import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import java.util.Collections
import java.util.WeakHashMap

/**
 * Arms the map-SDK channels *late* — after the app's own classes are actually
 * loadable, and again on a few timers after that.
 *
 * ## The problem it solves
 *
 * `onPackageReady` is the earliest point a module can resolve the target's
 * classes, and for a hardened APK it is too early. 建行员工 (`com.ccb.ecpmobile.ecp`,
 * measured 2026-11-12) ships 梆梆/SecNeo: `AndroidManifest` names
 * `com.secneo.apkwrapper.AW`, the APK carries `libDexHelper.so`, and the real dex
 * is decrypted by that stub during `attachBaseContext`. At `onPackageReady` the
 * loader therefore contains the wrapper and nothing else:
 *
 * ```
 * Class.forName("com.baidu.location.BDLocation", false, loader) -> ClassNotFoundException
 * ```
 *
 * [MapSdkHooks] answered every lookup with a bare `return`, logged nothing, and
 * the app ended up with **no channel at all** while the log looked exactly like
 * an app that simply does not use a map SDK. What the user sees is worse than
 * "no spoofing": with `strictSources` on, the module also withholds cell and
 * WiFi scans, which are the inputs the SDK needs to compute its first fix — so
 * the SDK reports failure and the app prints 地理位置获取失败.
 *
 * ## Why a second attempt is enough
 *
 * The stub decrypts on the way into `Application.onCreate`, so by the time the
 * framework calls `Instrumentation.callApplicationOnCreate` the classes are
 * there and a lookup succeeds — including through the *same* loader object,
 * because the decrypted classes are defined into it rather than into a new one.
 * Hooking that one framework method is enough and needs no reflection into
 * hidden API, no knowledge of which shell this is, and no assumption about the
 * loader's identity.
 *
 * ## Why also timers
 *
 * Some shells decrypt on first *use* instead of at startup, and some apps load
 * the SDK from a downloaded module long after `onCreate`. The loaders are
 * therefore also retried on a short schedule, and [DynamicLoaderHooks] still
 * catches the case where the SDK arrives in a loader of its own. Every retry is
 * cheap: [MapSdkHooks] remembers per-channel success, so a channel already armed
 * costs one set lookup, and a channel that is genuinely absent costs three
 * failed `Class.forName` calls and one log line per loader.
 *
 * ## What it does not fix
 *
 * A registration that happened *before* the channel was armed is not
 * recoverable — the listener object was never seen, so there is nothing to push
 * a fix into. In practice the arm at `callApplicationOnCreate` runs before any
 * screen exists, which is what keeps this theoretical; if a report ever shows
 * `channel armed` arriving *after* the app's own `registerLocationListener`,
 * re-entering the map screen is the workaround.
 */
internal object LateArmHooks {

    private const val TAG = "LateArm"

    /**
     * Delays after which the channels are attempted again, in milliseconds.
     *
     * Front-loaded: the shell case is already covered by the `onCreate` arm, so
     * these exist for the "SDK appears from a downloaded module" case, where the
     * useful window is the first minute. Four attempts is small enough to stay
     * invisible in the log and in the frame budget.
     */
    private val RETRY_DELAYS_MS = longArrayOf(1_500L, 5_000L, 15_000L, 45_000L)

    /**
     * Loaders the framework named for this process.
     *
     * Kept because the timer retries run without an `Application` on some paths
     * (the hook into `Instrumentation` may not land on every ROM), and the loader
     * from `onPackageReady` is the only one known for certain at that point.
     */
    private val announced: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    fun install(loader: ClassLoader) {
        announced.add(loader)
        watchApplicationCreation()
        schedule(null)
    }

    /**
     * `callApplicationOnCreate` rather than `Application.onCreate` on purpose:
     * the latter is an override point, and an app (or a shell) that overrides it
     * without calling `super` would never reach the hook. The framework calls
     * this method exactly once per process, on the main thread, with the
     * application instance in hand — which also hands us the loaders to retry.
     */
    private fun watchApplicationCreation() {
        if (!HookKit.firstTime("late-arm-instrumentation")) return
        val hooked = HookKit.hookAll(Instrumentation::class.java, "callApplicationOnCreate", TAG) { chain ->
            val forwarded = chain.proceed()
            val application = chain.args.getOrNull(0) as? Application
            if (application != null) {
                arm(application, "the application finished creating")
                schedule(application)
            }
            forwarded
        }
        if (hooked > 0) {
            HookKit.info(TAG, "watching for the application to finish creating ($hooked entry point(s))")
        } else {
            HookKit.warn(TAG, "Instrumentation#callApplicationOnCreate not hookable — timers only")
        }
    }

    private fun schedule(application: Application?) {
        val looper = Looper.getMainLooper() ?: return
        val handler = Handler(looper)
        for (delay in RETRY_DELAYS_MS) {
            handler.postDelayed({ arm(application, "t+${delay}ms") }, delay)
        }
    }

    /**
     * Tries every loader this process might have hidden the SDK in.
     *
     * [MapSdkHooks.install] is the guard against doing the work twice; this only
     * decides *where* to look. Parents are included because a shell that hands
     * the SDK to a child loader still leaves the parent visible from it.
     */
    private fun arm(application: Application?, why: String) {
        var armed = 0
        for (loader in loaders(application)) {
            armed += runCatching { MapSdkHooks.install(loader) }
                .onFailure { HookKit.error(TAG, "arming after $why failed", it) }
                .getOrDefault(0)
        }
        if (armed > 0) {
            HookKit.info(TAG, "$armed channel(s) armed after $why")
        }
    }

    private fun loaders(application: Application?): List<ClassLoader> {
        val out = ArrayList<ClassLoader>(8)
        fun include(loader: ClassLoader?) {
            var current = loader
            var depth = 0
            while (current != null && depth < 8) {
                if (out.none { it === current }) out.add(current)
                current = current.parent
                depth++
            }
        }
        if (application != null) {
            // The loader that defined the Application is the one the shell
            // decrypted into; `baseContext` covers contexts created with their
            // own loader, which is how some shells swap it in.
            runCatching { include(application.classLoader) }
            runCatching { include(application.baseContext?.classLoader) }
        }
        runCatching { include(Thread.currentThread().contextClassLoader) }
        synchronized(announced) {
            for (loader in announced) {
                if (out.none { it === loader }) out.add(loader)
            }
        }
        return out
    }
}
