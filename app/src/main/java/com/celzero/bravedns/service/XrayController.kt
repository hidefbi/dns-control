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

/**
 * Runs xray-core together with the VPN.
 *
 * Xray starts before the tunnel is established, because the tunnel sends its traffic to xray's
 * local SOCKS5 inbound. It stops, and its SOCKS5 slot is removed, whenever the VPN stops, so the
 * next VPN start never inherits a dead proxy.
 */
class XrayController(
    private val context: Context,
    private val appConfig: AppConfig,
    private val persistentState: PersistentState,
) {

    companion object {
        /** Name of the SOCKS5 slot that xray owns; other SOCKS5 settings are never touched. */
        const val PROXY_NAME = "XRAY"
    }

    private val runner = XrayRunner(context)

    /**
     * Called before the tunnel is established. Does nothing when the user has not enabled xray.
     * A failure means the VPN must not start, so traffic does not silently go direct.
     */
    suspend fun startForVpn(): Result<Unit> {
        if (!persistentState.xrayEnabled) {
            removeStaleSlot()
            return Result.success(Unit)
        }
        if (persistentState.routeRethinkInRethink) {
            return Result.failure(IllegalStateException(context.getString(R.string.xray_rethink_on)))
        }

        val profile = runner.savedProfile()
        if (profile.isNullOrBlank()) {
            return Result.failure(IllegalStateException(context.getString(R.string.xray_none)))
        }
        // re-check on every start: a profile saved by an older version may not pass today's rules
        val problem = XrayConfig.check(profile)
        if (problem != null) {
            val res =
                if (problem == XrayConfig.Problem.HOST_NOT_IP) {
                    R.string.xray_err_host_ip
                } else {
                    R.string.xray_err_outbound
                }
            return Result.failure(IllegalStateException(context.getString(res)))
        }

        // start() returns early when xray is already running, so a VPN restart reuses it
        val started = runner.start(profile)
        if (started.isFailure) return started

        val current = appConfig.getSocks5ProxyDetails()
        if (current == null) {
            runner.stop()
            return Result.failure(IllegalStateException(context.getString(R.string.xray_no_slot)))
        }
        appConfig.updateCustomSocks5Proxy(socks5Endpoint(current.id))
        return Result.success(Unit)
    }

    /**
     * Stops xray and removes its SOCKS5 slot. Synchronous on purpose: it is called while the VPN
     * service is being destroyed, when coroutine scopes are already cancelled.
     */
    fun stopNow() {
        if (!XrayRunner.isRunning()) return
        runner.stop()
        appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
    }

    /** Removes a leftover XRAY slot, for example after the app was killed while xray ran. */
    private suspend fun removeStaleSlot() {
        if (appConfig.getSocks5ProxyDetails()?.proxyName == PROXY_NAME) {
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
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
}
