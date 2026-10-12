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
package com.celzero.bravedns.scheduler

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BUG_REPORT
import org.koin.core.component.KoinComponent
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.celzero.bravedns.scheduler.EnhancedBugReport.MAX_TOTAL_FILES
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

object EnhancedBugReport : KoinComponent {

    const val TOMBSTONE_DIR_NAME = "tombstone"
    const val TOMBSTONE_ZIP_FILE_NAME = "rethinkdns.tombstone.zip"


    // GoLogFileDescriptorReader2 → golog_<ts>.txt   (Go runtime log lines)
    const val PREFIX_GO_LOG = "golog_"
    // GoCrashFileDescriptorReader → gocrash_<ts>.txt (Go runtime panic dump)
    const val PREFIX_GO_CRASH = "gocrash_"
    // EnhancedBugReport (Kotlin/JVM) → kotlin_<ts>.txt
    const val PREFIX_KOTLIN = "kotlin_"

    const val FILE_EXTENSION = ".txt"
    const val FLIGHT_REC_EXT = ".pprof"

    // Total cap across ALL three file types in the tombstone folder.
    // Files accumulate up to this limit before the oldest are deleted.
    private const val MAX_TOTAL_FILES = 20

    private const val MAX_BYTES = 64 * 1024

    /**
     * Tracks file names that are currently being written by an active reader
     * (GoLogFileDescriptorReader or GoCrashFileDescriptorReader).
     *
     * A file is added here the moment it is created (before any reader touches it) and removed
     * only after the reader has fully closed the file.  local tombstone maintenance
     * skips every file present in this set, regardless of when it was created, eliminating
     * the race condition that a timestamp-based guard cannot handle.
     */
    private val activeSessionFileNames = CopyOnWriteArraySet<String>()

    private val mutex = Mutex()

    /**
     * Marks [fileName] as belonging to the current session.
     * Called immediately after file creation, before handing the file to any reader.
     */
    fun markFileAsActive(fileName: String) = Unit

    /** Returns a new File for a Go log session (golog_<ts>.txt), creating the folder if needed. */
    fun newGoLogFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_GO_LOG)

    /** Returns a new File for a Go crash dump (gocrash_<ts>.txt). */
    fun newGoCrashFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_GO_CRASH)

    /** Returns a new File for a Kotlin/JVM crash log (kotlin_<ts>.txt). */
    fun newKotlinCrashFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_KOTLIN)

    suspend fun writeLogsToFile(context: Context?, token: String, logs: String) = Unit

    @RequiresApi(Build.VERSION_CODES.O)
    fun addLogsToZipFile(context: Context) = Unit

    fun getTombstoneZipFile(context: Context): File? = null

    /**
     * Extracts the Unix-millisecond timestamp embedded in a tombstone filename.
     *
     * All three file types follow the pattern `<prefix><ts>.txt` where `<ts>` is the raw
     * [System.currentTimeMillis] value at file-creation time.  We strip the known prefix and
     * the `.txt` suffix to isolate the numeric string.
     *
     * Returns 0 if the name does not match any known prefix or the numeric part cannot be parsed.
     * Callers treat 0 as "unknown assume unreported" so the file gets sent rather than skipped.
     */
    private fun extractTimestampFromName(name: String): Long {
        if (name.isEmpty()) return 0L
        val prefix = when {
            name.startsWith(PREFIX_GO_CRASH) -> PREFIX_GO_CRASH
            name.startsWith(PREFIX_GO_LOG)   -> PREFIX_GO_LOG
            name.startsWith(PREFIX_KOTLIN)   -> PREFIX_KOTLIN
            else                             -> return 0L
        }
        // strip prefix and .txt suffix to get the numeric timestamp string.
        val tsStr = name.removePrefix(prefix).removeSuffix(FILE_EXTENSION)
        return tsStr.toLongOrNull() ?: 0L
    }

    /**
     * Deletes the oldest files until the total count is ≤ [MAX_TOTAL_FILES].
     * [justWritten] is never deleted (it is the file being actively written by a reader).
     */
    private fun enforceFileCountCap(files: List<File>, justWritten: File?) {
        if (files.size <= MAX_TOTAL_FILES) return
        val deleteCount = files.size - MAX_TOTAL_FILES
        Logger.i(LOG_TAG_BUG_REPORT,
            "tombstone cap: ${files.size} files, deleting $deleteCount oldest (max=$MAX_TOTAL_FILES)")
        files.filterNot { it == justWritten }
            .take(deleteCount)
            .forEach { f ->
                val ok = f.delete()
                Logger.d(LOG_TAG_BUG_REPORT, "rotation: deleted ${f.name}, ok=$ok")
            }
    }

    /**
     * Called by each reader after creating/writing a new file.
     * Re-reads the full folder so rotation is always applied to the combined total.
     */
    fun enforceMaxFiles(context: Context, justWritten: File? = null) = Unit

    private fun newTombstoneFile(context: Context, prefix: String): File? = null

    fun tombstoneFolder(filesDir: File): File? = null
}
