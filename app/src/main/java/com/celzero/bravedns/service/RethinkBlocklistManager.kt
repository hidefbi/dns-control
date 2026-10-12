/*
 * Copyright 2022 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.data.FileTagDeserializer
import com.celzero.bravedns.database.LocalBlocklistPacksMap
import com.celzero.bravedns.database.LocalBlocklistPacksMapRepository
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RDNS
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

object RethinkBlocklistManager : KoinComponent {

    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val localBlocklistPacksMapRepository by inject<LocalBlocklistPacksMapRepository>()
    private val persistentState by inject<PersistentState>()

    private const val EMPTY_SUBGROUP = "others"

    private const val PARENTAL_CONTROL_TAG = "ParentalControl"
    private const val SECURITY_TAG = "Security"
    private const val PRIVACY_TAG = "Privacy"

    data class RethinkBlockType(val name: String, val label: Int, val desc: Int)

    data class PacksMappingKey(val pack: String, val level: Int)

    enum class RethinkBlocklistType {
        LOCAL;

        fun isLocal(): Boolean {
            return this == LOCAL
        }
    }

    enum class DownloadType(val id: Int) {
        LOCAL(0);

        fun isLocal(): Boolean {
            return this == LOCAL
        }
    }

    val PARENTAL_CONTROL =
        RethinkBlockType(
            PARENTAL_CONTROL_TAG,
            R.string.rbl_parental_control,
            R.string.rbl_parental_control_desc
        )
    val SECURITY = RethinkBlockType(SECURITY_TAG, R.string.rbl_security, R.string.rbl_security_desc)
    val PRIVACY = RethinkBlockType(PRIVACY_TAG, R.string.rbl_privacy, R.string.rbl_privacy_desc)

    // read and parse the local blocklist json file
    // returns true on success, false on error
    @Suppress("UNUSED_PARAMETER")
    suspend fun readJson(context: Context, type: DownloadType, timestamp: Long): Boolean {
        return readLocalJson(context, timestamp)
    }

    private suspend fun readLocalJson(context: Context, timestamp: Long): Boolean {
        val packsBlocklistMapping: Multimap<PacksMappingKey, Int> = HashMultimap.create()
        try {
            val dbFileTagLocal: MutableList<RethinkLocalFileTag> = mutableListOf()
            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            // register the type adapter to deserialize the class.
            // see FileTag.kt for more info (FileTagDeserializer)
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val l = getRethinkLocalObj(t)

                if (l.pack?.isNotEmpty() == true) {
                    l.pack?.forEachIndexed { index, s ->
                        // if the pack is empty or level is empty, then skip
                        if (s.isEmpty() || l.level == null || l.level?.isEmpty() == true) {
                            l.level = arrayListOf()
                            packsBlocklistMapping.put(PacksMappingKey(s, 0), l.value)
                            return@forEachIndexed
                        }
                        val level = l.level?.getOrNull(index) ?: 2
                        packsBlocklistMapping.put(PacksMappingKey(s, level), l.value)
                    }
                }

                dbFileTagLocal.add(l)
            }
            val selectedTags = localFileTagRepository.getSelectedTags()
            // edge case: found a residual block list entry still available in the database
            // during the insertion of new block list entries. This occurred when the number of
            // block lists in the preceding list is greater than the current list. Always
            // empty the data base entries before creating new entries.
            localFileTagRepository.deleteAll()
            localFileTagRepository.insertAll(dbFileTagLocal.toList())
            localFileTagRepository.updateTags(selectedTags.toSet(), 1)
            localBlocklistPacksMapRepository.deleteAll()
            // insert the packs and level mapping in the database
            localBlocklistPacksMapRepository.insertAll(
                packsBlocklistMapping.keySet().map { key ->
                    LocalBlocklistPacksMap(
                        key.pack,
                        key.level,
                        packsBlocklistMapping.get(key).toList(),
                        dbFileTagLocal.firstOrNull { it.pack?.contains(key.pack) == true }?.group ?: ""
                    )
                }
            )
            Logger.i(LOG_TAG_DNS, "New Local blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Logger.e(
                LOG_TAG_DNS,
                "Failure reading json file, blocklist type: local, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private fun getRethinkLocalObj(t: FileTag): RethinkLocalFileTag {
        return RethinkLocalFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.level,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    suspend fun updateFiletagLocal(local: RethinkLocalFileTag) {
        localFileTagRepository.update(local)
    }

    suspend fun updateFiletagsLocal(values: Set<Int>, isSelected: Int) {
        localFileTagRepository.updateTags(values, isSelected)
    }

    suspend fun getSelectedFileTagsLocal(): List<Int> {
        return localFileTagRepository.getSelectedTags()
    }

    suspend fun clearTagsSelectionLocal() {
        localFileTagRepository.clearSelectedTags()
    }

    fun cpSelectFileTag(localFileTags: RethinkLocalFileTag): Int {
        io {
            val selectedTags =
                getTagsFromStamp(persistentState.localBlocklistStamp, RethinkBlocklistType.LOCAL)
                    .toMutableSet()

            // remove the tag from the local blocklist if it exists and current selection is 0
            if (selectedTags.contains(localFileTags.value) && !localFileTags.isSelected) {
                selectedTags.remove(localFileTags.value)
            } else if (!selectedTags.contains(localFileTags.value) && localFileTags.isSelected) {
                // only add the tag if it is not already present
                selectedTags.add(localFileTags.value)
            } else {
                // no-op
            }

            val stamp = getStamp(selectedTags, RethinkBlocklistType.LOCAL)
            persistentState.localBlocklistStamp = stamp
        }
        return localFileTagRepository.contentUpdate(localFileTags)
    }

    suspend fun getStamp(fileValues: Set<Int>, type: RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            val rdns = getRDNS()
            if (rdns == null) {
                // Common when configuring Remote DNS while the VPN is stopped:
                // rdnsRemote is only available through the live Go resolver.
                // Returning "" here is intentional, but callers must NOT treat
                // "" as "the user cleared the selection" when fileValues is
                // non-empty (see RethinkBlocklistFragment observer / setStamp).
                Logger.w(
                    LOG_TAG_VPN,
                    "getStamp: RDNS unavailable for ${type.name}; cannot encode ${fileValues.size} flags"
                )
                return ""
            }
            val flags2Stamp = rdns.flagsToStamp(flags, Backend.EB32)
            Logger.d(LOG_TAG_VPN, "${type.name} flags: $flags; stamp-len: ${flags2Stamp.length}")
            flags2Stamp
        } catch (e: java.lang.Exception) {
            Logger.e(LOG_TAG_VPN, "err stamp2tags for $fileValues of type: ${type.name} ${e.message}, $e")
            ""
        }
    }

    suspend fun getTagsFromStamp(stamp: String, type: RethinkBlocklistType): Set<Int> {
        return try {
            val rdns = getRDNS()
            if (rdns == null) {
                Logger.w(
                    LOG_TAG_VPN,
                    "getTagsFromStamp: RDNS unavailable for ${type.name}; stamp='${stamp.take(32)}'"
                )
                return setOf()
            }
            val tags = convertCsvToList(rdns.stampToFlags(stamp))
            Logger.d(LOG_TAG_VPN, "${type.name} stamp-len: ${stamp.length}; tags: $tags")
            tags
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "err tags2stamp for $stamp of type: ${type.name} ${e.message}, $e")
            setOf()
        }
    }

    private fun convertCsvToList(csv: String?): Set<Int> {
        if (csv.isNullOrBlank()) return setOf()

        // Earlier, an empty/blank string was split into [""] which then mapped to 0
        // via `toIntOrNull() ?: 0`, producing a phantom tag with value 0. That 0
        // never matches any row in the file-tag tables, so the subsequent
        // `getSelectedFileTags*()` reads returned an empty set and clobbered the
        // in-memory selection. Filter blank tokens and drop unparseable ones
        // instead of substituting 0. See issue: Advanced Mode (Remote DNS) loses
        // the blocklist selection.
        return csv.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    private fun convertListToCsv(s: Set<Int>): String {
        return s.joinToString(",")
    }

    private suspend fun getRDNS(): RDNS? {
        return VpnController.getRDNS()
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
