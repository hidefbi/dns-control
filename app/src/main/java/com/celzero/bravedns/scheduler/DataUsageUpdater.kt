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
package com.celzero.bravedns.scheduler

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.DataUsageTracker
import com.celzero.bravedns.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Persists per-app data usage accumulated in memory by [DataUsageTracker] into the app-info
 * table. Connection-level rows are not stored, so this no longer reads any log database.
 */
class DataUsageUpdater(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val appInfoRepository by inject<AppInfoRepository>()

    override suspend fun doWork(): Result {
        val usage = DataUsageTracker.drain()
        usage.forEach { (uid, bytes) ->
            if (uid == Constants.INVALID_UID) return@forEach
            try {
                val current = appInfoRepository.getDataUsageByUid(uid)
                if (current == null) {
                    Logger.w(LOG_TAG_SCHEDULER, "No app info found for uid: $uid, skipping data usage")
                    return@forEach
                }
                val upload = current.uploadBytes + bytes.first
                val download = current.downloadBytes + bytes.second
                Logger.d(LOG_TAG_SCHEDULER, "Data usage for $uid, $upload, $download")
                appInfoRepository.updateDataUsageByUid(uid, upload, download)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_SCHEDULER, "err in data usage updater: ${e.message}", e)
            }
        }
        Logger.i(LOG_TAG_SCHEDULER, "Data usage updated for ${usage.size} apps")
        return Result.success()
    }
}
