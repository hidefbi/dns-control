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
package com.celzero.bravedns.ui

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_REGENERATION_PERIOD_DAYS
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getRandomString
import java.util.concurrent.TimeUnit
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.WelcomeActivity
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastO_MR1
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class HomeScreenActivity : BaseActivity(R.layout.activity_home_screen) {
    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()
    private val appConfig by inject<AppConfig>()

    // TODO: see if this can be replaced with a more robust solution
    // keep track of when app went to background
    private var appInBackground = false

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastO_MR1()) {
            Logger.vv(LOG_TAG_UI, "Setting up window insets for Android 27+")
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_view)) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = systemBars.bottom) // Add bottom padding to keep icons visible
                insets
                WindowInsetsCompat.CONSUMED
            }
        }

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        // do not launch on board activity when app is running on TV
        if (persistentState.firstTimeLaunch && !isAppRunningOnTv()) {
            launchOnboardActivity()
            return
        }

        handleFrostEffectIfNeeded(persistentState.theme)

        updateNewVersion()

        setupNavigationItemSelectedListener()


        observeAppState()

        handleOnBackPressed()

        NewSettingsManager.handleNewSettings()

        regenerateFirebaseTokenIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // if app is coming from background, don't reset the activity stack
        if (appInBackground) {
            appInBackground = false
            Logger.d(LOG_TAG_UI, "app restored from background, maintaining activity stack")
        }
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }

    private fun isInForeground(): Boolean {
        return !this.isFinishing && !this.isDestroyed
    }

    private fun regenerateFirebaseTokenIfNeeded() {
        if (Utilities.isFdroidFlavour()) return
        if (!persistentState.firebaseErrorReportingEnabled) return

        val now = System.currentTimeMillis()
        val fortyFiveDaysMs = TimeUnit.DAYS.toMillis(TOKEN_REGENERATION_PERIOD_DAYS)

        var token = persistentState.firebaseUserToken
        var ts = persistentState.firebaseUserTokenTimestamp
        if (token.isBlank() || now - ts > fortyFiveDaysMs) {
            token = getRandomString(TOKEN_LENGTH)
            ts = now
            persistentState.firebaseUserToken = token
            persistentState.firebaseUserTokenTimestamp = ts
            FirebaseErrorReporting.setUserId(token)
        }
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                startActivity(Intent().setClass(this, PauseActivity::class.java))
                finish()
            }
        }
    }

    private fun removeThisMethod() {
        persistentState.goMaxMemory = -1L

        // change the persistent state for defaultDnsUrl, if its google.com (only for v055d)
        // TODO: remove this post v054.
        // this is to fix the default dns url, as the default dns url is changed from
        // dns.google.com to dns.google. In servers.xml default ips available for dns.google
        // so changing the default dns url to dns.google
        if (persistentState.defaultDnsUrl.contains("dns.google.com")) {
            persistentState.defaultDnsUrl = ""
        }
        // if biometric auth is enabled, then set the biometric auth type to 3 (15 minutes)
        if (persistentState.biometricAuth) {
            persistentState.biometricAuthType =
                MiscSettingsActivity.BioMetricType.FIFTEEN_MIN.action
            // reset the bio metric auth time, as now the value is changed from System.currentTimeMillis
            // to SystemClock.elapsedRealtime
            persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
        }

        // reset the local blocklist download from android download manager to custom in v055o
        persistentState.useCustomDownloadManager = true

        // delete residue wgs from database, remove this post v055o
        io { WireguardManager.deleteResidueWgs() }
    }

    private fun launchOnboardActivity() {
        val intent = Intent(this, WelcomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun updateNewVersion() {
        if (!isNewVersion()) return

        val prevVersion = persistentState.appVersion
        // no need to show new settings on first time launch
        if (persistentState.appVersion != 0) {
            // if app version is not 0, then it means the app is updated
            NewSettingsManager.initializeNewSettings()
            Logger.i(LOG_TAG_UI, "app version already set, so its update, showing new settings")
        }
        val version = getLatestVersion()
        Logger.i(LOG_TAG_UI, "new version detected, updating the app version, version: $version")
        persistentState.appVersion = version
        persistentState.showWhatsNewChip = true
        persistentState.appUpdateTimeTs = System.currentTimeMillis()

        // 53-v055u, exempt rethink as the firewall rules is now stricter compared to prev versions.
        // remove this if we have a proper ui to communicate those to users.
        if (prevVersion <= 53) {
            val rethinkUid = android.os.Process.myUid()
            io {
                FirewallManager.exemptRethinkApp(rethinkUid)
            }
        }

        // FIXME: remove this post v054
        removeThisMethod()
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        Logger.i(LOG_TAG_UI, "base version code: ${BuildConfig.BASE_VERSION_CODE}")
        return BuildConfig.BASE_VERSION_CODE
    }

    override fun onStop() {
        super.onStop()
        // mark that app is going to background
        appInBackground = true
        Logger.v(LOG_TAG_UI, "home screen activity is stopped, app going to background")
    }

    private fun handleOnBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val navHostFragment =
                        supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
                    val navController = navHostFragment?.navController
                    val currentId = navController?.currentDestination?.id
                    val homeId = R.id.homeScreenFragment

                    when {
                        currentId == homeId -> {
                            finish()
                        }
                        else -> {
                            // Any other non-home top-level destination (statistics, configure,
                            // about, rethinkPlus), navigate to home and clear the back stack.
                            val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view)
                            btmNavView.selectedItemId = homeId
                            navController?.navigate(
                                homeId,
                                null,
                                NavOptions.Builder().setPopUpTo(homeId, true).build()
                            )
                        }
                    }
                }
            }
        )
    }


    private fun setupNavigationItemSelectedListener() {
        val btmNavView = findViewById<BottomNavigationView>(R.id.nav_view) ?: run {
            Logger.w(LOG_TAG_UI, "setupNavigationItemSelectedListener: BottomNavigationView not found")
            return
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val navController = navHostFragment?.navController ?: run {
            Logger.w(LOG_TAG_UI, "setupNavigationItemSelectedListener: NavController not found")
            return
        }

        val homeId = R.id.homeScreenFragment

        // Keep the rethinkPlus bottom-nav item highlighted whenever the user is on the
        // dashboard (a child destination of rethinkPlus that is not itself a menu item).
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.homeScreenFragment,
                R.id.configureFragment -> {
                    // These are direct menu items: BottomNavigationView updates isChecked
                    // automatically when selectedItemId is set; nothing extra needed here.
                }
                else -> { /* other destinations: no bottom-nav highlight change */ }
            }
        }

        btmNavView.setOnItemSelectedListener { item ->
            val currentId = navController.currentDestination?.id

            // Prevent re-navigating if we are already on this destination (or its child).
            // For rethinkPlus this also covers rethinkPlusDashboardFragment.
            val alreadyThere = currentId == item.itemId
            if (alreadyThere) return@setOnItemSelectedListener false

            when (item.itemId) {
                homeId -> {
                    navController.navigate(
                        homeId,
                        null,
                        NavOptions.Builder().setPopUpTo(homeId, true).build()
                    )
                    true
                }

                else -> {
                    navController.navigate(
                        item.itemId,
                        null,
                        NavOptions.Builder().setPopUpTo(homeId, false).build()
                    )
                    true
                }
            }
        }

        // Tapping an already-selected tab is a no-op (don't re-navigate or recreate).
        btmNavView.setOnItemReselectedListener { /* intentional no-op */ }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
