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
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.VpnController
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class BackupHelper {

    companion object {
        // MIME type is used for unknown binary files (if stored locally)
        const val INTENT_TYPE_OCTET = "application/octet-stream"

        // google drive will have its own mimetypes changed to "x-zip"
        const val INTENT_TYPE_XZIP = "application/x-zip"

        // file name for the backup file
        const val BACKUP_FILE_NAME = "Rethink_"

        // date format for backup  file name
        const val BACKUP_FILE_NAME_DATETIME = "yyMMddHHmmss"

        // backup file extension
        const val BACKUP_FILE_EXTN = ".rbk"
        const val BACKUP_FILE_EXTN_ALPHA = ".rbka"

        // backup shared pref file name
        const val SHARED_PREFS_BACKUP_FILE_NAME = "shared_prefs_backup.txt"

        // metadata file name
        const val METADATA_FILENAME = "rethink_backup.txt"

        // data builder uri string for restore worker
        const val DATA_BUILDER_RESTORE_URI = "restoreFileUri"

        // data builder uri string for backup worker
        const val DATA_BUILDER_BACKUP_URI = "backupFileUri"

        // temp zip  file name
        const val TEMP_ZIP_FILE_NAME = "temp.rbk"

        // intent scheme
        const val INTENT_SCHEME = "content"

        // restart app after database restore
        const val INTENT_RESTART_APP = "restartApp"

        // wireguard dir to backup the wireguard configs
        const val BACKUP_WG_DIR = "wireguard"

        // while restoring the wireguard config, the temp dir to store the wireguard config
        const val TEMP_WG_DIR = "temp_wireguard"

        // metadata constants
        // version
        const val VERSION = "version"
        // package name
        const val PACKAGE_NAME = "package"
        // time when the backup  is taken
        const val CREATED_TIME = "createdTs"

        fun getTempDir(context: Context): File {
            // temp dir (files/Rethink/)
            val backupDirectoryPath: String =
                context.filesDir.absolutePath +
                    File.separator +
                    context.getString(R.string.app_name)
            val file = File(backupDirectoryPath)
            if (!file.exists()) {
                file.mkdir()
            }

            return file
        }

        fun getRethinkDatabase(context: Context): File? {
            val path =
                (context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile?.path
                    ?: return null) + File.separator
            return File(path)
        }

        fun stopVpn(context: Context) {
            VpnController.stop("bkup", context)
        }

        fun startVpn(context: Context) {
            VpnController.start(context)
        }

        fun deleteResidue(backupFile: File) {
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }

        fun unzip(inputStream: InputStream?, path: String): Boolean {
            if (inputStream == null) return false
            val baseDir = File(path).canonicalFile
            if (!baseDir.exists() && !baseDir.mkdirs()) return false

            val maxEntries = 128
            val maxSingleFileBytes = 16L * 1024L * 1024L
            val maxTotalBytes = 64L * 1024L * 1024L
            var totalBytes = 0L
            var entries = 0

            try {
                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    var ze: ZipEntry? = zis.nextEntry
                    while (ze != null) {
                        if (++entries > maxEntries) return false
                        val rawName = ze.name
                        if (rawName.isBlank() || rawName.length > 240) return false

                        val normalizedName = rawName.replace('\\', '/')
                        if (normalizedName.startsWith('/') || normalizedName.startsWith("../") ||
                            normalizedName.contains("/../") || normalizedName == ".." ||
                            normalizedName.contains("\u0000") ||
                            Regex("^[A-Za-z]:/.*").matches(normalizedName)) {
                            return false
                        }

                        val target = File(baseDir, normalizedName).canonicalFile
                        val basePath = baseDir.path + File.separator
                        if (target.path != baseDir.path && !target.path.startsWith(basePath)) return false

                        if (ze.isDirectory) {
                            if (!target.exists() && !target.mkdirs()) return false
                            zis.closeEntry()
                            ze = zis.nextEntry
                            continue
                        }

                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) return false }
                        var written = 0L
                        FileOutputStream(target).use { fout ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zis.read(buffer)
                                if (count < 0) break
                                written += count
                                totalBytes += count
                                if (written > maxSingleFileBytes || totalBytes > maxTotalBytes) return false
                                fout.write(buffer, 0, count)
                            }
                        }
                        zis.closeEntry()
                        ze = zis.nextEntry
                    }
                }
            } catch (_: Exception) {
                return false
            }
            return true
        }

        fun getFileNameFromPath(file: String): String {
            return file.substring(file.lastIndexOf("/") + 1)
        }
    }
}
