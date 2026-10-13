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
package com.celzero.bravedns.backup

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.stopVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.unzip
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.deleteRecursive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream

class RestoreAgent(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val logDatabase by inject<LogDatabase>()
    private val appDatabase by inject<AppDatabase>()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "RestoreAgent"
    }

    override suspend fun doWork(): Result {
        val restoreUri = inputData.getString(DATA_BUILDER_RESTORE_URI)?.toUri()
        if (restoreUri == null) {
            return Result.failure()
        }

        val result = startRestore(restoreUri)

        return if (result) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private suspend fun startRestore(importUri: Uri): Boolean {
        var inputStream: InputStream? = null
        stopVpn(context)
        try {
            val tempDir = getTempDir(context)
            tempDir.deleteRecursively()
            if (!tempDir.mkdirs() && !tempDir.isDirectory) return false
            inputStream = context.contentResolver.openInputStream(importUri)

            // unzip the backup files to tempDir
            if (!unzip(inputStream, tempDir.path)) {
                return false
            } else {
                // proceed
            }

            if (!validateMetadata(tempDir.path)) {
                return false
            } else {
                // no-op; proceed
            }

            // copy SharedPreferences file to its directory,
            // if shared pref copy is succeeds then proceed to database restore else
            if (!restoreSharedPreferencesFromFile(tempDir.path)) {
                return false
            } else {
                // proceed
            }

            // Copy new database file into its final directory
            if (!restoreDatabaseFile(tempDir)) {
                return false
            } else {
                // proceed
            }

            // copy wireguard contents into temp_wg folder
            // if wireguard copy failed, the proceed with cleanup
            if (!restoreWireGuardFiles(tempDir)) {
                // clear WireGuard related entries from database
                wireGuardCleanup()
            } else {
            }

            // open log database if its not open
            handleDatabaseInit()

            // copy the blocklist file from assets to the remote blocklist folder

            // update app version after the restore process
            updateLatestVersion()

            // clean up the temp directory
            deleteRecursive(tempDir)

            // WG configs are restored during RefreshDatabase.ACTION_REFRESH_RESTORE
            // which runs after app restart with fresh Room connections.
            // The caller (HomeScreenActivity.observeRestoreWorker) triggers the
            // restart after this worker returns success.

            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during restore process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            inputStream?.close()
        }
    }

    private fun handleDatabaseInit() {
        // get writable database for logs
        if (!logDatabase.isOpen) {
            logDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }

        // get writable database for app
        if (!appDatabase.isOpen) {
            appDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }
    }

    // Restore database file stored at tempDir/nameOfFileToRestore.
    private fun restoreDatabaseFile(tempDir: File): Boolean {
        checkPoint()


        val files = tempDir.listFiles()
        if (files == null) {
            return false
        }

        for (file in files) {
            val currentDbFile = File(context.getDatabasePath(file.name).path)
            if (
                !file.name.contains(AppDatabase.DATABASE_NAME) &&
                    !file.name.contains(LogDatabase.LOGS_DATABASE_NAME)
            ) {
                continue
            }
            if (!Utilities.copy(file.path, currentDbFile.path)) {
                return false
            }
        }

        return true
    }

    private fun checkPoint() {
        appDatabase.checkPoint()
        logDatabase.checkPoint()
        return
    }

    private fun restoreWireGuardFiles(dir: File): Boolean {
        if (!dir.exists()) {
            // no files to restore
            return true
        }

        // store the wireguard files in the temp_wireguard folder and then copy it to the wireguard
        // folder during db updates, database update is handled in RefreshDatabase
        // clear if temp_wireguard folder is already there if not, create the folder
        val tempWgDir = File(context.filesDir, TEMP_WG_DIR)
        if (tempWgDir.exists()) {
            tempWgDir.deleteRecursively()
        }

        if (!tempWgDir.mkdirs()) {
            return false
        }

        var totalCopied = 0

        // collect .conf files from the root of the temp dir
        val confFiles = mutableListOf<File>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".conf")) {
                confFiles.add(file)
            } else {
            }
        }

        // also scan the wireguard/ subdirectory (where backup saves wg files)
        val backupWgSubDir = File(dir, BACKUP_WG_DIR)
        if (backupWgSubDir.exists() && backupWgSubDir.isDirectory) {
            backupWgSubDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".conf") && !confFiles.any { it.name == file.name }) {
                    confFiles.add(file)
                }
            }
        }


        confFiles.forEach { file ->
            val currentWgFile = File(tempWgDir, file.name)
            if (!Utilities.copy(file.path, currentWgFile.path)) {
                // no need to return false, proceed with the next file
                // missing files database entry will be handled (deleted) in RefreshDatabase
            } else {
                totalCopied++
            }
        }
        return true
    }

    private fun updateLatestVersion() {
        if (isNewVersion()) {
            persistentState.appVersion = getLatestVersion()
        } else {
        }
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    @Suppress("DEPRECATION")
    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(context.packageManager, context.packageName)
        return pInfo?.versionCode ?: 0
    }

    private fun validateMetadata(tempDirectory: String?): Boolean {
        // TODO: revisit this after v055 release
        if (isMetadataCompatible(tempDirectory)) {
            return true
        } else {
            // proceed with META_DATA_FILE validation
        }

        val file = File(tempDirectory, METADATA_FILENAME)
        var stream: InputStream? = null
        return try {
            stream = file.inputStream()
            val metadata = stream.bufferedReader().use { it.readText() }
            isVersionSupported(metadata)
        } catch (ex: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "err while restoring metadata, reason? ${ex.message}",
                ex
            )
            false
        } finally {
            try {
                stream?.close()
            } catch (ex: IOException) {
            }
        }
    }

    private fun wireGuardCleanup() {
        if (appConfig.isWireGuardEnabled()) {
            appConfig.removeAllProxies()
        }
        // cleaning up the wireguard entries are handled in RefreshDatabase
    }

    private fun isMetadataCompatible(tempDirectory: String?): Boolean {
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        return try {
            val root = JSONObject(prefsBackupFile.readText(Charsets.UTF_8))
            val appVersion = root.optInt(PersistentState.APP_VERSION, 0)
            appVersion >= 24
        } catch (_: Exception) {
            false
        }
    }

    private fun isVersionSupported(metadata: String): Boolean {
        try {
            val minVersionSupported = 24

            if (!metadata.contains(VERSION)) return false

            val versionDetails = metadata.split("|")
            if (versionDetails[0].isEmpty()) return false

            val version = versionDetails[0].split(":")[1].toIntOrNull() ?: 0

            // backup version should be equal to minVersionSupported (prior to that version
            // there is only one database), so do not consider the backups prior to that
            return version >= minVersionSupported && persistentState.appVersion >= version
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "error while reading metadata, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun restoreSharedPreferencesFromFile(tempDirectory: String?): Boolean {
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        val current = PreferenceManager.getDefaultSharedPreferences(context)
        return try {
            val root = JSONObject(prefsBackupFile.readText(Charsets.UTF_8))
            val editor = current.edit()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!shouldRestorePref(key)) continue
                val value = root.get(key)
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                    is JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until value.length()) set.add(value.optString(i))
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            deleteResidue(prefsBackupFile)
        }
    }

    private fun shouldRestorePref(key: String): Boolean {
        val k = key.lowercase()
        val blocked = listOf("androidx.security", "keyset", "master_key", "token", "secret", "password", "passwd", "privatekey", "private_key", "credential", "authorization", "cookie")
        return blocked.none { k.contains(it) }
    }
}
