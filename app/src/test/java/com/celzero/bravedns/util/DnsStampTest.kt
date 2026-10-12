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
