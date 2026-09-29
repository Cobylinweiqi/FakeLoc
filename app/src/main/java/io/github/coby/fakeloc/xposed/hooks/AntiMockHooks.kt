package io.github.coby.fakeloc.xposed.hooks

import android.app.AppOpsManager
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import io.github.coby.fakeloc.xposed.HookKit
import io.github.coby.fakeloc.xposed.HookState

/**
 * Makes the whole "mock location" concept invisible, so a detector cannot tell
 * that anything unusual is going on even if it never asks for a coordinate.
 *
 * Borrowed from `auag0/HideMockLocation`, which is the reference implementation
 * of this idea, and then widened: every *read* path a detector can take to the
 * `mock_location` flag is closed, in the process that actually answers it.
 *
 * | Surface | Process | Why it is hooked |
 * |---|---|---|
 * | `Settings.Secure.getStringForUser` | every app **and `system_server`** | the chokepoint behind `getString`/`getInt`/`getLong` |
 * | `SettingsProvider.call("GET_secure")` | `com.android.providers.settings` | a caller reaching the provider by binder bypasses the settings API |
 * | `SettingsProvider.query` | `com.android.providers.settings` | the *other* provider entry point — a raw `ContentResolver.query` never goes through `call` |
 * | `AppOpsManager.checkOp*` / `unsafeCheckOp*` | every app **and `system_server`** | `OP_MOCK_LOCATION` names the selected mock app with no fix requested |
 * | `AppOpsService.checkOperation*` | `system_server` | the authoritative answer, for callers that skip the app-side manager |
 *
 * ## Two deliberate omissions
 *
 * **`AppOpsManager.noteOp` / `startOp`** — the write side. Faking a *check* is
 * invisible; faking a *note* records an operation that never happened and
 * corrupts the app-op counters that `dumpsys` and Settings read back. It also
 * keeps a genuinely configured mock provider app working normally, which matters
 * if the user also runs one.
 *
 * **`Settings.Global.development_settings_enabled` / `adb_enabled`** — these are
 * not mock markers. Hiding them is a separate, more invasive decision (it changes
 * what every hooked app believes about the device) and is intentionally left out
 * of `antiMock`.
 */
internal object AntiMockHooks {

    private const val TAG = "AntiMock"

    /** `AppOpsManager.OP_MOCK_LOCATION`, stable since Android 6. */
    private const val OP_MOCK_LOCATION_ID = 58

    /**
     * Every public `AppOpsManager` entry point that answers "may this app provide
     * mock locations?".
     *
     * The `*Raw*` family is listed separately because the raw query skips the
     * `MODE_DEFAULT` normalisation the plain one does — a detector that wants the
     * un-normalised mode would otherwise walk straight past a hook set built only
     * from `checkOp` / `unsafeCheckOp`.
     *
     * All of these are addressed by *op name* in the public API; the int-ordinal
     * overloads are `@SystemApi`, but they are reached by the same hook because
     * [HookKit.methodsNamed] matches on name and hooks every overload.
     */
    private val APP_OP_CHECKS = arrayOf(
        "checkOp",
        "checkOpNoThrow",
        "checkOpRawNoThrow",
        "unsafeCheckOp",
        "unsafeCheckOpNoThrow",
        "unsafeCheckOpRaw",
        "unsafeCheckOpRawNoThrow",
    )

    // ------------------------------------------------------- app process

    /** Installs the settings + app-op hooks into an ordinary app process. */
    fun installApp(classLoader: ClassLoader) {
        installSecureSettings(classLoader)
        installAppOpsManager(classLoader)
    }

    /**
     * `Settings.Secure.getStringForUser` is the single hidden chokepoint behind
     * `getString`, `getInt` and `getLong`, so blocking it here covers every way
     * an app can read `mock_location`.
     */
    fun installSecureSettings(classLoader: ClassLoader) {
        val secureClass = HookKit.findClass(
            classLoader,
            "android.provider.Settings\$Secure",
        ) ?: return

        HookKit.hookAllLogged(secureClass, "getStringForUser", TAG) { chain ->
            val name = chain.args.getOrNull(1) as? String
            if (name == MOCK_LOCATION_KEY && HookState.antiMockEngaged()) {
                // "0" — no mock location app is configured. Returning null would
                // be more honest-looking but several callers parse it straight
                // into an int and would throw.
                return@hookAllLogged "0"
            }
            chain.proceed()
        }
    }

    fun installAppOpsManager(classLoader: ClassLoader) {
        val appOpsClass = HookKit.findClass(classLoader, "android.app.AppOpsManager") ?: return

        for (methodName in APP_OP_CHECKS) {
            HookKit.hookAllLogged(appOpsClass, methodName, TAG) { chain ->
                if (!HookState.antiMockEngaged() || !isMockLocationOp(chain.args.firstOrNull())) {
                    return@hookAllLogged chain.proceed()
                }
                AppOpsManager.MODE_ERRORED
            }
        }
    }

    // --------------------------------------------- settings provider process

    /**
     * Patches both raw provider entry points, so a caller that talks to the
     * settings `ContentProvider` directly gets the same answer as one going
     * through `Settings.Secure`.
     *
     * `call` and `query` are genuinely different paths, not two spellings of the
     * same one: `Settings.Secure` prefers `call("GET_secure")` and only falls back
     * to `query`, while a detector that constructs the `ContentResolver` call
     * itself lands on `query` straight away.
     */
    fun installSettingsProvider(classLoader: ClassLoader) {
        val providerClass = HookKit.findClass(
            classLoader,
            "com.android.providers.settings.SettingsProvider",
        ) ?: return

        hookProviderCall(providerClass)
        hookProviderQuery(providerClass)
    }

