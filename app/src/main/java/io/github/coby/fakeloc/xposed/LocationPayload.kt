package io.github.coby.fakeloc.xposed

import android.location.Location
import java.util.Collections

/**
 * Overwrites every [Location] reachable from an intercepted callback argument,
 * *in place*.
 *
 * ## Why in place, and why at this layer
 *
 * The property that makes a spoofed fix survive is that it lands in the *real
 * field values* of the object the app is about to read. Hooking
 * `Location.getLatitude()` only covers callers that go through the Java getter,
 * and a native consumer reads the field directly through JNI — every map SDK has
 * a native renderer, and it never touches the getter. Mutating the object before
 * it is handed over covers both readers at once.
 *
 * ## Why the container shape matters
 *
 * Callbacks no longer carry a bare `Location`. Depending on the release and the
 * OEM tree the payload is one of:
 *
 * | Shape | Where it shows up |
 * |---|---|
 * | `android.location.Location` | `getLastKnownLocation`, legacy callbacks |
 * | `List<Location>` | `ILocationListener.onLocationChanged` (Android 14 AIDL) |
 * | an object exposing `getLocations()` / `asList()` | `LocationResult` containers |
 * | an object with a private `mLocations` field | hidden `android.location.LocationResult` |
 *
 * A rewriter that knows only the first two does *nothing* on the others, and the
 * result is indistinguishable from "the module is not installed" — the callback
 * fires, the app receives real coordinates, and nothing is logged anywhere. The
 * accessor for each class is resolved once and cached, negative results included,
 * so an argument that is not a container (an `IRemoteCallback`, a `String`) costs
 * one map lookup and never a repeated reflection scan.
 */
internal object LocationPayload {

    private const val TAG = "Payload"

    /** Public accessors a location container may expose, newest naming first. */
    private val CONTAINER_READERS = arrayOf("getLocations", "asList")

    /** Private backing field used when no public accessor exists. */
    private val CONTAINER_FIELDS = arrayOf("mLocations")

    /** Cached "this class carries no locations"; avoids a nullable map value. */
    private val NOT_A_CONTAINER: (Any) -> List<*>? = { null }

    /** `Class -> reader`, with [NOT_A_CONTAINER] standing in for a negative hit. */
    private val readers: MutableMap<Class<*>, (Any) -> List<*>?> =
        Collections.synchronizedMap(HashMap())

    /** Rewrites every [Location] found in [value]. */
    fun overwriteAll(value: Any?, path: String) {
        when (value) {
            null -> Unit
            is Location -> overwriteOne(value, path)
            is List<*> -> value.forEach { overwriteAll(it, path) }
            is Array<*> -> value.forEach { overwriteAll(it, path) }
            else -> readerFor(value.javaClass).invoke(value)?.forEach { overwriteAll(it, path) }
        }
    }

    private fun overwriteOne(location: Location, path: String) {
        // Report the source *before* rewriting it. This is the only evidence that
        // separates "a real fix arrived on this path and was rewritten" from
        // "this path never fires" — and the two need opposite fixes.
        Diag.observeFix(path, location)
        HookState.applyTo(location)
    }

    private fun readerFor(type: Class<*>): (Any) -> List<*>? {
        synchronized(readers) {
            readers[type]?.let { return it }
        }

        val reader = accessorReader(type) ?: fieldReader(type) ?: NOT_A_CONTAINER

        synchronized(readers) { readers[type] = reader }
        if (reader !== NOT_A_CONTAINER) {
            HookKit.info(TAG, "location container ${type.name} recognised")
        }
        return reader
    }

    private fun accessorReader(type: Class<*>): ((Any) -> List<*>?)? {
        for (name in CONTAINER_READERS) {
            val method = runCatching { type.getMethod(name) }.getOrNull() ?: continue
            if (!List::class.java.isAssignableFrom(method.returnType)) continue
            runCatching { method.isAccessible = true }
            return { container -> runCatching { method.invoke(container) as? List<*> }.getOrNull() }
        }
        return null
    }

    private fun fieldReader(type: Class<*>): ((Any) -> List<*>?)? {
        val field = HookKit.findField(type, *CONTAINER_FIELDS) ?: return null
        return { container -> runCatching { field.get(container) as? List<*> }.getOrNull() }
    }
}
