/*
 * Copyright 2021 RethinkDNS and its authors
 * Copyright 2019 Jigsaw Operations LLC
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
package com.celzero.bravedns.net.go

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.VpnService.NOTIFICATION_SERVICE
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.BOOTSTRAP_DNS_IF_NET_DNS_EMPTY
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.service.GlobalProxyHandler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.AntiCensorshipActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.Client
import com.celzero.firestack.backend.DNSResolver
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.Proxies
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.intra.Controller
import com.celzero.firestack.intra.DefaultDNS
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Tunnel
import com.celzero.firestack.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI
import java.net.URLEncoder

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel
    private var context: Context
    private var externalScope: CoroutineScope

    constructor(
        context: Context,
        externalScope: CoroutineScope,
        tunFd: Long,
        ifaceAddresses: String,
        mtu: Int,
        nwMtu: Int,
        opts: TunnelOptions) {
        this.context = context
        this.externalScope = externalScope
        val defaultDns = newDefaultTransport(appConfig.getDefaultDns())
        tunnel =
            Intra.connect(
                tunFd,
                nwMtu.toLong(),
                mtu.toLong(),
                ifaceAddresses,
                opts.fakeDns,
                defaultDns,
                opts.bridge
            ) // may throw exception
        setTunMode(opts)
    }

    suspend fun initResolverProxiesPcap(opts: TunnelOptions) {
        if (!tunnel.isConnected) {
            return
        }

        // no need to add default in init as it is already added in connect
        // addDefaultTransport(appConfig.getDefaultDns())
        // TODO: ideally the values required for transport, alg and rdns should be set in the
        // opts itself.
        setRDNS()
        addTransport()
        setWireguardTunnelModeIfNeeded(opts.tunProxyMode)
        setSocks5TunnelModeIfNeeded(opts.tunProxyMode)
        setHttpProxyIfNeeded(opts.tunProxyMode)
        setDnsAlg()
        notifyLoopback()
        setDialStrategy()
        setTransparency()
        undelegatedDomains()
        setExperimentalWireGuardSettings()
        setAutoDialsParallel()
        setAutoMode()
        setFloodWgMode()
        onLowMemory()
    }

    suspend fun addTransport() {
        if (!tunnel.isConnected) {
            return
        }

        // TODO: #321 As of now there is no block free transport for dns types other than Rethink.
        // introduce block free transport for other dns types when there is a UI to configure the
        // block free transport
        // always set the block free transport before setting the transport to the resolver
        // because of the way the alg is implemented in the go code.

        // TODO: should show notification if the transport is not added?
        // now it will fallback to rethink default if the transport is not available in kt to add
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                addDohTransport(Backend.Preferred)
            }

            AppConfig.DnsType.DOT -> {
                addDotTransport(Backend.Preferred)
            }

            AppConfig.DnsType.ODOH -> {
                addOdohTransport(Backend.Preferred)
            }

            AppConfig.DnsType.DNSCRYPT -> {
                addDnscryptTransport(Backend.Preferred)
            }

            AppConfig.DnsType.DNS_PROXY -> {
                addDnsProxyTransport(Backend.Preferred)
            }

            AppConfig.DnsType.SYSTEM_DNS -> {
                // no-op; system dns propagated by ConnectionMonitor
            }

            AppConfig.DnsType.RETHINK_REMOTE -> {
                // legacy RDNS type, unreachable: getDnsType() maps it to SYSTEM_DNS
            }
        }

        // only in rethink's remote dns, we have block free transport, so need to remove the
        // block free transport if the selected dns type is not rethink remote, to avoid having
        // stale block free transport when user switches from rethink remote to other dns type.
        // for other dns types, the block free transport is not added at all, so no need to remove.
        removeBlockFreeTransportIfNeeded()
    }

    // previously this silently switched the user's selection to system dns. when an encrypted
    // dns is selected, do not do that: leave the selection as is, add no transport, and surface
    // the failure. the tunnel then cannot resolve through the encrypted dns (fail closed).
    private suspend fun failClosedDns(reason: String = "transport unavailable") {
        val type = appConfig.getDnsType()
        if (!type.isEncrypted()) {
            return
        }
        showDnsFailureNotification(
            context.getString(R.string.other_dns_list_tab1),
            context.getString(R.string.system_dns_connection_failure)
        )
    }

    // TODO: remove below method when there is new UI for blockfree transport
    private suspend fun removeBlockFreeTransportIfNeeded() {
        if (appConfig.getDnsType() != AppConfig.DnsType.RETHINK_REMOTE) {
            removeResolver(Backend.BlockFree)
        }
    }

    private suspend fun removeResolver(id: String): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        return try {
            val res = getResolver()?.remove(id) ?: false
            res
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun addDohTransport(id: String) {
        var url: String? = null
        try {
            val doh = appConfig.getDOHDetails()
            if (doh == null || doh.dohURL.isEmpty()) {
                failClosedDns()
                return
            }
            url = doh.dohURL
            val ips: String = getIpString(context, url)
            if (!doh.isSecure || !url.startsWith("https://", ignoreCase = true)) {
                failClosedDns()
                return
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoHTransport(tunnel, id, url, ips)
        } catch (e: Exception) {
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.other_dns_list_tab1), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(url.orEmpty())
        }
    }

    private suspend fun addDotTransport(id: String) {
        var url: String? = null
        try {
            val dot = appConfig.getDOTDetails()
            if (dot == null || dot.url.isEmpty()) {
                failClosedDns()
                return
            }
            url = dot.url
            // if tls is present, remove it and pass it to getIpString
            val ips: String = getIpString(context, url.replace("tls://", ""))
            if (dot.isSecure && !url.startsWith("tls")) {
                // add tls to the url if isSecure is true and the url does not start with tls
                url = "tls://$url"
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoTTransport(tunnel, id, url, ips)
        } catch (e: Exception) {
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.lbl_dot), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(url.orEmpty())
        }
    }

    private suspend fun addOdohTransport(id: String) {
        var resolver: String? = null
        try {
            val odoh = appConfig.getODoHDetails()
            if (odoh == null || odoh.resolver.isEmpty()) {
                failClosedDns()
                return
            }
            // a target may carry its own relay (older entries); otherwise use the selected relay
            val proxy = odoh.proxy.ifEmpty { persistentState.odohSelectedRelay }
            if (proxy.isEmpty()) {
            }
            resolver = odoh.resolver
            val proxyIps = ""
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addODoHTransport(tunnel, id, proxy, resolver, proxyIps)
        } catch (e: Exception) {
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.lbl_odoh), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(resolver.orEmpty())
        }
    }

    private suspend fun addDnscryptTransport(id: String) {
        try {
            val dc = appConfig.getConnectedDnscryptServer()
            if (dc == null || dc.dnsCryptURL.isEmpty()) {
                failClosedDns()
                return
            }
            val url = dc.dnsCryptURL
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSCryptTransport(tunnel, id, url)
            setDnscryptRelaysIfAny() // is expected to catch exceptions
        } catch (e: Exception) {
            removeResolver(id)
            removeDnscryptRelaysIfAny()
            showDnsFailureNotification(context.getString(R.string.dc_dns_crypt), e.message ?: context.getString(R.string.dns_crypt_connection_failure))
            showDnscryptConnectionFailureToast()
        }
    }

    private suspend fun addDnsProxyTransport(id: String) {
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails()
            if (dnsProxy == null || dnsProxy.proxyIP.isNullOrEmpty()) {
                failClosedDns()
                return
            }
            // append port number to each ip if multiple ips are present else add the single ip:port
            val port = dnsProxy.proxyPort
            val ipPortCsv = dnsProxy.proxyIP?.split(",")?.joinToString(",") {
                "${it.trim()}:$port"
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSProxy(tunnel, id, ipPortCsv)
        } catch (e: Exception) {
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.dc_dns_proxy), e.message ?: context.getString(R.string.dns_proxy_connection_failure))
            showDnsProxyConnectionFailureToast()
        }
    }

    private fun showDnsFailureNotification(dnsType: String, message: String) {
        val notifChannelId = "DNS_failure_channel"
        ui {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (isAtleastO()) {
                val channelName = context.getString(R.string.status_dns_error)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(notifChannelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    context,
                    Intent(context, AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )
            val dnsErrorTxt = context.getString(R.string.status_dns_error)
            // capitalize first 3 letter of the error message (dns error => DNS Error)
            val formattedErrTxt =
                if (dnsErrorTxt.length >= 5) {
                    dnsErrorTxt.replaceRange(0, 5, dnsErrorTxt.substring(0, 5).uppercase())
                } else {
                    dnsErrorTxt
                }
            val title = context.getString(R.string.two_argument_colon, formattedErrTxt, dnsType)
            val builder =
                NotificationCompat.Builder(context, notifChannelId)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            builder.color = ContextCompat.getColor(context, getAccentColor(persistentState.theme))
            notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        }
    }

    suspend fun unlink() {
        if (!tunnel.isConnected) {
            return
        }
        try {
            tunnel.unlink()
        } catch (e: Exception) {
        }
    }

    fun setTunMode(tunnelOptionsWithoutMtu: TunnelOptions) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Settings.setTunMode(
                tunnelOptionsWithoutMtu.tunDnsMode.mode.toInt(),
                tunnelOptionsWithoutMtu.tunFirewallMode.mode,
                tunnelOptionsWithoutMtu.ptMode.id
            )
        } catch (e: Exception) {
        }
    }

    suspend fun setRDNS() {
        // Set brave dns to tunnel - local blocklist

        // enable local blocklist if enabled
        if (persistentState.blocklistEnabled) {
            setRDNSLocal()
        } else {
            try {
                // remove local blocklist, if any
                getResolver()?.setRdnsLocal(null, null, null, null)
            } catch (_: Exception) { }
        }
    }

    private suspend fun setDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            try {
                Intra.addDNSCryptRelay(tunnel, it) // entire url is the id
            } catch (ex: Exception) {
                appConfig.removeDnscryptRelay(it)
                removeResolver(it)
            }
        }
    }

    private suspend fun removeDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            // remove from appConfig, as this is not from ui, but from the tunnel start up
            appConfig.removeDnscryptRelay(it)
            removeResolver(it)
        }
    }

    suspend fun addDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Intra.addDNSCryptRelay(tunnel, relay.dnsCryptRelayURL)
        } catch (e: Exception) {
            appConfig.removeDnscryptRelay(relay.dnsCryptRelayURL)
            removeResolver(relay.dnsCryptRelayURL)
        }
    }

    suspend fun removeDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // no need to remove from appConfig, as it is already removed
            val rmv = getResolver()?.remove(relay.dnsCryptRelayURL)
        } catch (e: Exception) {
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and other parameters. Return
     * the tunnel to the adapter.
     */
    private suspend fun setSocks5Proxy(
        tunProxyMode: AppConfig.TunProxyMode,
        userName: String?,
        password: String?,
        ipAddress: String?,
        port: Int
    ) {
        try {
            val url = constructSocks5ProxyUrl(userName, password, ipAddress, port)
            if (url.isEmpty()) {
                return
            }
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    ProxyManager.ID_S5_BASE
                }
            val res = getProxies()?.addProxy(id, url)
            // track the add so that the global proxy handler can re-add if it goes missing
            GlobalProxyHandler.track(id)
        } catch (e: Exception) {
        }
    }

    private fun constructSocks5ProxyUrl(
        userName: String?,
        password: String?,
        ipAddress: String?,
        port: Int
    ): String {
        try {
            // socks5://<username>:<password>@<ip>:<port>
            // convert string to url
            val proxyUrl = StringBuilder()
            proxyUrl.append("socks5://")
            if (!userName.isNullOrEmpty()) {
                val encUser = URLEncoder.encode(userName, Charsets.UTF_8.name())
                proxyUrl.append(encUser)
                if (!password.isNullOrEmpty()) {
                    val encPass = URLEncoder.encode(password, Charsets.UTF_8.name())
                    proxyUrl.append(":")
                    proxyUrl.append(encPass)
                }
                proxyUrl.append("@")
            }
            proxyUrl.append(ipAddress)
            proxyUrl.append(":")
            proxyUrl.append(port)
            return URI.create(proxyUrl.toString()).toASCIIString()
        } catch (e: Exception) {
        }
        return ""
    }

    private fun showDnscryptConnectionFailureToast() {
        ui {
            showToastUiCentered(
                context.applicationContext,
                context.getString(R.string.dns_crypt_connection_failure),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun showDnsFailureToast(url: String) {
        ui {
            val msg =
                context.getString(
                    R.string.two_argument_colon,
                    url,
                    context.getString(R.string.dns_proxy_connection_failure)
                )
            showToastUiCentered(context.applicationContext, msg, Toast.LENGTH_LONG)
        }
    }

    private fun showWireguardFailureToast(message: String) {
        ui { showToastUiCentered(context.applicationContext, message, Toast.LENGTH_LONG) }
    }

    private fun showDnsProxyConnectionFailureToast() {
        ui {
            showToastUiCentered(
                context.applicationContext,
                context.getString(R.string.dns_proxy_connection_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private suspend fun setWireguardTunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxyWireguard()) return

        val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
        if (wgConfigs.isEmpty()) {
            return
        }
        // re-order the configs so that hops are added first
        val hops = WgHopManager.getAllHop()
        // separate the id, hop has WG1 where id is 1
        val hopIds = hops.map { it.substring(ID_WG_BASE.length).toIntOrNull() }
        val hopConfigs = wgConfigs.filter { it.getId() in hopIds }
        val nonHopConfigs = wgConfigs.filter { it.getId() !in hopIds }
        // add hop wireguard first
        hopConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            addWgProxy(id)
        }
        nonHopConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            addWgProxy(id)
        }
    }

    private suspend fun addHopIfAny(origin: String) {
        // assumption: by this time the add call should have happened
        val hop = WgHopManager.getHop(origin)
        if (hop.isEmpty()) {
            return
        }
        try {
            tunnel.proxies.hop(hop, origin)
        } catch (e: Exception) {
            showHopFailureNotification(origin, hop, err = e.message)
        }
    }

    suspend fun hopStatus(src: String, hop: String): Pair<Int?, String> {
        return try {
            val status = tunnel.proxies.getProxy(src).router().via().status()
            Pair(status, "")
        } catch (e: Exception) {
            Pair(null, e.message ?: "failure")
        }
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        var err = ""
        try {
            tunnel.proxies.hop("", src)
            return Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            err = e.message ?: "err removing hop"
        }
        return Pair(false, err)
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        // returns empty on success, err msg on failure
        val s = tunnel.proxies.testHop(hop, src)
        val res = s.isNullOrEmpty()
        val err = s
        if (!res) {
        } else {
        }
        return Pair(res, err)
    }

    private fun showHopFailureNotification(src: String, hop: String, err: String? = "") {
        val notifChannelId = "hop_failure_channel"
        ui {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (isAtleastO()) {
                val channelName = context.getString(R.string.hop_failure_notification_title)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(notifChannelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            }
            val msg = context.getString(
                R.string.hop_failure_toast,
                src,
                hop
            ) + if (!err.isNullOrEmpty()) " ($err)" else ""
            showToastUiCentered(context.applicationContext, msg, Toast.LENGTH_LONG)
            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    context,
                    Intent(context, AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )
            val builder =
                NotificationCompat.Builder(context, notifChannelId)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(msg)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            builder.color = ContextCompat.getColor(context, getAccentColor(persistentState.theme))
            notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        }
    }

    private suspend fun setWireGuardDns(id: String) {
        try {
            val p = getProxies()?.getProxy(id)
            if (p == null) {
                return
            }
            Intra.addProxyDNS(tunnel, p) // dns transport has same id as the proxy (p)
        } catch (e: Exception) {
            removeResolver(id)
            showDnsFailureToast(id)
        }
    }

    suspend fun setCustomProxy(tunProxyMode: AppConfig.TunProxyMode) {
        setSocks5TunnelModeIfNeeded(tunProxyMode)
        setHttpProxyIfNeeded(tunProxyMode)
    }

    // Re-add the custom/Orbot proxies from the current app config. Used by the
    // global proxy handler when these proxies go missing from the tunnel.
    suspend fun readdCustomProxy() {
        setCustomProxy(appConfig.getTunProxyMode())
    }

    suspend fun readdSocks5Proxy() {
        setSocks5TunnelModeIfNeeded(appConfig.getTunProxyMode())
    }

    suspend fun readdHttpProxy() {
        setHttpProxyIfNeeded(appConfig.getTunProxyMode())
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        val socksEnabled = AppConfig.ProxyType.of(appConfig.getProxyType()).isSocks5Enabled()
        if (!socksEnabled) return

        val socks5: ProxyEndpoint? =
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.getConnectedOrbotProxy()
            } else {
                appConfig.getSocks5ProxyDetails()
            }
        if (socks5 == null) {
            return
        }
        setSocks5Proxy(
            tunProxyMode,
            socks5.userName,
            socks5.password,
            socks5.proxyIP,
            socks5.proxyPort
        )
    }

    suspend fun getProxyStatusById(id: String): Pair<Int?, String> {
        return try {
            val status = getProxies()?.getProxy(id)?.status()
            Pair(status, "")
        } catch (ex: Exception) {
            Pair(null, ex.message.orEmpty())
        }
    }

    suspend fun getProxyAddrById(id: String): String? {
        return try {
            getProxies()?.getProxy(id)?.addr
        } catch (ex: Exception) {
            null
        }
    }

    private suspend fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!AppConfig.ProxyType.of(appConfig.getProxyType()).isProxyTypeHasHttp()) return

        try {
            val endpoint: ProxyEndpoint
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    val orbotEndpoint = appConfig.getOrbotHttpEndpoint()
                    if (orbotEndpoint == null) {
                        return
                    }
                    endpoint = orbotEndpoint
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    val httpEndpoint = appConfig.getHttpProxyDetails()
                    if (httpEndpoint == null) {
                        return
                    }
                    endpoint = httpEndpoint
                    ProxyManager.ID_HTTP_BASE
                }
            val httpProxyUrl = endpoint.proxyIP ?: return

            val p = getProxies()?.addProxy(id, httpProxyUrl)
            // track the add so that the global proxy handler can re-add if it goes missing
            GlobalProxyHandler.track(id)
        } catch (e: Exception) {
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.ORBOT)
            } else {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            }
        }
    }

    suspend fun removeWgProxy(id: Int) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            val wgId = ID_WG_BASE + id
            getProxies()?.removeProxy(wgId)
            // the proxy is no longer in the tunnel, do not re-add it
            GlobalProxyHandler.untrack(wgId)
            removeResolver(wgId)
        } catch (e: Exception) {
        }
    }

    suspend fun addWgProxy(id: String, force: Boolean = false) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            val proxyId: Int? = id.substring(ID_WG_BASE.length).toIntOrNull()
            if (proxyId == null) {
                return
            }
            try {
                if (!force && getProxies()?.getProxy(id) != null) {
                    return
                }
            } catch (_: Exception) {
            }

            val wgConfig = WireguardManager.getConfigById(proxyId)
            val isOneWg = WireguardManager.getOneWireGuardProxyId() == proxyId
            if (!isOneWg) checkAndAddProxyForHopIfNeeded(id)
            val skipListenPort = !isOneWg && persistentState.randomizeListenPort
            val wgUserSpaceString = wgConfig?.toWgUserspaceString(skipListenPort)
            // track the add so that the global proxy handler can re-add if it goes missing
            GlobalProxyHandler.track(id)
            val p = getProxies()?.addProxy(id, wgUserSpaceString)
        } catch (e: Exception) {
            // do not auto remove failed wg proxy, let the user decide hop UI
            showWireguardFailureToast(
                e.message ?: context.getString(R.string.wireguard_connection_error)
            )
        }
    }

    suspend fun handleOnWgAdded(id: String) {
        setWireGuardDns(id)
        // initiate a ping request to the wg proxy
        initiateWgPing(id)
        addHopIfAny(id)
    }

    private suspend fun checkAndAddProxyForHopIfNeeded(id: String) {
        // see if there is hop for this proxy, start the hop-wg if not started
        try {
            val hop = WgHopManager.getHop(id)
            val hopId = hop.substring(ID_WG_BASE.length).toIntOrNull()
            if (hopId == null) { // hop can be empty, in that case, return
                return
            }
            val config = WireguardManager.getConfigFilesById(hopId)
            if (config == null) {
                return
            }
            // this will enable the config and initiate the add proxy to the tunnel
            WireguardManager.enableConfig(config)
        } catch (_: Exception) {}
    }

    suspend fun closeConnections(connIds: List<String>, isUid: Boolean = false, reason: String) {
        if (!tunnel.isConnected) {
            return
        }

        if (connIds.isEmpty()) {
            val res = tunnel.closeConns("") // closes all connections
            return
        }

        val connIdsStr = connIds.joinToString(",")
        // there maybe chance that the res can be empty, when the conns are already closed
        val res = tunnel.closeConns(connIdsStr)
        val closedConns = res.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // connIds not present in the result were either closed already, or closed when the
        // tunnel was disconnected; nothing else to do for them.
        val diff = connIds.filter { !closedConns.contains(it) }
        if (diff.isNotEmpty()) {
            if (!isUid) {
            }
        }
    }

    private suspend fun refreshWgProxy(id: String) {
        if (!tunnel.isConnected) {
            return
        }

        try {
            getProxies()?.getProxy(id)?.refresh()
        } catch (e: Exception) {
        }
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies(isMobileActive: Boolean, ssid: String) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // refresh proxies should never return error/exception
            val res = getProxies()?.refreshProxies()
            // re-add the proxies if the not available in the tunnel
            val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
            if (wgConfigs.isEmpty()) {
                return
            }
            // first, refresh or re-add the proxies that are missing or in a degraded state
            refreshOrReAddProxies(wgConfigs)
            // then, pause or resume the proxies based on the mobile/ssid conditions
            // mostly this should be no-op, as the proxies are already paused/resumed from
            // NetworkLifecycleObserver's callbacks
            pauseAndResumeProxies(wgConfigs, isMobileActive, ssid)
        } catch (e: Exception) {
        }
    }

    /**
     * Refreshes or re-adds the wireguard proxies that are missing (null) or in a
     * degraded state (TNT: proxy UP but not responding).
     *
     * Encapsulates the refresh/re-add half of [refreshOrPauseOrResumeOrReAddProxies].
     */
    private suspend fun refreshOrReAddProxies(wgConfigs: List<Config>) {
        val avoidReaddingProxies = true
        // re-add wireguard proxies in case of failure, consider proxy stats TNT as a failure
        // TNT means proxy UP but not responding
        wgConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            val stats = getProxyStatusById(id).first
            if (stats == null) {
                addWgProxy(id, true)
            } else if (stats == Backend.TNT) {
                // there are cases where the proxy needs to be re-added, so pingOrReAddProxy
                // case: some of the wg proxies are added to tunnel but erring out, so
                // re-adding those proxies seems working, work around for now until the
                // re-add logic is handled in go-tun
                // (github.com/celzero/firestack/blob/61187f88c1/intra/ipn/wgproxy.go#L404)
                if (avoidReaddingProxies) {
                    refreshWgProxy(id)
                } else {
                    addWgProxy(id, true)
                }
            }
        }
    }

    /**
     * Pauses or resumes the wireguard proxies based on the active network state
     * (mobile data active / current ssid).
     *
     * Encapsulates the pause/resume half of [refreshOrPauseOrResumeOrReAddProxies].
     */
    suspend fun pauseAndResumeProxies(
        wgConfigs: List<Config>,
        isMobileActive: Boolean,
        ssid: String
    ) {
        wgConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            val files = WireguardManager.getConfigFilesById(it.getId())
            // if the proxy is one-wg then mobile-only/ssid automaton doesn't apply
            // we can skip those wg's
            val isWireGuardMobileOnly = files?.useOnlyOnMetered == true && !files.oneWireGuard
            val canResumeMobileWg = isWireGuardMobileOnly && isMobileActive

            val useOnlyOnSsid = files?.ssidEnabled == true && !files.oneWireGuard
            val configuredSsids = files?.ssids.orEmpty()
            val ssidMatch = WireguardManager.matchesSsidList(configuredSsids, ssid) && ssid.isNotEmpty()
            val canResumeSsidWg = useOnlyOnSsid && ssidMatch

            val canResume = canResumeMobileWg || canResumeSsidWg

            val stats = getProxyStatusById(id).first
            if (stats == Backend.TPU && canResume) {
                // if the proxy is paused, then resume it
                // this is needed when the tunnel is reconnected and the proxies are paused
                // so resume them, also when there is switch in wg-config for useOnlyOnMetered
                // or ssid change for ssidEnabled wgs
                val res = resumeWireguard(id)
            } else if (isWireGuardMobileOnly && !isMobileActive && !canResume) {
                // if the proxy is not paused, then pause it
                // this is needed when the network is on mobile data
                // and the wg-config is set to useOnlyOnMetered
                val res = pauseWireguard(id)
            } else if (useOnlyOnSsid && !ssidMatch && !canResume) {
                // when the ssidEnabled is set and the ssid does not match
                val res = pauseWireguard(id)
            }

            if (stats == Backend.TPU && !isWireGuardMobileOnly && !useOnlyOnSsid) {
                // if the proxy is paused, then resume it
                // this is needed when the tunnel is reconnected and the proxies are paused
                val res = getProxies()?.getProxy(id)?.resume()
            }
        }
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return try {
            val stats = getProxies()?.getProxy(id)?.router()?.stat()
            stats
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return try {
            val proxy = getProxies()?.getProxy(id)

            val router = proxy?.router()
            val stat = router?.stat()
            val mtu = router?.mtu()
            val ip4 = router?.iP4()
            val ip6 = router?.iP6()
            val addr = proxy?.addr

            WireguardManager.WgStats(stat, mtu, ip4, ip6, addr)
        } catch (_: java.util.concurrent.TimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getLocalProxyStatsById(id: String): ProxyManager.ProxyStats? {
        return try {
            val proxy = getProxies()?.getProxy(id)
            val router = proxy?.router()
            val stat = router?.stat()
            val ip4 = router?.iP4()
            val ip6 = router?.iP6()
            val addr = proxy?.addr

            ProxyManager.ProxyStats(stat, ip4, ip6, addr)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pauseWireguard(id: String): Boolean {
        return try {
            val res = getProxies()?.getProxy(id)?.pause()
            res ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun resumeWireguard(id: String): Boolean {
        return try {
            val res = getProxies()?.getProxy(id)?.resume()
            res ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getNetStat(): NetStat? {
        return try {
            tunnel.stat()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDnsStatus(id: String): Int? {
        try {
            val transport = getResolver()?.get(id)
            val tid = transport?.id()
            val status = transport?.status()
            // some special transports like blockfree, preferred, alg etc are handled specially.
            // in those cases, if the transport id is not there, it will serve the default transport
            // so return null in those cases
            if (tid != id) {
                return null
            }
            return status
        } catch (e: Exception) {
        }
        return null
    }

    suspend fun getDnsIps(id: String): String? {
        try {
            val ips = getResolver()?.getIPs(id)
            return ips
        } catch (e: Exception) {
            return null
        }
    }

    suspend fun getRDNS(): RDNS? {
        try {
            return getResolver()?.rdnsLocal
        } catch (e: Exception) {
        }
        return null
    }

    fun hasTunnel(): Boolean {
        return tunnel.isConnected
    }

    suspend fun refreshResolvers() {
        if (!tunnel.isConnected) {
            return
        }

        val id = Backend.Preferred
        val mainDnsStatus = getDnsStatus(id)
        val mainDnsOK = mainDnsStatus != null && mainDnsStatus != Backend.DEnd

        if (!mainDnsOK) {
            addTransport()
        }

        try {
            getResolver()?.refresh()
        } catch (e: Exception) {
        }
    }

    suspend fun refreshProxies() {
        if (!tunnel.isConnected) {
            return
        }

        try {
            tunnel.proxies.refreshProxies()
        } catch (e: Exception) {
        }
    }

    suspend fun closeTun() {
        try {
            if (tunnel.isConnected) {
                // this is not the only place where tunnel is disconnected
                // netstack can also close the tunnel on errors
                tunnel.disconnect()
            } else {
            }
        } catch (e: Exception) {
        }
    }

    suspend fun dupTunfd(dup: Boolean) {
        try {
            val res = Settings.dupTunFd(dup)
        } catch (e: Exception) {
        }
    }

    private fun newDefaultTransport(url: String): DefaultDNS? {
        val fallbackDns = getDefaultFallbackDns()
        try {
            // when the url is empty, set the default transport to 9.9.9.9, 2620:fe::fe or
            // null based on the value of SKIP_DEFAULT_DNS_ON_INIT
            if (url.isEmpty()) { // empty url denotes default dns set to none (system dns)
                if (SKIP_DEFAULT_DNS_ON_INIT && !persistentState.routeRethinkInRethink) {
                    return null
                }
                return Intra.newDefaultDNS(Backend.DNS53, fallbackDns, "")
            }
            val ips: String = getIpString(context, url)
            val res = if (url.contains("http")) {
                Intra.newDefaultDNS(Backend.DOH, url, ips)
            } else {
                // no need to set ips for dns53
                Intra.newDefaultDNS(Backend.DNS53, url, "")
            }
            return res
        } catch (e: Exception) {
            if (appConfig.getDnsType().isEncrypted()) {
                // fail closed: do not route through system dns when an encrypted dns is selected
                return null
            }
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            try {
                return Intra.newDefaultDNS(Backend.DNS53, fallbackDns, "")
            } catch (e: Exception) {
                Logger.crash(LOG_TAG_VPN, "$TAG err add $fallbackDns dns: ${e.message}", e)
                return null
            }
        }
    }

    private fun getDefaultFallbackDns(): String {
        return try {
            val netDns1 = System.getProperty("net.dns1")
            val netDns2 = System.getProperty("net.dns2")
            if (netDns1?.isNotEmpty() == true && netDns2?.isNotEmpty() == true) {
                "$netDns1,$netDns2"
            } else if (netDns1?.isNotEmpty() == true) {
                netDns1
            } else if (netDns2?.isNotEmpty() == true) {
                netDns2
            } else {
                BOOTSTRAP_DNS_IF_NET_DNS_EMPTY
            }
        } catch (_: Exception) {
            BOOTSTRAP_DNS_IF_NET_DNS_EMPTY
        }
    }

    suspend fun addDefaultTransport(url: String?): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        var type = Backend.DNS53
        val fallbackUrl = getDefaultFallbackDns()

        val usingSysDnsAsDefaultDns = isDefaultDnsNone()
        var usingGoos = fallbackUrl.isEmpty() // always false here, as fallbackUrl is not empty
        // make the tun to use goos as default transport if the system dns is set as none in
        // persistent state and not in rinr mode, as in that case the system dns
        if (SET_SYS_DNS_AS_DEFAULT_IFF_LOOPBACK && !persistentState.routeRethinkInRethink) {
            usingGoos = usingSysDnsAsDefaultDns
        }

        // default transport is always sent to Ipn.Exit in the go code and so dns
        // request sent to the default transport will not be looped back into the tunnel
        return try {
            // when the url is empty, set the default transport to fallbackUrl
            if (url.isNullOrEmpty()) {
                if (usingGoos) {
                    Intra.addDefaultTransport(tunnel, "", "", "")
                } else {
                    Intra.addDefaultTransport(tunnel, type, fallbackUrl, "")
                }
                return true
            } else if (url.contains("http")) {
                type = Backend.DOH
            }

            val ips: String = getIpString(context, url)
            Intra.addDefaultTransport(tunnel, type, url, ips)
            true
        } catch (e: Exception) {
            if (appConfig.getDnsType().isEncrypted()) {
                // fail closed: do not route through system dns when an encrypted dns is selected
                return false
            }
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            return try {
                if (usingGoos) {
                    Intra.addDefaultTransport(tunnel, "", "", "")
                } else {
                    Intra.addDefaultTransport(tunnel, type, fallbackUrl, "")
                }
                true
            } catch (e: Exception) {
                // fixme: this is not expected to happen, should show a notification?
                false
            }
        }
    }

    private fun isDefaultDnsNone(): Boolean {
        // if none is set then the url will either be empty or will not be one of the default dns
        return persistentState.defaultDnsUrl.isEmpty() ||
                !Constants.DEFAULT_DNS_LIST.any { it.url == persistentState.defaultDnsUrl }
    }

    suspend fun getSystemDns(): String? {
        return try {
            val sysDns = getResolver()?.get(Backend.System)?.addr
            sysDns
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun notifyLoopback() {
        val t = persistentState.routeRethinkInRethink
        try {
            Intra.loopback(t)
        } catch (e: Exception) {
        }
    }

    suspend fun setSystemDns(systemDns: List<String?>): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        // for Rethink within rethink mode, the system dns is system dns is always set to Ipn.Base
        // in go and so dns request sent to the system dns will be looped back into the tunnel
        return try {
            // TODO: system dns may be non existent; see: AppConfig#updateSystemDnsServers
            // convert list to comma separated string, as Intra expects as csv
            val sysDnsStr = systemDns.filter { it?.isNotEmpty() == true }.joinToString(",")
            // below code is commented out, add the code to set the system dns via resolver
            // tunnel?.resolver?.addSystemDNS(transport)
            // no need to send the dnsProxy.port for the below method, as it is not expecting port
            Intra.setSystemDNS(tunnel, sysDnsStr)
            true
        } catch (e: Exception) { // this is not expected to happen
            // remove the system dns, if it could not be set
            removeResolver(Backend.System)
            false
        }
    }

    suspend fun updateLinkAndRoutes(
        tunFd: Long,
        mtu: Int,
        nwMtu: Int,
        proto: Long
    ): Boolean {
        if (!tunnel.isConnected) {
            return false
        }

        return try {
            tunnel.setLinkAndRoutes2(tunFd, mtu.toLong(), nwMtu.toLong(), proto)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restartTunnel(tunFd: Long, mtu: Int, nwMtu: Int, proto: Long): Boolean {
        if (!tunnel.isConnected) {
            return false
        }

        try {
            tunnel.restart(tunFd, nwMtu.toLong(), mtu.toLong(), proto)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun setLinkMtu(nwMtu: Int): Boolean {
        if (!tunnel.isConnected) {
            return false
        }

        return try {
            val ok = tunnel.setLinkMtu(nwMtu.toLong())
            if (ok) {
            } else {
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Updates the DOH server URL for the VPN. If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation. If Go-DoH is not enabled, this method has
     * no effect.
     */
    suspend fun updateTun(tunnelOptions: TunnelOptions): Boolean {
        // changes made in connectTunnel()
        if (!tunnel.isConnected) {
            // Adapter is closed.
            return false
        }

        // ok to init again, as updateTun is called to handle edge cases
        initResolverProxiesPcap(tunnelOptions)
        setTunMode(tunnelOptions)
        return tunnel.isConnected
    }

    suspend fun setDnsAlg() {
        // set translate to false for dns mode (regardless of setting in dns screen),
        // since apps cannot understand alg ips
        if (appConfig.getBraveMode().isDnsMode()) {
            getResolver()?.translate(false, false)
            return
        }

        getResolver()?.translate(persistentState.enableDnsAlg, persistentState.splitDns)
    }

    suspend fun setRDNSStamp() {
        try {
            val rl = getRDNS()
            if (rl != null) {
                rl.stamp = persistentState.localBlocklistStamp
            } else {
            }
        } catch (e: Exception) {
            resetLocalBlocklistStampFromTunnel()
        }
    }

    private suspend fun resetLocalBlocklistStampFromTunnel() {
        try {
            val rl = getRDNS()
            if (rl == null) {
                return
            }

            persistentState.localBlocklistStamp =
                rl.stamp ?: "" // throws exception if stamp is invalid
        } catch (e: Exception) {
            persistentState.localBlocklistStamp = ""
        }
    }

    private suspend fun setRDNSLocal() {
        try {
            val stamp: String = persistentState.localBlocklistStamp
            val rdns = getResolver()

            val path: String =
                Utilities.blocklistDownloadBasePath(
                    context,
                    Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    persistentState.localBlocklistTimestamp
                )
            rdns?.setRdnsLocal(
                path + Constants.ONDEVICE_BLOCKLIST_FILE_TD,
                path + Constants.ONDEVICE_BLOCKLIST_FILE_RD,
                path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                path + ONDEVICE_BLOCKLIST_FILE_TAG
            )
            rdns?.rdnsLocal?.stamp = stamp
        } catch (ex: Exception) {
            // set the local blocklist to disabled only when there is no file in the local blocklist
            // folder
            var filesExist = false
            try {
                filesExist = Utilities.hasLocalBlocklists(
                    context,
                    persistentState.localBlocklistTimestamp
                )
            } catch (e: Exception) {
            }
            if (!filesExist) {
                persistentState.blocklistEnabled = false
            }
        }
    }

    companion object {

        private const val TAG = "TunAdapter;"

        // if the default dns is set to none then no need to set the system dns as default,
        // set it as empty or null, which was the behaviour before v055a
        private const val SKIP_DEFAULT_DNS_ON_INIT = true

        // on system dns changes, set the system dns if default dns is none and loopback is true
        private const val SET_SYS_DNS_AS_DEFAULT_IFF_LOOPBACK = true

        fun getIpString(context: Context?, url: String?): String {
            if (url.isNullOrEmpty()) return ""

            val res: Resources? = context?.resources
            val urls: Array<out String>? = res?.getStringArray(R.array.urls)
            val ips: Array<out String>? = res?.getStringArray(R.array.ips)
            if (urls == null) return ""
            for (i in urls.indices) {
                // either the url is a substring of urls[i] or urls[i] is a substring of url
                if ((url.contains(urls[i])) || (urls[i].contains(url))) {
                    if (ips != null) return ips[i]
                }
            }
            return ""
        }

        fun replaceHostWithIp(url: String, ips: String): String {
            val firstIp = ips.split(",").firstOrNull()?.trim().orEmpty()
            if (firstIp.isEmpty()) return url
            return try {
                val uri = URI(url)
                val scheme = uri.scheme ?: return url
                val host = uri.host
                if (host.isNullOrEmpty()) return url
                val ipHost = if (firstIp.contains(":")) "[$firstIp]" else firstIp
                val portPart = if (uri.port > 0) ":${uri.port}" else ""
                val path = uri.rawPath.orEmpty()
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
                "$scheme://$ipHost$portPart$path$query$fragment"
            } catch (e: Exception) {
                url
            }
        }

        fun setLogLevel(l1: Int, l2: Int = Logger.uiLogLevel.toInt(), includeFileTrace: Boolean) {
            // 0 - very verbose, 1 - verbose, 2 - debug, 3 - info, 4 - warn, 5 - error, 6 - stacktrace, 7 - user, 8 - none
            // from UI, if none is selected, set the log level to 7 (user), usr will send only
            // user notifications
            // there is no user level shown in UI, so if the user selects none, set the log level
            // to 8 (none)
            val goLogLevel = if (l1 == 7) 8 else l1
            val consoleLogLevel = if (l2 == 7) 8 else l2
            // depth to include the file trace, 9 - max, 0 - min
            val depth = if (includeFileTrace) 9 else 0
            Intra.logLevel(goLogLevel, consoleLogLevel, depth)
        }

        suspend fun printStack(): String {
            // where=0: stdout, where=1: console (no bytes returned by Go),
            // any other value: returns goroutine stacks as bytes.
            val where = 2
            return withContext(Dispatchers.IO) {
                try {
                    val bytes = Intra.printStack(where)
                    if (bytes == null || bytes.isEmpty()) {
                        return@withContext ""
                    }
                    // The buffer may be zero-padded at the tail; trim trailing null bytes.
                    val end = bytes.indexOfLast { it != 0.toByte() }
                    if (end < 0) {
                        return@withContext ""
                    }
                    val content = bytes.copyOfRange(0, end + 1)
                    String(content, Charsets.UTF_8)
                } catch (e: Exception) {
                    ""
                }
            }
        }

    }

    suspend fun p50(id: String): Long {
        try {
            val transport = getResolver()?.get(id)
            return transport?.p50() ?: -1L
        } catch (e: Exception) {
        }
        return -1L
    }

    private suspend fun getResolver(): DNSResolver? {
        try {
            if (!tunnel.isConnected) {
                return null
            }
            return tunnel.resolver
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_VPN, "$TAG err get resolver: ${e.message}", e)
        }
        return null
    }

    private suspend fun getProxies(): Proxies? {
        try {
            if (!tunnel.isConnected) {
                return null
            }
            val px = tunnel.proxies
            return px
        } catch (e: Exception) {
        }
        return null
    }

    suspend fun hasProxy(id: String): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        try {
            return getProxies()?.hasProxy(id) == true
        } catch (e: Exception) {
        }
        return false
    }

    suspend fun getSupportedIpVersion(proxyId: String): Pair<Boolean, Boolean> {
        try {
            val router =
                getProxies()?.getProxy(proxyId)?.router() ?: return Pair(false, false)
            val has4 = router.iP4()
            val has6 = router.iP6()
            return Pair(has4, has6)
        } catch (e: Exception) {
        }
        return Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(proxyId: String, pair: Pair<Boolean, Boolean>): Boolean {
        return try {
            val router = getProxies()?.getProxy(proxyId)?.router() ?: return false
            // if the router contains 0.0.0.0, then it is not split tunnel for ipv4
            // if the router contains ::, then it is not split tunnel for ipv6
            val res: Boolean =
                if (pair.first && pair.second) {
                    // if the pair is true, check for both ipv4 and ipv6
                    !router.contains("isSplitTunnelProxy", UNSPECIFIED_IP_IPV4) || !router.contains(
                        "isSplitTunnelProxy", UNSPECIFIED_IP_IPV6
                    )
                } else if (pair.first) {
                    !router.contains("isSplitTunnelProxy", UNSPECIFIED_IP_IPV4)
                } else if (pair.second) {
                    !router.contains("isSplitTunnelProxy", UNSPECIFIED_IP_IPV6)
                } else {
                    false
                }

            res
        } catch (e: Exception) {
            false
        }
    }

    suspend fun initiateWgPing(proxyId: String) {
        try {
            val res = getProxies()?.getProxy(proxyId)?.ping()
        } catch (e: Exception) {
        }
    }

    suspend fun getActiveProxiesIpAndMtu(): BraveVPNService.OverlayNetworks {
        try {
            val router = getProxies()?.router() ?: return BraveVPNService.OverlayNetworks()
            val has4 = router.iP4()
            val has6 = router.iP6()
            val failOpen = !router.iP4() && !router.iP6()
            val mtu = router.mtu().toInt()
            return BraveVPNService.OverlayNetworks(has4, has6, failOpen, mtu)
        } catch (e: Exception) {
            // exceptions are expected only when the wireguard is not available
        }
        return BraveVPNService.OverlayNetworks()
    }

    suspend fun onLowMemory() {
        val limitBytes: Long = persistentState.goMaxMemory
        Intra.lowMem(limitBytes)
    }

    suspend fun setDialStrategy(
        mode: Int = persistentState.dialStrategy,
        retry: Int = persistentState.retryStrategy,
        tcpKeepAlive: Boolean = persistentState.tcpKeepAlive,
        timeoutSec: Int = persistentState.dialTimeoutSec,
        bufferSize: Int = persistentState.socketBufferSizeBytes
    ) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Settings.setDialerOpts(mode, retry, bufferSize, timeoutSec, tcpKeepAlive)
        } catch (e: Exception) {
        }
    }

    suspend fun setTransparency(eim: Boolean = persistentState.endpointIndependence) {
        if (!tunnel.isConnected) {
            return
        }
        if (!isAtleastS()) {
            Intra.transparency(false, false)
            return
        }
        // set both endpoint independent mapping and endpoint independent filtering
        // as a single value, as for the user is concerned, it is a single setting
        Intra.transparency(eim, eim)
    }

    suspend fun undelegatedDomains(useSystemDns: Boolean = persistentState.useSystemDnsForUndelegatedDomains) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Intra.undelegatedDomains(useSystemDns)
        } catch (e: Exception) {
        }
    }

    suspend fun getWgClientInfoById(id: String): Client? {
        if (!tunnel.isConnected) {
            return null
        }

        return try {
            tunnel.proxies.getProxy(id).client()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun handleOnProxyRemoved(id: String): Boolean {
        // for now, it's enough to remove the dns transport, the hop is automatically taken
        // care in the tunnel when the proxy is removed.
        // takes care of wg proxies as the remove call is based on proxy id
        return removeResolver(id)
    }

    suspend fun setFloodWgMode() {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Intra.floodWireGuard(persistentState.floodWireGuard)
        } catch (e: Exception) {
        }
    }

    suspend fun setExperimentalWireGuardSettings(value: Boolean = persistentState.nwEngExperimentalFeatures) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // modified from overall experimental settings to only wireguard experimental settings
            Intra.experimentalWireGuard(value)
            // refresh proxies on experimental settings change (required for wireguard)
        } catch (e: Exception) {
        }
    }

    suspend fun setAutoDialsParallel(value: Boolean = persistentState.autoDialsParallel) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            Settings.setAutoDialsParallel(value)
        } catch (e: Exception) {
        }
    }

    suspend fun setAutoMode() {
        if (!tunnel.isConnected) {
            return
        }
        try {
            val retryStrategy = AntiCensorshipActivity.RetryStrategies.fromInt(persistentState.retryStrategy)
            if (retryStrategy == null) {
                return
            }
            val dialStrategy = AntiCensorshipActivity.DialStrategies.fromInt(persistentState.dialStrategy)
            if (dialStrategy == null) {
                return
            }

            // proxy + none -> remote
            // proxy + other retry -> hybrid
            // all other dial strategies -> local
            var modeName = "Settings.AutoModeLocal"
            val mode = if (dialStrategy == AntiCensorshipActivity.DialStrategies.TCP_PROXY) {
                if (retryStrategy == AntiCensorshipActivity.RetryStrategies.RETRY_NEVER) {
                    modeName = "Settings.AutoModeRemote"
                    Settings.AutoModeRemote
                } else {
                    modeName = "Settings.AutoModeHybrid"
                    Settings.AutoModeHybrid
                }
            } else {
                Settings.AutoModeLocal
            }

            Settings.setAutoMode(mode)
        } catch (e: Exception) {
        }
    }

    suspend fun createHop(origin: String, hop: String): Pair<Boolean, String> {
        if (!tunnel.isConnected) {
            return Pair(false, "no tunnel")
        }
        // to remove hop there is a separate method, so no need to check for empty
        if (hop.isEmpty()) {
            return Pair(false, "hop is empty")
        }
        return try {
            tunnel.proxies.hop(hop, origin)
            Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            Pair(false, e.message ?: "Hop creation failed")
        }
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        try {
            val dummyAddrPort = "10.111.222.10:53"
            val router = Intra.controlledRouter(controller, id, dummyAddrPort)
            val res = router.reaches(addrPort)
            return res
        } catch (e: Exception) {
        }
        return false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        if (!tunnel.isConnected) {
            return false
        }
        try {
            val dummyAddrPort = "10.111.222.11:53"
            val router = Intra.controlledRouter(controller, id, dummyAddrPort)
            val res = router.reaches(mode)
            return res
        } catch (e: Exception) {
        }
        return false
    }

    fun tunMtu(): Int {
        if (!tunnel.isConnected) {
            return 0
        }
        return try {
            val mtu = tunnel.mtu()
            mtu
        } catch (e: Exception) {
            0
        }
    }

    fun crashTun(type: Long) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // 0: default, 1: don't panic
            // 0: will crash, gowtf
            // 1: won't crash, send it to console
            // 2: will crash, send it to console
            val afterMs = 1000L
            Intra.crash(type, afterMs)
        } catch (e: Exception) {
        }
    }

    suspend fun memProfile(filepath: String) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // int, string => int: granularity 0: no granularity, 1: more detailed
            Intra.memProfile(1, filepath)
        } catch (e: Exception) {
        }
        return
    }

    suspend fun cpuProfile(filepath: String) {
        if (!tunnel.isConnected) {
            return
        }
        try {
            // string filepath, string flightrecodered path, int granularity 0: no granularity,
            // 1: more detailed
            // for now, the flight recorder is set to empty
            Intra.cpuProfile(filepath, "", 1)
        } catch (e: Exception) {
        }
        return
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch(Dispatchers.Main) { f() }
    }

}
