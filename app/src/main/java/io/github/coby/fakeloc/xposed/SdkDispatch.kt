package io.github.coby.fakeloc.xposed

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Registry of listeners captured from a location SDK, fed on a timer.
 *
 * ## Why a push is needed at all
 *
 * Every other layer of this module is *pull*-shaped: it sits on a getter or a
 * callback and rewrites what the app was about to receive anyway. That is enough
 * only while the app keeps asking. Several classes of app do not:
 *
 *  * one that reads a fix once, during startup or when a screen opens, and
 *    caches it;
 *  * one whose SDK subscription is opened long before the user starts spoofing,
 *    so the registration hook never sees it;
 *  * one whose fix arrives on a cadence slower than the screen refresh
 *    (`setInterval` of 30 s is common), so a map looks frozen rather than wrong.
 *
 * The map SDKs solve this for their own callers by pushing: `AmapLocationClient`
 * hands `AMapLocationListener.onLocationChanged` a fresh fix every second or so.
 * Driving the same callback ourselves reproduces exactly that, which is why this
 * is the one interception that works when the app never asks the platform.
 *
 * ## Lifetime
 *
 * Both the key *and* the value hold the listener weakly on purpose. A
 * `WeakHashMap` whose value strongly references its key never releases its
 * entry — the classic way a per-listener registry turns into a process-wide
 * leak. Reaching through the `WeakReference` in [SdkPusher.push] and doing
 * nothing when it has been collected is what lets the app's own GC decide when a
 * listener is gone.
 *
 * The tick is only scheduled while at least one listener is registered, so an
 * app whose SDK has not been subscribed yet never pays for this.
 */
internal object SdkDispatch {

    private const val TAG = "SdkPush"

    /**
     * Cadence of the push.
     *
     * One second matches the map SDKs' own default for a continuous subscription.
     * Faster would burn battery for no visible gain (a real GPS chipset does not
     * resolve better than 1 Hz either, and 1 Hz is the value every app is already
     * tuned against); slower would make a followed position look laggy.
     */
    private const val INTERVAL_MS = 1_000L

    private val entries: MutableMap<Any, SdkPusher> =
        Collections.synchronizedMap(WeakHashMap<Any, SdkPusher>())

    /** The SDKs deliver on the main looper; ours lands on the same thread. */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var scheduled = false

    private val tick = object : Runnable {
        override fun run() {
            scheduled = false
            val live = snapshot()
            if (live.isEmpty()) return

            if (HookState.active()) {
                for (pusher in live) {
                    runCatching { pusher.push() }.onFailure {
                        HookKit.warn(TAG, "push failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
            }
            schedule()
        }
    }

    /** Purges stale entries as a side effect — iterating a `WeakHashMap` expunges. */
    private fun snapshot(): List<SdkPusher> = synchronized(entries) { entries.values.toList() }

    fun register(listener: Any, pusher: SdkPusher) {
        entries[listener] = pusher
        schedule()
    }

    fun unregister(listener: Any) {
        entries.remove(listener)
    }

    @Synchronized
    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(tick, INTERVAL_MS)
    }
}

/** One captured listener, able to produce and deliver a fix on demand. */
internal fun interface SdkPusher {
    fun push()
}

/**
 * Shared plumbing for a captured listener: holds it weakly, resolves its
 * callback once, and swallows anything thrown at delivery time.
 *
 * A throwing listener is the app's own business — but it must not be allowed to
 * propagate out of a `Handler` callback, where it would take down the process we
 * are supposed to be helping.
 */
internal abstract class CapturedListener(
    listener: Any,
) : SdkPusher {

    private val ref = WeakReference(listener)

    final override fun push() {
        val target = ref.get() ?: return
        runCatching { deliver(target) }
    }

    /** Builds a payload for [target] and hands it over. Only called when live. */
    protected abstract fun deliver(target: Any)
}
