package io.github.cobylinweiqi.fakeloc.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.cobylinweiqi.fakeloc.core.Keys
import io.github.cobylinweiqi.fakeloc.xposed.hooks.AntiMockHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.DynamicLoaderHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.FrameworkLocationHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.ListenerHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.LocationHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.MapSdkHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.PlayServicesHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.SourceLockHooks

/**
 * Module entry point, named in `META-INF/xposed/java_init.list`.
 *
 * LSPosed instantiates this class once per process it is injected into and
 * drives it through the [XposedModule] callbacks. Which hooks get installed
 * depends on *which* process this is — the same APK has to behave as an
 * app-side hook set, a `system_server` hook set, and a settings-provider hook
 * set, and mixing them up either does nothing or destabilises the process.
 *
 * | Process | Installed |
 * |---|---|
 * | ordinary app | [LocationHooks], [ListenerHooks], [PlayServicesHooks], [AntiMockHooks], [SourceLockHooks], [MapSdkHooks], [DynamicLoaderHooks] |
 * | `system` / `android` | [FrameworkLocationHooks] |
 * | `com.android.providers.settings` | [AntiMockHooks] (settings provider only) |
 */
class ModuleEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookKit.bind(this)
        HookKit.info(TAG, "loaded into ${param.processName}")
    }

    /**
     * Runs before the app's `Application` is created, which is the earliest point
     * at which the remote preferences are reachable. Loading them here means the
     * very first hooked call already sees the user's configuration instead of
     * defaults.
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val preferences = runCatching { getRemotePreferences(Keys.REMOTE_GROUP) }
            .onFailure { HookKit.error(TAG, "getRemotePreferences failed", it) }
            .getOrNull()

        if (preferences == null) {
            HookKit.error(TAG, "no remote preferences for ${param.packageName}; using defaults")
            return
        }
        HookState.init(preferences, param.packageName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // `onPackageReady` fires once per package in the process; the hooks are
        // process-wide, so only the first one matters.
        if (!param.isFirstPackage) return

        val packageName = param.packageName
        val loader = param.classLoader

        when (packageName) {
            "android", "system" -> {
                HookKit.info(TAG, "system_server hooks for $packageName")
                installSystemHooks(loader)
            }

            // Owns the `mock_location` secure setting. Hooking it here means a
            // detector asking the settings provider directly is answered
            // consistently with a detector reading it through Settings.Secure.
            "com.android.providers.settings" -> {
                if (!HookKit.firstTime("settings-provider")) return
                AntiMockHooks.installSettingsProvider(loader)
            }

            // Nothing location-related to spoof in the telephony process, and
            // installing app hooks there is a good way to break calls.
            "com.android.phone" -> Unit

            else -> {
                if (!HookKit.firstTime("app")) return
                LocationHooks.install(loader)
                ListenerHooks.install(loader)
                PlayServicesHooks.install(loader)
                AntiMockHooks.installApp(loader)
                SourceLockHooks.install(loader)
                // Last on purpose: the loader watcher exists only to catch an SDK
                // that turns out not to be reachable from this loader, and it
                // stays silent until one is.
                MapSdkHooks.install(loader)
                DynamicLoaderHooks.install()
            }
        }
    }

    /**
     * Fires only when `system` (or `android`) is in the module scope, i.e. when
     * the user explicitly opted into system-wide interception. It is the hook set
     * that makes Google Play Services' fused provider agree with us, because GMS
     * computes its fused fix from the raw provider data flowing through
     * `system_server`.
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookKit.info(TAG, "system_server starting")
        installSystemHooks(param.classLoader)
    }

    private fun installSystemHooks(loader: ClassLoader) {
        if (!HookKit.firstTime("system")) return
        FrameworkLocationHooks.install(loader)
        AntiMockHooks.installSystemServer(loader)
    }

    private companion object {
        const val TAG = "Entry"
    }
}
