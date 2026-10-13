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
package com.celzero.bravedns.scheduler

import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class WgProxyPingController(private val scope: CoroutineScope) {
    private val activeProxies = ConcurrentHashMap<String, PingConfig>()

    private val intervalMs = 60_000L
    private val durationMs = 5 * 60 * 1000L

    @Volatile private var schedulerJob: Job? = null

    companion object {
    }

    // startTime is a val, captured once in startPing and never modified.
    class PingConfig(val startTime: Long) {
        @Volatile var running: Boolean = false
    }

    fun startPing(proxyId: String) {
        val existing = activeProxies[proxyId]

        val newConfig = PingConfig(startTime = System.currentTimeMillis())
        if (existing != null) newConfig.running = existing.running
        activeProxies[proxyId] = newConfig

        ensureScheduler()
        if (existing != null) {
        } else {
        }
    }

    fun stopPing(proxyId: String) {
        activeProxies.remove(proxyId)

        if (activeProxies.isEmpty()) stopScheduler()
    }

    fun stopAll() {
        activeProxies.clear()
        stopScheduler()
    }

    private fun ensureScheduler() {
        if (schedulerJob?.isActive == true) return

        schedulerJob = scope.launch(Dispatchers.IO + CoroutineName("ping-scheduler")) {
            var nextTick = alignToNextInterval(System.currentTimeMillis())

            while (isActive && activeProxies.isNotEmpty()) {
                val delayMs = nextTick - System.currentTimeMillis()
                if (delayMs > 0) delay(delayMs.milliseconds)

                tick(System.currentTimeMillis())

                nextTick += intervalMs
            }
        }
    }

    private fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    private fun tick(now: Long) {
        for ((proxyId, config) in activeProxies) {

            // skip if a previous ping coroutine is still running (prevents overlap).
            if (config.running) {
                continue
            }

            val elapsed = now - config.startTime
            val remaining = durationMs - elapsed

            // enforce a minimum delay of intervalMs before the very first ping.
            if (elapsed < intervalMs) {
                continue
            }

            if (elapsed >= durationMs) {
                activeProxies.remove(proxyId)
                continue
            }


            // set running to true before launching so that even if the coroutine is scheduled
            // immediately, the next tick iteration already sees it as running
            config.running = true

            scope.launch(Dispatchers.IO + CoroutineName("ping-$proxyId")) {
                try {
                    pingProxy(proxyId)
                } catch (e: Exception) {
                } finally {
                    activeProxies[proxyId]?.running = false
                }
            }
        }
    }

    private fun alignToNextInterval(now: Long): Long {
        return ((now / intervalMs) + 1) * intervalMs
    }

    private suspend fun pingProxy(proxyId: String) {
        when {
            proxyId.startsWith(ID_WG_BASE) -> {
                VpnController.initiateWgPing(proxyId)
            }
        }

    }
}
