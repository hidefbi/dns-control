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
package com.celzero.bravedns.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory per-app data usage accumulator.
 *
 * Traffic is added when a socket closes (see [TunFlowManager]) and drained periodically by
 * the data usage worker, which persists the totals to the app-info table. Nothing about
 * individual connections is retained here, only byte counters keyed by app uid.
 */
object DataUsageTracker {

    private class Counters {
        val upload = AtomicLong(0L)
        val download = AtomicLong(0L)
    }

    private val usage = ConcurrentHashMap<Int, Counters>()

    fun add(uid: Int, upload: Long, download: Long) {
        if (upload <= 0L && download <= 0L) return
        val c = usage.getOrPut(uid) { Counters() }
        if (upload > 0L) c.upload.addAndGet(upload)
        if (download > 0L) c.download.addAndGet(download)
    }

    /** Returns the bytes accumulated since the last drain, and resets those counters. */
    fun drain(): Map<Int, Pair<Long, Long>> {
        val out = HashMap<Int, Pair<Long, Long>>()
        for ((uid, c) in usage) {
            val up = c.upload.getAndSet(0L)
            val down = c.download.getAndSet(0L)
            if (up != 0L || down != 0L) out[uid] = Pair(up, down)
        }
        return out
    }
}
