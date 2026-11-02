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

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.databinding.ActivityXrayBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.XrayRunner
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.XrayConfig
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.koin.android.ext.android.inject

/**
 * Edits the xray profile (vless link or xray JSON), checks it as the user types, and starts or
 * stops xray-core. Starting also points the custom SOCKS5 proxy at the local xray inbound.
 */
class XrayActivity : BaseActivity(R.layout.activity_xray) {

    private val b by viewBinding(ActivityXrayBinding::bind)
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val runner by lazy { XrayRunner(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b.xrayInput.setText(runner.savedProfile().orEmpty())
        b.xrayInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    validate()
                }
            }
        )
        b.xrayPaste.setOnClickListener { pasteFromClipboard() }
        b.xrayFormat.setOnClickListener { formatInput() }
        b.xrayClear.setOnClickListener { b.xrayInput.setText("") }
        b.xraySave.setOnClickListener { saveProfile() }
        b.xrayToggle.setOnClickListener { toggleXray() }
        validate()
        renderStatus()
    }

    /** Checks the current text, updates the message and buttons, and returns the problem. */
    private fun validate(): XrayConfig.Problem? {
        val text = b.xrayInput.text.toString()
        val problem = XrayConfig.check(text)
        val blank = text.isBlank()
        val valid = !blank && problem == null

        b.xraySave.isEnabled = valid
        b.xrayFormat.isEnabled = valid
        if (blank) {
            b.xrayProblem.visibility = View.GONE
        } else {
            b.xrayProblem.visibility = View.VISIBLE
            b.xrayProblem.text = getString(messageFor(problem))
            val attr = if (problem == null) MaterialR.attr.colorPrimary else MaterialR.attr.colorError
            b.xrayProblem.setTextColor(MaterialColors.getColor(b.xrayProblem, attr))
        }
        return problem
    }

    private fun messageFor(problem: XrayConfig.Problem?): Int {
        return when (problem) {
            null -> R.string.xray_ok
            XrayConfig.Problem.EMPTY -> R.string.xray_err_empty
            XrayConfig.Problem.INVALID_JSON -> R.string.xray_err_json
            XrayConfig.Problem.INVALID_LINK -> R.string.xray_err_link
            XrayConfig.Problem.NOT_REALITY -> R.string.xray_err_reality
            else -> R.string.xray_err_outbound
        }
    }

    private fun formatInput() {
        val text = b.xrayInput.text.toString()
        try {
            b.xrayInput.setText(XrayConfig.format(text))
        } catch (e: JSONException) {
            toast(getString(R.string.xray_format_failed))
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip =
            clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        if (clip.isNullOrBlank()) {
            toast(getString(R.string.xray_err_empty))
        } else {
            b.xrayInput.setText(clip.trim())
        }
    }

    private fun renderStatus() {
        val running = XrayRunner.isRunning()
        b.xrayStatus.text =
            getString(if (running) R.string.xray_status_on else R.string.xray_status_off)
        b.xrayToggle.text = getString(if (running) R.string.xray_stop else R.string.xray_start)
    }

    private fun saveProfile() {
        val text = b.xrayInput.text.toString()
        if (XrayConfig.check(text) != null) {
            validate()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runner.saveProfile(text)
            withContext(Dispatchers.Main) {
                toast(getString(if (saved) R.string.xray_saved else R.string.xray_save_failed))
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
        // when the app's own traffic is routed through the VPN, xray's outbound would loop back
        if (persistentState.routeRethinkInRethink) {
            return Result.failure(IllegalStateException(getString(R.string.xray_rethink_on)))
        }

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
