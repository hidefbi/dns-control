/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.data

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptEndpointRepository
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpointRepository
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DnsProxyEndpointRepository
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.DoTEndpointRepository
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.database.RethinkDnsEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.InternetProtocol.Companion.getInternetProtocol
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.settings.Settings

class AppConfig
internal constructor(
    private val context: Context,
    private val rethinkDnsEndpointRepository: RethinkDnsEndpointRepository,
    private val dnsProxyEndpointRepository: DnsProxyEndpointRepository,
    private val doHEndpointRepository: DoHEndpointRepository,
    private val dnsCryptEndpointRepository: DnsCryptEndpointRepository,
    private val dnsCryptRelayEndpointRepository: DnsCryptRelayEndpointRepository,
    private val doTEndpointRepository: DoTEndpointRepository,
    private val oDoHEndpointRepository: ODoHEndpointRepository,
    private val proxyEndpointRepository: ProxyEndpointRepository,
    private val persistentState: PersistentState
) {
    private val braveModeObserver: MutableLiveData<Int> = MutableLiveData()
    private var customSocks5Endpoint: ProxyEndpoint? = null
    private var customHttpEndpoint: ProxyEndpoint? = null
    private var orbotEndpoint: ProxyEndpoint? = null

    companion object {
        private val connectedDns: MutableLiveData<String> = MutableLiveData()

        private const val ORBOT_DNS = "Orbot"

        const val BOOTSTRAP_DNS_IF_NET_DNS_EMPTY = "9.9.9.9,2620:fe::fe"

    }

    init {
        // now connectedDnsName has the dns name and url, extract the dns name and update
        // csv is <dns-name,url>, url maybe empty
        val dnsName = persistentState.connectedDnsName.split(",").firstOrNull() ?: ""

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            connectedDns.value = dnsName
        } else {
            connectedDns.postValue(dnsName)
        }
    }

    data class TunnelOptions(
        val tunDnsMode: TunDnsMode,
        val tunFirewallMode: TunFirewallMode,
        val tunProxyMode: TunProxyMode,
        val ptMode: ProtoTranslationMode,
        val bridge: Bridge,
        val defaultDns: String,
        val fakeDns: String
    )

    enum class BraveMode(val mode: Int) {
        DNS(0),
        FIREWALL(1),
        DNS_FIREWALL(2);

        fun isFirewallActive(): Boolean {
            return isFirewallMode() || isDnsFirewallMode()
        }

        fun isDnsActive(): Boolean {
            return isDnsMode() || isDnsFirewallMode()
        }

        fun isFirewallMode(): Boolean {
            return mode == FIREWALL.mode
        }

        fun isDnsMode(): Boolean {
            return mode == DNS.mode
        }

        fun isDnsFirewallMode(): Boolean {
            return mode == DNS_FIREWALL.mode
        }

        companion object {
        }
    }

    enum class TunFirewallMode(val mode: Int) {
        FILTER_ANDROID9_ABOVE(Settings.BlockModeFilter),
        SINK(Settings.BlockModeSink),
        FILTER_ANDROID8_BELOW(Settings.BlockModeFilterProc),
        NONE(Settings.BlockModeNone);

        fun isFirewallSinkMode(): Boolean {
            return mode == SINK.mode
        }
    }

    enum class DnsType(val type: Int) {
        // fixme: remove the type RETHINK_REMOTE and merge it with type DOH
        DOH(1),
        DNSCRYPT(2),
        DNS_PROXY(3),
        RETHINK_REMOTE(4),
        SYSTEM_DNS(5),
        DOT(6),
        ODOH(7);

        fun isDnsProxy(): Boolean {
            return this == DNS_PROXY
        }

        fun isSystemDns(): Boolean {
            return this == SYSTEM_DNS
        }

        // encrypted resolvers: a failure here must never silently fall back to system dns
        fun isEncrypted(): Boolean {
            return this == DOH || this == DOT || this == ODOH || this == DNSCRYPT
        }

        fun isValidDnsType(): Boolean {
            return this == DOH ||
                this == DNSCRYPT ||
                this == DNS_PROXY ||
                this == RETHINK_REMOTE ||
                this == SYSTEM_DNS ||
                this == DOT ||
                this == ODOH
        }

        companion object {
            fun getDnsType(id: Int): DnsType {
                return when (id) {
                    DOH.type -> DOH
                    DNSCRYPT.type -> DNSCRYPT
                    DNS_PROXY.type -> DNS_PROXY
                    // legacy RDNS value: no longer selectable, read as system dns
                    RETHINK_REMOTE.type -> SYSTEM_DNS
                    SYSTEM_DNS.type -> SYSTEM_DNS
                    DOT.type -> DOT
                    ODOH.type -> ODOH
                    else -> DOH
                }
            }
        }
    }

    enum class TunDnsMode(val mode: Long) {
        NONE(Settings.DNSModeNone.toLong()),
        DNS_IP(Settings.DNSModeIP.toLong()),
        DNS_PORT(Settings.DNSModePort.toLong())
    }

    // TODO: untangle the mess of proxy modes and providers
    enum class TunProxyMode {
        NONE,
        HTTPS,
        SOCKS5,
        ORBOT,
        TCP,
        WIREGUARD;

        fun isTunProxyOrbot(): Boolean {
            return this == ORBOT
        }

        fun isTunProxyWireguard(): Boolean {
            return this == WIREGUARD
        }

    }

    // Provider - Custom - SOCKS5, Http proxy setup.
    // ORBOT - One touch Orbot integration.
    enum class ProxyProvider {
        NONE,
        CUSTOM,
        ORBOT,
        TCP,
        WIREGUARD;

        fun isProxyProviderCustom(): Boolean {
            return CUSTOM.name == name
        }

        fun isProxyProviderNone(): Boolean {
            return NONE.name == name
        }

        fun isProxyProviderOrbot(): Boolean {
            return ORBOT.name == name
        }

        companion object {
            fun getProxyProvider(name: String): ProxyProvider {
                return when (name) {
                    WIREGUARD.name -> WIREGUARD
                    CUSTOM.name -> CUSTOM
                    ORBOT.name -> ORBOT
                    TCP.name -> TCP
                    else -> NONE
                }
            }
        }
    }

    // Supported Proxy types
    enum class ProxyType {
        NONE,
        HTTP,
        SOCKS5,
        TCP,
        HTTP_SOCKS5,
        WIREGUARD;

        fun isProxyTypeHttp(): Boolean {
            return HTTP.name == name
        }

        fun isProxyTypeSocks5(): Boolean {
            return SOCKS5.name == name
        }

        fun isProxyTypeHttpSocks5(): Boolean {
            return HTTP_SOCKS5.name == name
        }

        fun isProxyTypeTcp(): Boolean {
            return TCP.name == name
        }

        fun isProxyTypeWireguard(): Boolean {
            return WIREGUARD.name == name
        }

        fun isProxyTypeHasHttp(): Boolean {
            return isProxyTypeHttp() || isProxyTypeHttpSocks5()
        }

        fun isSocks5Enabled(): Boolean {
            return isProxyTypeSocks5() || isProxyTypeHttpSocks5()
        }

        fun isAnyProxyEnabled(): Boolean {
            return isProxyTypeHttp() ||
                isProxyTypeSocks5() ||
                isProxyTypeHttpSocks5() ||
                isProxyTypeTcp() ||
                isProxyTypeWireguard()
        }

        companion object {
            fun of(name: String): ProxyType {
                return when (name) {
                    HTTP.name -> HTTP
                    SOCKS5.name -> SOCKS5
                    HTTP_SOCKS5.name -> HTTP_SOCKS5
                    WIREGUARD.name -> WIREGUARD
                    TCP.name -> TCP
                    else -> NONE
                }
            }
        }
    }

    enum class ProtoTranslationMode(val id: Int) {
        PTMODEAUTO(Settings.PtModeAuto),
        PTMODEFORCE64(Settings.PtModeForce64),
        PTMODEFORCE46(Settings.PtModeForce46),
        PTMODEFORCE(Settings.PtModeForce),
        PTMODENONE(Settings.PtModeNone)
    }

    fun getInternetProtocol(): InternetProtocol {
        return getInternetProtocol(persistentState.internetProtocolType)
    }

    fun getProtocolTranslationMode(): ProtoTranslationMode {
        // PT mode is now computed in BraveVPNService.calculatePtMode(),
        // which uses actual underlyingNetworks. This is a fallback, and used only during
        // vpn startup before the first network change event arrives.
        if (!persistentState.protocolTranslationType) {
            return ProtoTranslationMode.PTMODEAUTO
        }
        // default until BraveVPNService receives network info and calls setTunMode()
        return ProtoTranslationMode.PTMODEFORCE
    }

    fun getDnsType(): DnsType {
        return when (persistentState.dnsType) {
            DnsType.DOH.type -> DnsType.DOH
            DnsType.DNSCRYPT.type -> DnsType.DNSCRYPT
            DnsType.DNS_PROXY.type -> DnsType.DNS_PROXY
            // legacy RDNS value: no longer selectable, read as system dns
            DnsType.RETHINK_REMOTE.type -> DnsType.SYSTEM_DNS
            DnsType.SYSTEM_DNS.type -> DnsType.SYSTEM_DNS
            DnsType.DOT.type -> DnsType.DOT
            DnsType.ODOH.type -> DnsType.ODOH
            else -> {
                DnsType.DOH
            }
        }
    }

    private fun getDnsMode(): TunDnsMode {
        return when (persistentState.braveMode) {
            // Case: app mode - firewall, DNS mode should be none.
            BraveMode.FIREWALL.mode -> TunDnsMode.NONE
            BraveMode.DNS.mode,
            BraveMode.DNS_FIREWALL.mode -> determineTunDnsMode()
            else -> {
                TunDnsMode.NONE
            }
        }
    }

    private fun determineTunDnsMode(): TunDnsMode {
        // app mode - DNS & DNS+Firewall mode
        return if (DnsType.getDnsType(persistentState.dnsType).isValidDnsType()) {
            if (persistentState.preventDnsLeaks) {
                TunDnsMode.DNS_PORT
            } else {
                TunDnsMode.DNS_IP
            }
        } else {
            TunDnsMode.NONE
        }
    }

    fun preventDnsLeaks(): Boolean {
        return persistentState.preventDnsLeaks
    }

    fun isDnsProxyActive(): Boolean {
        return DnsType.DNS_PROXY == getDnsType()
    }

    fun getConnectedDnsObservable(): MutableLiveData<String> {
        return connectedDns
    }

    fun getBraveModeObservable(): MutableLiveData<Int> {
        return braveModeObserver
    }

    suspend fun getDOHDetails(): DoHEndpoint? {
        return doHEndpointRepository.getConnectedDoH()
    }

    suspend fun getAllDefaultDoHEndpoints(): List<DoHEndpoint> {
        return doHEndpointRepository.getAllDefaultDoHEndpoints()
    }

    suspend fun getAllDefaultDoTEndpoints(): List<DoTEndpoint> {
        return doTEndpointRepository.getAllDefaultDoTEndpoints()
    }

    suspend fun getDOTDetails(): DoTEndpoint? {
        return doTEndpointRepository.getConnectedDoT()
    }

    suspend fun getODoHDetails(): ODoHEndpoint? {
        return oDoHEndpointRepository.getConnectedODoH()
    }

    suspend fun getSocks5ProxyDetails(): ProxyEndpoint? {
        if (customSocks5Endpoint == null) {
            customSocks5Endpoint = proxyEndpointRepository.getCustomSocks5Endpoint()
        }
        return customSocks5Endpoint
    }

    suspend fun getHttpProxyDetails(): ProxyEndpoint? {
        if (customHttpEndpoint == null) {
            customHttpEndpoint = proxyEndpointRepository.getHttpProxyDetails()
        }
        return customHttpEndpoint
    }

    suspend fun getConnectedOrbotProxy(): ProxyEndpoint? {
        if (orbotEndpoint == null) {
            orbotEndpoint = proxyEndpointRepository.getConnectedOrbotProxy()
        }
        return orbotEndpoint
    }

    suspend fun getOrbotSocks5Endpoint(): ProxyEndpoint? {
        return proxyEndpointRepository.getOrbotSocks5Endpoint()
    }

    suspend fun getOrbotHttpEndpoint(): ProxyEndpoint? {
        return proxyEndpointRepository.getOrbotHttpEndpoint()
    }

    fun isWireGuardEnabled(): Boolean {
        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeWireguard()
    }

    private suspend fun getDNSProxyServerDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getSelectedProxy()
    }

    private suspend fun onDnsChange(dt: DnsType) {
        if (!isValidDnsType(dt)) return

        persistentState.dnsType = dt.type
        when (dt) {
            DnsType.DOH -> {
                val endpoint = getDOHDetails() ?: return

                postConnectedDnsName(endpoint.dohName, endpoint.dohURL)
            }
            DnsType.DOT -> {
                val endpoint = getDOTDetails() ?: return

                postConnectedDnsName(endpoint.name, endpoint.url)
            }
            DnsType.ODOH -> {
                val endpoint = getODoHDetails() ?: return

                postConnectedDnsName(endpoint.name, endpoint.resolver)
            }
            DnsType.DNSCRYPT -> {
                val endpoint = getConnectedDnscryptServer() ?: return
                postConnectedDnsName(endpoint.dnsCryptName, endpoint.dnsCryptURL)
            }
            DnsType.DNS_PROXY -> {
                val endpoint = getDNSProxyServerDetails() ?: return

                val ip = endpoint.proxyIP?.split(",")?.firstOrNull() ?: ""
                val url = ip + ":" + endpoint.proxyPort
                postConnectedDnsName(endpoint.proxyName, url)
            }
            DnsType.RETHINK_REMOTE -> {
                val endpoint = getRemoteRethinkEndpoint() ?: return

                postConnectedDnsName(endpoint.name, endpoint.url)
            }
            DnsType.SYSTEM_DNS -> {
                postConnectedDnsName(context.getString(R.string.network_dns))
            }
        }
    }

    private fun postConnectedDnsName(name: String, url: String = "") {
        connectedDns.postValue(name)
        if (url.isEmpty()) {
            persistentState.connectedDnsName = name
        } else {
            persistentState.connectedDnsName = "$name,$url"
        }
    }

    private fun isValidDnsType(dt: DnsType): Boolean {
        return (dt == DnsType.DOH ||
            dt == DnsType.DNSCRYPT ||
            dt == DnsType.DNS_PROXY ||
            dt == DnsType.RETHINK_REMOTE ||
            dt == DnsType.SYSTEM_DNS ||
            dt == DnsType.DOT ||
            dt == DnsType.ODOH)
    }

    fun changeBraveMode(braveMode: Int) {
        persistentState.braveMode = braveMode
        braveModeObserver.postValue(braveMode)
    }

    fun getBraveMode(): BraveMode {
        return when (persistentState.braveMode) {
            BraveMode.DNS.mode -> BraveMode.DNS
            BraveMode.FIREWALL.mode -> BraveMode.FIREWALL
            BraveMode.DNS_FIREWALL.mode -> BraveMode.DNS_FIREWALL
            else -> BraveMode.DNS_FIREWALL
        }
    }

    fun determineFirewallMode(): TunFirewallMode {
        // app mode - DNS, set the firewall mode as NONE.
        if (persistentState.braveMode == BraveMode.DNS.mode) {
            return TunFirewallMode.NONE
        }

        // app mode - Firewall & DNS+Firewall
        return if (isAtleastQ()) {
            TunFirewallMode.FILTER_ANDROID9_ABOVE
        } else {
            TunFirewallMode.FILTER_ANDROID8_BELOW
        }
    }

    fun newTunnelOptions(
        bridge: Bridge,
        fakeDns: String,
        ptMode: ProtoTranslationMode
    ): TunnelOptions {
        return TunnelOptions(
            getDnsMode(),
            determineFirewallMode(),
            getTunProxyMode(),
            ptMode,
            bridge,
            getDefaultDns(),
            fakeDns
        )
    }

    // -- DNS Manager --
    suspend fun getSelectedDnsProxyDetails(): DnsProxyEndpoint? {
        return dnsProxyEndpointRepository.getSelectedProxy()
    }

    suspend fun getConnectedDnscryptServer(): DnsCryptEndpoint? {
        return dnsCryptEndpointRepository.getConnectedDNSCrypt()
    }

    suspend fun getDnscryptRelayServers(): String {
        return dnsCryptRelayEndpointRepository.getServersToAdd()
    }

    suspend fun getDohCount(): Int {
        return doHEndpointRepository.getCount()
    }

    suspend fun getDoTCount(): Int {
        return doTEndpointRepository.getCount()
    }

    suspend fun getODoHCount(): Int {
        return oDoHEndpointRepository.getCount()
    }

    suspend fun getDnsProxyCount(): Int {
        return dnsProxyEndpointRepository.getCount()
    }

    suspend fun getDnscryptCount(): Int {
        return dnsCryptEndpointRepository.getCount()
    }

    suspend fun getDnscryptRelayCount(): Int {
        return dnsCryptRelayEndpointRepository.getCount()
    }

    suspend fun handleDoHChanges(doHEndpoint: DoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.DOH) {
            removeConnectionStatus()
        }

        doHEndpointRepository.update(doHEndpoint)
        onDnsChange(DnsType.DOH)
    }

    suspend fun handleDoTChanges(doTEndpoint: DoTEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.DOT) {
            removeConnectionStatus()
        }

        val prev = persistentState.connectedDnsName
        doTEndpointRepository.update(doTEndpoint)
        onDnsChange(DnsType.DOT)
    }

    // invoked when the selected ODoH relay changes; re-applies the transport if ODoH is active
    suspend fun onOdohRelayChanged() {
        if (getDnsType() == DnsType.ODOH) {
            onDnsChange(DnsType.ODOH)
        }
    }

    suspend fun handleODoHChanges(oDoHEndpoint: ODoHEndpoint) {
        // if the prev connection was not doh, then remove the connection status from database
        if (getDnsType() != DnsType.ODOH) {
            removeConnectionStatus()
        }

        val prev = persistentState.connectedDnsName
        oDoHEndpointRepository.update(oDoHEndpoint)
        onDnsChange(DnsType.ODOH)
    }

    suspend fun handleDnsProxyChanges(dnsProxyEndpoint: DnsProxyEndpoint) {
        // if the prev connection was not dns proxy, then remove the connection status from database
        if (getDnsType() != DnsType.DNS_PROXY) {
            removeConnectionStatus()
        }

        val prev = persistentState.connectedDnsName
        dnsProxyEndpointRepository.update(dnsProxyEndpoint)
        onDnsChange(DnsType.DNS_PROXY)
    }

    suspend fun isOrbotDns(): Boolean {
        if (!getDnsType().isDnsProxy()) return false

        return dnsProxyEndpointRepository.getSelectedProxy()?.proxyName == ORBOT_DNS
    }

    suspend fun handleDnscryptChanges(dnsCryptEndpoint: DnsCryptEndpoint) {
        // if the prev connection was not dnscrypt, then remove the connection status from database
        if (getDnsType() != DnsType.DNSCRYPT) {
            removeConnectionStatus()
        }

        val prev = persistentState.connectedDnsName
        dnsCryptEndpointRepository.update(dnsCryptEndpoint)
        onDnsChange(DnsType.DNSCRYPT)
    }

    suspend fun handleDnsrelayChanges(endpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.update(endpoint)
        persistentState.dnsCryptRelays.postValue(
            PersistentState.DnsCryptRelayDetails(endpoint, endpoint.isSelected)
        )

    }

    suspend fun removeDnscryptRelay(stamp: String) {
        dnsCryptRelayEndpointRepository.unselectRelay(stamp)
    }

    fun getDefaultDns(): String {
        return persistentState.defaultDnsUrl
    }

    suspend fun getRemoteRethinkEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointRepository.getConnectedEndpoint()
    }

    /**
     * Fallback used when a conflicting option (system DNS / DNS proxy) has to be turned off:
     * switch to a default DoH endpoint, preferring one that is not RethinkDNS-branded.
     */
    suspend fun enableDefaultDoh() {
        val candidates = doHEndpointRepository.getAllDefaultDoHEndpoints()
        val endpoint =
            candidates.firstOrNull { !it.dohName.contains("rethink", ignoreCase = true) }
                ?: candidates.firstOrNull()
                ?: return
        endpoint.isSelected = true
        handleDoHChanges(endpoint)
    }

    suspend fun enableSystemDns() {
        if (getDnsType() != DnsType.SYSTEM_DNS) {
            removeConnectionStatus()
        }

        val prev = persistentState.connectedDnsName
        onDnsChange(DnsType.SYSTEM_DNS)
    }

    fun isSystemDns(): Boolean {
        return getDnsType().isSystemDns()
    }

    suspend fun isDnscryptRelaySelectable(): Boolean {
        return dnsCryptEndpointRepository.getConnectedCount() >= 1
    }

    suspend fun isAppWiseDnsEnabled(uid: Int): Boolean {
        return rethinkDnsEndpointRepository.isAppWiseDnsEnabled(uid)
    }

    private suspend fun removeConnectionStatus() {
        when (getDnsType()) {
            DnsType.DOH -> {
                doHEndpointRepository.removeConnectionStatus()
            }
            DnsType.DOT -> {
                doTEndpointRepository.removeConnectionStatus()
            }
            DnsType.ODOH -> {
                oDoHEndpointRepository.removeConnectionStatus()
            }
            DnsType.DNSCRYPT -> {
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
            }
            DnsType.DNS_PROXY -> {
                dnsProxyEndpointRepository.removeConnectionStatus()
            }
            DnsType.RETHINK_REMOTE -> {
                rethinkDnsEndpointRepository.removeConnectionStatus()
            }
            DnsType.SYSTEM_DNS -> {
                // no-op, no need to remove connection status
            }
        }
    }

    suspend fun removeAppWiseDns(uid: Int) {
        rethinkDnsEndpointRepository.removeAppWiseDns(uid)
    }

    suspend fun insertDnscryptRelayEndpoint(endpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDnscryptEndpoint(endpoint: DnsCryptEndpoint) {
        dnsCryptEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDohEndpoint(endpoint: DoHEndpoint) {
        doHEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDoTEndpoint(endpoint: DoTEndpoint) {
        doTEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertODoHEndpoint(endpoint: ODoHEndpoint) {
        oDoHEndpointRepository.insertAsync(endpoint)
    }

    suspend fun insertDnsproxyEndpoint(endpoint: DnsProxyEndpoint) {
        dnsProxyEndpointRepository.insertAsync(endpoint)
    }

    suspend fun deleteDohEndpoint(id: Int) {
        doHEndpointRepository.deleteDoHEndpoint(id)
    }

    suspend fun deleteDoTEndpoint(id: Int) {
        doTEndpointRepository.deleteDoTEndpoint(id)
    }

    suspend fun deleteODoHEndpoint(id: Int) {
        oDoHEndpointRepository.deleteODoHEndpoint(id)
    }

    suspend fun deleteDnsProxyEndpoint(id: Int) {
        dnsProxyEndpointRepository.deleteDnsProxyEndpoint(id)
    }

    suspend fun deleteDnscryptEndpoint(id: Int) {
        dnsCryptEndpointRepository.deleteDNSCryptEndpoint(id)
    }

    suspend fun deleteDnscryptRelayEndpoint(id: Int) {
        dnsCryptRelayEndpointRepository.deleteDnsCryptRelayEndpoint(id)
    }

    // -- Proxy Manager --

    fun addProxy(proxyType: ProxyType, provider: ProxyProvider) {
        // When there is a request of add with proxy type or provider as none
        // then remove all the proxies.
        if (proxyType == ProxyType.NONE || provider == ProxyProvider.NONE) {
            removeAllProxies()
            return
        }

        if (provider == ProxyProvider.WIREGUARD) {
            setProxy(proxyType, provider)
            return
        }

        if (provider == ProxyProvider.ORBOT) {
            setProxy(proxyType, provider)
            return
        }

        if (provider == ProxyProvider.TCP) {
            setProxy(proxyType, provider)
            return
        }

        // If add proxy request is custom proxy (either http/socks5), check if the other
        // proxy is already set. if yes, then make the proxy type as HTTP_SOCKS5.
        val currentProxyType = ProxyType.of(getProxyType())
        if (proxyType.isProxyTypeHttp()) {
            if (currentProxyType.isProxyTypeSocks5()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.HTTP, provider)
            return
        }

        if (proxyType.isProxyTypeSocks5()) {
            if (currentProxyType.isProxyTypeHttp()) {
                setProxy(ProxyType.HTTP_SOCKS5, provider)
                return
            }
            setProxy(ProxyType.SOCKS5, provider)
            return
        }
    }

    fun removeAllProxies() {
        removeOrbot()
        persistentState.proxyProvider = ProxyProvider.NONE.name
        persistentState.proxyType = ProxyType.NONE.name
        persistentState.updateProxyStatus()
    }

    private fun removeOrbot() {
        OrbotHelper.selectedProxyType = ProxyType.NONE.name
    }

    fun removeProxy(removeType: ProxyType, removeProvider: ProxyProvider) {
        val currentProxyType = ProxyType.of(getProxyType())

        if (currentProxyType.isProxyTypeHttpSocks5()) {
            if (removeType.isProxyTypeHttp()) {
                setProxy(ProxyType.SOCKS5, removeProvider)
                return
            } else if (removeType.isProxyTypeSocks5()) {
                setProxy(ProxyType.HTTP, removeProvider)
                return
            } else {
            }
        } else {
            removeAllProxies()
        }
    }

    fun getProxyType(): String {
        return persistentState.proxyType
    }

    fun getProxyProvider(): String {
        return persistentState.proxyProvider
    }

    private fun setProxy(type: ProxyType, provider: ProxyProvider) {
        persistentState.proxyProvider = provider.name
        persistentState.proxyType = type.name
        persistentState.updateProxyStatus()
    }

    // Returns the proxymode to set in Tunnel.
    // Settings.ProxyModeNone
    // Settings.ProxyModeSOCKS5
    // Settings.ProxyModeHTTPS
    fun getTunProxyMode(): TunProxyMode {
        val type = persistentState.proxyType
        val provider = persistentState.proxyProvider

        if (ProxyProvider.WIREGUARD.name == provider) {
            return TunProxyMode.WIREGUARD
        }

        if (ProxyProvider.ORBOT.name == provider) {
            return TunProxyMode.ORBOT
        }

        when (type) {
            ProxyType.HTTP.name -> {
                return TunProxyMode.HTTPS
            }
            ProxyType.SOCKS5.name -> {
                return TunProxyMode.SOCKS5
            }
            ProxyType.HTTP_SOCKS5.name -> {
                // FIXME: tunnel does not support both http and socks5 at once.
                return TunProxyMode.SOCKS5
            }
        }
        return TunProxyMode.NONE
    }

    fun isCustomHttpProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        // return false if the proxy provider is not custom
        if (!proxyProvider.isProxyProviderCustom()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeHttp() || proxyType.isProxyTypeHttpSocks5()
    }

    fun isCustomSocks5Enabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        // return false if the proxy provider is not custom
        if (!proxyProvider.isProxyProviderCustom()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isProxyTypeSocks5() || proxyType.isProxyTypeHttpSocks5()
    }

    fun isOrbotProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return proxyProvider.isProxyProviderOrbot()
    }

    fun isProxyEnabled(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        if (proxyProvider.isProxyProviderNone()) return false

        val proxyType = ProxyType.of(persistentState.proxyType)
        return proxyType.isAnyProxyEnabled()
    }

    fun canEnableProxy(): Boolean {
        return !getBraveMode().isDnsMode()
    }

    fun canEnableSocks5Proxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableHttpProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return !getBraveMode().isDnsMode() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderCustom())
    }

    fun canEnableOrbotProxy(): Boolean {
        val proxyProvider = ProxyProvider.getProxyProvider(persistentState.proxyProvider)
        return canEnableProxy() &&
            (proxyProvider.isProxyProviderNone() || proxyProvider.isProxyProviderOrbot())
    }

    suspend fun getConnectedSocks5Proxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedSocks5Proxy()
    }

    suspend fun getConnectedHttpProxy(): ProxyEndpoint? {
        return proxyEndpointRepository.getConnectedHttpProxy()
    }

    suspend fun updateCustomSocks5Proxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        customSocks5Endpoint = proxyEndpoint
        addProxy(ProxyType.SOCKS5, ProxyProvider.CUSTOM)
    }

    suspend fun updateOrbotProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        orbotEndpoint = proxyEndpoint
    }

    suspend fun updateCustomHttpProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
        customHttpEndpoint = proxyEndpoint
        addProxy(ProxyType.HTTP, ProxyProvider.CUSTOM)
    }

    suspend fun updateOrbotHttpProxy(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointRepository.update(proxyEndpoint)
    }

    fun stats(): String {
        val sb = StringBuilder()
        sb.append("   App version: ${persistentState.appVersion}\n")
        sb.append("   Brave mode: ${getBraveMode()}\n")

        sb.append("DNS \n")
        sb.append("   dns type: ${getDnsType()}\n")
        sb.append("   preferred: ${persistentState.connectedDnsName}\n")
        sb.append("   alg: ${persistentState.enableDnsAlg}\n")
        sb.append("   split: ${persistentState.splitDns}\n")
        sb.append("   local blocklist enabled: ${persistentState.blocklistEnabled}\n")
        sb.append("   local blocklist stamp: ${persistentState.localBlocklistStamp}\n")
        sb.append("   dns booster: ${persistentState.enableDnsCache}\n")
        sb.append("   never proxy dns: ${!persistentState.proxyDns}\n")
        sb.append("   prevent dns leaks: ${persistentState.preventDnsLeaks}\n")
        sb.append("   block dns from unknown src: ${persistentState.blockDnsForUnknownApp}\n")
        sb.append("   use sys dns for undelegated domains: ${persistentState.useSystemDnsForUndelegatedDomains}\n")
        sb.append("   bypass-dns-mode: ${BlockFreeDnsMode.fromMode(persistentState.blockFreeDnsMode).name}\n")

        sb.append("Proxy \n")
        sb.append("   Proxy type: ${ProxyType.of(getProxyType()).name}\n")
        sb.append("   Proxy provider: ${getProxyProvider()}\n")

        sb.append("VPN \n")
        sb.append("   stall on nw loss: ${persistentState.stallOnNoNetwork}\n")
        sb.append("   do not route private ips: ${!persistentState.privateIps}\n")
        sb.append("   use all available nws: ${persistentState.useMultipleNetworks}\n")
        sb.append("   vpn tun metered? ${persistentState.setVpnBuilderToMetered}\n")
        sb.append("   meter mobile nw: ${persistentState.treatOnlyMobileNetworkAsMetered}\n")
        sb.append("   loopback? ${persistentState.routeRethinkInRethink}\n")
        sb.append("   bootstrap dns: ${persistentState.defaultDnsUrl}\n")
        sb.append("   conn policy: ${persistentState.vpnBuilderPolicy}\n")
        sb.append("   loopback proxy fwdr apps: ${!persistentState.excludeAppsInProxy}\n")
        sb.append("   randomize wg port: ${persistentState.randomizeListenPort}\n")
        sb.append("   global proxy lockdown: ${persistentState.wgGlobalLockdown}\n")
        sb.append("   flood wg: ${persistentState.floodWireGuard}\n")
        sb.append("   persistent keepalive: ${persistentState.smartPersistentKeepalive}\n")
        sb.append("   shorter TCP keepalive: ${persistentState.tcpKeepAlive}\n")
        sb.append("   endpoint independent mapping: ${persistentState.endpointIndependence}\n")
        sb.append("   idle timeout: ${persistentState.dialTimeoutSec}\n")
        sb.append("   bandwidth booster: ${persistentState.useMaxMtu}\n")
        sb.append("   socket buffer sz: ${persistentState.socketBufferSizeBytes}\n")
        sb.append("   internet protocol: ${getInternetProtocol()}\n")
        sb.append("   protocol translation mode: ${getProtocolTranslationMode()}\n")
        sb.append("   remember uninstalled apps: ${persistentState.tombstoneApps}\n")

        return sb.toString()
    }
}
