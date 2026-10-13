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
package com.celzero.bravedns.service

import android.content.Context
import android.text.format.DateUtils
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_WG_DIR
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.UIUtils.getRelativeTimeSpan
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object WireguardManager : KoinComponent {

    private val db: WgConfigFilesRepository by inject()
    private val applicationContext: Context by inject()
    private val appConfig: AppConfig by inject()
    private val persistentState: PersistentState by inject()

    const val WG_UPTIME_THRESHOLD = 5 * DateUtils.SECOND_IN_MILLIS

    const val NOTIF_CHANNEL_ID_WIREGUARD_ALERTS = "WireGuard_Alerts"

    // contains db values of wg configs (db stores path of the config file)
    private var mappings: CopyOnWriteArraySet<WgConfigFilesImmutable> = CopyOnWriteArraySet()

    // contains parsed wg configs
    private val configs: CopyOnWriteArraySet<Config> = CopyOnWriteArraySet()

    // mutex for the migration so that concurrent load() calls
    // do not race while decrypting and overwriting the same files
    private val migrationMutex = Mutex()

    // retrieve last added config id
    private var lastAddedConfigId = 0

    // invalid config id
    const val INVALID_CONF_ID = -1

    const val WARP_ID = 1
    const val WARP_NAME = "WARP"

    const val SEC_WARP_ID = 0
    const val SEC_WARP_NAME = "SEC_WARP"

    init {
        io { load(forceRefresh = false) }
    }

    suspend fun load(forceRefresh: Boolean): Int {
        // migration: old encrypted wg configs to plain text files.
        // must run before any config read so that the rest of this always uses plaintext files
        migrateEncryptedConfigsIfNeeded()

        if (!forceRefresh && configs.isNotEmpty()) {
            return configs.size
        }
        // go through all files in the wireguard directory and load them
        // parse the files as those are now plain text
        // increment the id by 1, as the first config id is 0
        lastAddedConfigId = db.getLastAddedConfigId()
        val m = db.getWgConfigs().map { it.toImmutable() }
        mappings = CopyOnWriteArraySet(m)
        for (it in mappings) {
            val path = it.configPath
            // WireGuard configs are encrypted at rest. Legacy plaintext files are migrated
            // in-place to encrypted storage before parsing.
            if (!ensureEncryptedConfigFile(path)) {
                if (it.isActive) {
                    val inactive = it.copy(isActive = false, oneWireGuard = false)
                    mappings.remove(it)
                    mappings.add(inactive)
                    db.disableConfig(it.id)
                    VpnController.removeWireGuardProxy(it.id)
                }
                continue
            }
            val config = try {
                val bytes = WireguardConfigFileManager.read(applicationContext, File(path))
                Config.parse(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                continue
            }
            if (config == null) {
                // TODO: delete the warp config from the wireguard directory
                // below code should be removed post v055o
                if ((it.id == WARP_ID && it.name == WARP_NAME) || (it.id == SEC_WARP_ID && it.name == SEC_WARP_NAME)) {
                    deleteConfig(it.id)
                }
                continue
            }
            if (configs.none { i -> i.getId() == it.id }) {
                val c =
                    Config.Builder()
                        .setId(it.id)
                        .setName(it.name)
                        .setInterface(config.getInterface())
                        .addPeers(config.getPeers())
                        .build()
                configs.add(c)
            }
        }
        return configs.size
    }

    /** Migrates legacy plaintext WireGuard configs to encrypted-at-rest files. */
    private suspend fun migrateEncryptedConfigsIfNeeded() {
        if (persistentState.wireguardPlainFileMigrationDone) return

        migrationMutex.withLock {
            if (persistentState.wireguardPlainFileMigrationDone) return@withLock
            db.getWgConfigs().forEach { entry ->
                val file = File(entry.configPath)
                if (!file.exists() || !WireguardConfigFileManager.isPlaintextConfig(file)) return@forEach
                try {
                    val bytes = file.readBytes()
                    EncryptedFileManager.write(applicationContext, bytes, file)
                } catch (_: Exception) {
                    if (entry.isActive) {
                        db.disableConfig(entry.id)
                        VpnController.removeWireGuardProxy(entry.id)
                    }
                }
            }
            persistentState.wireguardPlainFileMigrationDone = true
        }
    }

    /** Ensures the config is encrypted at rest; legacy plaintext is encrypted in-place. */
    private suspend fun ensureEncryptedConfigFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            if (WireguardConfigFileManager.isPlaintextConfig(file)) {
                val bytes = file.readBytes()
                EncryptedFileManager.write(applicationContext, bytes, file)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // remove this post v055o,  sometimes the db update does not delete the entry, so adding this
    // as precaution.
    suspend fun deleteResidueWgs() {
        val wgs = db.getWarpSecWarpConfig()
        if (wgs.isEmpty()) {
            return
        }
        wgs.forEach {
            if (it.name == SEC_WARP_NAME || it.name == WARP_NAME) {
                deleteConfig(it.id)
            }
        }
    }

    private fun clearLoadedConfigs() {
        configs.clear()
        mappings.clear()
    }

    fun getConfigById(id: Int): Config? {
        val config = configs.find { it.getId() == id }
        if (config == null) {
        }
        return config
    }

    fun getConfigFilesById(id: Int): WgConfigFilesImmutable? {
        val config = mappings.find { it.id == id }
        if (config == null) {
        }
        return config
    }

    fun isAnyWgActive(): Boolean {
        return mappings.any { it.isActive }
    }

    fun isAdvancedWgActive(): Boolean {
        return mappings.any { it.isActive && !it.oneWireGuard }
    }

    fun getAllMappings(): List<WgConfigFilesImmutable> {
        return mappings.toList()
    }

    fun getNumberOfMappings(): Int {
        return mappings.size
    }

    fun getActiveWgCount() = mappings.count { it.isActive }

    fun getActiveConfigs(): List<Config> {
        val m = mappings.filter { it.isActive }
        val l = mutableListOf<Config>()
        m.forEach {
            val config = configs.find { it1 -> it1.getId() == it.id }
            if (config != null) {
                l.add(config)
            }
        }
        return l
    }

    fun isConfigActive(configId: String): Boolean {
        try {
            val id = configId.split(ID_WG_BASE).last().toIntOrNull() ?: return false
            val mapping = mappings.find { it.id == id }
            if (mapping != null) {
                return mapping.isActive
            }
            return false
        } catch (e: Exception) {
        }
        return false
    }

    suspend fun enableConfig(unmapped: WgConfigFilesImmutable) {
        io {
            val map = mappings.find { it.id == unmapped.id }
            if (map == null) {
                return@io
            }

            val config = configs.find { it.getId() == map.id }
            if (config == null) {
                return@io
            }

            mappings.remove(map)
            val newMap =
                WgConfigFilesImmutable(
                    map.id,
                    map.name,
                    map.configPath,
                    map.serverResponse,
                    true,
                    map.isCatchAll,
                    map.isLockdown,
                    map.oneWireGuard,
                    map.useOnlyOnMetered,
                    map.isDeletable,
                    map.ssidEnabled,
                    map.ssids
                )
            mappings.add(newMap)
            val dbMap = WgConfigFiles.fromImmutable(newMap)
            db.update(dbMap)
            val proxyType = AppConfig.ProxyType.WIREGUARD
            val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
            appConfig.addProxy(proxyType, proxyProvider)
            VpnController.addWireGuardProxy(ID_WG_BASE + map.id)
        }
    }

    fun canEnableProxy(): Boolean {
        return appConfig.canEnableProxy()
    }

    fun isValidConfig(id: Int): Boolean {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return false
        }
        return true
    }

    fun isAnyOtherOneWgEnabled(id: Int): Boolean {
        return mappings.any { it.oneWireGuard && it.isActive && it.id != id }
    }

    // disables every active One-WireGuard config except the one with id == currentId
    suspend fun disableOtherOneWireGuardConfigs(currentId: Int) {
        val others = mappings.filter { it.oneWireGuard && it.isActive && it.id != currentId }
        if (others.isEmpty()) return
        others.forEach {
            disableConfig(it)
            updateOneWireGuardConfig(it.id, false)
        }
    }

    fun canDisableConfig(map: WgConfigFilesImmutable): Boolean {
        return when {
            map.isCatchAll -> false // cannot disable catch-all
            WgHopManager.isWgEitherHopOrSrc(map.id) -> false // cannot disable hop/via
            else -> true // safe to disable
        }
    }

    fun canDisableAllActiveConfigs(): Boolean {
        mappings.forEach {
            if (it.isActive && it.isCatchAll) {
                return false
            }
        }
        return true
    }

    fun getConfigName(id: Int): String {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return ""
        }
        return config.getName()
    }

    suspend fun disableAllActiveConfigs() {
        val activeConfigs = mappings.filter { it.isActive }
        activeConfigs.forEach {
            disableConfig(it)
            updateOneWireGuardConfig(it.id, false)
        }
    }

    fun disableConfig(unmapped: WgConfigFilesImmutable) {
        val m = mappings.find { it.id == unmapped.id }
        if (m == null) {
            return
        }

        val config = configs.find { it.getId() == unmapped.id }
        if (config == null) {
            return
        }

        // disable the config, update to db, cache and tunnel
        // also update mappings https://pl.kotl.in/g0mVapn4x
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                m.id,
                m.name,
                m.configPath,
                m.serverResponse,
                false, // confirms with db.disableConfig query
                m.isCatchAll,
                m.isLockdown,
                false, // confirms with db.disableConfig query
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)

        io { db.disableConfig(newMap.id) }
        if (mappings.none { it.isActive }) {
            val proxyType = AppConfig.ProxyType.WIREGUARD
            val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
            appConfig.removeProxy(proxyType, proxyProvider)
        }
        // directly remove the proxy from the tunnel, instead of calling updateTun
        io { VpnController.removeWireGuardProxy(newMap.id) }
        return
    }

    // pair - first: proxyId, second - can proceed for next check
    private fun canUseConfig(idStr: String, type: String, usesMobileNw: Boolean, ssid: String): Boolean {
        if (idStr.isEmpty()) {
            return false
        }
        val id = convertStringIdToId(idStr)
        val config = if (id == INVALID_CONF_ID) null else mappings.find { it.id == id }

        if (config == null) {
            return false
        }

        val lockdown = config.isLockdown

        if (lockdown && isEligibleForNetwork(id, usesMobileNw, ssid, config.useOnlyOnMetered, config.ssidEnabled)) {
            return true // no need to proceed further for lockdown
        }

        // check if the config is active and if it can be used on this network
        if (config.isActive && isEligibleForNetwork(id, usesMobileNw, ssid, config.useOnlyOnMetered, config.ssidEnabled)) {
            return true
        }

        return false
    }

    private fun isAnyProxyLockdown(proxies: List<String>): Boolean {
        var lockdown = false
        proxies.forEach { it ->
            val confId = convertStringIdToId(it)
            val conf = mappings.find { it.id == confId }
            lockdown = conf?.isLockdown ?: false
            if (lockdown) return true
        }

        return lockdown
    }

    private fun isLockdownConfig(idStr: String): Boolean {
        val confId = convertStringIdToId(idStr)
        val conf = mappings.find { it.id == confId }
        return conf?.isLockdown ?: false
    }

    // no need to check for app excluded from proxy here, expected to call this fn after that
    suspend fun getAllPossibleConfigIdsForApp(uid: Int, ip: String, port: Int, domain: String, usesMobileNw: Boolean, ssid: String, default: String): List<String> {
        val proxyIds: MutableList<String> = mutableListOf()

        if (oneWireGuardEnabled()) {
            val id = getOneWireGuardProxyId()
            if (id == null || id == INVALID_CONF_ID) {
                return emptyList()
            }

            proxyIds.add(ID_WG_BASE + id)
            // add default to the list, can route check is done in go-tun
            // let one-wg use wg-dns no need to add the default to the list
            if (default.isNotEmpty()) proxyIds.add(default)
            return proxyIds
        }

        // collect all proxy-ids for this uid and keep only WireGuard ones (wgX)
        val allProxyIdsForApp = ProxyManager.getProxyIdsForApp(uid)
        val wgProxyIdsForApp = allProxyIdsForApp.filter { isWgProxyId(it) }

        // app-specific configs may be empty if the app is not configured
        if (wgProxyIdsForApp.isNotEmpty()) {
            for (pid in wgProxyIdsForApp) {
                val canUse = canUseConfig(pid, "app($uid)", usesMobileNw, ssid)
                if (canUse) {
                    proxyIds.add(pid)
                }
            }
        }

        // if any of the app-specific config is a lockdown config but none of them can be
        // used on this network, then the traffic must be blocked; lockdown overrides the
        // catch-all configs and the default proxy
        if (wgProxyIdsForApp.any { isLockdownConfig(it) } && proxyIds.none { isLockdownConfig(it) }) {
            return listOf(Backend.Block)
        }

        // if any of the chosen proxy is lockdown, then no need to proceed further
        if (isAnyProxyLockdown(proxyIds)) {
            return proxyIds
        }

        // once the app-specific config is added, check if any catch-all config is enabled
        // if catch-all config is enabled, then add the config id to the list
        val cac = mappings.filter { it.isActive && it.isCatchAll }
        cac.forEach {
            if (isEligibleForNetwork(it.id, usesMobileNw, ssid, it.useOnlyOnMetered, it.ssidEnabled) &&
                !proxyIds.contains(ID_WG_BASE + it.id)
            ) {
                proxyIds.add(ID_WG_BASE + it.id)
            }
        }

        if (proxyIds.isEmpty()) {
            return if (default.isEmpty()) emptyList() else listOf(default)
        }

        // see if any id is part of lockdown, if so, then return the list, cac's can have lockdown
        val isAnyIdLockdown = proxyIds.any { id ->
            val confId = convertStringIdToId(id)
            val conf = mappings.find { it.id == confId }
            conf?.isLockdown ?: false
        }

        // add the default proxy to the end, will not be true for any id is set to lockdown
        if (default.isNotEmpty() && !isAnyIdLockdown) proxyIds.add(default)

        // the proxyIds list will contain the ip-app specific, domain-app specific, app specific,
        // universal ip, universal domain, catch-all and default configs in the order of priority
        // the go-tun will check the routing based on the order of the list
        return proxyIds
    }

    fun isWgProxyId(pid: String): Boolean {
        if (!pid.startsWith(ID_WG_BASE, ignoreCase = true)) return false
        val suffix = pid.removePrefix(ID_WG_BASE)
        return suffix.isNotEmpty() && suffix.toIntOrNull() != null
    }

    private fun isEligibleForNetwork(id: Int, usesMobileNw: Boolean, ssid: String, mobileOnlySetting: Boolean, ssidEnabled: Boolean): Boolean {
        if (!mobileOnlySetting && !ssidEnabled) return true

        val passMobileOnly = mobileOnlySetting && usesMobileNw
        // On mobile the SSID rule is bypassed (mobile has no SSID context); ssidEnabled
        // should not make a config ineligible on mobile data.
        val passSsid = ssidEnabled && (usesMobileNw || matchesSsidListForConfig(id, ssid))
        return passMobileOnly || passSsid
    }

    // returns true if the current ssid is allowed by the SSID list configured for the wireguard.
    // When the config is not found, returns false (fail-closed).
    private fun matchesSsidListForConfig(id: Int, ssid: String): Boolean {
        val config = mappings.find { it.id == id }
        if (config == null) {
            return false
        }
        return matchesSsidList(config.ssids, ssid)
    }

    private fun matchesWildcard(pattern: String, text: String): Boolean {
        // Convert wildcard pattern to regex
        // * matches any sequence of characters
        // ? matches any single character
        val regexPattern = pattern
            .replace(".", "\\.")  // Escape dots
            .replace("*", ".*")   // Convert * to .*
            .replace("?", ".")    // Convert ? to .

        return try {
            text.matches(Regex(regexPattern, RegexOption.IGNORE_CASE)) || text.contains(pattern, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    fun matchesSsidList(ssidList: String, ssid: String): Boolean {
        val ssidItems = SsidItem.parseStorageList(ssidList)
        if (ssidItems.isEmpty()) { // treat empty as match all
            return true
        }

        // Separate EQUAL items from NOT_EQUAL items
        val equalItems = ssidItems.filter { it.type.isEqual }
        val notEqualItems = ssidItems.filter { !it.type.isEqual }

        // Check NOT_EQUAL items first - if any match, return false
        val notEqualMatch = notEqualItems.any { ssidItem ->
            when {
                ssidItem.type.isExact -> {
                    ssidItem.name.equals(ssid, ignoreCase = true)
                }
                else -> { // wildcard
                    matchesWildcard(ssidItem.name, ssid)
                }
            }
        }

        if (notEqualMatch) {
            return false
        }

        // If there are only NOT_EQUAL items and none matched, return true
        if (equalItems.isEmpty() && notEqualItems.isNotEmpty()) {
            return true
        }

        // Check EQUAL items (exact or wildcard)
        return equalItems.any { ssidItem ->
            when {
                ssidItem.type.isExact -> {
                    ssidItem.name.equals(ssid, ignoreCase = true)
                }
                else -> { // wildcard
                    matchesWildcard(ssidItem.name, ssid)
                }
            }
        }
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (_: Exception) {
            INVALID_CONF_ID
        }
    }

    suspend fun addConfig(config: Config?, name: String = ""): Config? {
        if (config == null) {
            return null
        }
        // increment the id and add the config
        lastAddedConfigId += 1
        val id = lastAddedConfigId
        val n = name.ifEmpty { "${Backend.WG}$id" }
        val cfg =
            Config.Builder()
                .setId(id)
                .setName(n)
                .setInterface(config.getInterface())
                .addPeers(config.getPeers())
                .build()
        writeConfigAndUpdateDb(cfg)
        return config
    }

    suspend fun addOrUpdateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        return if (configId <= 0) {
            addInterface(configName, wgInterface)
        } else {
            updateInterface(configId, configName, wgInterface)
        }
    }

    private suspend fun addInterface(configName: String, wgInterface: WgInterface): Config {
        // create a new config and add the interface
        lastAddedConfigId += 1
        val id = lastAddedConfigId
        val name = configName.ifEmpty { "wg$id" }
        val cfg = Config.Builder().setId(id).setName(name).setInterface(wgInterface).build()
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private suspend fun updateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        val cfg: Config
        // update the interface for the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            return null
        }
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(configName)
                .setInterface(wgInterface)
                .addPeers(config.getPeers())
                .build()
        val cfgId = ID_WG_BASE + configId
        if (configName != config.getName()) {
            ProxyManager.updateProxyNameForProxyId(cfgId, configName)
        }
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private fun getConfigFileName(id: Int): String {
        return "wg$id.conf"
    }

    suspend fun deleteConfig(id: Int) {
        val cf = mappings.find { it.id == id }

        // delete the config file
        val config = configs.find { it.getId() == id }
        if (cf?.isActive == true) {
            disableConfig(cf)
        }

        withContext(Dispatchers.IO) {
            val fileName = getConfigFileName(id)
            val file = File(getConfigFilePath(), fileName)
            WireguardConfigFileManager.delete(file)
            // delete the config from the database
            db.deleteConfig(id)
            val proxyId = ID_WG_BASE + id
            ProxyManager.removeProxyId(proxyId)
            mappings.remove(mappings.find { it.id == id })
            if (config != null) configs.remove(config)
            WgHopManager.handleWgDelete(id)
        }
    }

    suspend fun deleteAllConfigs() {
        val allIds = configs.map { it.getId() }
        allIds.forEach { id ->
            deleteConfig(id)
        }
        appConfig.removeAllProxies()
    }

    suspend fun updateLockdownConfig(id: Int, isLockdown: Boolean) {
        val config = configs.find { it.getId() == id }
        val map = mappings.find { it.id == id }
        if (config == null) {
            return
        }
        db.updateLockdownConfig(id, isLockdown)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        mappings.add(
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                isLockdown, // just updating lockdown field
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        )
        if (map?.isActive == true) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + config.getId())
            VpnController.notifyConnectionMonitor()
        }
    }

    suspend fun updateCatchAllConfig(id: Int, isEnabled: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        db.updateCatchAllConfig(id, isEnabled)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                isEnabled, // just updating catch all field
                m.isLockdown,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)

        enableConfig(newMap) // catch all should be always enabled
    }

    suspend fun updateOneWireGuardConfig(id: Int, owg: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        db.updateOneWireGuardConfig(id, owg)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        mappings.add(
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.isLockdown,
                owg, // updating just one wireguard field
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        )
    }

    suspend fun updateUseOnMobileNetworkConfig(id: Int, useMobileNw: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        db.updateMobileConfig(id, useMobileNw)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.isLockdown,
                m.oneWireGuard,
                useMobileNw, // just updating useMobileNw
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)
        if (m.isActive) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new mobile-only setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun updateSsidEnabled(id: Int, ssidEnabled: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        val m = mappings.find { it.id == id } ?: return
        // this can be disabled/enabled multiple times, based on the user permission so check
        // before updating
        if (m.ssidEnabled == ssidEnabled) {
            return
        }

        db.updateSsidEnabled(id, ssidEnabled)
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.isLockdown,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                ssidEnabled, // just updating ssidEnabled
                m.ssids
            )
        mappings.add(newMap)
        if (m.isActive) {
            // trigger connection monitor to update the SSID info
            if (ssidEnabled) VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new ssid setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun updateSsids(id: Int, ssids: String) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        db.updateSsids(id, ssids)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.isLockdown,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                ssids // just updating ssids
            )
        mappings.add(newMap)
        if (m.isActive) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new ssid setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun addPeer(id: Int, peer: Peer) {
        // add the peer to the config
        val cfg: Config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        val peers = config.getPeers() ?: mutableListOf()
        val newPeers = peers.toMutableList()
        newPeers.add(peer)
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(newPeers)
                .build()
        writeConfigAndUpdateDb(cfg)
    }

    suspend fun deletePeer(id: Int, peer: Peer) {
        // delete the peer from the config
        val cfg: Config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            return
        }
        val peers = config.getPeers()?.toMutableList()
        if (peers == null) {
            return
        }
        val isRemoved =
            peers.removeIf {
                it.getPublicKey() == peer.getPublicKey() &&
                        it.getEndpoint() == peer.getEndpoint() &&
                        it.getAllowedIps() == peer.getAllowedIps() &&
                        it.getPreSharedKey() == peer.getPreSharedKey()
            }
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(peers)
                .build()
        writeConfigAndUpdateDb(cfg)
    }

    private fun addOrUpdateConfig(cfg: Config) {
        val existing = configs.find { it.getId() == cfg.getId() }
        if (existing != null) configs.remove(existing)
        configs.add(cfg)
    }

    private suspend fun writeConfigAndUpdateDb(cfg: Config, serverResponse: String = "") {
        // write the contents to a plain text file
        val parsedCfg = cfg.toWgQuickString()
        val fileName = getConfigFileName(cfg.getId())
        try {
            WireguardConfigFileManager.write(applicationContext, parsedCfg, fileName)
        } catch (e: java.io.IOException) {
            // I/O failure - cannot save config
            throw e // Bubble up to caller
        }
        val path = getConfigFilePath() + fileName
        val file = db.isConfigAdded(cfg.getId())
        if (file == null) {
            val wgf =
                WgConfigFiles(
                    cfg.getId(),
                    cfg.getName(),
                    path,
                    serverResponse,
                    isActive = false,
                    isCatchAll = false,
                    isLockdown = false,
                    oneWireGuard = false,
                    useOnlyOnMetered = false,
                    isDeletable = true,
                    ssidEnabled = false,
                    ssids = ""
                )
            db.insert(wgf)
            addOrUpdateConfigFileMapping(cfg, null, path, serverResponse)
        } else {
            file.name = cfg.getName()
            file.configPath = path
            file.serverResponse = serverResponse
            db.update(file)
            addOrUpdateConfigFileMapping(cfg, file.toImmutable(), path, serverResponse)
        }
        addOrUpdateConfig(cfg)
        if (file?.isActive == true) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + cfg.getId(), force = true)
        }
    }

    private fun addOrUpdateConfigFileMapping(
        cfg: Config,
        file: WgConfigFilesImmutable?,
        path: String,
        serverResponse: String
    ) {
        if (file == null) {
            val wgf =
                WgConfigFilesImmutable(
                    cfg.getId(),
                    cfg.getName(),
                    path,
                    serverResponse,
                    isActive = false,
                    isCatchAll = false,
                    isLockdown = false,
                    oneWireGuard = false,
                    isDeletable = true,
                    useOnlyOnMetered = false,
                    ssidEnabled = false,
                    ssids = ""
                )
            mappings.add(wgf)
        } else {
            val configFile = mappings.find { it.id == cfg.getId() }
            mappings.remove(configFile)
            mappings.add(file)
        }
    }

    data class WgStats(val routerStats: RouterStats?, val mtu: Long?, val ip4: Boolean?, val ip6: Boolean?, val addr: String?)
    suspend fun stats(): String {
        val sb = StringBuilder()
        mappings.filter { it.isActive }.forEach {
            val id = ID_WG_BASE + it.id
            val stats = VpnController.getWireGuardStats(id)
            val routerStats = stats?.routerStats
            sb.append("   id: ${it.id}, name: ${it.name}\n")
            sb.append("   always-on? ${it.isCatchAll}\n")
            sb.append("   lockdown? ${it.isLockdown}\n")
            sb.append("   mobile-only? ${it.useOnlyOnMetered}\n")
            sb.append("   ssid-only? ${it.ssidEnabled}")
            if (it.ssidEnabled) {
                sb.append(", ssids: ${it.ssids}")
            }
            sb.append("\n")
            sb.append("   ifaddr: ${routerStats?.addrs}").append("\n")
            sb.append("   mtu: ${stats?.mtu}\n")
            sb.append("   status: ${routerStats?.status}\n")
            sb.append("   status-reason: ${routerStats?.statusReason}\n")
            sb.append("   ip4: ${stats?.ip4}\n")
            sb.append("   ip6: ${stats?.ip6}\n")
            sb.append("   rx: ${routerStats?.rx}\n")
            sb.append("   tx: ${routerStats?.tx}\n")
            sb.append("   lastRx: ${getRelativeTimeSpan(routerStats?.lastRx)}\n")
            sb.append("   lastTx: ${getRelativeTimeSpan(routerStats?.lastTx)}\n")
            sb.append("   lastGoodRx: ${getRelativeTimeSpan(routerStats?.lastGoodRx)}\n")
            sb.append("   lastGoodTx: ${getRelativeTimeSpan(routerStats?.lastGoodTx)}\n")
            sb.append("   lastRxErr: ${routerStats?.lastRxErr}\n")
            sb.append("   lastTxErr: ${routerStats?.lastTxErr}\n")
            sb.append("   lastOk: ${getRelativeTimeSpan(routerStats?.lastOK)}\n")
            sb.append("   lastOpen: ${getRelativeTimeSpan(routerStats?.lastOpen)}\n")
            sb.append("   hdl: ${routerStats?.hdl}\n")
            sb.append("   since: ${getRelativeTimeSpan(routerStats?.since)}\n")
            sb.append("   addr: ${stats?.addr ?: "N/A"}\n")
            sb.append("   errRx: ${routerStats?.errRx}\n")
            sb.append("   errTx: ${routerStats?.errTx}\n")
            sb.append("   extra: ${routerStats?.extra}\n")
        }
        if (sb.isEmpty()) {
            sb.append("   N/A\n")
        }
        val s = sb.toString()
        return sb.toString()
    }

    private fun getConfigFilePath(): String {
        return applicationContext.filesDir.absolutePath +
                File.separator +
                WIREGUARD_FOLDER_NAME +
                File.separator
    }

    fun getPeers(id: Int): MutableList<Peer> {
        return configs.find { it.getId() == id }?.getPeers()?.toMutableList() ?: mutableListOf()
    }

    suspend fun restoreProcessRetrieveWireGuardConfigs() {
        val count = db.getWgConfigs().size
        clearLoadedConfigs()
        performRestore()
        val loadedCount = load(forceRefresh = true)
    }

    suspend fun performRestore() {
        try {
            // during restore process, plain text wg configs are present in the temp dir
            // move the files to the wireguard directory and load the configs
            val tempDir = File(applicationContext.filesDir, TEMP_WG_DIR)
            val dbconfs = db.getWgConfigs()

            val tempFiles = tempDir.listFiles()?.filter { it.name.endsWith(".conf") } ?: emptyList()

            if (tempFiles.isEmpty() && dbconfs.isEmpty()) {
                return
            }

            if (tempFiles.isEmpty()) {
                return
            }

            if (dbconfs.isEmpty()) {
                return
            }

            val restoredIds = mutableSetOf<Int>()
            val unmatchedFiles = mutableListOf<String>()

            dbconfs.forEach { c ->
                // for each database entry, corresponding file with $id.conf is present in the temp dir
                // move the file to the wireguard directory with the name available in the database
                val file = File(tempDir, "${c.id}.conf")
                if (file.exists()) {
                } else {
                    db.deleteConfig(c.id)
                    return@forEach
                }
                // Read the backup bytes and always persist them encrypted at rest.
                val bytes = file.readBytes()
                val targetFile = File(c.configPath).canonicalFile
                val wgBase = WireguardConfigFileManager.getConfigDirectory(applicationContext).canonicalFile
                val basePath = wgBase.path + File.separator
                if (targetFile.path != wgBase.path && !targetFile.path.startsWith(basePath)) {
                    db.deleteConfig(c.id)
                    return@forEach
                }
                try {
                    EncryptedFileManager.write(applicationContext, bytes, targetFile)
                    restoredIds.add(c.id)
                } catch (_: Exception) {
                    db.deleteConfig(c.id)
                }
            }

            // identify files that were not matched to any db entry
            tempFiles.forEach { tf ->
                val id = tf.nameWithoutExtension.toIntOrNull()
                if (id == null || id !in restoredIds) {
                    unmatchedFiles.add(tf.name)
                }
            }

            if (unmatchedFiles.isNotEmpty()) {
                // do not delete temp dir, unmatched files may be needed
                return
            }

            val isResidueDeleted = Utilities.deleteRecursive(tempDir)
            if (isResidueDeleted) {
            } else {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
        }
    }

    fun oneWireGuardEnabled(): Boolean {
        return mappings.any { it.oneWireGuard && it.isActive }
    }

    fun getActiveSsidEnabledConfigs(): List<WgConfigFilesImmutable> {
        return mappings.filter { it.ssidEnabled && it.isActive }
    }

    fun getOneWireGuardProxyId(): Int? {
        return mappings.find { it.oneWireGuard && it.isActive }?.id
    }

    fun getActiveCatchAllConfig(): List<WgConfigFilesImmutable> {
        return mappings.filter { it.isActive && it.isCatchAll }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
