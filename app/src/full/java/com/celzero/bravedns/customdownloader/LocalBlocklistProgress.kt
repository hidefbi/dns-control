/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.customdownloader

import android.content.Context
import android.widget.ProgressBar
import androidx.work.Data
import androidx.work.workDataOf
import com.celzero.bravedns.R
import java.util.Locale

/**
 * Progress reported by [LocalBlocklistCoordinator] through WorkManager's progress data.
 * Read by the UI from `WorkInfo.progress`.
 *
 * [KEY_PERCENT] is 0..100 for the current file, or -1 when the server did not send a
 * Content-Length, in which case the bar is shown as indeterminate.
 */
object LocalBlocklistProgress {
    const val KEY_PERCENT = "lb_progress_percent"
    const val KEY_DONE_BYTES = "lb_progress_done_bytes"
    const val KEY_TOTAL_BYTES = "lb_progress_total_bytes"
    const val KEY_FILE_INDEX = "lb_progress_file_index"
    const val KEY_FILE_COUNT = "lb_progress_file_count"

    fun build(percent: Int, done: Long, total: Long, index: Int, count: Int): Data {
        return workDataOf(
            KEY_PERCENT to percent.coerceIn(-1, 100),
            KEY_DONE_BYTES to done,
            KEY_TOTAL_BYTES to total,
            KEY_FILE_INDEX to index,
            KEY_FILE_COUNT to count
        )
    }

    /** Applies the percent to [bar]; indeterminate when the total size is unknown. */
    fun applyTo(bar: ProgressBar, data: Data) {
        val percent = data.getInt(KEY_PERCENT, -1)
        if (percent < 0) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.max = 100
            bar.progress = percent
        }
    }

    /** e.g. "File 1 of 6 · 42% · 0.9 MB of 2.1 MB", or `null` when there is no progress yet. */
    fun format(context: Context, data: Data): String? {
        val count = data.getInt(KEY_FILE_COUNT, 0)
        if (count <= 0) return null
        val index = data.getInt(KEY_FILE_INDEX, 1)
        val percent = data.getInt(KEY_PERCENT, -1)
        val done = mb(data.getLong(KEY_DONE_BYTES, 0L))
        val total = data.getLong(KEY_TOTAL_BYTES, -1L)
        return if (percent >= 0 && total > 0) {
            context.getString(R.string.lb_download_progress, index, count, percent, done, mb(total))
        } else {
            context.getString(R.string.lb_download_progress_unknown, index, count, done)
        }
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)

    private const val BYTES_PER_MB = 1048576.0
}
