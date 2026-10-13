/*
 * Copyright 2021 RethinkDNS and its authors
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

        const val BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS: Long = 3
        const val DATA_USAGE_TIME_INTERVAL_MINS: Long = 20

        fun isWorkRunning(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running = workStatus.state == WorkInfo.State.RUNNING
                }
                running
            } catch (e: ExecutionException) {
                false
            } catch (e: InterruptedException) {
                false
            }
        }

        // Check if the job is already scheduled.
        // It will be in either running or enqueued state.
        fun isWorkScheduled(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running =
                        workStatus.state == WorkInfo.State.RUNNING ||
                                workStatus.state == WorkInfo.State.ENQUEUED
                }
                running
            } catch (e: ExecutionException) {
                false
            } catch (e: InterruptedException) {
                false
            }
        }
    }

    // schedule blocklist update check (based on user settings)
    fun scheduleBlocklistUpdateCheckJob() {
        // if (isWorkScheduled(context.applicationContext, BLOCKLIST_UPDATE_CHECK_JOB_TAG)) return

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

    fun scheduleDataUsageJob() {
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