    private fun hookProviderCall(providerClass: Class<*>) {
        HookKit.hookAllLogged(providerClass, "call", TAG) { chain ->
            val result = chain.proceed() as? Bundle
            if (result == null || !HookState.antiMockEngaged()) {
                return@hookAllLogged result
            }

            val method = chain.args.getOrNull(0) as? String
            val name = chain.args.getOrNull(1) as? String
            if (method == "GET_secure" && name == MOCK_LOCATION_KEY && result.containsKey("value")) {
                Bundle(result).apply { putString("value", "0") }
            } else {
                result
            }
        }
    }

    private fun hookProviderQuery(providerClass: Class<*>) {
        HookKit.hookAllLogged(providerClass, "query", TAG) { chain ->
            val cursor = chain.proceed() as? Cursor
            if (cursor == null || !HookState.antiMockEngaged()) {
                return@hookAllLogged cursor
            }
            withoutMockRow(cursor)
        }
    }

    // ------------------------------------------------- system_server process

    /**
     * Installs the authoritative app-op hook, the settings hook, and the
     * `Location` probes.
     *
     * `Settings.Secure` and `AppOpsManager` live on the boot classpath, so the
     * same interception works inside `system_server` — and it has to be installed
     * here too, because a component running in this process (a system service, an
     * OEM telemetry job) resolving `mock_location` would otherwise see the truth.
     */
    fun installSystemServer(classLoader: ClassLoader) {
        installSecureSettings(classLoader)
        installAppOpsManager(classLoader)

        val appOpsService = HookKit.findClass(
            classLoader,
            // Android 10+ split the service into its own package; Android 9 and
            // older kept it in com.android.server.
            "com.android.server.appop.AppOpsService",
            "com.android.server.AppOpsService",
        ) ?: run {
            HookKit.warn(TAG, "AppOpsService not found in system_server")
            return
        }

        for (methodName in APP_OP_CHECKS_SERVICE) {
            HookKit.hookAllLogged(appOpsService, methodName, TAG) { chain ->
                if (!HookState.antiMockEngaged() || !isMockLocationOp(chain.args.firstOrNull())) {
                    return@hookAllLogged chain.proceed()
                }
                AppOpsManager.MODE_ERRORED
            }
        }

        // `Location` lives in the boot classpath, shared with system_server, so
        // the same probes apply here.
        LocationHooks.installMockTraceProbes(classLoader)
    }

    /**
     * The service-side mirrors of [APP_OP_CHECKS].
     *
     * `checkOperation` is the internal entry point behind the public manager, and
     * `checkOperationUnchecked` is the variant that skips the caller-identity
     * check — a system component asking about a *third* package's op lands there.
     */
    private val APP_OP_CHECKS_SERVICE = arrayOf(
        "checkOperation",
        "checkOperationImpl",
        "checkOperationUnchecked",
    )

    // ------------------------------------------------------------- helpers

    private const val MOCK_LOCATION_KEY = "mock_location"

    /** The column name the settings provider uses for a key. */
    private const val NAME_COLUMN = "name"

    /**
     * The app-op is addressed either by name (`"android:mock_location"`) or by
     * ordinal ([OP_MOCK_LOCATION_ID]) depending on the overload, so both forms
     * are recognised.
     */
    private fun isMockLocationOp(op: Any?): Boolean = when (op) {
        is String -> op == AppOpsManager.OPSTR_MOCK_LOCATION
        is Int -> op == OP_MOCK_LOCATION_ID
        else -> false
    }

    /**
     * Returns [cursor] without its `mock_location` row, or [cursor] itself when
     * there is nothing to hide.
     *
     * Two details matter here:
     *
     *  * the *position* is restored before handing the original back — a
     *    `CursorAdapter` starts at `-1`, and a peek would silently make the first
     *    row look preconsumed;
     *  * the replacement is a [MatrixCursor] built from the same column names, so
     *    every caller keeps working unchanged; only the row disappears.
     */
    private fun withoutMockRow(cursor: Cursor): Cursor {
        val nameIndex = runCatching { cursor.getColumnIndex(NAME_COLUMN) }.getOrDefault(-1)
        if (nameIndex < 0) return cursor

        val startPosition = cursor.position
        val columnNames = cursor.columnNames ?: return cursor
        val survivors = ArrayList<Array<Any?>>(cursor.count)
        var dropped = false

        val walked = runCatching {
            cursor.moveToPosition(-1)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == MOCK_LOCATION_KEY) {
                    dropped = true
                    continue
                }
                survivors += readRow(cursor, columnNames.size)
            }
            true
        }.getOrDefault(false)

        if (!walked || !dropped) {
            runCatching { cursor.moveToPosition(startPosition) }
            return cursor
        }

        runCatching { cursor.close() }
        HookKit.info(TAG, "hid the $MOCK_LOCATION_KEY row from a raw settings query")
        return MatrixCursor(columnNames).apply { survivors.forEach(::addRow) }
    }

    /** Copies one row verbatim, preserving each column's storage type. */
    private fun readRow(cursor: Cursor, columnCount: Int): Array<Any?> =
        Array(columnCount) { index -> readCell(cursor, index) }

    private fun readCell(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        Cursor.FIELD_TYPE_NULL -> null
        else -> cursor.getString(index)
    }
}
