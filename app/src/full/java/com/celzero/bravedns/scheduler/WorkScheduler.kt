/*
 * Copyright 2021 RethinkDNS and its authors
 * Modifications Copyright 2026 hidefbi
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

import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class WorkScheduler(val context: Context) {
    companion object {
        const val BLOCKLIST_UPDATE_CHECK_JOB_TAG = "ScheduledBlocklistUpdateCheckJob"
        const val DATA_USAGE_JOB_TAG = "ScheduledDataUsageJob"

        const val BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS: Long = 8
        const val DATA_USAGE_TIME_INTERVAL_MINS: Long = 20

        fun isWorkRunning(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            Logger.i(LOG_TAG_SCHEDULER, "Job $tag already running check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running = workStatus.state == WorkInfo.State.RUNNING
                }
                Logger.i(LOG_TAG_SCHEDULER, "Job $tag already running? $running")
                running
            } catch (e: ExecutionException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }

        // Check if the job is already scheduled.
        // It will be in either running or enqueued state.
        fun isWorkScheduled(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            Logger.i(LOG_TAG_SCHEDULER, "Job $tag already scheduled check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running =
                        workStatus.state == WorkInfo.State.RUNNING ||
                                workStatus.state == WorkInfo.State.ENQUEUED
                }
                Logger.i(LOG_TAG_SCHEDULER, "Job $tag already scheduled? $running")
                running
            } catch (e: ExecutionException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }
    }

    // schedule blocklist update check (based on user settings)
    fun scheduleBlocklistUpdateCheckJob() {
        // if (isWorkScheduled(context.applicationContext, BLOCKLIST_UPDATE_CHECK_JOB_TAG)) return

        Logger.i(LOG_TAG_SCHEDULER, "Scheduled blocklist update check")
        val blocklistUpdateCheck =
            PeriodicWorkRequest.Builder(
                BlocklistUpdateCheckJob::class.java,
                BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS,
                TimeUnit.DAYS
            )
                .addTag(BLOCKLIST_UPDATE_CHECK_JOB_TAG)
                .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                BLOCKLIST_UPDATE_CHECK_JOB_TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                blocklistUpdateCheck
            )
    }

    // installs from before the interval change still run on the old period,
    // so enqueue once more when the saved interval differs from the one in code
    fun rescheduleBlocklistUpdateCheckIfNeeded(persistentState: PersistentState) {
        if (!persistentState.periodicallyCheckBlocklistUpdate) return
        val interval = BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS.toInt()
        if (persistentState.blocklistCheckIntervalDays == interval) return

        Logger.i(LOG_TAG_SCHEDULER, "blocklist check interval changed, rescheduling to $interval days")
        scheduleBlocklistUpdateCheckJob()
        persistentState.blocklistCheckIntervalDays = interval
    }

    fun scheduleDataUsageJob() {
        Logger.i(LOG_TAG_SCHEDULER, "Data usage job scheduled")
        val workRequest =
            PeriodicWorkRequest.Builder(
                DataUsageUpdater::class.java,
                DATA_USAGE_TIME_INTERVAL_MINS,
                TimeUnit.MINUTES // Set the repeat interval for every 15 minutes
            )
                .addTag(DATA_USAGE_JOB_TAG)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                DATA_USAGE_JOB_TAG,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
    }
}
