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
package com.celzero.bravedns.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.databinding.ActivityXrayBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.XrayRunner
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.XrayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Holds the xray profile and starts or stops xray-core. Starting also points the custom SOCKS5
 * proxy at the local xray inbound, so the existing whole-device SOCKS5 mode routes through it.
 */
class XrayActivity : BaseActivity(R.layout.activity_xray) {

    private val b by viewBinding(ActivityXrayBinding::bind)
    private val appConfig by inject<AppConfig>()
    private val runner by lazy { XrayRunner(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b.xrayInput.setText(runner.savedProfile().orEmpty())
        b.xraySave.setOnClickListener { saveProfile() }
        b.xrayToggle.setOnClickListener { toggleXray() }
        renderStatus()
    }

    private fun renderStatus() {
        val running = XrayRunner.isRunning()
        b.xrayStatus.text =
            getString(if (running) R.string.xray_status_on else R.string.xray_status_off)
        b.xrayToggle.text = getString(if (running) R.string.xray_stop else R.string.xray_start)
    }

    private fun saveProfile() {
        val text = b.xrayInput.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val valid = runCatching { runner.buildConfig(text) }
            val saved = valid.isSuccess && runner.saveProfile(text)
            withContext(Dispatchers.Main) {
                if (saved) {
                    toast(getString(R.string.xray_saved))
                } else {
                    showError(valid.exceptionOrNull() ?: IllegalStateException("save failed"))
                }
            }
        }
    }

    private fun toggleXray() {
        b.xrayToggle.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result: Result<Unit> =
                if (XrayRunner.isRunning()) {
                    runner.stop()
                    // without this the SOCKS5 slot would keep pointing at a dead local port
                    appConfig.removeProxy(
                        AppConfig.ProxyType.SOCKS5,
                        AppConfig.ProxyProvider.CUSTOM
                    )
                    Result.success(Unit)
                } else {
                    startXray()
                }
            withContext(Dispatchers.Main) {
                b.xrayToggle.isEnabled = true
                result.exceptionOrNull()?.let { showError(it) }
                renderStatus()
            }
        }
    }

    private suspend fun startXray(): Result<Unit> {
        val profile = runner.savedProfile()
        if (profile.isNullOrBlank()) {
            return Result.failure(IllegalStateException(getString(R.string.xray_none)))
        }

        val started = runner.start(profile)
        if (started.isFailure) return started

        if (!registerSocks5()) {
            runner.stop()
            return Result.failure(IllegalStateException(getString(R.string.xray_no_slot)))
        }
        return started
    }

    /** Points the custom SOCKS5 slot at the local xray inbound. */
    private suspend fun registerSocks5(): Boolean {
        val current = appConfig.getSocks5ProxyDetails() ?: return false
        val endpoint =
            ProxyEndpoint(
                id = current.id,
                proxyName = "XRAY",
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
        appConfig.updateCustomSocks5Proxy(endpoint)
        return true
    }

    private fun showError(error: Throwable) {
        toast(getString(R.string.xray_error, error.message ?: error.javaClass.simpleName))
    }

    private fun toast(message: String) {
        Utilities.showToastUiCentered(this, message, Toast.LENGTH_SHORT)
    }
}
