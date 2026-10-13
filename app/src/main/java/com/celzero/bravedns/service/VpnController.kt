/*
 * Copyright 2018 Jigsaw Operations LLC
 * Copyright 2021 RethinkDNS and its authors
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
import android.content.Intent
import android.net.Network
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Client
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.intra.Controller
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.Socket

object VpnController : KoinComponent {

    @Volatile private var rvpn: BraveVPNService? = null
    private var vpnState: BraveVPNService.State? = null
    private val persistentState by inject<PersistentState>()
    @Volatile private var states: BraveVPNService.State? = null
    @Volatile private var protocol: Pair<Boolean, Boolean> = Pair(false, false)
    private const val URL4 = "IPv4"
    private const val URL6 = "IPv6"

    @Volatile private var vpnStartElapsedTime: Long = SystemClock.elapsedRealtime()

    // FIXME: Publish VpnState through this live-data to relieve direct access
    // into VpnController's state(), isOn(), hasTunnel() etc.
    var connectionStatus: MutableLiveData<BraveVPNService.State?> = MutableLiveData()

    // TODO: make clients listen on create, start, stop, destroy from vpn-service
    fun onVpnCreated(b: BraveVPNService) {
        rvpn = b

        // store app start time, used in HomeScreenBottomSheet
        vpnStartElapsedTime = SystemClock.elapsedRealtime()
    }

    fun onVpnDestroyed() {
        rvpn = null
        states = null
        vpnStartElapsedTime = SystemClock.elapsedRealtime()
    }

    fun uptimeMs(): Long {
        val b = rvpn
        val start = vpnStartElapsedTime
        val t = SystemClock.elapsedRealtime() - start

        return if (b?.hasTunnel() == true) {
            t
        } else {
            -1L * t
        }
    }

    fun onConnectionStateChanged(state: BraveVPNService.State?) {
        when (state) {
            null -> {
                updateState(null)
            }
            BraveVPNService.State.NEW -> {
                updateState(state)
            }
            else -> {
                // do not update if in paused-state unless state is new / null
                if (!isAppPaused()) {
                    updateState(state)
                }
            }
        }
    }

    private fun updateState(state: BraveVPNService.State?) {
        vpnState = state
        connectionStatus.postValue(state)
    }

    fun start(context: Context, autoAttempt: Boolean = false) {
        val b = rvpn
        // if the tunnel has the go-adapter then there's nothing to do
        if (b?.hasTunnel() == true) {
            return
        }
        // below check is to avoid multiple calls to start the vpn when always-on is enabled
        // case: after a device reboot, vpn?.isAlwaysOnEnabled() may return false even though
        // always-on is actually enabled; this causes the VPN to start twice and fails doing so.
        // one approach is to store the always-on state in persistent state and check it here.
        // another is to check whether the vpn is already running.
        // todo: see whether changing the persistent state is really necessary.
        if (b != null && autoAttempt) {
            return
        }
        try {
            // else: resend/send the start-command to the vpn service which handles both false-start
            // and actual-start scenarios just fine; ref: isNewVpn bool in vpnService.onStartCommand
            val startServiceIntent = Intent(context, BraveVPNService::class.java)

            // ContextCompat will take care of calling the proper service based on the API version.
            // before Android O, context.startService(intent) should be invoked.
            // on or after Android O, context.startForegroundService(intent) should be invoked.
            ContextCompat.startForegroundService(context, startServiceIntent)

            onConnectionStateChanged(vpnState)
        } catch (e: Exception) {
        }
    }

    fun stop(reason: String, context: Context, userInitiated: Boolean = true) {
        vpnState = null
        onConnectionStateChanged(null)
        rvpn?.signalStopService(reason, userInitiated)
    }

    @Suppress("DEPRECATION")
    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
        val b = rvpn
        val cs = vpnState
        val on = b?.hasTunnel() == true
        return VpnState(requested, on, cs)
    }

    @Deprecated(message = "use hasTunnel() instead", replaceWith = ReplaceWith("hasTunnel()"))
    fun isOn(): Boolean {
        return hasTunnel()
    }

    suspend fun refreshResolvers() {
        rvpn?.refreshResolvers()
    }

    suspend fun refreshProxies() {
        rvpn?.refreshProxies()
    }

    fun hasTunnel(): Boolean {
        return rvpn?.hasTunnel() == true
    }

    fun isAppPaused(): Boolean {
        return vpnState == BraveVPNService.State.PAUSED
    }

    fun isVpnLockdown(): Boolean {
        return Utilities.isVpnLockdownEnabled(rvpn)
    }

    fun isAlwaysOn(context: Context): Boolean {
        return Utilities.isAlwaysOnEnabled(context, rvpn)
    }

    fun pauseApp() {
        rvpn?.let {
            onConnectionStateChanged(BraveVPNService.State.PAUSED)
            it.pauseApp()
        }
    }

    fun resumeApp() {
        rvpn?.let {
            onConnectionStateChanged(BraveVPNService.State.NEW)
            it.resumeApp()
        }
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long>? {
        return rvpn?.getPauseCountDownObserver()
    }

    fun increasePauseDuration(durationMs: Long) {
        rvpn?.increasePauseDuration(durationMs)
    }

    fun decreasePauseDuration(durationMs: Long) {
        rvpn?.decreasePauseDuration(durationMs)
    }

    suspend fun getProxyStatusById(id: String): Pair<Int?, String> {
        return rvpn?.getProxyStatusById(id) ?: Pair(null, "vpn service not available")
    }

    suspend fun getProxyAddrById(id: String): String? {
        return rvpn?.getProxyAddrById(id)
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return rvpn?.getProxyStats(id)
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return rvpn?.getWireGuardStats(id)
    }

    suspend fun getLocalProxyStatsById(id: String): ProxyManager.ProxyStats? {
        return rvpn?.getLocalProxyStatsById(id)
    }

    suspend fun memProfile(filepath: String) {
        rvpn?.memProfile(filepath)
    }

    suspend fun cpuProfile(filepath: String) {
        rvpn?.cpuProfile(filepath)
    }

    suspend fun getDnsIps(id: String): String? {
        return rvpn?.getDnsIps(id)
    }

    suspend fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean> {
        return rvpn?.getSupportedIpVersion(id) ?: Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return rvpn?.isSplitTunnelProxy(id, pair) ?: false
    }

    suspend fun p50(id: String): Long {
        return rvpn?.p50(id) ?: -1L
    }

    fun getRegionLiveData(): LiveData<String> {
        return rvpn?.getRegionLiveData() ?: MutableLiveData()
    }

    fun protocols(): String {
        val p = protocol
        val ipv4 = p.first
        val ipv6 = p.second
        return if (ipv4 && ipv6) {
            "$URL4, $URL6"
        } else if (ipv6) {
            URL6
        } else if (ipv4) {
            URL4
        } else {
            // if both are false, then return based on the stallOnNoNetwork value
            if (!persistentState.stallOnNoNetwork) {
                "$URL4, $URL6"
            } else {
                "-"
            }
        }
    }

    fun updateProtocol(proto: Pair<Boolean, Boolean>) {
        val finalProto =
            if (!proto.first && !proto.second) {
                val failOpen = !persistentState.stallOnNoNetwork
                Pair(failOpen, failOpen)
            } else {
                proto
            }
        protocol = finalProto
    }

    fun mtu(): Int {
        return rvpn?.tunMtu() ?: 0
    }

    fun underlyingSsid(): String? {
        val b = rvpn ?: return ""
        return b.underlyingNetworks?.activeSsid ?: b.underlyingNetworks?.ipv4Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid ?: b.underlyingNetworks?.ipv6Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid.orEmpty()
    }

    fun netType(): String {
        // using firewall_status_unknown from strings.xml as a place holder to show network
        // type as Unknown.
        val b = rvpn
        var t = b?.getString(R.string.firewall_status_unknown) ?: ""
        if (b == null) {
            return t
        }

        t =
            if (b.underlyingNetworks?.isActiveNetworkMetered == true) {
                b.getString(R.string.ada_app_metered).toString()
            } else {
                // the network type is shown as unmetered even when rethink cannot determine
                // the underlying network / no underlying network
                b.getString(R.string.ada_app_unmetered).toString()
            }
        return t
    }

    suspend fun hasCid(cid: String, uid: Int): Boolean {
        return rvpn?.hasCid(cid, uid) ?: false
    }

    suspend fun removeWireGuardProxy(id: Int) {
        rvpn?.removeWireGuardProxy(id)
    }

    suspend fun addWireGuardProxy(id: String, force: Boolean = false) {
        rvpn?.addWireGuardProxy(id, force)
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies() {
        rvpn?.refreshOrPauseOrResumeOrReAddProxies()
    }

    suspend fun readdCustomProxy() {
        rvpn?.readdCustomProxy()
    }

    suspend fun readdSocks5Proxy() {
        rvpn?.readdSocks5Proxy()
    }

    suspend fun readdHttpProxy() {
        rvpn?.readdHttpProxy()
    }

    fun closeConnectionsIfNeeded(uid: Int = INVALID_UID, reason: String) {
        rvpn?.closeConnectionsIfNeeded(uid, reason)
    }


    suspend fun getDnsStatus(id: String): Int? {
        return rvpn?.getDnsStatus(id)
    }

    suspend fun getRDNS(): RDNS? {
        return rvpn?.getRDNS()
    }

    fun protectSocket(socket: Socket) {
        rvpn?.protectSocket(socket)
    }

    suspend fun probeIpOrUrl(ip: String, useAuto: Boolean): ConnectionMonitor.ProbeResult? {
        return rvpn?.probeIpOrUrl(ip, useAuto)
    }

    suspend fun notifyConnectionMonitor(enforcePolicyChange: Boolean = false) {
        rvpn?.notifyConnectionMonitor(enforcePolicyChange)
    }

    suspend fun getSystemDns(): String {
        return rvpn?.getSystemDns().orEmpty()
    }

    suspend fun getNetStat(): NetStat? {
        return rvpn?.getNetStat()
    }

    /** Ask the tunnel to refresh the WIN proxy state and return the updated bytes. */

    suspend fun hasProxy(id: String): Boolean {
        return rvpn?.hasProxy(id) ?: false
    }

    suspend fun createWgHop(origin: String, hop: String): Pair<Boolean, String> {
        return (rvpn?.createWgHop(origin, hop) ?: Pair(false, "vpn service not available"))
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        return rvpn?.testHop(src, hop) ?: Pair(false, "vpn service not available")
    }

    suspend fun hopStatus(src: String, hop: String): Pair<Int?, String> {
        return rvpn?.hopStatus(src, hop) ?: Pair(null, "vpn service not available")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return rvpn?.removeHop(src) ?: Pair(false, "vpn service not available")
    }

    suspend fun getWgClientInfoById(id: String): Client? {
        return rvpn?.getWgClientInfoById(id)
    }

    suspend fun vpnStats(): String? {
        return rvpn?.vpnStats()
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        return rvpn?.performConnectivityCheck(controller, id, addrPort) ?: false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        return rvpn?.performAutoConnectivityCheck(controller, id, mode) ?: false
    }

    fun bindToNwForConnectivityChecks(nw: Network, pfd: Long): Boolean {
        return rvpn?.bindToNwForConnectivityChecks(nw, pfd) ?: false
    }

    fun protectFdForConnectivityChecks(fd: Long) {
        this.rvpn?.protectFdForConnectivityChecks(fd)
    }

    fun isUnderlyingVpnNetworkEmpty(): Boolean {
        return rvpn?.isUnderlyingVpnNetworkEmpty() ?: false
    }

    fun screenUnlock() {
        rvpn?.screenUnlock()
    }

    suspend fun initiateWgPing(proxyId: String) {
        rvpn?.initiateWgPing(proxyId)
    }

    fun screenLock() {
        rvpn?.screenLock()
    }

    suspend fun crashTun(type: Long) {
        rvpn?.crashTun(type)
    }

}
