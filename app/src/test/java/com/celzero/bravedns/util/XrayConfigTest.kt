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
package com.celzero.bravedns.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigTest {

    private val fullLink =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=PUBKEY123" +
            "&sid=abcd&flow=xtls-rprx-vision&type=tcp#My%20Node"

    @Test
    fun parsesFullRealityLink() {
        val link = XrayConfig.parseVlessLink(fullLink)
        assertEquals("My Node", link.name)
        assertEquals("11111111-2222-3333-4444-555555555555", link.uuid)
        assertEquals("example.com", link.host)
        assertEquals(443, link.port)
        assertEquals("xtls-rprx-vision", link.flow)
        assertEquals("www.example.org", link.serverName)
        assertEquals("chrome", link.fingerprint)
        assertEquals("PUBKEY123", link.publicKey)
        assertEquals("abcd", link.shortId)
    }

    @Test
    fun usesDefaultFingerprintWhenMissing() {
        val link =
            XrayConfig.parseVlessLink(
                "vless://u@example.com:443?security=reality&sni=a.example&pbk=K"
            )
        assertEquals("chrome", link.fingerprint)
        assertEquals("", link.flow)
    }

    @Test
    fun stripsBracketsFromIpv6Host() {
        val link =
            XrayConfig.parseVlessLink(
                "vless://u@[2001:db8::1]:8443?security=reality&sni=a.example&pbk=K"
            )
        assertEquals("2001:db8::1", link.host)
        assertEquals(8443, link.port)
    }

    @Test
    fun rejectsNonRealitySecurity() {
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink("vless://u@example.com:443?security=tls&sni=a&pbk=K")
        }
    }

    @Test
    fun rejectsMissingPublicKey() {
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink("vless://u@example.com:443?security=reality&sni=a")
        }
    }

    @Test
    fun rejectsNonVlessScheme() {
        assertFalse(XrayConfig.isVlessLink("vmess://abc"))
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink("vmess://abc")
        }
    }

    @Test
    fun builtConfigExposesUdpSocksInboundOnLocalPort() {
        val config = XrayConfig.buildConfig(XrayConfig.parseVlessLink(fullLink))
        assertTrue(config.contains("\"protocol\":\"socks\""))
        assertTrue(config.contains("\"port\":${XrayConfig.LOCAL_PORT}"))
        assertTrue(config.contains("\"listen\":\"127.0.0.1\""))
        assertTrue(config.contains("\"udp\":true"))
    }

    @Test
    fun builtConfigCarriesRealityParameters() {
        val config = XrayConfig.buildConfig(XrayConfig.parseVlessLink(fullLink))
        assertTrue(config.contains("\"security\": \"reality\""))
        assertTrue(config.contains("\"publicKey\": \"PUBKEY123\""))
        assertTrue(config.contains("\"serverName\": \"www.example.org\""))
        assertTrue(config.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(config.contains("\"port\": 443"))
    }

    @Test
    fun omitsFlowWhenAbsent() {
        val config =
            XrayConfig.buildConfig(
                XrayConfig.parseVlessLink(
                    "vless://u@example.com:443?security=reality&sni=a.example&pbk=K"
                )
            )
        assertFalse(config.contains("\"flow\""))
    }

    @Test
    fun checkAcceptsValidLink() {
        assertNull(XrayConfig.check(fullLink))
    }

    @Test
    fun checkReportsEmptyInput() {
        assertEquals(XrayConfig.Problem.EMPTY, XrayConfig.check("   "))
    }

    @Test
    fun checkReportsBrokenLink() {
        assertEquals(
            XrayConfig.Problem.INVALID_LINK,
            XrayConfig.check("vless://u@example.com:443?security=tls&sni=a&pbk=K")
        )
    }

    @Test
    fun checkReportsInvalidJson() {
        assertEquals(XrayConfig.Problem.INVALID_JSON, XrayConfig.check("{ not json"))
    }

    @Test
    fun checkReportsMissingOutbounds() {
        assertEquals(XrayConfig.Problem.NO_OUTBOUND, XrayConfig.check("{\"log\":{}}"))
    }

    @Test
    fun checkReportsNonVlessOutbound() {
        assertEquals(
            XrayConfig.Problem.NOT_VLESS,
            XrayConfig.check("{\"outbounds\":[{\"protocol\":\"vmess\"}]}")
        )
    }

    @Test
    fun checkAcceptsGeneratedJsonConfig() {
        val json = XrayConfig.buildConfig(XrayConfig.parseVlessLink(fullLink))
        assertNull(XrayConfig.check(json))
    }

    @Test
    fun checkReportsMissingPublicKeyInJson() {
        val json =
            "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"example.com\"," +
                "\"port\":443,\"users\":[{\"id\":\"u\"}]}]},\"streamSettings\":{\"security\":\"reality\"," +
                "\"realitySettings\":{}}}]}"
        assertEquals(XrayConfig.Problem.MISSING_PUBLIC_KEY, XrayConfig.check(json))
    }

    @Test
    fun checkReportsNonRealityInJson() {
        val json =
            "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"example.com\"," +
                "\"port\":443,\"users\":[{\"id\":\"u\"}]}]},\"streamSettings\":{\"security\":\"tls\"}}]}"
        assertEquals(XrayConfig.Problem.NOT_REALITY, XrayConfig.check(json))
    }

    @Test
    fun formatKeepsJsonContentEquivalent() {
        val pretty = XrayConfig.format("{\"a\":1}")
        assertEquals(1, org.json.JSONObject(pretty).getInt("a"))
    }

    @Test
    fun formatKeepsVlessLinkUnchanged() {
        assertEquals(fullLink, XrayConfig.format("  $fullLink  "))
    }

    @Test
    fun builtInTemplateNeedsFillingIn() {
        assertEquals(5, XrayConfig.placeholderCount(XrayConfig.TEMPLATE))
        assertEquals(XrayConfig.Problem.PLACEHOLDER, XrayConfig.check(XrayConfig.TEMPLATE))
    }

    @Test
    fun filledTemplateIsAccepted() {
        val filled =
            XrayConfig.TEMPLATE
                .replace("REPLACE_SERVER_IP", "203.0.113.10")
                .replace("REPLACE_UUID", "11111111-2222-3333-4444-555555555555")
                .replace("REPLACE_SNI", "www.example.org")
                .replace("REPLACE_PUBLIC_KEY", "PUBKEY123")
                .replace("REPLACE_SHORT_ID", "abcd")
        assertEquals(0, XrayConfig.placeholderCount(filled))
        assertNull(XrayConfig.check(filled))
    }

    @Test
    fun formatKeepsBuiltInTemplateParseable() {
        assertEquals(
            XrayConfig.check(XrayConfig.TEMPLATE),
            XrayConfig.check(XrayConfig.format(XrayConfig.TEMPLATE))
        )
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesAndControls() {
        assertEquals("\"a\\\"b\\\\c\"", XrayConfig.jsonString("a\"b\\c"))
        assertEquals("\"x\\ny\"", XrayConfig.jsonString("x\ny"))
    }
}
