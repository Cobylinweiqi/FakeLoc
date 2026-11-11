package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.location.Location
import io.github.cobylinweiqi.fakeloc.core.Datum
import io.github.cobylinweiqi.fakeloc.xposed.CapturedListener
import io.github.cobylinweiqi.fakeloc.xposed.Diag
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import io.github.cobylinweiqi.fakeloc.xposed.SdkDispatch
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hooks the *inside* of the map and location SDKs themselves.
 *
 * ## Why hooking `android.location` is not enough
 *
 * The rest of the module intercepts what the platform hands to the app. A
 * location SDK does not have to go through that path at all. Amap's, Baidu's and
 * Tencent's SDKs each ship a closed-source engine that mixes GNSS, WiFi and cell
 * data on their own hardware, and each publishes the result through its *own*
 * classes:
 *
 * ```
 * AmapLocationClient ──> AMapLocationListener.onLocationChanged(AMapLocation)
 * LocationClient     ──> BDLocationListener.onReceiveLocation(BDLocation)
 * TencentLocationManager ──> TencentLocationListener.onLocationChanged(TencentLocation, …)
 * ```
 *
 * A `BDLocation` is not an `android.location.Location` at all, and a
 * `TencentLocation` never passes through `LocationManager`. An app built on
 * these sees nothing this module does — which is exactly the "map opens on the
 * fake position and then settles onto the real one" report, except that with an
 * SDK that never consults the platform it never even starts on the fake position.
 *
 * ## One vendor, more than one package
 *
 * A vendor's Java package is not a constant: Tencent ships the same engine as
 * `com.tencent.map.geolocation` and, repackaged, as
 * `com.tencent.map.geolocation.sapp`. Which one an app calls was decided when it
 * was integrated, and the two are not interchangeable — a hook resolves classes
 * in *its* process, so naming the wrong one installs nothing and says nothing.
 * Every Tencent entry point below therefore goes through [TENCENT_FLAVORS]; see
 * [TencentFlavor] for the case that established it (企业微信's check-in page).
 *
 * ## The three channels do not work the same way
 *
 * | SDK | Payload | How it is rewritten |
 * |---|---|---|
 * | Amap | `AMapLocation **extends** android.location.Location` | written in place through the inherited setters, and the getters are read through them too |
 * | Baidu | `BDLocation`, a standalone class | written in place through its own public setters |
 * | Tencent | `TencentLocation`, a bare **interface** (a class in `sapp`) | the payload cannot be rebuilt, so the concrete class's getters are hooked on first sight |
 *
 * The Amap case is the one that looks trivial and is not: `AMapLocation` declares
 * `getLatitude()` reading its **own** field `q`, not the inherited
 * `mLatitude`. Writing a `Location` there would leave the value the app actually
 * reads untouched. What makes the write work is that `AMapLocation` also
 * overrides `setLatitude`/`setLongitude`/`setAltitude`/`setSpeed`/`setBearing`,
 * so a virtual call through the `Location` reference reaches those overrides and
 * lands in the right fields. `getAccuracy` is the one that delegates to
 * `super`, which is why it is on the list as well rather than assumed to follow.
 *
 * ## Datum
 *
 * Each channel is handed the coordinate in the datum its own SDK would have
 * reported: GCJ-02 for Amap and Tencent, BD-09 for Baidu. Feeding an SDK the
 * WGS-84 value it never produces would put the result several hundred metres
 * from the point the user picked, which reads as "it works but it is wrong" and
 * is far harder to diagnose than a plain failure.
 *
 * ## When the SDK is not there
 *
 * Every channel is looked up by name and skipped silently when absent. A module
 * that refuses to install because one app does not use Amap would be useless; a
 * module that logs a warning for it would bury the warnings that matter.
 */
internal object MapSdkHooks {

    private const val TAG = "MapSdk"

    /**
     * One entry per class loader the channels were armed in.
     *
     * Weak keys on purpose: a `DexClassLoader` the app builds for one screen is
     * garbage once that screen is gone, and holding it strongly would pin the
     * whole loader — its dex, its `Class` objects — for the process's lifetime.
     */
    private val installed: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    /** Concrete `TencentLocation` implementations already hooked, one pass each. */
    private val tencentPayloads: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    /** Address fields whose first read has already been reported, `sdk.name` keys. */
    private val addressSeen: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /**
     * Reports one address field the app just read, and answers it with the
     * anchor's own address.
     *
     * ## Coordinates and addresses are two separate lies
     *
     * Rewriting `getLatitude()` rewrites a number. The city, province, street and
     * POI the same payload carries are **strings the SDK computed from the real
     * fix** — by asking its own server, not by consulting anything on the device —
     * and no part of the coordinate path touches them. An app that maps the
     * coordinates it is handed, but prints the SDK's own `getCity()`, therefore
     * ends up half converted: a fake map with a real city in the header. From the
     * outside that reads as "part of the app follows the fake position and part of
     * it does not", which is the report this was written for.
     *
     * ## Two jobs in one hook
     *
     *  * the **first** read of each field is logged with the value the SDK
     *    actually produced. That line settles the one question nothing else can:
     *    an app that never asks the SDK for an address gets its city from
     *    somewhere this module cannot reach, while one that does read it can be
     *    fixed here;
     *  * with `SpoofConfig.syncAddress` on, the field answers the anchor's
     *    counterpart instead.
     *
     * ## Why it answers a value rather than nothing
     *
     * Answering an empty string was tried first and made things worse. CCB Life
     * reads this city name and looks the *name* up in its own table to get a code
     * — `position_city_code = lookup(cityName).code`, inside the WebView home page
     * — so an empty name skips the whole update, and the header keeps the city it
     * cached from the last real fix. It then never moves, for any anchor, which is
     * indistinguishable from the module doing nothing. A plausible wrong answer is
     * something the app can act on; no answer is not.
     *
     * Only `String` fields are ever replaced. Answering text to a getter that
     * normally returns a code would hand the app a value of the wrong type, and an
     * app that went on to parse it would be a crash this module caused.
     *
     * Reported once per field per process: an app reads `getCity()` far more often
     * than once, and the only question worth answering is whether it reads it at
     * all.
     */
    private fun observeAddress(sdk: String, name: String, original: Any?): Any? {
        // Same switch as the coordinate getters, not merely "spoofing is on": with
        // `mapSdkCompat` off nothing in this channel rewrites the payload, and a
        // substituted city there would pair the *real* coordinate with a fake name
        // — the half-converted state the channel exists to prevent, arrived at from
        // the other direction.
        if (!HookState.mapSdkEngaged()) return original
        val cfg = HookState.config()
        // `null` counts as much as a String here, and that is the crux of this
        // channel: the case worth rewriting is precisely the one where the SDK has
        // *no* address to offer. CCB Life's BDLocation answers every one of these
        // getters with null, so an `original is String` test skips exactly the
        // reads that need rewriting — the fake city gets resolved, stored, logged
        // and then handed straight past, and the app keeps the city it cached.
        //
        // A non-String, non-null value is still left alone: some of these getters
        // answer with an int or a long, and putting a String there is a crash we
        // would have caused ourselves.
        val answer = if (cfg.syncAddress && (original == null || original is String)) {
            cfg.addressFor(name)
        } else {
            null
        }
        if (addressSeen.add("$sdk.$name")) {
            HookKit.info(
                TAG,
                if (answer == null) {
                    "$sdk address field read: $name=\"$original\""
                } else {
                    // Both halves matter. Logging only the SDK's own value says
                    // nothing about whether the substitution happened — and in
                    // the case this channel exists for, that value is null, so
                    // the old one-liner read as "hooked, did nothing".
                    "$sdk address field read: $name=\"$original\" — answering \"$answer\""
                },
            )
        }
        return answer ?: original
    }

    /**
     * Installs [observeAddress] on every address getter in [names].
     *
     * Absent names are skipped silently, on purpose: the three SDKs disagree about
     * which parts they publish (`getTownship` only on Amap, `getAddrStr` only on
     * Baidu, `getVillage` only on Tencent), so a warning per missing name would
     * bury the ones that matter for an app that uses all three.
     *
     * The count is reported once at install time so that an empty log later reads
     * as "hooked, and the app never asked" rather than "the hooks never landed".
     * In the LSPosed log those two are otherwise identical, and they call for
     * opposite next steps.
     */
    private fun installAddressReads(clazz: Class<*>, sdk: String, names: Array<String>): Int {
        var hooked = 0
        for (name in names) {
            if (HookKit.methodsNamed(clazz, name).isEmpty()) continue
            hooked += HookKit.hookAll(clazz, name, TAG) { chain ->
                observeAddress(sdk, name, chain.proceed())
            }
        }
        if (hooked > 0) HookKit.info(TAG, "$sdk address fields hooked ($hooked getter(s))")
        return hooked
    }

    /**
     * Arms every channel whose SDK is reachable from [classLoader].
     *
     * Called twice in practice: once for the app's own loader at
     * `onPackageReady`, and again from [DynamicLoaderHooks] if the SDK turns out
     * to live in a loader of its own. The [installed] guard means the second call
     * is a no-op unless it carries a genuinely different loader.
     */
    fun install(classLoader: ClassLoader) {
        if (!installed.add(classLoader)) return
        runCatching { installAmap(classLoader) }
            .onFailure { HookKit.error(TAG, "AMap channel failed", it) }
        runCatching { installBaidu(classLoader) }
            .onFailure { HookKit.error(TAG, "Baidu channel failed", it) }
        runCatching { installTencent(classLoader) }
            .onFailure { HookKit.error(TAG, "Tencent channel failed", it) }
    }

    // ==================================================================== Amap

    private const val AMAP_LOCATION = "com.amap.api.location.AMapLocation"
    private const val AMAP_LISTENER = "com.amap.api.location.AMapLocationListener"
    private const val AMAP_CLIENT = "com.amap.api.location.AMapLocationClient"

    /** Provider string the synthetic fix is tagged with. */
    private const val PROVIDER = "gps"

    /**
     * The reads `AMapLocation` answers from its own fields.
     *
     * Every one of them has a matching override on the same class, so writing
     * through a `Location` reference reaches it. `getAccuracy` is included
     * because it is the one that delegates to `Location.getAccuracy` instead —
     * verified in the SDK's bytecode, not assumed from the pattern.
     */
    private val AMAP_READS = arrayOf(
        "getLatitude",
        "getLongitude",
        "getAccuracy",
        "getAltitude",
        "getSpeed",
        "getBearing",
    )

    /**
     * The address parts `AMapLocation` publishes.
     *
     * Kept apart from [AMAP_READS] because the two answer different questions:
     * that list is what the fix *is*, this one is what the SDK made of it. None of
     * these values is derived from the coordinates on the device — the SDK asks
     * its own server — so rewriting the coordinate moves none of them.
     */
    private val AMAP_ADDRESS = arrayOf(
        "getCountry",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTownship",
        "getStreet",
        "getStreetNumber",
        "getAddress",
        "getPoiName",
        "getAoiName",
        "getRoad",
        "getAdCode",
        "getBuildingId",
        "getFloor",
        "getDescription",
        "getLocationDetail",
    )

    private fun installAmap(loader: ClassLoader) {
        val locationClass = HookKit.findClass(loader, AMAP_LOCATION) ?: return
        val listenerClass = HookKit.findClass(loader, AMAP_LISTENER) ?: return
        val clientClass = HookKit.findClass(loader, AMAP_CLIENT) ?: return

        var reads = 0
        for (name in AMAP_READS) {
            reads += HookKit.hookAll(locationClass, name, TAG) { chain ->
                if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
                (chain.thisObject as? Location)?.let { HookState.applyTo(it, Datum.GCJ02) }
                chain.proceed()
            }
        }

        installAddressReads(locationClass, "amap", AMAP_ADDRESS)

        var opened = 0
        opened += HookKit.hookAll(clientClass, "setLocationListener", TAG) { chain ->
            val forwarded = chain.proceed()
            for (argument in chain.args) {
                if (argument != null && listenerClass.isInstance(argument)) {
                    subscribeAmap(argument, listenerClass, locationClass)
                }
            }
            forwarded
        }
        HookKit.hookAll(clientClass, "unRegisterLocationListener", TAG) { chain ->
            for (argument in chain.args) if (argument != null) SdkDispatch.unregister(argument)
            chain.proceed()
        }

        if (reads > 0 || opened > 0) {
            HookKit.info(TAG, "AMap channel armed ($reads read path(s), $opened listener entry point(s))")
        }
    }

    /**
     * Wires one `AMapLocationListener`: its callback gets rewritten, and the
     * listener is enrolled for the active push.
     */
    private fun subscribeAmap(listener: Any, listenerClass: Class<*>, locationClass: Class<*>) {
        val hooked = HookKit.hookAll(listener.javaClass, "onLocationChanged", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            for (argument in chain.args) {
                (argument as? Location)?.let { HookState.applyTo(it, Datum.GCJ02) }
            }
            chain.proceed()
        }
        if (hooked == 0) return

        val constructor = runCatching { locationClass.getConstructor(String::class.java) }.getOrNull()
            ?: runCatching { locationClass.getConstructor(Location::class.java) }.getOrNull()
            ?: return

        SdkDispatch.register(listener, AmapPusher(listener, locationClass, constructor, amapSuccessFields(locationClass)))
        Diag.event("amap", "AMap listener captured (onLocationChanged x$hooked) — feeding the SDK's own callback")
    }

    /**
     * The fields that say "this fix succeeded", which a fix built from scratch
     * does not carry on its own.
     *
     * Left off the list, deliberately: `setTrustedLevel` and
     * `setGpsAccuracyStatus`. Both encode a claim about *how* the position was
     * obtained (trusted hardware, a good satellite geometry) that this module is
     * in no position to make, and a fabricated one is precisely the kind of
     * internal inconsistency a detector looks for. The defaults an app sees
     * without them are the same defaults it sees from any fresh SDK object.
     */
    private fun amapSuccessFields(locationClass: Class<*>): List<Pair<Method, Any>> =
        buildList<Pair<Method, Any>> {
            HookKit.findMethod(locationClass, "setErrorCode", java.lang.Integer.TYPE)?.let { add(it to 0) }
            HookKit.findMethod(locationClass, "setErrorInfo", String::class.java)?.let { add(it to "success") }
            HookKit.findMethod(locationClass, "setLocationType", java.lang.Integer.TYPE)?.let { add(it to 1) }
            HookKit.findMethod(locationClass, "setCoordType", String::class.java)?.let { add(it to "GCJ02") }
            HookKit.findMethod(locationClass, "setFixLastLocation", java.lang.Boolean.TYPE)?.let { add(it to false) }
        }

    private class AmapPusher(
        listener: Any,
        private val locationClass: Class<*>,
        private val constructor: Constructor<*>,
        private val success: List<Pair<Method, Any>>,
    ) : CapturedListener(listener) {

        private val callback: Method? = runCatching {
            listener.javaClass.getMethod("onLocationChanged", locationClass).apply { isAccessible = true }
        }.getOrNull()

        override fun deliver(target: Any) {
            val method = callback ?: return
            val payload = constructor.newInstance(PROVIDER) as? Location ?: return
            HookState.stampTiming(payload)
            HookState.applyTo(payload, Datum.GCJ02)
            for ((setter, value) in success) setter.invoke(payload, value)
            method.invoke(target, payload)
        }
    }

    // =================================================================== Baidu

    private const val BAIDU_LOCATION = "com.baidu.location.BDLocation"
    private const val BAIDU_LISTENER = "com.baidu.location.BDLocationListener"
    private const val BAIDU_ABSTRACT_LISTENER = "com.baidu.location.BDAbstractLocationListener"
    private const val BAIDU_CLIENT = "com.baidu.location.LocationClient"

    /**
     * Entry point that takes a listener.
     *
     * `LocationClient` has no second one — unlike Amap, which also offers a
     * one-shot query — so unlike the other two channels this list has a single
     * member. Kept as a list anyway so the three installers read the same way.
     */
    private val BAIDU_ENTRIES = arrayOf("registerLocationListener")

    /** `BDLocation.TypeGpsLocation` — a valid, high-quality fix. */
    private const val BD_TYPE_GPS = 61

    /** Baidu's own datum tag for BD-09, which is what its SDK reports. */
    private const val BD_COORD_TYPE = "bd09ll"

    /**
     * Satellite count on a synthetic fix.
     *
     * A `TypeGpsLocation` fix with zero satellites is a contradiction a detector
     * can read straight off the object, so a synthesised one has to carry a
     * plausible number. Twelve is an ordinary open-sky count — high enough to
     * justify a GPS-grade fix, low enough not to look like an idealised maximum.
     */
    private const val BD_SATELLITES = 12

    /** `BDLocation.setTime` takes a formatted string, not a millisecond count. */
    private const val BD_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /**
     * The address parts `BDLocation` publishes.
     *
     * Same reasoning as [AMAP_ADDRESS]: these are strings the SDK's server
     * produced, not values derived from the coordinates, so they stay real no
     * matter what the coordinate hooks report.
     */
    private val BAIDU_ADDRESS = arrayOf(
        "getCountry",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTown",
        "getStreet",
        "getStreetNumber",
        "getAddrStr",
        "getLocationDescribe",
        "getAdCode",
    )

    private fun installBaidu(loader: ClassLoader) {
        val locationClass = HookKit.findClass(loader, BAIDU_LOCATION) ?: return
        val listenerClass = HookKit.findClass(loader, BAIDU_LISTENER) ?: return
        // `LocationClient.registerLocationListener` has two overloads — one for
        // the interface, one for `BDAbstractLocationListener`, the abstract base
        // most apps actually extend. Matching only the interface would silently
        // skip every registration made through the other one, which is the
        // majority of them.
        val abstractListener = HookKit.findClass(loader, BAIDU_ABSTRACT_LISTENER)
        val clientClass = HookKit.findClass(loader, BAIDU_CLIENT) ?: return
        val writer = BaiduWriter(locationClass)

        fun isListener(value: Any?): Boolean = value != null &&
            (listenerClass.isInstance(value) || abstractListener?.isInstance(value) == true)

        var reads = 0
        for (name in writer.readerNames) {
            reads += HookKit.hookAll(locationClass, name, TAG) { chain ->
                val payload = chain.thisObject
                if (HookState.mapSdkEngaged() && payload != null) writer.apply(payload)
                chain.proceed()
            }
        }

        installAddressReads(locationClass, "baidu", BAIDU_ADDRESS)

        var opened = 0
        for (entry in BAIDU_ENTRIES) {
            opened += HookKit.hookAll(clientClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                for (argument in chain.args) {
                    if (isListener(argument)) subscribeBaidu(argument!!, listenerClass, locationClass, writer)
                }
                forwarded
            }
        }
        HookKit.hookAll(clientClass, "unRegisterLocationListener", TAG) { chain ->
            for (argument in chain.args) if (argument != null) SdkDispatch.unregister(argument)
            chain.proceed()
        }

        if (reads > 0 || opened > 0) {
            HookKit.info(TAG, "Baidu channel armed ($reads read path(s), $opened listener entry point(s))")
        }
    }

    /**
     * `BDLocation` shares nothing with `android.location.Location`, so there is
     * no base class to write through: every value goes in through the SDK's own
     * setter. The `Method` handles are resolved once, at install time, and reused
     * on every read — reflection lookup on `getLatitude()` would be far too slow
     * for a path an app may hit hundreds of times a second.
     */
    private class BaiduWriter(locationClass: Class<*>) {

        private val latitude = HookKit.findMethod(locationClass, "setLatitude", java.lang.Double.TYPE)
        private val longitude = HookKit.findMethod(locationClass, "setLongitude", java.lang.Double.TYPE)
        private val altitude = HookKit.findMethod(locationClass, "setAltitude", java.lang.Double.TYPE)
        private val speed = HookKit.findMethod(locationClass, "setSpeed", java.lang.Float.TYPE)
        private val radius = HookKit.findMethod(locationClass, "setRadius", java.lang.Float.TYPE)
        private val locType = HookKit.findMethod(locationClass, "setLocType", java.lang.Integer.TYPE)
        private val coorType = HookKit.findMethod(locationClass, "setCoorType", String::class.java)
        private val satellites = HookKit.findMethod(locationClass, "setSatelliteNumber", java.lang.Integer.TYPE)
        private val time = HookKit.findMethod(locationClass, "setTime", String::class.java)

        /**
         * Getter names that trigger a rewrite when they are read.
         *
         * `getLocType` is *not* on this list: a real fix already carries a
         * truthful type and this module has nothing better to put there, so
         * hooking it would only add a call for no change.
         */
        val readerNames = arrayOf(
            "getLatitude",
            "getLongitude",
            "getRadius",
            "getAltitude",
            "getSpeed",
            "getCoorType",
        )

        /**
         * @param synthesised `true` only for a payload this module built itself.
         *   A fix the SDK produced already carries a truthful `locType` (and the
         *   satellite count behind it); overwriting that with a canned
         *   "GPS, 12 satellites" on every read would replace real provenance with
         *   a fixed story, which is both unnecessary and a tell.
         */
        fun apply(target: Any, synthesised: Boolean = false) {
            val cfg = HookState.config()
            val (lat, lng) = HookState.positionIn(Datum.BD09)

            call(latitude, target, lat)
            call(longitude, target, lng)
            call(coorType, target, BD_COORD_TYPE)
            if (cfg.useAccuracy) call(radius, target, cfg.accuracy)
            if (cfg.useAltitude) call(altitude, target, cfg.altitude)
            if (cfg.useSpeed) call(speed, target, cfg.speed)

            if (synthesised) {
                call(locType, target, BD_TYPE_GPS)
                call(satellites, target, BD_SATELLITES)
                call(time, target, SimpleDateFormat(BD_TIME_PATTERN, Locale.US).format(Date()))
            }
        }

        private fun call(method: Method?, receiver: Any, value: Any) {
            if (method == null) return
            runCatching { method.invoke(receiver, value) }
        }
    }

    private fun subscribeBaidu(
        listener: Any,
        listenerClass: Class<*>,
        locationClass: Class<*>,
        writer: BaiduWriter,
    ) {
        val hooked = HookKit.hookAll(listener.javaClass, "onReceiveLocation", TAG) { chain ->
            val payload = chain.args.getOrNull(0)
            if (HookState.mapSdkEngaged() && payload != null) writer.apply(payload)
            chain.proceed()
        }
        if (hooked == 0) return

        val factory = BaiduFactory.of(locationClass)
        if (factory == null) {
            HookKit.warn(TAG, "BDLocation has no usable constructor — Baidu push path disabled")
            return
        }

        SdkDispatch.register(listener, BaiduPusher(listener, locationClass, factory, writer))
        Diag.event("baidu", "Baidu listener captured (onReceiveLocation x$hooked) — feeding the SDK's own callback")
    }

    /**
     * Builds a fresh, empty `BDLocation` for the push path.
     *
     * `BDLocation(String)` is a **JSON deserialising** constructor, not a
     * provider-name one — unlike `AMapLocation(String)`, which really does take a
     * provider. Measured on Baidu locSDK 9.7 inside CCB Life (2026-09-29): handing
     * it `"gps"` made `BDLocation.<init>` run `new JSONObject("gps")` and throw
     * `Value gps of type java.lang.String cannot be converted to JSONObject` once
     * a second, so no synthetic fix was ever delivered and the push path was
     * silently dead while still costing the target app an exception per tick.
     *
     * The no-argument constructor is the correct one. The JSON form is kept only
     * as a fallback and fed `"{}"`, which parses to an empty object whose every
     * field the writer then sets.
     */
    private class BaiduFactory(private val constructor: Constructor<*>, private val argument: Any?) {

        fun create(): Any? = runCatching {
            if (argument == null) constructor.newInstance() else constructor.newInstance(argument)
        }.getOrNull()

        companion object {
            fun of(locationClass: Class<*>): BaiduFactory? {
                runCatching { locationClass.getConstructor() }
                    .getOrNull()
                    ?.let { return BaiduFactory(it, null) }
                runCatching { locationClass.getConstructor(String::class.java) }
                    .getOrNull()
                    ?.let { return BaiduFactory(it, EMPTY_JSON) }
                return null
            }

            /** Parses to an empty object; every field is left to [BaiduWriter]. */
            private const val EMPTY_JSON = "{}"
        }
    }

    private class BaiduPusher(
        listener: Any,
        private val locationClass: Class<*>,
        private val factory: BaiduFactory,
        private val writer: BaiduWriter,
    ) : CapturedListener(listener) {

        private val callback: Method? = runCatching {
            listener.javaClass.getMethod("onReceiveLocation", locationClass).apply { isAccessible = true }
        }.getOrNull()

        /** Guards the one-time warning below; set once, read on every tick. */
        private val payloadFailure = AtomicBoolean(false)

        override fun deliver(target: Any) {
            val method = callback ?: return
            // Never throw out of the dispatch loop: this runs once a second in
            // someone else's process, and a failure here must not take the tick
            // with it or spam the log. A null payload means the constructor
            // refused, which is a fact worth saying exactly once.
            val payload = factory.create()
            if (payload == null) {
                if (payloadFailure.compareAndSet(false, true)) {
                    HookKit.warn(TAG, "BDLocation construction failed — Baidu push path disabled")
                }
                return
            }
            writer.apply(payload, synthesised = true)
            runCatching { method.invoke(target, payload) }
        }
    }

    // ================================================================= Tencent

    /**
     * One packaging of Tencent's location SDK.
     *
     * The engine is the same in every build; only the Java package differs, and
     * which one an app ends up with is decided by how it was integrated, not by
     * the SDK version. Tencent publishes the `com.tencent.map.geolocation` build
     * for apps that follow its setup guide; some apps — 企业微信 among them —
     * embed the same classes repackaged under `com.tencent.map.geolocation.sapp`.
     *
     * Getting this wrong is silent in the worst possible way: a channel armed
     * against a package the process does not contain resolves no class, installs
     * no hook and logs no warning, so the app behaves exactly as if this module
     * were not installed. That was the state of the 企业微信 check-in path until
     * 1.7.2 — the module hooked the public package while the app called the
     * `sapp` one, so the map sat on the real position whatever was configured.
     * Measured on 2026-10-22 against 企微小打手 1.6.1, whose entire location
     * channel names the `sapp` pair and nothing else.
     */
    private class TencentFlavor(
        /** Log label, and the key the per-field diagnostics dedupe on. */
        val label: String,
        val manager: String,
        val listener: String,
        /** An interface in the public build, a class in `sapp` — either is fine. */
        val location: String,
    )

    private val TENCENT_FLAVORS = arrayOf(
        TencentFlavor(
            "tencent",
            "com.tencent.map.geolocation.TencentLocationManager",
            "com.tencent.map.geolocation.TencentLocationListener",
            "com.tencent.map.geolocation.TencentLocation",
        ),
        TencentFlavor(
            "tencent-sapp",
            "com.tencent.map.geolocation.sapp.TencentLocationManager",
            "com.tencent.map.geolocation.sapp.TencentLocationListener",
            "com.tencent.map.geolocation.sapp.TencentLocation",
        ),
    )

    /** Entry points that take a listener. */
    private val TENCENT_ENTRIES = arrayOf(
        "requestLocationUpdates",
        "requestSingleFreshLocation",
    )

    /** Manager methods that return a payload instead of delivering one. */
    private val TENCENT_PULL_ENTRIES = arrayOf("getLastKnownLocation")

    /**
     * The address parts a `TencentLocation` implementation publishes.
     *
     * Same reasoning as [AMAP_ADDRESS], with one twist: the list lands on whichever
     * class *implements* the interface, and that is only known the first time a
     * payload shows up — see [armTencentPayload].
     *
     * `getName` is on the list because on Tencent it is the **POI label**, not the
     * address: a check-in screen prints it as the place ("腾讯滨海大厦") with
     * `getAddress()` underneath. Leaving it out meant the coordinate and the street
     * moved while the place name stayed real, which is precisely the half-converted
     * state this channel exists to prevent. It answers
     * [io.github.cobylinweiqi.fakeloc.core.SpoofConfig.addrPlaceName], never the
     * formatted address — see `addressFor`.
     */
    private val TENCENT_ADDRESS = arrayOf(
        "getNation",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTown",
        "getVillage",
        "getStreet",
        "getStreetNo",
        "getName",
        "getAddress",
    )

    /**
     * What a POI row publishes.
     *
     * The SDK's picker list is a `List<TencentPoi>` hanging off the payload, and
     * each element is a place with its own name, address and coordinate. An app
     * that builds its "choose the place" sheet from that list therefore reads a
     * *second* copy of the real position — one the payload getters never touch —
     * and 企业微信's check-in sheet is built exactly that way. Hooking the rows is
     * what makes the list agree with the map instead of contradicting it.
     */
    private val TENCENT_POI_ADDRESS = arrayOf("getName", "getAddress")

    /** Concrete payload classes already hooked, one pass each. */
    private val tencentPois: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    /** Listener classes whose `onLocationChanged` is already intercepted. */
    private val tencentListeners: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    private fun installTencent(loader: ClassLoader) {
        for (flavor in TENCENT_FLAVORS) {
            runCatching { installTencentFlavor(loader, flavor) }
                .onFailure { HookKit.error(TAG, "${flavor.label} channel failed", it) }
        }
    }

    private fun installTencentFlavor(loader: ClassLoader, flavor: TencentFlavor) {
        val managerClass = HookKit.findClass(loader, flavor.manager) ?: return

        // Diagnostic only. The listener is recognised by its *shape* below, not by
        // this type: the `sapp` build repackages it exactly like the manager, and a
        // name that drifts one version later must not be able to switch the whole
        // subscription path off. When this lookup fails, the log says so and the
        // shape test still arms whatever the app actually passes.
        if (HookKit.findClass(loader, flavor.listener) == null) {
            HookKit.warn(TAG, "${flavor.label}: ${flavor.listener} not found — recognising listeners by shape")
        }

        var opened = 0
        for (entry in TENCENT_ENTRIES) {
            opened += HookKit.hookAll(managerClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                for (argument in chain.args) {
                    argument?.let { if (isTencentListener(it)) subscribeTencent(it, flavor) }
                }
                forwarded
            }
        }

        // The one-shot queries hand back a payload without ever calling a listener.
        //
        // Armed whatever the switches say: installing the getter hooks is free and
        // they gate themselves per read, while *skipping* the install leaves an
        // object the app has already cached reading the real position for as long
        // as it lives — the case that motivated the declared-type hook below.
        for (entry in TENCENT_PULL_ENTRIES) {
            opened += HookKit.hookAll(managerClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                armTencentPayload(forwarded, flavor)
                forwarded
            }
        }
        HookKit.hookAll(managerClass, "removeUpdates", TAG) { chain ->
            for (argument in chain.args) if (argument != null) SdkDispatch.unregister(argument)
            chain.proceed()
        }

        // The declared payload type, hooked before any fix arrives.
        //
        // The lazy path can only arm a class it has *seen*, which is enough when
        // the app asks for a fix after this module was loaded — and not enough when
        // the SDK hands back an object it built or cached earlier. When the type is
        // a concrete class (the `sapp` build's shape) there is something to hook by
        // name right now; when it is the interface the public build publishes — or
        // an abstract base with no getter bodies — there is not, and the lazy path
        // is the only option.
        val locationClass = HookKit.findClass(loader, flavor.location)
        if (locationClass != null &&
            !locationClass.isInterface &&
            !Modifier.isAbstract(locationClass.modifiers)
        ) {
            armTencentPayloadClass(locationClass, flavor)
        }

        if (opened > 0) {
            HookKit.info(TAG, "${flavor.label} channel armed ($opened entry point(s))")
        }
    }

    /**
     * Whether [value] is something the SDK can deliver a fix to.
     *
     * A `TencentLocationListener` is an interface with a single interesting
     * method — `onLocationChanged(TencentLocation, int, String)` — and every
     * subscription call passes an implementation of it beside a
     * `TencentLocationRequest`. Testing the method shape rather than the type is
     * what keeps this working when the build repackages or renames the interface:
     * the hook needs a method it can intercept, not a name it can compare.
     *
     * `TencentLocationRequest` carries no `onLocationChanged`, so the more
     * numerous argument is rejected without a type lookup.
     */
    private fun isTencentListener(value: Any): Boolean =
        HookKit.methodsNamed(value.javaClass, "onLocationChanged").isNotEmpty()

    private fun subscribeTencent(listener: Any, flavor: TencentFlavor) {
        // One pass per listener *class*, not per registration.
        //
        // A check-in screen re-subscribes on every refresh, and the app typically
        // builds its listener once and reuses it — so without this guard the same
        // `Method` would be wrapped again every second, and a hook chain that grows
        // without bound is a stack overflow in someone else's process. Hooking the
        // class covers every instance of it, which is what the registration call
        // was going to give us anyway.
        if (!tencentListeners.add(listener.javaClass)) return

        val hooked = HookKit.hookAll(listener.javaClass, "onLocationChanged", TAG) { chain ->
            // Unconditional for the same reason as the pull path: arming is cheap,
            // gating happens per getter read, and a payload that arrived while the
            // module was off must not keep answering with the real fix after it is
            // switched on.
            armTencentPayload(chain.args.getOrNull(0), flavor)
            chain.proceed()
        }
        if (hooked > 0) {
            Diag.event(
                "${flavor.label}-listener",
                "${flavor.label} listener captured (onLocationChanged x$hooked) — the payload class is now hooked",
            )
        }
    }

    /**
     * Hooks the *concrete* class behind a `TencentLocation`.
     *
     * `TencentLocation` is an interface with no setters in the public build, and
     * its implementation lives outside the published SDK, so there is nothing to
     * write into and nothing to construct. Replacing the payload with a `Proxy`
     * would answer the getters, but the first app that casts the result back to
     * the real class — and some do — would take a `ClassCastException`. Hooking the
     * getters on whatever class arrives is the version that cannot crash the app:
     * it is installed the moment the payload is first seen, before the app's own
     * callback body reads it.
     *
     * The cost is that there is no active push on this channel: with no way to
     * build a payload there is nothing to push.
     *
     * ## Why every getter, not only the coordinate
     *
     * Coordinates and the strings describing them are two separate lies. The city,
     * province, street and POI name the payload carries were computed by the SDK's
     * **server** from the real fix, so rewriting `getLatitude()` moves none of
     * them. A check-in screen prints both: a map on the fake point with the real
     * place name in the chip, or — worse — a record whose coordinates and address
     * disagree. [TENCENT_ADDRESS] and [TENCENT_POI_ADDRESS] are what keep the two
     * halves consistent.
     */
    private fun armTencentPayload(payload: Any?, flavor: TencentFlavor) {
        val clazz = payload?.javaClass ?: return
        armTencentPayloadClass(clazz, flavor)
    }

    private fun armTencentPayloadClass(clazz: Class<*>, flavor: TencentFlavor) {
        if (!tencentPayloads.add(clazz)) return

        HookKit.hookAll(clazz, "getLatitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).first
        }
        HookKit.hookAll(clazz, "getLongitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).second
        }
        HookKit.hookAll(clazz, "getAccuracy", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged() || !cfg.useAccuracy) return@hookAll chain.proceed()
            cfg.accuracy
        }
        HookKit.hookAll(clazz, "getAltitude", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged() || !cfg.useAltitude) return@hookAll chain.proceed()
            cfg.altitude
        }
        HookKit.hookAll(clazz, "getSpeed", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged() || !cfg.useSpeed) return@hookAll chain.proceed()
            cfg.speed
        }

        installAddressReads(clazz, flavor.label, TENCENT_ADDRESS)
        installTencentPoiReads(clazz, flavor)
    }

    /**
     * Arms the rows of `getPoiList()` on first sight.
     *
     * The list is read, not replaced: keeping the app's own `List` object and
     * every element's identity means a caller that caches the list, sorts it or
     * casts an element back to the SDK's POI class keeps working. What changes is
     * what each element answers — the anchor's coordinate, its place label and its
     * address — so the picker sheet and the map tell the same story.
     */
    private fun installTencentPoiReads(clazz: Class<*>, flavor: TencentFlavor) {
        HookKit.hookAll(clazz, "getPoiList", TAG) { chain ->
            val result = chain.proceed()
            // Armed regardless of the switches, like the payload itself: the row
            // getters gate per read, and a list handed out while the module was off
            // must not keep answering with the real places after it is switched on.
            val first = (result as? List<*>)?.firstOrNull() ?: return@hookAll result
            armTencentPoi(first.javaClass, flavor)
            result
        }
    }

    private fun armTencentPoi(clazz: Class<*>, flavor: TencentFlavor) {
        if (!tencentPois.add(clazz)) return

        installAddressReads(clazz, "${flavor.label}-poi", TENCENT_POI_ADDRESS)

        HookKit.hookAll(clazz, "getLatitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).first
        }
        HookKit.hookAll(clazz, "getLongitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).second
        }
    }
}
