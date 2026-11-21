/*
 * Copyright 2026 hidefbi
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
package com.celzero.bravedns.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsStampTest {

    @Test
    fun odohTargetStampBecomesHttpsUrl() {
        assertEquals(
            "https://adl01.dnscry.pt/dns-query",
            DnsStamp.toHttpsUrl("sdns://BQcAAAAAAAAAD2FkbDAxLmRuc2NyeS5wdAovZG5zLXF1ZXJ5")
        )
    }

    @Test
    fun odohRelayStampBecomesHttpsUrl() {
        assertEquals(
            "https://odoh-relay.edgecompute.app/",
            DnsStamp.toHttpsUrl("sdns://hQcAAAAAAAAAAAAab2RvaC1yZWxheS5lZGdlY29tcHV0ZS5hcHABLw")
        )
    }

    @Test
    fun plainHttpsUrlIsNotAStamp() {
        assertNull(DnsStamp.toHttpsUrl("https://odoh.cloudflare-dns.com/dns-query"))
    }

    @Test
    fun dnscryptStampIsNotAnOdohStamp() {
        // protocol 0x01 (DNSCrypt) must not be treated as ODoH
        assertNull(DnsStamp.toHttpsUrl("sdns://AQcAAAAAAAAAEzE0OS4xMTIuMTEyLjEwOjg0NDMgZ8hHuMh1jNEgJFVDvnVnRt803x2EwAuMRwNo34Idhj4ZMi5kbnNjcnlwdC1jZXJ0LnF1YWQ5Lm5ldA"))
    }

    @Test
    fun truncatedOrGarbageInputIsRejected() {
        assertNull(DnsStamp.toHttpsUrl("sdns://BQcA"))
        assertNull(DnsStamp.toHttpsUrl("sdns://!!!!"))
        assertNull(DnsStamp.toHttpsUrl(""))
    }
}
