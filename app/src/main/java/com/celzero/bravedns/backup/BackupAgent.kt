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

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.CREATED_TIME
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.PACKAGE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_ZIP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getFileNameFromPath
import com.celzero.bravedns.backup.BackupHelper.Companion.getRethinkDatabase
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.startVpn
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.copyWithStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ref:
// https://gavingt.medium.com/refactoring-my-backup-and-restore-feature-to-comply-with-scoped-storage-e2b6c792c3b
class BackupAgent(val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), KoinComponent {

    var filesPathToZip: MutableList<String> = ArrayList()
    private val persistentState by inject<PersistentState>()
    private val appDatabase by inject<AppDatabase>()

    companion object {
        const val TAG = "BackupExport"
    }

    override fun doWork(): Result {
        val backupFileUri = inputData.getString(DATA_BUILDER_BACKUP_URI)?.toUri()
        if (backupFileUri == null) {
            return Result.failure()
        }

        val isBackupSucceed = startBackupProcess(backupFileUri)

        if (isBackupSucceed) {
            startVpn(context)
            return Result.success()
        }
        return Result.failure()
    }

    private fun startBackupProcess(backupFileUri: Uri): Boolean {
        var processCompleted: Boolean
        try {
            val tempDir = getTempDir(context)

            val prefsBackupFile = File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME)

            processCompleted = saveSharedPreferencesToFile(context, prefsBackupFile)

            if (processCompleted) {
            } else {
                return false
            }

            // checkpoint databases to flush WAL entries into the main database file
            // so the backup includes all committed data, not just what's in the db file
            appDatabase.checkPoint()

            processCompleted = saveDatabasesToFile(tempDir.path)

            if (processCompleted) {
            } else {
                return false
            }

            // no need to check the return value, we can proceed even if the wireguard config backup
            // fails
            backupWireGuardConfig(tempDir)

            processCompleted = createMetaData(tempDir)

            if (processCompleted) {
            } else {
                return false
            }

            return zipAndCopyToDestination(tempDir, backupFileUri)
        } catch (e: Exception) {
            return false
        } finally {
            for (filePath in filesPathToZip) {
                val file = File(filePath)
                deleteResidue(file)
            }
            filesPathToZip.clear()
        }
    }

    private fun backupWireGuardConfig(tempDir: File): Boolean {
        // get all the wireguard config from the database,
        // loop through them and get the path from the database
        // create a copy of a encrypted file to normal file in the temp dir
        // add the file to the zip list

        val dir = File(tempDir, BACKUP_WG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        try {
            val mappings = WireguardManager.getAllMappings()
            mappings.forEach { m ->
                val file = File(m.configPath)
                if (!file.exists()) {
                    return@forEach
                }
                val content = file.readBytes()
                if (content.isNotEmpty()) {
                    val tmpWgFile = File(dir, "${m.id}.conf")
                    tmpWgFile.writeBytes(content)
                    filesPathToZip.add(tmpWgFile.absolutePath)
                } else {
                }
            }
            return true
        } catch (e: Exception) {
        }
        return false
    }

    private fun createMetaData(backupDir: File): Boolean {
        // check if the file exists already, if yes, delete it
        val file = File(backupDir, METADATA_FILENAME)
        if (file.exists()) {
            file.delete()
            filesPathToZip.remove(file.absolutePath)
        }
        val metadata = backupMetadata()
        try {
            val metadataFile = File(backupDir, METADATA_FILENAME)
            metadataFile.writer().use {
                writer -> writer.write(metadata)
                writer.flush()
            }
            // add the metadata file to the list of files to be zipped
            filesPathToZip.add(metadataFile.absolutePath)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun backupMetadata(): String {
        return "$VERSION:${persistentState.appVersion}|$PACKAGE_NAME:${context.packageName}|$CREATED_TIME:${SystemClock.elapsedRealtime()}"
    }

    private fun zipAndCopyToDestination(tempDir: File, destUri: Uri): Boolean {
        val bZipSucceeded: Boolean = zip(filesPathToZip, tempDir.path)


        if (bZipSucceeded) {
            val tempZipFile = File(tempDir, TEMP_ZIP_FILE_NAME)
            val zipFileUri: Uri = Uri.fromFile(tempZipFile)
            val inputStream: InputStream =
                context.contentResolver.openInputStream(zipFileUri) ?: return false
            val outputStream: OutputStream =
                context.contentResolver.openOutputStream(destUri) ?: return false

            // we are passing the streams instead of actual files because we do not have
            // write access to the destination dir.
            val copySucceeded: Boolean = copyWithStream(inputStream, outputStream)
            return if (copySucceeded) {
                deleteResidue(tempZipFile)
                true
            } else {
                false
            }
        } else {
            return false
        }
    }

    private fun saveDatabasesToFile(path: String): Boolean {
        val files = getRethinkDatabase(context)?.listFiles() ?: return false

        for (f in files) {
            if (f.name.endsWith("-wal") || f.name.endsWith("-shm") || f.name.endsWith("-journal") ||
                f.name == "rethink_logs.db") continue
            // looks like the journal, shm and wal files are needed for proper restore, so
            // commenting out the below code. still testing it out.
            // TODO: check if the journal files are needed for restore
            // skip journal files, they are not needed for restore
            val databaseFile =
                backUpFile(f.absolutePath, constructDbFileName(path, f.name)) ?: return false
            if (f.name == AppDatabase.DATABASE_NAME) {
                sanitizeBackupDatabase(databaseFile)
            }
            filesPathToZip.add(databaseFile.absolutePath)
        }

        return true
    }

    private fun sanitizeBackupDatabase(file: File) {
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                    while (cursor.moveToNext()) { }
                }
                val statements = listOf(
                    "UPDATE ProxyEndpoint SET password = ''",
                    "UPDATE TcpProxyEndpoint SET token = ''"
                )
                statements.forEach { sql ->
                    try { db.execSQL(sql) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            // If sanitization fails, do not export the database.
            throw IllegalStateException("Unable to sanitize backup database")
        }
    }

    private fun constructDbFileName(path: String, fileName: String): String {
        return path + File.separator + fileName
    }

    private fun saveSharedPreferencesToFile(context: Context, prefFile: File): Boolean {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        return try {
            val root = JSONObject()
            sharedPrefs.all.forEach { (key, value) ->
                if (!shouldBackupPref(key)) return@forEach
                when (value) {
                    is Boolean, is Float, is Double, is Int, is Long, is String -> root.put(key, value)
                    is Set<*> -> root.put(key, value.filterIsInstance<String>().toTypedArray().toList())
                }
            }
            prefFile.writeText(root.toString(), Charsets.UTF_8)
            filesPathToZip.add(prefFile.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldBackupPref(key: String): Boolean {
        val k = key.lowercase()
        val sensitive = listOf("token", "secret", "password", "passwd", "privatekey", "private_key", "credential", "authorization", "cookie", "master_key", "keyset")
        return sensitive.none { k.contains(it) }
    }

    private fun backUpFile(backupFilePath: String?, destFilePath: String?): File? {
        if (backupFilePath == null || destFilePath == null) {
            return null
        }
        val isCopySuccess = Utilities.copy(backupFilePath, destFilePath)
        if (isCopySuccess) return File(destFilePath)

        return null
    }

    private fun zip(files: List<String>, zipDirectory: String): Boolean {
        val outputFileName = zipDirectory + File.separator + TEMP_ZIP_FILE_NAME
        return try {
            val dest = FileOutputStream(outputFileName)
            val out = ZipOutputStream(BufferedOutputStream(dest))
            val bufferSize = 80000
            var origin: BufferedInputStream
            val data = ByteArray(bufferSize)
            for (file in files) {
                val fi = FileInputStream(file)
                origin = BufferedInputStream(fi, bufferSize)
                val entry = ZipEntry(getFileNameFromPath(file))
                out.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, bufferSize).also { count = it } != -1) {
                    out.write(data, 0, count)
                }
                origin.close()
            }
            out.close()
            out.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
