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
package com.celzero.bravedns.ui.activity

import android.Manifest
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityMiscSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.LauncherSwitcher
import com.celzero.bravedns.ui.activity.AppLockActivity.Companion.APP_LOCK_ALIAS
import com.celzero.bravedns.ui.activity.AppLockActivity.Companion.HOME_ALIAS
import com.celzero.bravedns.ui.bottomsheet.BackupRestoreBottomSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class MiscSettingsActivity : BaseActivity(R.layout.activity_misc_settings) {
    private val b by viewBinding(ActivityMiscSettingsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()

    private var isThemeChanged: Boolean = false
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    enum class BioMetricType(val action: Int, val mins: Long) {
        OFF(BIOMETRIC_ACTION_OFF, BIOMETRIC_MINS_OFF),
        IMMEDIATE(BIOMETRIC_ACTION_IMMEDIATE, BIOMETRIC_MINS_IMMEDIATE),
        FIVE_MIN(BIOMETRIC_ACTION_FIVE_MIN, BIOMETRIC_MINS_FIVE),
        FIFTEEN_MIN(BIOMETRIC_ACTION_FIFTEEN_MIN, BIOMETRIC_MINS_FIFTEEN);

        companion object {
            fun fromValue(action: Int): BioMetricType {
                return entries.firstOrNull { it.action == action } ?: OFF
            }
        }

        fun enabled(): Boolean {
            return this != OFF
        }
    }

    companion object {
        private const val SCHEME_PACKAGE = "package"
        const val THEME_CHANGED_RESULT = 24
        private const val KEY_THEME_CHANGE = "key_theme_change"
        private const val CLICK_DELAY_SHORT_MS = 500L

        // Biometric type constants
        private const val BIOMETRIC_ACTION_OFF = 0
        private const val BIOMETRIC_ACTION_IMMEDIATE = 1
        private const val BIOMETRIC_ACTION_FIVE_MIN = 2
        private const val BIOMETRIC_ACTION_FIFTEEN_MIN = 3
        private const val BIOMETRIC_MINS_OFF = -1L
        private const val BIOMETRIC_MINS_IMMEDIATE = 0L
        private const val BIOMETRIC_MINS_FIVE = 5L
        private const val BIOMETRIC_MINS_FIFTEEN = 15L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            isThemeChanged = savedInstanceState.getBoolean(KEY_THEME_CHANGE, false)
        }

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        registerForActivityResult()
        initView()
        setupClickListeners()
        setupOnBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_THEME_CHANGE, isThemeChanged)
    }

    private fun setupOnBackPressed() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isThemeChanged) {
                    setResult(THEME_CHANGED_RESULT)
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun initView() {

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
            b.dividerCheckUpdate.visibility = View.GONE
        } else {
            b.settingsActivityCheckUpdateRl.visibility = View.VISIBLE
            b.dividerCheckUpdate.visibility = View.VISIBLE
            // check for app updates
            b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate
        }

        // add ipInfo inc to the desc
        b.genSettingsIpInfoDesc.text =
            getString(R.string.download_ip_info_desc, getString(R.string.lbl_ipinfo_inc))


        // for app locale (default system/user selected locale)
        if (isAtleastT()) {
            try {
                val localeManager = getSystemService(LocaleManager::class.java)
                val currentAppLocales: LocaleList = localeManager?.applicationLocales ?: LocaleList.getEmptyLocaleList()
                b.settingsLocaleDesc.text =
                    if (currentAppLocales.isEmpty) {
                        getString(R.string.settings_locale_desc)
                    } else {
                        currentAppLocales[0]?.displayName ?: getString(R.string.settings_locale_desc)
                    }
            } catch (e: Exception) {
                b.settingsLocaleDesc.text = getString(R.string.settings_locale_desc)
            }
        } else {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            b.settingsLocaleDesc.text =
                if (appLocales.isEmpty) {
                    getString(R.string.settings_locale_desc)
                } else {
                    appLocales.get(0)?.displayName ?: getString(R.string.settings_locale_desc)
                }
        }
        // biometric authentication
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            val bioMetricType = BioMetricType.fromValue(persistentState.biometricAuthType)
            when (bioMetricType) {
                BioMetricType.OFF -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_0)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.IMMEDIATE -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_1)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.FIVE_MIN -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_2)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.FIFTEEN_MIN -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_3)
                    )
                    b.settingsBiometricDesc.text = txt
                }
            }
        } else {
            b.settingsBiometricRl.visibility = View.GONE
            b.dividerBiometric.visibility = View.GONE
        }

        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        b.dvIpInfoSwitch.isChecked = persistentState.downloadIpInfo

        b.tombstoneAppSwitch.isChecked = persistentState.tombstoneApps

        displayAppThemeUi()
        displayNotificationActionUi()
    }

    private fun displayNotificationActionUi() {
        b.settingsActivityNotificationRl.isEnabled = true
        when (
            NotificationActionType.getNotificationActionType(persistentState.notificationActionType)
        ) {
            NotificationActionType.PAUSE_STOP -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc1)
                    )
            }

            NotificationActionType.DNS_FIREWALL -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc2)
                    )
            }

            NotificationActionType.NONE -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc3)
                    )
            }
        }
    }

    private fun displayAppThemeUi() {
        b.settingsActivityThemeRl.isEnabled = true
        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_1)
                    )
            }

            Themes.LIGHT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_2)
                    )
            }

            Themes.DARK.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_3)
                    )
            }

            Themes.TRUE_BLACK.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_4)
                    )
            }

            Themes.LIGHT_PLUS.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_5)
                    )
            }

            Themes.DARK_PLUS.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_6)
                    )
            }

            Themes.DARK_FROST.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_7)
                    )
            }

            else -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_4)
                    )
            }
        }
    }

    private fun setupClickListeners() {
        b.settingsActivityCheckUpdateRl.setOnClickListener {
            b.settingsActivityCheckUpdateSwitch.isChecked =
                !b.settingsActivityCheckUpdateSwitch.isChecked
        }

        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton,
                                                                         b: Boolean ->
            persistentState.checkForAppUpdate = b
        }


        b.settingsActivityThemeRl.setOnClickListener {
            enableAfterDelay(CLICK_DELAY_SHORT_MS, b.settingsActivityThemeRl)
            showThemeDialog()
        }

        // Ideally this property should be part of VPN category / section.
        // As of now the VPN section will be disabled when the
        // VPN is in lockdown mode.
        // TODO - Find a way to place this property to place in correct section.
        b.settingsActivityNotificationRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityNotificationRl)
            showNotificationActionDialog()
        }

        b.settingsActivityAppNotificationPersistentRl.setOnClickListener {
            b.settingsActivityAppNotificationPersistentSwitch.isChecked =
                !b.settingsActivityAppNotificationPersistentSwitch.isChecked
        }

        b.settingsActivityAppNotificationPersistentSwitch.setOnCheckedChangeListener { _: CompoundButton,
                                                                                       b: Boolean ->
            persistentState.persistentNotification = b
        }

        b.settingsActivityImportExportRl.setOnClickListener { invokeImportExport() }

        b.settingsActivityAppNotificationSwitch.setOnClickListener {
            b.settingsActivityAppNotificationSwitch.isChecked =
                !b.settingsActivityAppNotificationSwitch.isChecked
            invokeNotificationPermission()
        }

        b.settingsLocaleRl.setOnClickListener { invokeChangeLocaleDialog() }

        b.settingsBiometricRl.setOnClickListener {
            showBiometricDialog()
        }

        b.settingsBiometricImg.setOnClickListener {
            showBiometricDialog()
        }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean
            ->
            persistentState.prefAutoStartBootUp = isChecked
        }

        b.donateBtcCopy.setOnClickListener {
            UIUtils.clipboardCopy(this, getString(R.string.donate_btc_address), getString(R.string.donate_btc_label))
            showToastUiCentered(this, getString(R.string.donate_copied), Toast.LENGTH_SHORT)
        }

        b.donateEthCopy.setOnClickListener {
            UIUtils.clipboardCopy(this, getString(R.string.donate_eth_address), getString(R.string.donate_eth_label))
            showToastUiCentered(this, getString(R.string.donate_copied), Toast.LENGTH_SHORT)
        }

        b.settingsTaskerRl.setOnClickListener {
            showAppTriggerPackageDialog(this , onPackageSet = { packageName ->
                persistentState.appTriggerPackages = packageName
            })
        }

        b.settingsIpInfoRl.setOnClickListener {
            b.dvIpInfoSwitch.isChecked = !b.dvIpInfoSwitch.isChecked
        }

        b.dvIpInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.downloadIpInfo = isChecked
        }

        b.tombstoneAppRl.setOnClickListener {
            b.tombstoneAppSwitch.isChecked = !b.tombstoneAppSwitch.isChecked
        }

        b.tombstoneAppSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tombstoneApps = isChecked
            io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE) }
        }

    }


    private fun showBiometricDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_biometric_dialog_heading))
        // show an list of options disable, enable immediate, ask after 5 min, ask after 15 min
        val item0 = getString(R.string.settings_biometric_dialog_option_0)
        val item1 = getString(R.string.settings_biometric_dialog_option_1)
        val item2 = getString(R.string.settings_biometric_dialog_option_2)
        val item3 = getString(R.string.settings_biometric_dialog_option_3)
        val items = arrayOf(item0, item1, item2, item3)

        val checkedItem = persistentState.biometricAuthType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.biometricAuthType == which) {
                return@setSingleChoiceItems
            }
            val bioMetricType = BioMetricType.fromValue(which)
            when (bioMetricType) {
                BioMetricType.OFF -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_0))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.OFF.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.IMMEDIATE -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_1))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.IMMEDIATE.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.FIVE_MIN -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_2))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.FIVE_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }

                BioMetricType.FIFTEEN_MIN -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_3))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.FIFTEEN_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }
            }
            if (bioMetricType.enabled()) {
                LauncherSwitcher.switchLauncherAlias(applicationContext, APP_LOCK_ALIAS, HOME_ALIAS)
            } else {
                LauncherSwitcher.switchLauncherAlias(applicationContext, HOME_ALIAS, APP_LOCK_ALIAS)
            }
        }
        alertBuilder.create().show()
    }

    private fun invokeChangeLocaleDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_locale_dialog_title))
        val languages = getLocaleEntries()
        val items = languages.keys.toTypedArray()
        val selectedKey = AppCompatDelegate.getApplicationLocales()
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
        var checkedItem = 0
        languages.values.forEachIndexed { index, s ->
            if (s == selectedKey) {
                checkedItem = index
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val item = items[which]
            val tag = languages[item].orEmpty()
            if (tag.isBlank()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // https://developer.android.com/guide/topics/resources/app-languages#app-language-settings
                val locale = Locale.forLanguageTag(languages.getOrDefault(item, "en-US"))
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
            }
        }
        alertBuilder.create().show()
    }

    // read the list of supported languages from locale_config.xml
    private fun getLocalesFromLocaleConfig(): LocaleListCompat {
        val tagsList = mutableListOf<CharSequence>()
        try {
            val xpp: XmlPullParser = resources.getXml(R.xml.locale_config)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG) {
                    if (xpp.name == "locale") {
                        tagsList.add(xpp.getAttributeValue(0))
                      }
                  }
                  xpp.next()
              }
          } catch (e: XmlPullParserException) {
          } catch (e: IOException) {
          }

          return LocaleListCompat.forLanguageTags(tagsList.joinToString(","))
      }

      private fun getLocaleEntries(): Map<String, String> {
          val localeList = getLocalesFromLocaleConfig()
          val map = mutableMapOf<String, String>()

          // Add default/system locale option first
          map[getString(R.string.settings_locale_dialog_default)] = ""
          
          // Add all available locales from locale_config.xml
          for (i in 0 until localeList.size()) {
              localeList[i]?.let { locale ->
                  map[locale.displayName] = locale.toLanguageTag()
              }
          }
          return map
      }

      private fun invokeImportExport() {
          if (this.isFinishing || this.isDestroyed) {
              return
          }

          val bottomSheetFragment = BackupRestoreBottomSheet()
          bottomSheetFragment.show(this.supportFragmentManager, bottomSheetFragment.tag)
      }

      fun showAppTriggerPackageDialog(context: Context, onPackageSet: (String) -> Unit) {
          val editText = AppCompatEditText(context).apply {
              hint = context.getString(R.string.adv_tasker_dialog_edit_hint)
              inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
              setHorizontallyScrolling(true)
              if (persistentState.appTriggerPackages.isNotEmpty()) {
                  setText(persistentState.appTriggerPackages)
              }
              setPadding(50, 40, 50, 40)
              gravity = Gravity.TOP or Gravity.START
              android.R.style.Widget_Material_EditText
          }

          val selectableTextView = AppCompatTextView(context).apply {
              text = context.getString(R.string.adv_tasker_dialog_msg)
              setTextIsSelectable(true)
              setPadding(50, 40, 50, 0)
              textSize = 16f
          }

          val instructionsTextView = AppCompatTextView(context).apply {
              text = context.getString(R.string.adv_tasker_dialog_instructions)
              setTextIsSelectable(true)
              setPadding(50, 40, 50, 0)
              textSize = 16f
          }

          // add a LinearLayout as the single child of the ScrollView, then add the text view and
          // edit text to the LinearLayout.
          val linearLayout = LinearLayout(context).apply {
              orientation = LinearLayout.VERTICAL
              addView(selectableTextView)
              addView(editText)
              addView(instructionsTextView)
          }

          val scrollView = ScrollView(context).apply {
              setPadding(40, 10, 40, 0)
              addView(linearLayout)
          }

          AlertDialog.Builder(context)
              .setTitle(context.getString(R.string.adv_taster_title))
              .setView(scrollView)
              .setPositiveButton(context.getString(R.string.lbl_save)) { dialog, _ ->
                  val pkgName = editText.text.toString().trim()
                  if (pkgName.isNotEmpty()) {
                      onPackageSet(pkgName)
                  }
                  dialog.dismiss()
              }
              .setNegativeButton(context.getString(R.string.lbl_cancel)) { dialog, _ -> dialog.cancel() }
              .show()
      }


    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items = if (isAtleastS()) {
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4),
                getString(R.string.settings_theme_dialog_themes_5),
                getString(R.string.settings_theme_dialog_themes_6),
                getString(R.string.settings_theme_dialog_themes_7)
            )
        } else {
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4),
                getString(R.string.settings_theme_dialog_themes_5),
                getString(R.string.settings_theme_dialog_themes_6)
            )
        }
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            isThemeChanged = true
            when (which) {
                Themes.SYSTEM_DEFAULT.id -> {
                    if (isDarkThemeOn()) {
                        setThemeRecreate(R.style.AppTheme)
                    } else {
                        setThemeRecreate(R.style.AppThemeWhite)
                    }
                }
                Themes.LIGHT.id -> {
                    setThemeRecreate(R.style.AppThemeWhite)
                }
                Themes.DARK.id -> {
                    setThemeRecreate(R.style.AppTheme)
                }
                Themes.TRUE_BLACK.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlack)
                }
                Themes.LIGHT_PLUS.id -> {
                    setThemeRecreate(R.style.AppThemeWhitePlus)
                }
                Themes.DARK_PLUS.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlackPlus)
                }
                Themes.DARK_FROST.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlackFrost)
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun setThemeRecreate(theme: Int) {
        setTheme(theme)
        recreate()
    }

    private fun showNotificationActionDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_notification_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_notification_dialog_option_1),
                getString(R.string.settings_notification_dialog_option_2),
                getString(R.string.settings_notification_dialog_option_3)
            )
        val checkedItem = persistentState.notificationActionType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.notificationActionType == which) {
                return@setSingleChoiceItems
            }

            when (NotificationActionType.getNotificationActionType(which)) {
                NotificationActionType.PAUSE_STOP -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc1)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.PAUSE_STOP.action
                }

                NotificationActionType.DNS_FIREWALL -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc2)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.DNS_FIREWALL.action
                }

                NotificationActionType.NONE -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc3)
                        )
                    persistentState.notificationActionType = NotificationActionType.NONE.action
                }
            }
        }
        alertBuilder.create().show()
    }

    override fun onResume() {
        super.onResume()
        // app notification permission android 13
        showEnableNotificationSettingIfNeeded()
    }

    private fun registerForActivityResult() {
        // Sets up permissions request launcher for the generic app-notification switch.
        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
                if (it) {
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = true
                } else {
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = false
                    invokeAndroidNotificationSetting()
                }
            }
    }

    private fun invokeNotificationPermission() {
        if (!isAtleastT()) {
            // notification permission is needed for version 13 or above
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification already granted
            invokeAndroidNotificationSetting()
            return
        }

        try {
            notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
            // Fallback: try opening notification settings directly
            invokeAndroidNotificationSetting()
        } catch (e: Exception) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun invokeAndroidNotificationSetting() {
        val packageName = this.packageName
        try {
            val intent = Intent()
            if (Utilities.isAtleastO()) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = "$SCHEME_PACKAGE:$packageName".toUri()
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.notification_screen_error),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun showEnableNotificationSettingIfNeeded() {
        if (!isAtleastT()) {
            // notification permission and persistent is only needed for version 13 or above
            b.settingsActivityAppNotificationRl.visibility = View.GONE
            b.dividerNotification.visibility = View.GONE
            b.settingsActivityAppNotificationPersistentRl.visibility = View.GONE
            b.dividerAppNotification.visibility = View.GONE
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification permission is granted to the app, enable switch
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.dividerNotification.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = true
            if (!persistentState.shouldRequestNotificationPermission) {
                // if the user has already granted the permission, set the flag to true
                persistentState.shouldRequestNotificationPermission = true
            }
        } else {
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.dividerNotification.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = false
            persistentState.shouldRequestNotificationPermission = false
        }
        b.settingsActivityAppNotificationPersistentRl.visibility = View.VISIBLE
        b.dividerAppNotification.visibility = View.VISIBLE
        b.settingsActivityAppNotificationPersistentSwitch.isChecked =
            persistentState.persistentNotification
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isNotificationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }

    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
