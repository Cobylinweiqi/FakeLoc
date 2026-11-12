package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import io.github.cobylinweiqi.fakeloc.core.Datum
import io.github.cobylinweiqi.fakeloc.xposed.Diag
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState

/**
 * Makes an app's own H5 bridge legible, because "位置获取失败" is not always
 * a location problem.
 *
 * ## Why this exists
 *
 * A bank app's check-in page is a WebView, and the page gets its position
 * through one of three doors that look identical on screen:
 *
 *  1. the app's own JSBridge command (`CMDgetLocation` and friends), which calls
 *     a map SDK inside the app process — interceptable, and already covered by
 *     [MapSdkHooks];
 *  2. `navigator.geolocation`, which Chromium answers itself through the
 *     platform `LocationManager` — covered only if the provider chain is clean;
 *  3. nothing at all: the page decides it failed before asking anyone, because
 *     the bridge call was rejected or the command does not exist on this build.
 *
 * Measured on CCB's employee app (2026-11-12): entering its check-in page and
 * getting "地理位置获取失败" produced **zero** Baidu callbacks and **zero**
 * `fused` registrations in `dumpsys location`. That rules out 1 and 2 and
 * leaves 3 — but nothing in the module could say so, and a whole afternoon went
 * into rewriting coordinates that were never read.
 *
 * So this records the three facts that separate the cases, once each:
 *
 * ```
 * FakeLoc/Bridge  JSBridge command class CMDgetLocation methods=[...]
 * FakeLoc/Bridge  WebView asked for geolocation permission
 * FakeLoc/Bridge  evalJS[1/20] ... {"locType":161,"latitude":29.6...}
 * ```
 *
 * Read it as: which commands the page actually loads (1), whether Chromium was
 * asked for a position (2), and what the app finally handed back to the page —
 * including the error payload that produced the message on screen (3).
 *
 * ## Cost
 *
 * `ClassLoader.loadClass` is the hottest hook in the process, so the body does
 * one `indexOfAny` over a fixed marker list and returns. Nothing is allocated on
 * the rejecting path. The `evaluateJavascript` side stops logging after
 * [JS_BUDGET] lines: an H5 page evaluates script continuously, and the point is
 * a sample of the payload, not a transcript.
 */
internal object WebBridgeHooks {

    private const val TAG = "Bridge"

    /**
     * Shapes that identify a bridge command class rather than business code.
     *
     * `ECPWebExeJSThread`'s dispatcher lives under `.ecpweb.cmd.` in this app;
     * the marker is matched as a substring of the fully qualified name so the
     * same rule holds for an app whose bridge package is nested one level
     * deeper. A class is only *reported*, never hooked — the command set changes
     * between app releases, and a hook on a name that no longer exists is dead
     * weight.
     */
    private val CMD_MARKERS = arrayOf(".ecpweb.cmd.")

    /** How many `evaluateJavascript` payloads are kept before this goes quiet. */
    private const val JS_BUDGET = 20

    /**
     * Substrings that make a script worth keeping.
     *
     * A bridge reply carries the position as a JSON object, and the field names
     * are the SDK's own (`locType`, `latitude`, `adCode`). Matching those finds
     * the reply without having to know the app's callback naming.
     */
    private val JS_KEYS = arrayOf("locType", "latitude", "longitude", "adCode", "cityCode", "geolocation")

    /**
     * Command methods worth tracing once the class is known.
     *
     * `doWork` is the synchronous entry the page calls: its *return value* goes
     * straight back to JavaScript, so a page that renders "位置获取失败" while
     * the asynchronous reply on `PJF.shellNotify` looks perfect is almost
     * certainly reading this instead. `startLocation` is the other half — it
     * kicks off the SDK lookup whose callback produces that reply.
     */
    private val CMD_TRACE = arrayOf("doWork", "startLocation")

    /** How many traced command calls are reported; the rest stay silent. */
    private const val CALL_BUDGET = 12

    /**
     * Simple-name marker of the command that answers "where am I?".
     *
     * Matched case-insensitively so `CMDgetLocation`, `GetLocationCommand` and
     * `cmdGetLocation` all count: the name is the one stable thing about this
     * command across app releases, and getting it wrong only means the repair
     * does not install.
     */
    private const val LOCATION_COMMAND = "getlocation"

