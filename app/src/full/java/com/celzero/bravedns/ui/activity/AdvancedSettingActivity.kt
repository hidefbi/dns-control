/*
 * Copyright 2024 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.core.view.WindowInsetsControllerCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityAdvancedSettingBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.tour.GuidedTourManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject

class AdvancedSettingActivity : BaseActivity(R.layout.activity_advanced_setting) {
    private val persistentState by inject<PersistentState>()
    private val b by viewBinding(ActivityAdvancedSettingBinding::bind)
    private val memoryValues = arrayOf(32, 64, 128, 256, 512, 1024)

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)
        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
    }

    private fun initView() {
        if (DEBUG) {
            b.settingsExperimentalRl.visibility = View.VISIBLE
            b.dvExperimentalSwitch.isChecked = persistentState.nwEngExperimentalFeatures
            b.settingsAutoDialRl.visibility = View.VISIBLE
            b.dvAutoDialSwitch.isChecked = persistentState.autoDialsParallel
            b.settingsResetTourRl.visibility = View.VISIBLE
            b.settingsPtModeRl.visibility = View.VISIBLE
            updatePtModeDescription()
            b.settingsGoMaxMemoryLl.visibility = View.VISIBLE
            val maxMemMb = (persistentState.goMaxMemory / (1024 * 1024)).toInt()
            val index = memoryValues.indexOf(maxMemMb).coerceAtLeast(0)
            b.goMaxMemorySeekbar.progress = index
            updateMaxMemoryValueText(memoryValues[index])
        } else {
            b.settingsExperimentalRl.visibility = View.GONE
            b.settingsAutoDialRl.visibility = View.GONE
            b.settingsResetTourRl.visibility = View.GONE
            b.settingsPtModeRl.visibility = View.GONE
            b.settingsGoMaxMemoryLl.visibility = View.GONE
        }
    }

    private fun updatePtModeDescription() {
        val modeId = persistentState.advSettingForcePTModeId
        val modeText = if (modeId == -1) {
            "No override"
        } else {
            AppConfig.ProtoTranslationMode.entries.firstOrNull { it.id == modeId }?.name
                ?: "No override"
        }
        b.settingsPtModeDesc.text = "Override: $modeText"
    }

    private fun showPtModeDialog() {
        // "None" as first item means no debug override (normal computed mode)
        val noneLabel = "No override"
        val modeLabels = AppConfig.ProtoTranslationMode.entries.map { it.name }
        val items = (listOf(noneLabel) + modeLabels).toTypedArray()

        val currentId = persistentState.advSettingForcePTModeId
        val checkedItem = if (currentId == -1) {
            0
        } else {
            val idx = AppConfig.ProtoTranslationMode.entries.indexOfFirst { it.id == currentId }
            if (idx == -1) 0 else idx + 1
        }

        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle("Protocol Translation Mode")
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    persistentState.advSettingForcePTModeId = -1
                } else {
                    persistentState.advSettingForcePTModeId =
                        AppConfig.ProtoTranslationMode.entries[which - 1].id
                }
                updatePtModeDescription()
                Logger.i(
                    LOG_TAG_UI,
                    "AdvSetting; pt mode override set to ${items[which]}"
                )
            }
            .create()
            .show()
    }

    private fun setupClickListeners() {

        b.settingsExperimentalRl.setOnClickListener {
            b.dvExperimentalSwitch.isChecked = !b.dvExperimentalSwitch.isChecked
        }

        b.dvExperimentalSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.nwEngExperimentalFeatures = isChecked
        }

        b.settingsAutoDialRl.setOnClickListener {
            b.dvAutoDialSwitch.isChecked = !b.dvAutoDialSwitch.isChecked
        }

        b.dvAutoDialSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.autoDialsParallel = isChecked
        }

        b.settingsResetTourRl.setOnClickListener {
            GuidedTourManager.resetForDebug(persistentState)
            b.settingsResetTourDesc.text = getString(R.string.tour_debug_reset_done)
        }

        b.settingsPtModeRl.setOnClickListener {
            showPtModeDialog()
        }

        b.goMaxMemorySeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val maxMemMb = memoryValues[progress]
                        val maxMemBytes = maxMemMb.toLong() * 1024 * 1024
                        persistentState.goMaxMemory = maxMemBytes
                        updateMaxMemoryValueText(maxMemMb)
                        Logger.i(LOG_TAG_UI, "AdvSetting; goMaxMemory set to $maxMemMb ($maxMemBytes)")
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
    }

    private fun updateMaxMemoryValueText(maxMemMb: Int) {
        if (maxMemMb >= 1024) {
            b.goMaxMemoryValueTxt.text =
                getString(R.string.two_argument_space, (maxMemMb / 1024).toString(), "GB")
        } else {
            b.goMaxMemoryValueTxt.text =
                getString(R.string.two_argument_space, maxMemMb.toString(), "MB")
        }
    }

}
