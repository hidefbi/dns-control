/*
 * Copyright 2025 RethinkDNS and its authors
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

import com.celzero.bravedns.database.IpInfo
import com.celzero.bravedns.database.IpInfoRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Local-only: never contacts any server; only reads IP info already in the local database. */
object IpInfoDownloader : KoinComponent {

    private val db by inject<IpInfoRepository>()

    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchIpInfoIfRequired(ipToLookup: String) {
        // no-op: remote ip-info lookup removed
    }

    suspend fun getIpInfo(ip: String): IpInfo? {
        return db.getIpInfo(ip)
    }
}