    /** How many repaired replies are reported; a page polls, one line is enough. */
    private const val REPAIR_REPORTS = 3

    /** Event id inside a `PJF.shellNotify('<id>', …)` script. */
    private val NOTIFY_EVENT = Regex("shellNotify\\s*\\(\\s*['\"]([^'\"]+)['\"]")

    /** Fallback id when the script's quoting is unusual; any id reaches the page. */
    private const val DEFAULT_EVENT = "_evt_id_0"

    /** The app's wording for a fix that never arrived, as pushed to the page. */
    private const val LOCATION_FAILURE = "定位失败"

    /** Repaired replies already reported. */
    @Volatile
    private var repairs = 0

    /** Delay before `WebView` may be touched: its initialiser needs the app. */
    private const val DEVTOOLS_DELAY_MS = 400L

    /** Traced calls already reported. */
    @Volatile
    private var callsLogged = 0

    @Volatile
    private var jsLogged = 0

    fun install() {
        exposeDevtools()
        watchCommandClasses()
        watchJavascript()
        watchGeolocationPrompt()
    }

    /**
     * Lets `chrome://inspect` — or plain CDP over adb — attach to this app's
     * WebViews.
     *
     * Needed because the half that decides is JavaScript: the bridge glue that
     * turns a native reply into the object a page's `success` handler receives
     * is injected at runtime, so neither the APK nor the cache holds it. With a
     * debugger attached the page's own state can be read directly — which
     * beats inferring it from log lines, as the check-in page proved: its
     * "位置获取失败" text turned out to be a `||` fallback for a missing
     * *place name*, not a failed fix.
     *
     * Delayed rather than called inline: `WebView`'s static initialiser reaches
     * for the app's WebView package, which is not resolvable at process fork.
     * The flag only has to be set before the first `WebView` is constructed,
     * and that happens long after this fires.
     */
    private fun exposeDevtools() {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    WebView.setWebContentsDebuggingEnabled(true)
                    HookKit.info(TAG, "WebView debugging on — forward a port to webview_devtools_remote_<pid>")
                }
            }, DEVTOOLS_DELAY_MS)
        }
    }

    /**
     * Reports every command class as it is loaded.
     *
     * `loadClass` is used rather than `findClass` because the bridge resolves its
     * commands by name through `Class.forName`, which lands here even when the
     * class was already cached — a page that is opened a second time still
     * announces the commands it uses.
     */
    private fun watchCommandClasses() {
        val hooked = HookKit.hookAll(ClassLoader::class.java, "loadClass", TAG) { chain ->
            val loaded = chain.proceed()

            val name = chain.args.getOrNull(0) as? String
            if (name != null && name.indexOfAny(CMD_MARKERS) >= 0) {
                report(name, loaded)
            }
            loaded
        }
        if (hooked > 0) {
            HookKit.info(TAG, "watching JSBridge command classes ($hooked loadClass overload(s))")
        }
    }

    private fun report(name: String, loaded: Any?) {
        if (!HookKit.firstTime("cmd:$name")) return
        val clazz = loaded as? Class<*> ?: return
        val methods = runCatching {
            clazz.declaredMethods
                .filter { !it.isSynthetic }
                .map { it.name }
                .distinct()
        }.getOrDefault(emptyList())
        HookKit.info(TAG, "JSBridge command class ${clazz.simpleName} methods=$methods")
        if (isLocationCommand(clazz)) {
            repairLocationReply(clazz)
        }
        traceCalls(clazz)
    }

    /**
     * Reports what each traced command call received and returned.
     *
     * Installed the moment its class loads, i.e. before the first call: the
     * page instantiates a command and invokes it in the same breath, so hooking
     * lazily on a later visit would miss the very call that failed.
     */
    private fun traceCalls(clazz: Class<*>) {
        for (name in CMD_TRACE) {
            HookKit.hookAll(clazz, name, TAG) { chain ->
                val result = chain.proceed()
                if (callsLogged < CALL_BUDGET) {
                    callsLogged++
                    val args = chain.args.joinToString(",") { it?.toString()?.take(60) ?: "null" }
                    HookKit.info(
                        TAG,
                        "${clazz.simpleName}#$name($args) -> ${result?.toString()?.take(500) ?: "null"}",
                    )
                }
                result
            }
        }
    }

    /**
     * Answers a location command that gave up before a fix arrived.
     *
     * ## What the check-in page actually reads
     *
     * Measured on CCB's app (2026-11-12) with the page attached over CDP. The
     * page's bridge glue registers its callback like this:
     *
     * ```js
     * var o = this._callShell(cmd, params);
     * if (o.success) this.once(o._eventID, cb);   // only on a good sync reply
     * return o;
     * ```
     *
     * and the command's `doWork` answers synchronously with
     * `{"result":"定位失败","success":false}` whenever the map SDK has not
     * produced a fix yet. The asynchronous `PJF.shellNotify` that follows a
     * second later *does* carry the spoofed position with `success:true` — but
     * by then the callback was never registered, so the page never sees it and
     * renders its fallback text.
     *
     * That is why every field could be perfect and the page still failed: the
     * good payload was addressed to nobody.
     *
     * ## The repair
     *
     * On a failed reply, and only while spoofing is on, the map returned by
     * `doWork` is rewritten into the success shape the native side produces for
     * a real fix (`result` = the position object, `success` = true).
     * The payload is built through [SpoofConfig.addressFor], the same mapping
     * the SDK getters use, so both doors tell the same story.
     *
     * A reply that already succeeded is left untouched: the native side built
     * it from the `BDLocation` this module had already rewritten.
     */
    private fun repairLocationReply(clazz: Class<*>) {
        val hooked = HookKit.hookAll(clazz, "doWork", TAG) { chain ->
            val result = chain.proceed()
            repairReply(result) ?: result
        }
        if (hooked > 0) {
            Diag.event(
                "repair",
                "${clazz.simpleName}#doWork answers location requests from the configuration",
            )
        }
    }

    /**
     * `true` for the app's own "where am I" command class.
     *
     * Exposed so [MapSdkHooks] can recognise the same class through the route
     * it *already* has a hard reference to: that command doubles as the Baidu
     * `BDLocationListener`, and a listener is handed over as an object rather
     * than by name. `loadClass` is not a dependable door for it — the second
     * launch of the page resolved the class without ever passing through the
     * Java method, so the repair never installed and the page kept failing
     * while every field was correct (measured 2026-11-12).
     */
    internal fun isLocationCommand(clazz: Class<*>): Boolean =
        clazz.simpleName.lowercase().contains(LOCATION_COMMAND)

    /**
     * Rewrites a failed bridge reply into a success carrying the configured
     * address, or returns `null` to leave the reply alone.
     *
     * `internal` rather than private because [MapSdkHooks] applies it on the
     * listener route: every method of the command class is already hooked for
     * diagnostics there, and the one that builds the reply is indistinguishable
     * from the outside, so the rewrite is attempted on each of them and the
     * guard decides.
     */
    internal fun repairReply(result: Any?): Any? {
        if (!HookState.active()) return null
        @Suppress("UNCHECKED_CAST")
        val reply = result as? MutableMap<Any?, Any?> ?: return null
        if (reply["success"] == true) return null
        val payload = locationPayload() ?: return null
        val written = runCatching {
            reply["result"] = payload
            reply["success"] = true
        }.isSuccess
        if (!written) return null
        if (repairs < REPAIR_REPORTS) {
            repairs++
            HookKit.info(TAG, "location reply repaired[$repairs/$REPAIR_REPORTS] -> $payload")
        }
        return reply
    }

    /**
     * The position object a successful bridge reply carries, or `null` when the
     * configuration has no address to stand behind.
     *
     * Field names and order follow what the app's own command produced for a
     * real fix, read off the wire on 2026-11-12; the values come from the
     * configuration so that a re-picked anchor needs no rebuild.
     *
     * A **map**, not a JSON string. The page's glue hands this straight to the
     * business code (`options.success(resp.result)`), which reads `e.latitude`,
     * `e.address` and friends as properties; a serialised string carries the
     * same text and none of those properties, so the page renders its
     * "地理位置获取失败" fallback with the address sitting right there in the
     * payload. Measured 2026-11-12 against the live page over CDP.
     */
    private fun locationPayload(): Map<String, Any>? {
        val cfg = HookState.config()
        val address = cfg.addressFor("getAddrStr") ?: run {
            reportMissingAddress()
            return null
        }
        val (lat, lng) = HookState.positionIn(Datum.GCJ02)
        val fields = LinkedHashMap<String, Any>(12)
        fields["coordType"] = "gcj02"
        put(fields, "country", cfg.addressFor("getCountry"))
        fields["address"] = address
        put(fields, "province", cfg.addressFor("getProvince"))
        put(fields, "city", cfg.addressFor("getCity"))
        put(fields, "street", cfg.addressFor("getStreet"))
        put(fields, "cityCode", cfg.addressFor("getCityCode"))
        fields["latitude"] = lat
        put(fields, "district", cfg.addressFor("getDistrict"))
        put(fields, "AdCode", cfg.addressFor("getAdCode"))
        fields["longitude"] = lng
        return fields
    }

    /** Adds a text field unless the configuration left it empty. */
    private fun put(fields: MutableMap<String, Any>, key: String, value: String?) {
        if (!value.isNullOrEmpty()) fields[key] = value
    }

    /** Missing-address notes already logged. */
    @Volatile
    private var addressNotes = 0

    /**
     * Says out loud that a reply was left unrepaired because the configuration
     * has no address for the anchor.
     *
     * This is the failure mode the whole file is otherwise built to avoid: when
     * the address is blank, [locationPayload] answers `null`, both repairs back
     * off, and the page renders its own "地理位置获取失败" — with **nothing** in
     * the log to say the module had noticed. Measured 2026-11-12: the config
     * held a perfectly good pair of coordinates and not one address field, the
     * bridge pushed `{"coordType":"gcj02","cityCode":"194","latitude":…}` with no
     * address in it, and the only clue anywhere was a page that said it could
     * not get a location.
     *
     * Once per process: a page polls, and a repeated line would bury the one that
     * matters.
     */
    private fun reportMissingAddress() {
        if (addressNotes > 0) return
        addressNotes = 1
        Diag.event(
            "no-address",
            "cannot repair a location reply: the configuration has no address for the anchor " +
                "(coordinates only) — the page will show its own failure text; " +
                "pick the spot on the map once to resolve it",
        )
    }

    /**
     * Samples what the app pushes back into the page.
     *
     * This is the only place the *result* of a bridge call is visible from Java:
     * the command builds its reply, hands it to a native dispatcher, and the
     * dispatcher ends up in `WebView.evaluateJavascript`. A failure the page
     * renders came from one of these strings.
     */
    private fun watchJavascript() {
        val hooked = HookKit.hookAll(WebView::class.java, "evaluateJavascript", TAG) { chain ->
            val script = chain.args.getOrNull(0) as? String
            val repaired = script?.let { repairNotify(it) }
            if (repaired != null) {
                // Rewrite in place and let the app's own call run: the page's
                // callback is keyed by the event id inside this string, so
                // posting a second script would arrive on a different turn.
                val written = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (chain.args as? Array<Any?>).also { it?.set(0, repaired) }
                }.getOrNull() != null
                if (written) {
                    if (repairs < REPAIR_REPORTS) {
                        repairs++
                        HookKit.info(TAG, "failed location notify rewritten[$repairs/$REPAIR_REPORTS] ${repaired.take(200)}")
                    }
                    return@hookAll chain.proceed()
                }
                // `args` was read-only: drop the failure and post the good one.
                (chain.thisObject as? WebView)?.let {
                    runCatching { it.evaluateJavascript(repaired, null) }
                }
                return@hookAll null
            }
            if (jsLogged < JS_BUDGET && script != null && looksLikePosition(script)) {
                jsLogged++
                HookKit.info(TAG, "evalJS[$jsLogged/$JS_BUDGET] ${script.take(600)}")
            }
            chain.proceed()
        }
        if (hooked > 0) {
            HookKit.info(TAG, "sampling WebView scripts ($hooked evaluateJavascript overload(s))")
        }
    }

    /**
     * Turns a failed `PJF.shellNotify` into the success the page is waiting for.
     *
     * ## Why the reply that matters is this one
     *
     * The synchronous answer the page gets back from the command is
     * `{"success":true,"result":""}` — it only says "a lookup started", and the
     * page registers its callback on the strength of it. The position itself
     * arrives a moment later as `PJF.shellNotify(eventID, …)`, and *that* is
     * what the page renders. Measured over CDP on 2026-11-12: the callback
     * received `{"result":"定位失败","success":false}` while the map SDK had
     * already handed over a fix, so the page took its failure branch with a
     * perfectly good position sitting one frame away.
     *
     * Repairing only the synchronous map (see [repairLocationReply]) therefore
     * fixed nothing: it was already reporting success. The failure is pushed
     * through `evaluateJavascript` as a script, which is why the rewrite lives
     * here — the event id and the payload are both plain text in that string.
     *
     * `null` when the script is not a failed location notify, i.e. almost
     * always; this runs on every script the app evaluates.
     */
    private fun repairNotify(script: String): String? {
        if (!HookState.active()) return null
        val at = script.indexOf("shellNotify")
        if (at < 0) return null
        // The app words a failed fix as `{"result":"定位失败","success":false}`;
        // requiring that text keeps every other command's failure out of this
        // rewrite — a bridge that silently turns an unrelated error into a
        // success would be far worse than the bug being fixed.
        if (script.indexOf(LOCATION_FAILURE) < 0) return null
        if (script.indexOf("\"success\":false") < 0 && script.indexOf("success:false") < 0) return null
        val payload = locationPayload() ?: return null
        val id = NOTIFY_EVENT.find(script)?.groupValues?.getOrNull(1) ?: DEFAULT_EVENT
        return script.substring(0, at) + "shellNotify('" + id + "',{\"success\":true,\"result\":" + literal(payload) + "})"
    }

    /** Renders the payload as a JavaScript object literal. */
    private fun literal(fields: Map<String, Any>): String {
        val out = StringBuilder(fields.size * 32 + 8).append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) out.append(',')
            first = false
            out.append('"').append(key).append("\":")
            if (value is Number) out.append(value.toString())
            else out.append('"').append(escaped(value.toString())).append('"')
        }
        return out.append('}').toString()
    }

    /** Quotes a text value for the script; addresses carry no control characters. */
    private fun escaped(value: String): String =
        if (value.indexOf('"') < 0 && value.indexOf('\\') < 0) value
        else value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun looksLikePosition(script: String): Boolean {
        for (key in JS_KEYS) {
            if (script.contains(key)) return true
        }
        return false
    }

    /**
     * Records that Chromium itself was asked for a position.
     *
     * `navigator.geolocation` in a WebView only reaches a provider once the
     * host app's `WebChromeClient` grants the origin, so its presence here means
     * the page is *not* using a JSBridge command — a distinction that otherwise
     * takes a packet capture to make.
     */
    private fun watchGeolocationPrompt() {
        val hooked = HookKit.hookAll(
            WebChromeClient::class.java,
            "onGeolocationPermissionsShowPrompt",
            TAG,
        ) { chain ->
            Diag.event(
                "geo-prompt",
                "WebView asked for geolocation permission — the page uses navigator.geolocation, not a JSBridge command",
            )
            chain.proceed()
        }
        if (hooked > 0) {
            HookKit.info(TAG, "watching WebView geolocation prompts ($hooked entry point(s))")
        }
    }

    /**
     * `true` when [name] contains any of [markers].
     *
     * A manual loop rather than `String.contains(CharSequence)` in an `any {}`:
     * this runs on every class load in the process, and the inlined loop skips
     * both the lambda allocation and the regex-free `indexOf` overhead per
     * candidate.
     */
    private fun String.indexOfAny(markers: Array<String>): Int {
        for (marker in markers) {
            val at = indexOf(marker)
            if (at >= 0) return at
        }
        return -1
    }
}
