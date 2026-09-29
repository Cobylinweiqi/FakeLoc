package io.github.coby.fakeloc

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the LSPosed service connection for the whole app, and brings up the
 * Baidu Map SDK that the map picker needs.
 *
 * The manager UI has no root and no privileged channel: the only way it can
 * reach the module's remote preferences is by binding to LSPosed's service, which
 * `XposedServiceHelper` does on our behalf. The connection can drop (manager
 * update, LSPosed restart) and come back, so it is exposed as a [StateFlow]
 * rather than a one-shot lookup — the UI reacts to both edges.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        // No map SDK is started here on purpose. Its key comes from the config,
        // the config comes from LSPosed over a service that may bind after this
        // returns, and these SDKs only ever read a key once — starting one now
        // would lock in the wrong one. `MapSdkBootstrap` does it when the picker
        // is actually opened, which is also the only place a map is drawn.
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        serviceState.value = service
    }

    override fun onServiceDied(service: XposedService) {
        serviceState.value = null
    }

    companion object {
        private const val TAG = "FakeLoc"

        private val serviceState = MutableStateFlow<XposedService?>(null)

        /** `null` until LSPosed's service is bound. */
        val services: StateFlow<XposedService?> = serviceState.asStateFlow()

        /** Blocking accessor, for places that cannot collect a flow. */
        val service: XposedService?
            get() = serviceState.value
    }
}
