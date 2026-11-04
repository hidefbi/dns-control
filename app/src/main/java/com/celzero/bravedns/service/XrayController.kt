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
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.util.XrayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs xray-core together with the VPN.
 *
 * The switch decides whether xray is used. While the VPN is on, switching xray on starts it and
 * points the SOCKS5 slot at it; switching it off stops it. xray takes over the custom SOCKS5 slot
 * only for as long as it runs: the slot's previous contents and the proxy selection are saved
 * first, and handed back when xray stops, including after a crash (the snapshot is persisted).
 * The tunnel is brought up only after xray is listening, and xray and WireGuard cannot run together.
 */
class XrayController(
    private val context: Context,
    private val appConfig: AppConfig,
    private val persistentState: PersistentState,
) {

    companion object {
        /** Name of the SOCKS5 row that xray uses while it runs. */
        const val PROXY_NAME = "XRAY"
    }

    private val runner = XrayRunner(context)

    // serialises start, stop and restore, so a VPN restart never races a stop
    private val lock = Mutex()

    // outlives the VPN service: stopNow() is called while the service is being destroyed
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called before the tunnel carries traffic. Does nothing when the switch is off, apart from
     * handing back a slot that xray left behind. A failure means the VPN must not start, so
     * traffic does not silently go direct.
     */
    suspend fun startForVpn(): Result<Unit> =
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (!persistentState.xrayEnabled) {
                    restoreSlot()
                    return@withContext Result.success(Unit)
                }
                // wireguard runs only while xray is off. A stale xray switch, left on by an older
                // build or by a crash, must not block a VPN that uses wireguard: the switch is
                // turned off and the slot handed back, so wireguard starts as it did before xray.
                if (appConfig.isWireGuardEnabled()) {
                    persistentState.xrayEnabled = false
                    restoreSlot()
                    return@withContext Result.success(Unit)
                }
                startRunning()
            }
        }

    /**
     * Called when the user flips the switch. While the VPN is on, enabling starts xray right away
     * and disabling stops it. While the VPN is off, the flag is only saved for the next VPN start.
     */
    suspend fun setEnabled(enabled: Boolean, vpnOn: Boolean): Result<Unit> =
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (!enabled) {
                    persistentState.xrayEnabled = false
                    runner.stop()
                    restoreSlot()
                    return@withContext Result.success(Unit)
                }
                conflict()?.let {
                    return@withContext Result.failure(IllegalStateException(context.getString(it)))
                }
                persistentState.xrayEnabled = true
                if (!vpnOn) return@withContext Result.success(Unit)
                val started = startRunning()
                if (started.isFailure) persistentState.xrayEnabled = false
                started
            }
        }

    /**
     * Stops xray and hands the SOCKS5 slot back. Called while the VPN service is being destroyed:
     * the process is stopped right away, the slot is restored on the controller's own scope.
     */
    fun stopNow() {
        // xray runs only while the vpn runs: the switch is turned off with the vpn, so a later
        // wireguard start is not blocked by a leftover xray switch. set before the stop so that
        // no start can see xray on while the slot is being handed back
        persistentState.xrayEnabled = false
        runner.stop()
        scope.launch {
            lock.withLock {
                withContext(Dispatchers.IO) { restoreSlot() }
            }
        }
    }

    /** Resource id of the reason xray cannot run now, or null when it can. */
    fun conflict(): Int? {
        if (persistentState.routeRethinkInRethink) return R.string.xray_rethink_on
        if (appConfig.isWireGuardEnabled()) return R.string.xray_wg_conflict
        return null
    }

    private suspend fun startRunning(): Result<Unit> {
        conflict()?.let { return Result.failure(IllegalStateException(context.getString(it))) }
        // proxies are not allowed in dns-only mode (see AppConfig#canEnableProxy). Xray would start
        // but no traffic or DNS would reach it, so refuse with a reason instead.
        if (!appConfig.canEnableProxy()) {
            return Result.failure(IllegalStateException(context.getString(R.string.xray_err_dns_mode)))
        }
        // the proxy provider is a single setting. Pointing the SOCKS5 slot at xray would overwrite
        // a WireGuard or Orbot selection, so refuse to start instead of silently replacing it.
        val provider = AppConfig.ProxyProvider.getProxyProvider(appConfig.getProxyProvider())
        if (provider == AppConfig.ProxyProvider.WIREGUARD || provider == AppConfig.ProxyProvider.ORBOT) {
            return Result.failure(IllegalStateException(context.getString(R.string.xray_err_provider)))
        }

        val profile = runner.savedProfile()
        if (profile.isNullOrBlank()) {
            return Result.failure(IllegalStateException(context.getString(R.string.xray_none)))
        }
        // re-check on every start: a profile saved by an older version may not pass today's rules
        val problem = XrayConfig.check(profile)
        if (problem != null) {
            val res =
                when (problem) {
                    XrayConfig.Problem.HOST_NOT_IP -> R.string.xray_err_host_ip
                    XrayConfig.Problem.DNS_BLOCK -> R.string.xray_err_dns
                    XrayConfig.Problem.BAD_RULE -> R.string.xray_err_rule
                    else -> R.string.xray_err_outbound
                }
            return Result.failure(IllegalStateException(context.getString(res)))
        }

        // start() returns early when xray is already running, so a VPN restart reuses it
        val started = runner.start(profile)
        if (started.isFailure) return started

        return try {
            takeSlot()
            Result.success(Unit)
        } catch (e: Exception) {
            runner.stop()
            restoreSlot()
            Result.failure(IllegalStateException(context.getString(R.string.xray_no_slot), e))
        }
    }

    /**
     * Points the SOCKS5 slot at xray. The first time, the slot's previous contents and the proxy
     * selection are saved, so they can be handed back. A slot that is already pointing at xray is
     * left alone, so a VPN restart does not change the proxy again.
     */
    private suspend fun takeSlot() {
        if (persistentState.xrayProxySnapshot.isBlank()) {
            saveSnapshot()
        }
        val row = appConfig.getSocks5ProxyDetails() ?: throw IllegalStateException("no slot")
        val pointingAtXray =
            row.proxyName == PROXY_NAME &&
                row.proxyIP == XrayConfig.LOCAL_HOST &&
                row.proxyPort == XrayConfig.LOCAL_PORT &&
                appConfig.isCustomSocks5Enabled()
        if (pointingAtXray) return
        appConfig.updateCustomSocks5Proxy(socks5Endpoint(row.id))
    }

    private suspend fun saveSnapshot() {
        val original = appConfig.getSocks5ProxyDetails()
        // a row named XRAY was left by an older build; it is xray's own, so it is removed on restore
        val created = original == null || original.proxyName == PROXY_NAME
        val slotId =
            if (original == null) {
                appConfig.insertCustomSocks5Row(socks5Endpoint(0))
                appConfig.getSocks5ProxyDetails()?.id ?: throw IllegalStateException("no slot")
            } else {
                original.id
            }
        val snapshot =
            Snapshot(
                created = created,
                slotId = slotId,
                original = if (created) null else original,
                proxyType = persistentState.proxyType,
                proxyProvider = persistentState.proxyProvider,
            )
        persistentState.xrayProxySnapshot = snapshot.toJson()
    }

    /**
     * Hands the SOCKS5 slot and the proxy selection back to what they were before xray. A slot
     * left by xray without a snapshot (older build) is removed, but only the SOCKS5 selection that
     * xray set: a WireGuard or other proxy selection is never touched.
     */
    private suspend fun restoreSlot() {
        val raw = persistentState.xrayProxySnapshot
        if (raw.isBlank()) {
            removeLegacySlot()
            return
        }
        val snapshot = Snapshot.fromJson(raw)
        if (snapshot == null) {
            persistentState.xrayProxySnapshot = ""
            return
        }
        if (snapshot.created) {
            appConfig.deleteCustomSocks5Row(socks5Endpoint(snapshot.slotId))
        } else {
            snapshot.original?.let { appConfig.saveCustomSocks5Row(it) }
        }
        persistentState.proxyType = snapshot.proxyType
        persistentState.proxyProvider = snapshot.proxyProvider
        persistentState.updateProxyStatus()
        persistentState.xrayProxySnapshot = ""
    }

    private suspend fun removeLegacySlot() {
        val row = appConfig.getSocks5ProxyDetails() ?: return
        if (row.proxyName != PROXY_NAME) return
        appConfig.deleteCustomSocks5Row(row)
        val isXraySelection =
            persistentState.proxyProvider == AppConfig.ProxyProvider.CUSTOM.name &&
                AppConfig.ProxyType.of(persistentState.proxyType).isProxyTypeSocks5()
        if (isXraySelection) {
            persistentState.proxyType = AppConfig.ProxyType.NONE.name
            persistentState.proxyProvider = AppConfig.ProxyProvider.NONE.name
            persistentState.updateProxyStatus()
        }
    }

    private fun socks5Endpoint(id: Int): ProxyEndpoint {
        return ProxyEndpoint(
            id = id,
            proxyName = PROXY_NAME,
            proxyMode = ProxyManager.ProxyMode.SOCKS5.value,
            proxyType = ProxyEndpoint.DEFAULT_PROXY_TYPE,
            proxyAppName = "",
            proxyIP = XrayConfig.LOCAL_HOST,
            proxyPort = XrayConfig.LOCAL_PORT,
            userName = null,
            password = null,
            isSelected = true,
            isCustom = true,
            isUDP = true,
            modifiedDataTime = 0L,
            latency = 0,
        )
    }

    /** What xray replaced; persisted so that a crash does not lose the user's slot. */
    private class Snapshot(
        val created: Boolean,
        val slotId: Int,
        val original: ProxyEndpoint?,
        val proxyType: String,
        val proxyProvider: String,
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("created", created)
            json.put("slotId", slotId)
            json.put("proxyType", proxyType)
            json.put("proxyProvider", proxyProvider)
            json.put("original", original?.let { rowToJson(it) } ?: JSONObject.NULL)
            return json.toString()
        }

        companion object {
            fun fromJson(raw: String): Snapshot? {
                return try {
                    val json = JSONObject(raw)
                    val original =
                        if (json.isNull("original")) null else rowFromJson(json.getJSONObject("original"))
                    Snapshot(
                        created = json.getBoolean("created"),
                        slotId = json.getInt("slotId"),
                        original = original,
                        proxyType = json.getString("proxyType"),
                        proxyProvider = json.getString("proxyProvider"),
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // the password stays encrypted as stored, it is never written here in plaintext
            private fun rowToJson(row: ProxyEndpoint): JSONObject {
                val json = JSONObject()
                json.put("id", row.id)
                json.put("proxyName", row.proxyName)
                json.put("proxyMode", row.proxyMode)
                json.put("proxyType", row.proxyType)
                json.put("proxyAppName", row.proxyAppName ?: JSONObject.NULL)
                json.put("proxyIP", row.proxyIP ?: JSONObject.NULL)
                json.put("proxyPort", row.proxyPort)
                json.put("userName", row.userName ?: JSONObject.NULL)
                json.put("encryptedPassword", row.encryptedPassword ?: JSONObject.NULL)
                json.put("isSelected", row.isSelected)
                json.put("isCustom", row.isCustom)
                json.put("isUDP", row.isUDP)
                json.put("modifiedDataTime", row.modifiedDataTime)
                json.put("latency", row.latency)
                return json
            }

            private fun rowFromJson(json: JSONObject): ProxyEndpoint {
                val row = ProxyEndpoint()
                row.id = json.getInt("id")
                row.proxyName = json.getString("proxyName")
                row.proxyMode = json.getInt("proxyMode")
                row.proxyType = json.getString("proxyType")
                row.proxyAppName = optionalString(json, "proxyAppName")
                row.proxyIP = optionalString(json, "proxyIP")
                row.proxyPort = json.getInt("proxyPort")
                row.userName = optionalString(json, "userName")
                row.encryptedPassword = optionalString(json, "encryptedPassword")
                row.isSelected = json.getBoolean("isSelected")
                row.isCustom = json.getBoolean("isCustom")
                row.isUDP = json.getBoolean("isUDP")
                row.modifiedDataTime = json.getLong("modifiedDataTime")
                row.latency = json.getInt("latency")
                return row
            }

            private fun optionalString(json: JSONObject, key: String): String? {
                return if (json.isNull(key)) null else json.getString(key)
            }
        }
    }
}
