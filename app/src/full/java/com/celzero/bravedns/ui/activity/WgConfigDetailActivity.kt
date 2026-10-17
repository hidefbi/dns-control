/*
 * Copyright 2023 RethinkDNS and its authors
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
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity.Companion.STATS_POLL_MS
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgHopDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class WgConfigDetailActivity : BaseActivity(R.layout.activity_wg_detail) {
    private val b by viewBinding(ActivityWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var wgPeersAdapter: WgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()
    private var wgType: WgType = WgType.DEFAULT
    private var isRestoredFromState = false

    /** Coroutine that polls VpnController every [STATS_POLL_MS] ms. */
    private var statsJob: Job? = null

    companion object {
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        const val INTENT_EXTRA_WG_TYPE = "WIREGUARD_TUNNEL_TYPE"

        /** Polling interval for live stats. */
        private const val STATS_POLL_MS = 2_000L
    }

    enum class WgType(val value: Int) {
        DEFAULT(0),
        ONE_WG(1);

        fun isOneWg() = this == ONE_WG

        fun isDefault() = this == DEFAULT

        companion object {
            fun fromInt(value: Int): WgType {
                return entries.firstOrNull { it.value == value }
                    ?: throw IllegalArgumentException("Invalid WgType value: $value")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        isRestoredFromState = savedInstanceState != null

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        wgType = WgType.fromInt(intent.getIntExtra(INTENT_EXTRA_WG_TYPE, WgType.DEFAULT.value))
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
        startStatsPolling(configId)
    }

    override fun onPause() {
        super.onPause()
        cancelStatsJob()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "VPN not active, config may not be available")
            Utilities.showToastUiCentered(
                this,
                        getString(R.string.settings_socks5_vpn_disabled_error),
                Toast.LENGTH_LONG
            )
            finish()
            return
        }

        b.editBtn.text = getString(R.string.rt_edit_dialog_positive).uppercase()
        b.deleteBtn.text = getString(R.string.lbl_delete).uppercase()

        b.lockdownTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.firewall_rule_global_lockdown),
                getString(R.string.symbol_lockdown)
            )

        b.catchAllTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.catch_all_wg_dialog_title),
                getString(R.string.symbol_lightening)
            )

        val mobileOnlyExperimentalTxt = getString(
            R.string.two_argument_space,
            getString(R.string.wg_setting_use_on_mobile),
            getString(R.string.lbl_experimental)
        )
        b.useMobileTitleTv.text =
            getString(
                R.string.two_argument_space,
                mobileOnlyExperimentalTxt,
                getString(R.string.symbol_mobile)
            )
        if (wgType.isDefault()) {
            b.wgHeaderTv.text = getString(R.string.lbl_advanced).replaceFirstChar(Char::titlecase)
            b.lockdownRl.visibility = View.VISIBLE
            b.catchAllRl.visibility = View.VISIBLE
            b.oneWgInfoTv.visibility = View.GONE
            b.hopBtnX.visibility = View.VISIBLE
            b.mobileSettingsCard.visibility = View.VISIBLE
        } else if (wgType.isOneWg()) {
            b.wgHeaderTv.text =
                getString(R.string.rt_list_simple_btn_txt).replaceFirstChar(Char::titlecase)
            b.lockdownRl.visibility = View.GONE
            b.catchAllRl.visibility = View.GONE
            b.hopBtnX.visibility = View.GONE
            b.oneWgInfoTv.visibility = View.VISIBLE
            b.applicationsBtnX.isEnabled = false
            b.applicationsBtnX.alpha = 0.5f
            b.mobileSettingsCard.visibility = View.GONE
            b.appsLabel.text = getString(R.string.lbl_all_apps)
        } else {
            // invalid wireguard type, finish the activity
            finish()
            return
        }
        val config = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)

        if (config == null) {
            showInvalidConfigDialog()
            return
        }

        if (mapping != null) {
            // if catch all is enabled, disable the add apps button and lockdown
            b.catchAllCheck.isChecked = mapping.isCatchAll
            if (mapping.isCatchAll) {
                b.applicationsBtnX.isEnabled = false
                b.applicationsBtnX.alpha = 0.5f
                b.appsLabel.text = getString(R.string.lbl_all_apps)
            }
            b.lockdownCheck.isChecked = mapping.isLockdown
            b.useMobileCheck.isChecked = mapping.useOnlyOnMetered
        }

        if (shouldObserveAppsCount()) {
            handleAppsCount()
        } else {
            b.applicationsBtnX.isEnabled = false
            b.applicationsBtnX.alpha = 0.5f
            // texts are updated based on the catch all and one-wg
        }
        io { updateStatusUi(config.getId()) }
        prefillConfig(config)
        refreshHopStatus()
        if (mapping?.isActive == true) {
            resolveClientIps(configId)
        } else {
            b.shimmerClientInfo.stopShimmer()
            b.shimmerClientInfo.visibility = View.GONE
            b.rowClientInfo.visibility = View.GONE
        }
    }

    private suspend fun updateStatusUi(id: Int) {
        val config = WireguardManager.getConfigFilesById(id)
        val cid = ID_WG_BASE + id
        if (config?.isActive == true) {
            val statusPair = VpnController.getProxyStatusById(cid)
            val stats = VpnController.getProxyStats(cid)
            val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            val dnsStatusId = if (persistentState.splitDns) {
                VpnController.getDnsStatus(cid)
            } else {
                null
            }
            uiCtx {
                if (dnsStatusId != null && isDnsError(dnsStatusId) && ps != UIUtils.ProxyStatus.TPU) {
                    // check for dns failure cases and update the UI
                    b.statusText.text = getString(R.string.status_failing)
                        .replaceFirstChar(Char::titlecase)
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceDetailCard.strokeColor = fetchColor(this, R.attr.chipTextNegative)
                } else if (statusPair.first != null) {
                    val handshakeTime = getHandshakeTime(stats).toString()
                    val statusText = getStatusText(ps, handshakeTime, stats, statusPair.second)
                    b.statusText.text = statusText
                } else {
                    if (statusPair.second.isEmpty()) {
                        b.statusText.text =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                    } else {
                        val txt =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase) + " (${statusPair.second})"
                        b.statusText.text = txt
                    }
                }
                val strokeColor = getStrokeColorForStatus(ps, stats)
                b.interfaceDetailCard.strokeWidth = 2
                b.interfaceDetailCard.strokeColor = fetchColor(this, strokeColor)
            }
        } else {
            uiCtx {
                b.interfaceDetailCard.strokeWidth = 0
                b.statusText.text =
                    getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }
    }

    private fun cancelStatsJob() {
        if (statsJob?.isActive == true) statsJob?.cancel()
        statsJob = null
    }

    /**
     * Launches a coroutine that fetches and applies live stats every
     * [STATS_POLL_MS] ms. Client IPs are resolved separately by
     * [resolveClientIps]: this method only handles VpnController stats.
     */
    private fun startStatsPolling(id: Int) {
        cancelStatsJob()
        val config = WireguardManager.getConfigFilesById(id)
        if (config?.isActive != true) return

        statsJob = io {
            while (true) {
                try {
                    fetchAndApplyStats(id)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "stats poll error: ${e.message}")
                }
                delay(STATS_POLL_MS.milliseconds)
            }
        }
    }

    private suspend fun fetchAndApplyStats(id: Int) {
        updateStatusUi(id)
    }

    /**
     * Resolves client tunnel IPs (and all [IPMetadata]) on IO and applies them on the main thread.
     * Runs independently of stats polling so the table renders fast.
     */
    private fun resolveClientIps(id: Int) {
        showClientIpShimmer()
        io {
            var ip4Meta: IPMetadata? = null
            var ip6Meta: IPMetadata? = null
            try {
                val cid = ID_WG_BASE + id
                Logger.d(LOG_TAG_UI, "resolveClientIps[$id]: live fetch")
                val client = VpnController.getWgClientInfoById(cid)
                ip4Meta = client?.iP4()
                ip6Meta = client?.iP6()
                Logger.v(LOG_TAG_UI, "client ips resolved for $id: ip4: ${ip4Meta}, ip6: $ip6Meta")
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "failed to resolve client ips: ${e.message}")
            }
            uiCtx { applyClientIps(ip4Meta, ip6Meta) }
        }
    }

    /** Show inline shimmer placeholders for client value cell. */
    private fun showClientIpShimmer() {
        b.shimmerClientInfo.visibility = View.VISIBLE
        b.shimmerClientInfo.startShimmer()
        b.valueIpv4.visibility = View.GONE
    }

    /**
     * Replaces the inline shimmer with rich IP + metadata text.
     * ASN / location / providerUrl are embedded inside
     */
    private fun applyClientIps(ip4: IPMetadata?, ip6: IPMetadata?) {
        val na = getString(R.string.lbl_not_available_short)

        // client info
        b.shimmerClientInfo.stopShimmer()
        b.shimmerClientInfo.visibility = View.GONE
        b.valueIpv4.visibility = View.VISIBLE
        b.valueIpv4.text = ip4?.takeIf { it.ip?.isNotBlank() == true }
            ?.let { buildIpDetailSpan(it) }
            ?: na
        if (ip6 == null) {
            b.valueIpv6.visibility = View.GONE
        } else {
            b.valueIpv6.text = ip6.takeIf { it.ip?.isNotBlank() == true }
                ?.let { buildIpDetailSpan(it) }
                ?: na
        }
    }

    /**
     * Builds a multi-line [SpannableStringBuilder] for a single [IPMetadata].
     *
     * ```
     * 10.0.0.1
     * ASN  AS13335 · Cloudflare Inc · net.cloudflare.com
     * LOC  Frankfurt · 50.1109°, 8.6821°
     * via  cloudflare.com
     * ```
     */
    private fun buildIpDetailSpan(meta: IPMetadata): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val labelColor = fetchColor(this, R.attr.primaryLightColorText)

        fun styleLabel(start: Int, end: Int) {
            sb.setSpan(ForegroundColorSpan(labelColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.80f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun appendLine(label: String, value: String) {
            if (value.isBlank()) return
            sb.append("\n")
            val ls = sb.length
            sb.append(label)
            styleLabel(ls, sb.length)
            sb.append("  $value")
        }

        // ip
        val ipStart = 0
        sb.append(meta.ip ?: "")
        val ipEnd = sb.length
        sb.setSpan(TypefaceSpan("monospace"), ipStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.07f),   ipStart, ipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // asn
        val asnParts = buildList {
            val asn = meta.asn ?: ""
            val org = meta.asnOrg ?: ""
            val dom = meta.asnDom ?: ""
            if (asn.isNotBlank()) add(asn)
            if (org.isNotBlank()) add(org)
            if (dom.isNotBlank()) add(dom)
        }
        if (asnParts.isNotEmpty()) appendLine("ASN", asnParts.joinToString("  ·  "))

        val locParts = buildList {
            val city = meta.city ?: ""
            val cc = meta.cc ?: ""
            val lat = meta.lat
            val lon = meta.lon
            if (city.isNotBlank()) add(city)
            if (cc.isNotBlank()) add(cc)
            if (lat != 0.0 || lon != 0.0) add(String.format(Locale.US, "%.4f°, %.4f°", lat, lon))
        }
        if (locParts.isNotEmpty()) appendLine("LOC", locParts.joinToString("  ·  "))

        return sb
    }

    private fun isDnsError(statusId: Int?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        handshakeTime: String? = null,
        stats: RouterStats?,
        errMsg: String? = null
    ): String {
        if (status == null) {
            val txt = if (!errMsg.isNullOrEmpty()) {
                getString(R.string.status_waiting) + " ($errMsg)"
            } else {
                getString(R.string.status_waiting)
            }
            return txt.replaceFirstChar(Char::titlecase)
        }

        // no need to check for lastOk/since for paused wg
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)
        }

        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }

        val baseText = getString(UIUtils.getProxyStatusStringRes(status.id))
            .replaceFirstChar(Char::titlecase)

        return if (stats?.lastOK != 0L && handshakeTime != null) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    private fun getHandshakeTime(stats: RouterStats?): CharSequence {
        if (stats == null) {
            return ""
        }
        if (stats.lastOK == 0L) {
            return ""
        }
        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        return DateUtils.getRelativeTimeSpanString(
            stats.lastOK,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
        return when (status) {
            UIUtils.ProxyStatus.TOK -> if (isFailing) R.attr.chipTextNeutral else R.attr.accentGood
            UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ, UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative // TKO, TEND
        }
    }

    private fun showInvalidConfigDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.lbl_wireguard))
        builder.setMessage(getString(R.string.config_invalid_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ ->
            finish()
        }
        builder.setNeutralButton(getString(R.string.lbl_delete)) { _, _ ->
            io {
                WireguardManager.deleteConfig(configId)
            }
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun shouldObserveAppsCount(): Boolean {
        return !wgType.isOneWg() && !b.catchAllCheck.isChecked
    }

    private fun prefillConfig(config: Config) {
        wgInterface = config.getInterface()
        peers.clear()
        peers.addAll(config.getPeers() ?: emptyList())
        if (wgInterface == null) {
            return
        }
        b.configNameText.visibility = View.VISIBLE
        if (!isRestoredFromState) {
            b.configNameText.setText(config.getName(), TextView.BufferType.NORMAL)
        }
        b.configIdText.text =
            getString(R.string.single_argument_parenthesis, config.getId().toString())

        setPeersAdapter()
        // show dns servers if in one-wg mode
        if (wgType.isOneWg()) {
            b.dnsServersLabel.visibility = View.VISIBLE
            b.dnsServersText.visibility = View.VISIBLE
            var dns = wgInterface?.dnsServers?.joinToString { it.hostAddress ?: "" }
            val searchDomains = wgInterface?.dnsSearchDomains?.joinToString { it }
            dns =
                if (!searchDomains.isNullOrEmpty()) {
                    "$dns,$searchDomains"
                } else {
                    dns
                }
            b.dnsServersText.text = dns
        } else {
            b.publicKeyLabel.visibility = View.VISIBLE
            b.publicKeyText.visibility = View.VISIBLE
            b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64()
            b.dnsServersLabel.visibility = View.GONE
            b.dnsServersText.visibility = View.GONE
        }


    }

    private fun handleAppsCount() {
        val id = ID_WG_BASE + configId
        b.applicationsBtnX.isEnabled = true
        b.applicationsBtnX.alpha = 1.0f
        mappingViewModel.getAppCountById(id).observe(this) {
            // Don't override the "All apps" state when catch-all or one-wg is active
            if (b.catchAllCheck.isChecked || wgType.isOneWg()) return@observe
            if (it == 0) {
                b.appsLabel.setTextColor(fetchColor(this, R.attr.accentBad))
            } else {
                b.appsLabel.setTextColor(fetchColor(this, R.attr.accentGood))
            }
            b.appsLabel.text = getString(R.string.two_argument_parenthesis, getString(R.string.apps_info_title), it)
        }
    }

    private fun setupClickListeners() {
        b.editBtn.setOnClickListener {
            val intent = Intent(this, WgConfigEditorActivity::class.java)
            intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, configId)
            intent.putExtra(INTENT_EXTRA_WG_TYPE, wgType.value)
            this.startActivity(intent)
        }

        b.addPeerFab.setOnClickListener { openAddPeerDialog() }

        b.applicationsBtnX.setOnClickListener {
            val proxyName = WireguardManager.getConfigName(configId)
            openAppsDialog(proxyName)
        }

        b.deleteBtn.setOnClickListener { showDeleteInterfaceDialog() }


        b.publicKeyLabel.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.lockdownRl.setOnClickListener {
            b.lockdownCheck.isChecked = !b.lockdownCheck.isChecked
            updateLockdown(b.lockdownCheck.isChecked)
        }

        b.lockdownCheck.setOnClickListener { updateLockdown(b.lockdownCheck.isChecked) }

        b.catchAllRl.setOnClickListener {
            b.catchAllCheck.isChecked = !b.catchAllCheck.isChecked
            updateCatchAll(b.catchAllCheck.isChecked)
        }

        b.catchAllCheck.setOnClickListener { updateCatchAll(b.catchAllCheck.isChecked) }

        b.useMobileRl.setOnClickListener {
            b.useMobileCheck.callOnClick()
        }

        b.useMobileCheck.setOnClickListener {
            val sid = ID_WG_BASE + configId
            if (WgHopManager.isAlreadyHop(sid)) {
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.wg_mobile_only_hop_err),
                    Toast.LENGTH_LONG
                )
                b.useMobileCheck.isChecked = !b.useMobileCheck.isChecked
                return@setOnClickListener
            }
            updateUseOnMobileNetwork(b.useMobileCheck.isChecked)
            if (b.useMobileCheck.isChecked) {
                // Enable experimental-dependent settings when experimental features are enabled
                if (persistentState.enableStabilityDependentSettings()) {
                    SnackbarHelper.showStabilityProgram(b.root, persistentState)
                }
            }
        }

        b.hopBtnX.setOnClickListener {
            val mapping = WireguardManager.getConfigFilesById(configId)
            if (mapping == null) {
                showInvalidConfigDialog()
                return@setOnClickListener
            }

            if (mapping.isActive || mapping.isLockdown || mapping.isCatchAll) {
                io {
                    val sid = ID_WG_BASE + configId
                    val isVia = WgHopManager.isAlreadyHop(sid)
                    if (isVia) {
                        uiCtx {
                            Utilities.showToastUiCentered(
                                this,
                                getString(
                                    R.string.hop_error_toast_msg_1,
                                    getString(R.string.lbl_hop)
                                ),
                                Toast.LENGTH_LONG
                            )
                        }
                        return@io
                    }
                    val hopId = WgHopManager.getHop(configId)
                    Logger.d(LOG_TAG_PROXY, "hop result: $hopId")
                    val iid = convertStringIdToId(hopId)
                    val hopables = WgHopManager.getHopableWgs(configId)
                    uiCtx {
                        if (hopables.isEmpty()) {
                            Utilities.showToastUiCentered(
                                this,
                                getString(R.string.hop_error_toast_msg_2),
                                Toast.LENGTH_LONG
                            )
                        } else {
                            openHopDialog(hopables, iid)
                        }
                    }
                }
            } else {
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.wireguard_no_config_msg),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (_: Exception) {
            Logger.i(LOG_TAG_PROXY, "err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    private fun updateUseOnMobileNetwork(enabled: Boolean) {
        io { WireguardManager.updateUseOnMobileNetworkConfig(configId, enabled) }
        logEvent(
            "WireGuard Use on Mobile Networks",
            "User ${if (enabled) "enabled" else "disabled"} use on mobile networks for WireGuard config with id $configId"
        )
    }

    private fun updateLockdown(enabled: Boolean) {
        io { WireguardManager.updateLockdownConfig(configId, enabled) }
    }

    private fun updateCatchAll(enabled: Boolean) {
        io {
            if (!VpnController.hasTunnel()) {
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    b.catchAllCheck.isChecked = !enabled
                }
                return@io
            }

            // checks only for app mode, so show appropriate error message if not enabled
            if (!WireguardManager.canEnableProxy()) {
                Logger.i(
                    LOG_TAG_PROXY,
                    "not in DNS+Firewall mode, cannot enable WireGuard"
                )
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.wireguard_dns_mode_conflict),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }

            if (WireguardManager.oneWireGuardEnabled()) {
                // this should not happen, ui is disabled if one wireGuard is enabled
                Logger.w(LOG_TAG_PROXY, "one wireGuard is already enabled")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.wireguard_one_wg_active_conflict),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }


            val config = WireguardManager.getConfigFilesById(configId)
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "updateCatchAll: config not found for $configId")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    showInvalidConfigInfoDialog()
                }
                return@io
            }

            WireguardManager.updateCatchAllConfig(configId, enabled)
            logEvent(
                "WireGuard Catch All apps",
                "User ${if (enabled) "enabled" else "disabled"} catch all apps for WireGuard config with id $configId"
            )
            uiCtx {
                b.applicationsBtnX.isEnabled = !enabled
                b.applicationsBtnX.alpha = if (enabled) 0.5f else 1.0f
                if (enabled) {
                    b.appsLabel.text = getString(R.string.lbl_all_apps)
                } else {
                    handleAppsCount()
                }
            }
        }
    }

    private fun openAppsDialog(proxyName: String) {
        val proxyId = ID_WG_BASE + configId
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        // Remove any observers registered by previous openAppsDialog() calls so that stale
        // adapters from dismissed dialogs do not continue to receive paging data.
        mappingViewModel.apps.removeObservers(this)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }

    private fun refreshHopStatus() {
        io {
            val hopIdStr = WgHopManager.getHop(configId)
            val hopName: String? = if (hopIdStr.isNotEmpty()) {
                val id = convertStringIdToId(hopIdStr)
                WireguardManager.getConfigById(id)?.getName()
            } else {
                null
            }
            uiCtx { updateHopStatusLabel(hopName) }
        }
    }

    private fun updateHopStatusLabel(hopName: String?) {
        if (hopName != null) {
            b.hopBadge.visibility = View.VISIBLE
            b.hopLabel.setTextColor(fetchColor(this, R.attr.accentGood))
            b.hopIcon.alpha = 1.0f
        } else {
            b.hopBadge.visibility = View.GONE
            b.hopLabel.setTextColor(fetchColor(this, R.attr.primaryTextColor))
            b.hopIcon.alpha = 0.5f
        }
    }

    private fun openHopDialog(hopables: List<Config>, selectedId: Int) {
        val curr = WireguardManager.getConfigById(configId)
        if (curr == null) {
            Utilities.showToastUiCentered(
                this,
                getString(R.string.config_invalid_desc),
                Toast.LENGTH_SHORT
            )
            return
        }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val hopDialog = WgHopDialog(this, themeId, configId, hopables, selectedId)
        hopDialog.setCanceledOnTouchOutside(false)
        hopDialog.setOnDismissListener { refreshHopStatus() }
        hopDialog.show()
    }

    private fun showInvalidConfigInfoDialog() {
        if (isFinishing) return
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.wireguard_invalid_config_title))
        builder.setMessage(getString(R.string.wireguard_invalid_config_message))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_dismiss)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun showDeleteInterfaceDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        val delText =
            getString(
                R.string.two_argument_space,
                getString(R.string.config_delete_dialog_title),
                getString(R.string.lbl_wireguard)
            )
        builder.setTitle(delText)
        builder.setMessage(getString(R.string.config_delete_dialog_desc))
        builder.setCancelable(true)
        builder.setPositiveButton(delText) { _, _ ->
            io {
                WireguardManager.deleteConfig(configId)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    )
                    finish()
                }
                logEvent("Delete WireGuard config", "User deleted WireGuard config with id $configId")
            }
        }

        builder.setNegativeButton(this.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun openAddPeerDialog() {
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val addPeerDialog = WgAddPeerDialog(this, themeId, configId, null)
        addPeerDialog.setCanceledOnTouchOutside(false)
        addPeerDialog.show()
        addPeerDialog.setOnDismissListener {
            if (wgPeersAdapter != null) {
                wgPeersAdapter?.dataChanged()
            } else {
                setPeersAdapter()
            }
        }
    }

    private fun setPeersAdapter() {
        layoutManager = LinearLayoutManager(this)
        b.peersList.layoutManager = layoutManager
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        wgPeersAdapter = WgPeersAdapter(this, themeId, configId, peers)
        b.peersList.adapter = wgPeersAdapter
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }
}
