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
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.XrayRunner
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.XrayConfig
import com.google.android.material.color.MaterialColors
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.koin.android.ext.android.inject

/**
 * Edits the xray profile (vless link or xray JSON), checks it as the user types, and starts or
 * stops xray-core. Starting also points the custom SOCKS5 proxy at the local xray inbound.
 *
 * A fresh install opens with the built-in client template. Its REPLACE_ values are listed as
 * missing fields with a progress bar, highlighted in the code, and block saving until filled in.
 */
class XrayActivity : BaseActivity(R.layout.activity_xray) {

    private val b by viewBinding(ActivityXrayBinding::bind)
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val runner by lazy { XrayRunner(applicationContext) }
    // error colour comes from the layout (?attr/colorError); captured before it is ever changed
    private var errorColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        errorColor = b.xrayProblem.currentTextColor
        b.xrayToolbar.setNavigationOnClickListener { finish() }
        b.xrayInput.setText(runner.savedProfile() ?: XrayConfig.TEMPLATE)
        b.xrayInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    s?.let { highlight(it) }
                    validate()
                    b.xrayInput.post { updateGutter() }
                }
            }
        )
        // the line numbers follow wrapping and size changes, not only text edits
        b.xrayInput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGutter() }
        b.xrayPaste.setOnClickListener { pasteFromClipboard() }
        b.xrayFormat.setOnClickListener { formatInput() }
        b.xrayClear.setOnClickListener { b.xrayInput.setText("") }
        b.xraySave.setOnClickListener { saveProfile() }
        b.xrayToggle.setOnClickListener { toggleXray() }
        b.xrayInput.text?.let { highlight(it) }
        validate()
        renderStatus()
        b.xrayInput.post { updateGutter() }
    }

    /** Checks the current text, updates the progress, message, footer and buttons. */
    private fun validate(): XrayConfig.Problem? {
        val text = b.xrayInput.text.toString()
        val problem = XrayConfig.check(text)
        val blank = text.isBlank()
        val valid = !blank && problem == null
        val pending = problem == XrayConfig.Problem.PLACEHOLDER

        b.xraySave.isEnabled = valid
        b.xrayFormat.isEnabled = valid
        renderProgress(text, pending)

        // while fields are missing, the progress card says what is left, so no extra message
        val showMessage = !blank && !pending
        b.xrayProblem.visibility = if (showMessage) View.VISIBLE else View.GONE
        if (showMessage) {
            b.xrayProblem.text = messageFor(problem)
            b.xrayProblem.setTextColor(if (problem == null) okColor() else errorColor)
        }

        b.xrayFooter.text =
            if (pending) {
                getString(R.string.xray_footer_need, XrayConfig.placeholderCount(text))
            } else {
                getString(R.string.xray_footer_ok)
            }
        return problem
    }

    /** Success text uses the theme's primary colour, so it follows light and dark themes. */
    private fun okColor(): Int =
        MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary)

    private fun messageFor(problem: XrayConfig.Problem?): String {
        val res =
            when (problem) {
                null -> R.string.xray_ok
                XrayConfig.Problem.EMPTY -> R.string.xray_err_empty
                XrayConfig.Problem.INVALID_JSON -> R.string.xray_err_json
                XrayConfig.Problem.INVALID_LINK -> R.string.xray_err_link
                XrayConfig.Problem.NOT_REALITY -> R.string.xray_err_reality
                else -> R.string.xray_err_outbound
            }
        return getString(res)
    }

    /** The five values of the built-in template, in the order they appear in the progress card. */
    private val fieldLabels =
        linkedMapOf(
            "REPLACE_SERVER_IP" to R.string.xray_field_server,
            "REPLACE_UUID" to R.string.xray_field_uuid,
            "REPLACE_SNI" to R.string.xray_field_sni,
            "REPLACE_PUBLIC_KEY" to R.string.xray_field_pubkey,
            "REPLACE_SHORT_ID" to R.string.xray_field_shortid,
        )

    private fun renderProgress(text: String, pending: Boolean) {
        b.xrayProgressCard.visibility = if (pending) View.VISIBLE else View.GONE
        if (!pending) return

        val missing = fieldLabels.filterKeys { text.contains(it) }.values
        val total = fieldLabels.size
        b.xrayProgress.max = total
        b.xrayProgress.setProgressCompat(total - missing.size, true)
        b.xrayProgressLeft.text = getString(R.string.xray_progress_left, missing.size)
        b.xrayMissing.removeAllViews()
        missing.forEach { b.xrayMissing.addView(buildFieldChip(getString(it))) }
    }

    /** A non-clickable chip in the theme's error container colours, one per missing field. */
    private fun buildFieldChip(label: String): Chip {
        return Chip(this).apply {
            text = label
            isClickable = false
            isCheckable = false
            chipStrokeWidth = 0f
            chipBackgroundColor =
                ColorStateList.valueOf(
                    MaterialColors.getColor(
                        b.root,
                        com.google.android.material.R.attr.colorErrorContainer
                    )
                )
            setTextColor(
                MaterialColors.getColor(
                    b.root,
                    com.google.android.material.R.attr.colorOnErrorContainer
                )
            )
        }
    }

    /** Colours keys, string values and REPLACE_ items in the code panel. */
    private fun highlight(text: Editable) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, StyleSpan::class.java).forEach { text.removeSpan(it) }

        val source = text.toString()
        val keys = KEY.findAll(source).map { it.range.first }.toSet()
        // values first, then keys, then placeholders on top
        STRING.findAll(source)
            .filter { it.range.first !in keys }
            .forEach { text.setSpan(ForegroundColorSpan(COLOR_VALUE), it.range.first, it.range.last + 1, 0) }
        KEY.findAll(source).forEach {
            text.setSpan(ForegroundColorSpan(COLOR_KEY), it.range.first, it.range.last + 1, 0)
        }
        for (range in XrayConfig.placeholderRanges(source)) {
            val end = range.last + 1
            text.setSpan(ForegroundColorSpan(COLOR_PLACEHOLDER), range.first, end, 0)
            text.setSpan(BackgroundColorSpan(COLOR_PLACEHOLDER_BG), range.first, end, 0)
            text.setSpan(StyleSpan(Typeface.BOLD), range.first, end, 0)
        }
    }

    /**
     * Builds the line numbers next to the code. A wrapped line shows its number only on its first
     * visual row, so the numbers stay aligned with the logical lines of the JSON.
     */
    private fun updateGutter() {
        val layout = b.xrayInput.layout ?: return
        val text = b.xrayInput.text ?: return
        val rows = ArrayList<String>(layout.lineCount)
        var number = 1
        var cursor = 0
        for (row in 0 until layout.lineCount) {
            val start = layout.getLineStart(row)
            for (i in cursor until start) {
                if (text[i] == '\n') number++
            }
            cursor = start
            val firstRowOfLine = start == 0 || text[start - 1] == '\n'
            rows.add(if (firstRowOfLine) number.toString() else "")
        }
        val gutter = rows.joinToString("\n")
        if (b.xrayGutter.text.toString() != gutter) b.xrayGutter.text = gutter
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
        b.xrayChipStatus.text =
            getString(if (running) R.string.xray_chip_on else R.string.xray_chip_off)
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
        val starting = !XrayRunner.isRunning()
        // xray only carries traffic that the VPN sends to it, so warn when the VPN is off
        if (starting && !VpnController.isOn()) {
            toast(getString(R.string.xray_vpn_hint))
        }
        b.xrayToggle.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result: Result<Unit> =
                if (!starting) {
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

    companion object {
        // matches a JSON key ("name" followed by a colon) and a string value
        private val KEY = Regex("\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:)")
        private val STRING = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

        // code panel colours; the panel itself is fixed dark in both themes
        private val COLOR_KEY = Color.parseColor("#9CDCFE")
        private val COLOR_VALUE = Color.parseColor("#CE9178")
        private val COLOR_PLACEHOLDER = Color.parseColor("#FFD166")
        private val COLOR_PLACEHOLDER_BG = Color.parseColor("#4D3D12")
    }
}
