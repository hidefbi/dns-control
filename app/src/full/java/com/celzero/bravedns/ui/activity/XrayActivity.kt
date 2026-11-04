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
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.celzero.bravedns.databinding.ActivityXrayBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.XrayRunner
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.XrayConfig
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.koin.android.ext.android.inject

/**
 * Edits the xray profile (vless link or xray JSON). Xray itself is not started here: it runs
 * with the VPN, which is switched on from the main screen. This page only decides whether xray
 * starts with the VPN, and checks the profile as the user types.
 *
 * A fresh install opens with the built-in client template. Its REPLACE_ values are listed as
 * missing fields, highlighted in the code, and block saving until filled in.
 */
class XrayActivity : BaseActivity(R.layout.activity_xray) {

    private val b by viewBinding(ActivityXrayBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val runner by lazy { XrayRunner(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { refresh() }

    // 1-based line of the JSON syntax error, or -1 when there is none
    private var errorLine = -1
    // error colour comes from the layout (?attr/colorError), read before it is ever changed
    private var errorColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // apply the user's theme before the views are inflated, as the other activities do;
        // without this the page follows the system theme instead of the app's setting
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        errorColor = b.xrayProblem.currentTextColor
        b.xrayToolbar.setNavigationOnClickListener { finish() }
        b.xrayInput.setText(runner.savedProfile() ?: XrayConfig.TEMPLATE)
        b.xrayInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    scheduleRefresh()
                }
            }
        )
        // line numbers follow wrapping and size changes, not only text edits
        b.xrayInput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGutter() }
        b.xrayPaste.setOnClickListener { pasteFromClipboard() }
        b.xrayFormat.setOnClickListener { formatInput() }
        b.xrayClear.setOnClickListener { b.xrayInput.setText("") }
        b.xraySave.setOnClickListener { saveProfile() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    /** Debounced: checking and colouring the whole JSON on every key press is wasteful. */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, DEBOUNCE_MS)
    }

    private fun refresh() {
        // validate first: it sets the error line that the highlight uses
        validate()
        b.xrayInput.text?.let { highlight(it) }
        updateGutter()
    }

    /** Checks the current text and updates the progress, message, footer and buttons. */
    private fun validate() {
        val text = b.xrayInput.text.toString()
        val blank = text.isBlank()
        val problem = XrayConfig.check(text)
        val valid = !blank && problem == null
        b.xraySave.isEnabled = valid
        b.xrayFormat.isEnabled = valid

        errorLine =
            if (problem == XrayConfig.Problem.INVALID_JSON) {
                XrayConfig.errorSpot(text)?.line ?: -1
            } else {
                -1
            }

        val missing = if (blank) null else XrayConfig.missingFields(text)
        renderProgress(missing)

        val pending = problem == XrayConfig.Problem.PLACEHOLDER && !missing.isNullOrEmpty()
        // the footer only says how many values are still missing; nothing about starting xray
        if (!missing.isNullOrEmpty()) {
            b.xrayFooter.text = getString(R.string.xray_footer_fill, missing.size)
            b.xrayFooter.visibility = View.VISIBLE
        } else {
            b.xrayFooter.visibility = View.GONE
        }

        // while values are missing, the progress card already says what is left
        b.xrayProblem.visibility = if (blank || pending) View.GONE else View.VISIBLE
        if (!blank && !pending) {
            b.xrayProblem.text = messageFor(problem, text)
            b.xrayProblem.setTextColor(
                if (problem == null) {
                    MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary)
                } else {
                    errorColor
                }
            )
        }
    }

    private fun messageFor(problem: XrayConfig.Problem?, text: String): String {
        return when (problem) {
            null -> getString(R.string.xray_ok)
            XrayConfig.Problem.EMPTY -> getString(R.string.xray_err_empty)
            XrayConfig.Problem.PLACEHOLDER ->
                getString(R.string.xray_err_placeholder, XrayConfig.placeholderCount(text))
            XrayConfig.Problem.INVALID_JSON -> {
                val spot = XrayConfig.errorSpot(text)
                if (spot == null) {
                    getString(R.string.xray_err_json)
                } else {
                    getString(R.string.xray_err_json_at, spot.line, spot.column)
                }
            }
            XrayConfig.Problem.INVALID_LINK -> getString(R.string.xray_err_link)
            XrayConfig.Problem.NOT_REALITY -> getString(R.string.xray_err_reality)
            XrayConfig.Problem.HOST_NOT_IP -> getString(R.string.xray_err_host_ip)
            XrayConfig.Problem.DNS_BLOCK -> getString(R.string.xray_err_dns)
            XrayConfig.Problem.BAD_RULE -> getString(R.string.xray_err_rule)
            else -> getString(R.string.xray_err_outbound)
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun labelOf(field: XrayConfig.Field): Int {
        return when (field) {
            XrayConfig.Field.SERVER_ADDRESS -> R.string.xray_field_server
            XrayConfig.Field.UUID -> R.string.xray_field_uuid
            XrayConfig.Field.SERVER_NAME -> R.string.xray_field_sni
            XrayConfig.Field.PUBLIC_KEY -> R.string.xray_field_pubkey
            XrayConfig.Field.SHORT_ID -> R.string.xray_field_shortid
        }
    }

    /** Shows the card only while values are missing; the counts come from the real fields. */
    private fun renderProgress(missing: List<XrayConfig.Field>?) {
        if (missing.isNullOrEmpty()) {
            b.xrayProgressCard.visibility = View.GONE
            return
        }
        b.xrayProgressCard.visibility = View.VISIBLE
        val total = XrayConfig.Field.values().size
        b.xrayProgress.max = total
        b.xrayProgress.setProgressCompat(total - missing.size, true)
        b.xrayProgressLeft.text = getString(R.string.xray_progress_left, missing.size)
        b.xrayMissing.removeAllViews()
        missing.forEach { b.xrayMissing.addView(buildFieldChip(getString(labelOf(it)))) }
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

    /** Theme colours for the code: keys, values, the placeholders and the error line. */
    private data class Palette(
        val key: Int,
        val value: Int,
        val placeholderText: Int,
        val placeholderBg: Int,
        val errorBg: Int,
    )

    private fun palette(): Palette {
        return Palette(
            key = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary),
            value = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorTertiary),
            placeholderText =
                MaterialColors.getColor(
                    b.root,
                    com.google.android.material.R.attr.colorOnTertiaryContainer
                ),
            placeholderBg =
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorTertiaryContainer),
            errorBg = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorErrorContainer),
        )
    }

    private fun highlight(text: Editable) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, StyleSpan::class.java).forEach { text.removeSpan(it) }

        val colors = palette()
        val source = text.toString()
        val keys = KEY.findAll(source).toList()
        val keyStarts = keys.map { it.range.first }.toSet()
        // values first, then keys, then the error line and placeholders on top
        STRING.findAll(source)
            .filter { it.range.first !in keyStarts }
            .forEach { setFg(text, it.range.first, it.range.last + 1, colors.value) }
        keys.forEach { setFg(text, it.range.first, it.range.last + 1, colors.key) }

        lineRange(source, errorLine)?.let {
            text.setSpan(BackgroundColorSpan(colors.errorBg), it.first, it.last + 1, 0)
        }
        for (range in XrayConfig.placeholderRanges(source)) {
            val end = range.last + 1
            setFg(text, range.first, end, colors.placeholderText)
            text.setSpan(BackgroundColorSpan(colors.placeholderBg), range.first, end, 0)
            text.setSpan(StyleSpan(Typeface.BOLD), range.first, end, 0)
        }
    }

    private fun setFg(text: Editable, start: Int, end: Int, color: Int) {
        text.setSpan(ForegroundColorSpan(color), start, end, 0)
    }

    /** Character range of the given 1-based line, or null when there is no such line. */
    private fun lineRange(source: String, line: Int): IntRange? {
        if (line <= 0) return null
        val lines = source.split('\n')
        if (line > lines.size) return null
        var start = 0
        for (n in 0 until line - 1) start += lines[n].length + 1
        return start until start + lines[line - 1].length
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
    }

    private fun saveProfile() {
        val text = b.xrayInput.text.toString()
        if (XrayConfig.check(text) != null) {
            refresh()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runner.saveProfile(text)
            withContext(Dispatchers.Main) {
                toast(getString(if (saved) R.string.xray_saved else R.string.xray_save_failed))
            }
        }
    }

    private fun toast(message: String) {
        Utilities.showToastUiCentered(this, message, Toast.LENGTH_SHORT)
    }

    companion object {
        private const val DEBOUNCE_MS = 300L

        // matches a JSON key ("name" followed by a colon) and a string value
        private val KEY = Regex("\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:)")
        private val STRING = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
    }
}
