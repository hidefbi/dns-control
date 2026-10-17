/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_CONNECTION

/**
 * A lightweight network observer that forwards raw network lifecycle events for
 * WiFi and Cellular networks to a [NetworkLifecycleObserver.Listener].
 *
 * The observer does no processing of its own: it only reports which network changed,
 * what happened to it, and over which transport (WiFi or Cellular).
 */
class NetworkLifecycleObserver(
    private val context: Context,
    private val listener: Listener
) {

    /**
     * Custom callback invoked by [NetworkLifecycleObserver] whenever a tracked network
     * lifecycle event occurs. Receives the [Network] involved and an [EventType]
     * describing what happened.
     */
    interface Listener {
        fun onNetworkEvent(network: Network, eventType: EventType)
    }

    /**
     * The set of network lifecycle events reported by [NetworkLifecycleObserver]. Each
     * value corresponds to a method on [ConnectivityManager.NetworkCallback].
     */
    enum class EventType {
        /** A new network matching the request became available. Maps to onAvailable. */
        NETWORK_ADDED,

        /** The network is no longer available. Maps to onLost. */
        NETWORK_LOST,

        /** The network's capabilities changed. Maps to onCapabilitiesChanged. */
        CAPABILITY_CHANGE,

        /** The network's link properties changed. Maps to onLinkPropertiesChanged. */
        LINK_PROPERTY_CHANGE
    }

    // Transport types are used to filter network requests. Only transport types are required here
    // (no NET_CAPABILITY_INTERNET) so the observer acts purely on network type. Two requests
    // are used because transport types within one NetworkRequest are ANDed.
    private val wifiNetworkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

    private val cellularNetworkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

    @Volatile
    private var connectivityManager: ConnectivityManager? = null
    @Volatile
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var stopped: Boolean = false

    /**
     * Creates a callback for one transport. The transport label is known from the
     * request the callback is registered with, so no capability lookup is needed.
     */
    private fun createCallback(transport: String): ConnectivityManager.NetworkCallback {
        Logger.d(LOG_TAG_CONNECTION, "$TAG; createCallback($transport)")
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) =
                dispatch(network, EventType.NETWORK_ADDED, transport)

            override fun onLost(network: Network) =
                dispatch(network, EventType.NETWORK_LOST, transport)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) = dispatch(network, EventType.CAPABILITY_CHANGE, transport)

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) = dispatch(network, EventType.LINK_PROPERTY_CHANGE, transport)
        }
    }

    /** Forwards a callback event to the listener. Drops the event once [stopped]. */
    private fun dispatch(network: Network, eventType: EventType, transport: String) {
        // Drop callbacks delivered after stop()
        if (stopped) return
        Logger.d(
            LOG_TAG_CONNECTION,
            "$TAG; ${eventType.name}, transport: $transport, nwHandle: ${network.networkHandle}"
        )
        listener.onNetworkEvent(network, eventType)
    }

    /**
     * Registers the network callbacks
     *
     * @return true if the Cellular callback registered successfully (WiFi is
     *         best-effort and may fail), or the observer was already active;
     *         false if the connectivity manager is unavailable or the Cellular
     *         registration threw (any successful WiFi callback is rolled back).
     */
    fun start(): Boolean {
        if (wifiCallback != null || cellularCallback != null) {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; already started, ignoring start()")
            return true
        }
        Logger.i(LOG_TAG_CONNECTION, "$TAG; start()")
        val manager = connectivityManager
            ?: (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager)?.also { connectivityManager = it }
        if (manager == null) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; ConnectivityManager unavailable")
            return false
        }
        // (Re)enable forwarding before any callback is registered, so that the new
        // callbacks are not dropped by the stopped-guard in dispatch().
        stopped = false
        // WiFi and Cellular are independent transports, so each registration is
        // attempted independently: a failing WiFi registration no longer prevents
        // cellular-only observation. The atomic rollback contract is preserved for
        // the case where WiFi succeeded but Cellular then fails.
        val wifiCb = createCallback(TRANSPORT_WIFI)
        var wifiRegistered = false
        try {
            manager.registerNetworkCallback(wifiNetworkRequest, wifiCb)
            wifiCallback = wifiCb
            wifiRegistered = true
            Logger.i(LOG_TAG_CONNECTION, "$TAG; wifi callback registered")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering wifi callback", e)
        }

        // Cellular registration is attempted regardless of the WiFi outcome.
        val cellCb = createCallback(TRANSPORT_CELLULAR)
        try {
            manager.registerNetworkCallback(cellularNetworkRequest, cellCb)
            cellularCallback = cellCb
            Logger.i(LOG_TAG_CONNECTION, "$TAG; cellular callback registered")
        } catch (e: Exception) {
            if (wifiRegistered) {
                // Roll back the WiFi callback so start() stays atomic when Cellular
                // fails alongside a successful WiFi registration.
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering cellular callback, rolling back wifi", e)
                wifiCallback = null
                try {
                    manager.unregisterNetworkCallback(wifiCb)
                } catch (e2: Exception) {
                    Logger.w(LOG_TAG_CONNECTION, "$TAG; err rolling back wifi callback", e2)
                }
            } else {
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering cellular callback", e)
            }
            return false
        }

        Logger.i(LOG_TAG_CONNECTION, "$TAG; start() ok, wifi? $wifiRegistered, cellular? true")
        return true
    }

    /**
     * Unregisters the network callbacks if any are active and sets the [stopped] guard
     * so that any callback delivered after this returns (the framework does not
     * synchronously cancel in-flight callbacks) is dropped in [dispatch].
     */
    fun stop() {
        // Set the guard first: any callback dispatched after this point (including
        // those already queued on the binder thread) is dropped in dispatch().
        stopped = true
        val manager = connectivityManager
        val cbs = listOfNotNull(wifiCallback, cellularCallback)
        wifiCallback = null
        cellularCallback = null
        if (cbs.isEmpty()) {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; stop() no-op, not started")
            return
        }
        Logger.i(LOG_TAG_CONNECTION, "$TAG; stop(), unregistering ${cbs.size} callback/s")
        for (cb in cbs) {
            try {
                manager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err unregistering callback", e)
            }
        }
        // Intentionally NOT clearing connectivityManager: it is an app-wide singleton
        // obtained from applicationContext, so holding it is not a leak, and reusing it
        // avoids a getSystemService lookup on re-start.
    }

    companion object {
        private const val TAG = "NetworkLifecycleObserver"
        private const val TRANSPORT_WIFI = "wifi"
        private const val TRANSPORT_CELLULAR = "cellular"
    }
}
