package io.github.cobylinweiqi.fakeloc.xposed

import android.location.Location
import android.os.Build
import android.os.Bundle
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Removes every trace of "this fix came from a mock provider" from a [Location].
 *
 * There are four independent carriers of that bit on modern Android, and
 * detectors check all of them, so all four have to go:
 *
 *  1. `Location.mFieldsMask` — the internal bitfield whose `HAS_MOCK_PROVIDER_MASK`
 *     bit backs `isMock()` (API 31+) and `isFromMockProvider()` (deprecated).
 *     This is the one that actually matters, and it is reachable by writing the
 *     field: the hidden setters are not always callable.
 *  2. `Location.mIsFromMockProvider` — the pre-31 separate boolean, kept for
 *     older ROMs.
 *  3. The hidden setters `setMock(false)` / `setIsFromMockProvider(false)`, in
 *     case a caller reads them back later.
 *  4. The `mockLocation` extras entry that `LocationManagerService` stamps onto
 *     a fix it knows came from a mock provider.
 *
 * Every step is best-effort. OEM ROMs rename these members; failing to scrub one
 * carrier degrades the result, and must not break `getLatitude()`.
 */
internal object MockTrace {

    private const val TAG = "MockTrace"

    /** Extras keys seen in the wild for "this is a mock fix". */
    private val MOCK_EXTRA_KEYS = arrayOf(
        "mockLocation",
        "mock_location",
        "android.location.extra.MOCK_LOCATION",
    )

    private const val FIELD_FIELDS_MASK = "mFieldsMask"
    private const val FIELD_HAS_MOCK_MASK = "HAS_MOCK_PROVIDER_MASK"
    /**
     * The pre-31 boolean. `mIsFromMockProvider` is the AOSP spelling; a few
     * vendor trees shipped an older `mMock` name alongside it, so both are tried.
     */
    private val FIELD_IS_FROM_MOCK = arrayOf("mIsFromMockProvider", "mMock")
    private const val FIELD_EXTRAS = "mExtras"
    private const val METHOD_SET_MOCK = "setMock"
    private const val METHOD_SET_IS_FROM_MOCK = "setIsFromMockProvider"

    @Volatile
    private var resolved = false

    private var fieldsMask: Field? = null
    private var hasMockMask: Field? = null
    private var isFromMockField: Field? = null
    private var extrasField: Field? = null
    private var setMockMethod: Method? = null
    private var setIsFromMockMethod: Method? = null

    @Synchronized
    private fun resolve() {
        if (resolved) return
        resolved = true
        val locationClass = Location::class.java

        fieldsMask = HookKit.findField(locationClass, FIELD_FIELDS_MASK)
        hasMockMask = HookKit.findField(locationClass, FIELD_HAS_MOCK_MASK)
        isFromMockField = HookKit.findField(locationClass, *FIELD_IS_FROM_MOCK)
        extrasField = HookKit.findField(locationClass, FIELD_EXTRAS)
        setMockMethod = HookKit.findMethod(locationClass, METHOD_SET_MOCK, java.lang.Boolean.TYPE)
        setIsFromMockMethod =
            HookKit.findMethod(locationClass, METHOD_SET_IS_FROM_MOCK, java.lang.Boolean.TYPE)

        if (fieldsMask == null || hasMockMask == null) {
            // Not fatal — the flag may live in `mIsFromMockProvider` on this ROM.
            HookKit.warn(
                TAG,
                "bitfield not found (sdk=${Build.VERSION.SDK_INT}); " +
                    "falling back to setters/extras only"
            )
        }
    }

    /** Strips every mock marker from [location] in place. */
    fun scrub(location: Location) {
        resolve()

        clearBitfield(location)
        clearLegacyField(location)
        invokeSetters(location)

        val extras = readExtras(location)
        if (extras != null && hasMockExtra(extras)) {
            writeExtras(location, scrubBundle(extras))
        }
    }

    private fun clearBitfield(location: Location) {
        val maskField = fieldsMask ?: return
        val maskConstant = hasMockMask ?: return
        runCatching {
            val mockBit = maskConstant.getInt(null)
            val current = maskField.getInt(location)
            if (current and mockBit != 0) {
                maskField.setInt(location, current and mockBit.inv())
            }
        }.onFailure { HookKit.error(TAG, "clearing $FIELD_FIELDS_MASK failed", it) }
    }

    private fun clearLegacyField(location: Location) {
        val field = isFromMockField ?: return
        // A vendor ROM reusing one of those names for something else is not worth
        // throwing over: `setMock(false)` below already covers API 31+, and this
        // path exists only to clean up leftovers on older frameworks.
        if (field.type != java.lang.Boolean.TYPE) return
        runCatching {
            if (field.getBoolean(location)) {
                field.setBoolean(location, false)
            }
        }.onFailure { HookKit.error(TAG, "clearing the legacy mock field failed", it) }
    }

    private fun invokeSetters(location: Location) {
        setMockMethod?.let { method ->
            runCatching { method.invoke(location, false) }
                .onFailure { HookKit.error(TAG, "invoking $METHOD_SET_MOCK failed", it) }
        }
        setIsFromMockMethod?.let { method ->
            runCatching { method.invoke(location, false) }
                .onFailure { HookKit.error(TAG, "invoking $METHOD_SET_IS_FROM_MOCK failed", it) }
        }
    }

    // -------------------------------------------------------------- extras

    /** Reads the private `mExtras` bundle, or `null`. */
    fun readExtras(location: Location): Bundle? {
        resolve()
        val field = extrasField ?: return null
        return runCatching { field.get(location) as? Bundle }.getOrNull()
    }

    /**
     * Writes a scrubbed bundle back into `mExtras`.
     *
     * Deliberately bypasses `Location.setExtras()`, which is a public method we
     * also hook — going through it from our own code would recurse.
     */
    fun writeExtras(location: Location, bundle: Bundle?) {
        val field = extrasField ?: return
        runCatching { field.set(location, bundle) }
            .onFailure { HookKit.error(TAG, "writing $FIELD_EXTRAS failed", it) }
    }

    /** `true` when [bundle] carries any known mock marker. */
    fun hasMockExtra(bundle: Bundle?): Boolean {
        if (bundle == null) return false
        for (key in MOCK_EXTRA_KEYS) {
            if (bundle.containsKey(key)) return true
        }
        return false
    }

    /**
     * Returns a copy of [bundle] without the mock markers, or [bundle] itself
     * when there is nothing to strip.
     *
     * Copying only on a hit keeps the common path allocation-free: this runs on
     * every hooked `Location.getExtras()`.
     */
    fun scrubBundle(bundle: Bundle?): Bundle? {
        if (bundle == null) return null
        if (!hasMockExtra(bundle)) return bundle
        return Bundle(bundle).apply {
            for (key in MOCK_EXTRA_KEYS) {
                remove(key)
            }
        }
    }
}
