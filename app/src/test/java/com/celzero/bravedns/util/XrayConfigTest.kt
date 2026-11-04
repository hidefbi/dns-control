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
        "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&sid=abcd&flow=xtls-rprx-vision&type=tcp#My%20Node"

    @Test
    fun parsesFullRealityLink() {
        val link = XrayConfig.parseVlessLink(fullLink)
        assertEquals("My Node", link.name)
        assertEquals("11111111-2222-3333-4444-555555555555", link.uuid)
        assertEquals("203.0.113.10", link.host)
        assertEquals(443, link.port)
        assertEquals("xtls-rprx-vision", link.flow)
        assertEquals("www.example.org", link.serverName)
        assertEquals("chrome", link.fingerprint)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", link.publicKey)
        assertEquals("abcd", link.shortId)
    }

    @Test
    fun usesDefaultFingerprintWhenMissing() {
        val link =
            XrayConfig.parseVlessLink(
                "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=reality&sni=a.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            )
        assertEquals("chrome", link.fingerprint)
        assertEquals("", link.flow)
    }

    @Test
    fun stripsBracketsFromIpv6Host() {
        val link =
            XrayConfig.parseVlessLink(
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:8443?security=reality&sni=a.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            )
        assertEquals("2001:db8::1", link.host)
        assertEquals(8443, link.port)
    }

    @Test
    fun rejectsNonRealitySecurity() {
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink("vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=tls&sni=a&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        }
    }

    @Test
    fun rejectsMissingPublicKey() {
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink("vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=reality&sni=a")
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
        assertTrue(config.contains("\"publicKey\": \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\""))
        assertTrue(config.contains("\"serverName\": \"www.example.org\""))
        assertTrue(config.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(config.contains("\"port\": 443"))
    }

    @Test
    fun omitsFlowWhenAbsent() {
        val config =
            XrayConfig.buildConfig(
                XrayConfig.parseVlessLink(
                    "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=reality&sni=a.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
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
            XrayConfig.check("vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=tls&sni=a&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
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
            "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"203.0.113.10\"," +
                "\"port\":443,\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\"}]}]},\"streamSettings\":{\"security\":\"reality\"," +
                "\"realitySettings\":{}}}]}"
        assertEquals(XrayConfig.Problem.MISSING_PUBLIC_KEY, XrayConfig.check(json))
    }

    @Test
    fun checkReportsNonRealityInJson() {
        val json =
            "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"203.0.113.10\"," +
                "\"port\":443,\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\"}]}]},\"streamSettings\":{\"security\":\"tls\"}}]}"
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
                .replace("REPLACE_PUBLIC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
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
    fun missingFieldsListsEveryFieldOfTheTemplate() {
        assertEquals(XrayConfig.Field.values().toList(), XrayConfig.missingFields(XrayConfig.TEMPLATE))
    }

    @Test
    fun missingFieldsIsEmptyWhenFilledIn() {
        val filled =
            XrayConfig.TEMPLATE
                .replace("REPLACE_SERVER_IP", "203.0.113.10")
                .replace("REPLACE_UUID", "11111111-2222-3333-4444-555555555555")
                .replace("REPLACE_SNI", "www.example.org")
                .replace("REPLACE_PUBLIC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .replace("REPLACE_SHORT_ID", "abcd")
        assertEquals(emptyList<XrayConfig.Field>(), XrayConfig.missingFields(filled))
    }

    @Test
    fun missingFieldsIsNullForVlessLinkAndBrokenJson() {
        assertNull(XrayConfig.missingFields(fullLink))
        assertNull(XrayConfig.missingFields("{ not json"))
    }

    @Test
    fun errorSpotIsNullForValidJson() {
        assertNull(XrayConfig.errorSpot("{\"a\":1}"))
    }

    @Test
    fun errorSpotPointsAtBrokenLine() {
        // the missing comma after 1 is on line 2 or later, never on line 1
        val spot = XrayConfig.errorSpot("{\n  \"a\": 1\n  \"b\": 2\n}")
        assertTrue(spot != null && spot.line >= 2)
    }

    @Test
    fun generatedConfigHasNoDnsBlock() {
        val config = XrayConfig.buildConfig(XrayConfig.parseVlessLink(fullLink))
        assertFalse(config.contains("\"dns\""))
    }

    @Test
    fun generatedConfigSendsEverythingElseToProxy() {
        val config = XrayConfig.buildConfig(XrayConfig.parseVlessLink(fullLink))
        assertTrue(config.contains("\"network\": \"tcp,udp\", \"outboundTag\": \"proxy\""))
        assertTrue(config.contains("\"::1/128\""))
    }

    @Test
    fun canonicalConfigDropsUserDnsAndRouting() {
        val userJson =
            "{\"dns\":{\"servers\":[\"1.1.1.1\"]},\"routing\":{\"rules\":[]}," +
                "\"outbounds\":[{\"tag\":\"d\",\"protocol\":\"freedom\"},{\"protocol\":\"vless\"," +
                "\"settings\":{\"vnext\":[{\"address\":\"203.0.113.10\",\"port\":443," +
                "\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"encryption\":\"none\"}]}]}," +
                "\"streamSettings\":{\"security\":\"reality\",\"realitySettings\":" +
                "{\"serverName\":\"a.example\",\"publicKey\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"shortId\":\"ab\"}}}]}"
        val config = XrayConfig.canonicalConfig(userJson)
        assertFalse(config.contains("\"dns\""))
        assertTrue(config.contains("\"outboundTag\": \"direct\"") || config.contains("\"outboundTag\":\"direct\""))
        assertTrue(config.contains("\"publicKey\": \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\""))
    }

    @Test
    fun hostMustBeAnIpAddress() {
        assertEquals(
            XrayConfig.Problem.HOST_NOT_IP,
            XrayConfig.check("vless://11111111-2222-3333-4444-555555555555@server.example:443?security=reality&sni=a.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        )
        assertTrue(XrayConfig.isIpLiteral("203.0.113.10"))
        assertTrue(XrayConfig.isIpLiteral("2001:db8::1"))
        assertFalse(XrayConfig.isIpLiteral("server.example"))
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesAndControls() {
        assertEquals("\"a\\\"b\\\\c\"", XrayConfig.jsonString("a\"b\\c"))
        assertEquals("\"x\\ny\"", XrayConfig.jsonString("x\ny"))
    }

    private fun filledJsonWith(extra: String): String =
        "{" + extra + "\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"203.0.113.10\"," +
            "\"port\":443,\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"encryption\":\"none\"}]}]},\"streamSettings\":{\"security\":\"reality\"," +
            "\"realitySettings\":{\"serverName\":\"a.example\",\"publicKey\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"shortId\":\"ab\"}}}]}"

    @Test
    fun checkRefusesDnsBlock() {
        assertEquals(
            XrayConfig.Problem.DNS_BLOCK,
            XrayConfig.check(filledJsonWith("\"dns\":{\"servers\":[\"1.1.1.1\"]},"))
        )
    }

    @Test
    fun checkRefusesDirectRuleCoveringDnsPort() {
        assertEquals(
            XrayConfig.Problem.BAD_RULE,
            XrayConfig.check(
                filledJsonWith("\"routing\":{\"rules\":[{\"port\":\"50-60\",\"outboundTag\":\"direct\"}]},")
            )
        )
    }

    @Test
    fun checkRefusesDirectRuleWithoutPrivateAddress() {
        assertEquals(
            XrayConfig.Problem.BAD_RULE,
            XrayConfig.check(filledJsonWith("\"routing\":{\"rules\":[{\"port\":443,\"outboundTag\":\"direct\"}]},"))
        )
    }

    @Test
    fun checkAcceptsDirectRuleForPrivateAddress() {
        assertNull(
            XrayConfig.check(
                filledJsonWith(
                    "\"routing\":{\"rules\":[{\"ip\":[\"10.0.0.0/8\"],\"port\":443,\"outboundTag\":\"direct\"}]},"
                )
            )
        )
    }

    @Test
    fun checkRefusesBlanketUserRule() {
        assertEquals(
            XrayConfig.Problem.BAD_RULE,
            XrayConfig.check(
                filledJsonWith("\"routing\":{\"rules\":[{\"network\":\"tcp,udp\",\"outboundTag\":\"direct\"}]},")
            )
        )
    }

    @Test
    fun canonicalKeepsNarrowUserRuleBeforeCatchAll() {
        val config =
            XrayConfig.canonicalConfig(
                filledJsonWith(
                    "\"routing\":{\"rules\":[{\"type\":\"field\",\"domain\":[\"example.org\"],\"outboundTag\":\"proxy\"}]},"
                )
            )
        assertTrue(config.contains("\"domain\":[\"example.org\"]"))
        assertTrue(config.indexOf("example.org") < config.lastIndexOf("\"network\": \"tcp,udp\""))
        assertFalse(config.contains("\"dns\""))
    }

    private fun jsonWithRule(rule: String): String =
        "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{" +
            "\"address\":\"203.0.113.10\",\"port\":443," +
            "\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\"}]}]}," +
            "\"streamSettings\":{\"security\":\"reality\",\"realitySettings\":{" +
            "\"serverName\":\"www.example.org\",\"publicKey\":\"" + "A".repeat(43) + "\"," +
            "\"shortId\":\"abcd\"}}}],\"routing\":{\"rules\":[" + rule + "]}}"

    @Test
    fun directRuleForPrivateNetworkIsAccepted() {
        val json = jsonWithRule("{\"ip\":[\"10.0.0.0/8\"],\"outboundTag\":\"direct\"}")
        assertNull(XrayConfig.check(json))
    }

    @Test
    fun directRuleForPublicAddressIsRefused() {
        val json = jsonWithRule("{\"ip\":[\"8.8.8.8/32\"],\"outboundTag\":\"direct\"}")
        assertEquals(XrayConfig.Problem.BAD_RULE, XrayConfig.check(json))
    }

    @Test
    fun catchAllDirectRuleIsRefused() {
        val json = jsonWithRule("{\"ip\":[\"0.0.0.0/0\"],\"outboundTag\":\"direct\"}")
        assertEquals(XrayConfig.Problem.BAD_RULE, XrayConfig.check(json))
    }

    @Test
    fun directRuleCoveringDnsPortIsRefused() {
        val json =
            jsonWithRule(
                "{\"ip\":[\"10.0.0.0/8\"],\"port\":\"50-60\",\"outboundTag\":\"direct\"}"
            )
        assertEquals(XrayConfig.Problem.BAD_RULE, XrayConfig.check(json))
    }

    @Test
    fun ruleWithHostnameIsRefused() {
        val json = jsonWithRule("{\"ip\":[\"example.com\"],\"outboundTag\":\"proxy\"}")
        assertEquals(XrayConfig.Problem.BAD_RULE, XrayConfig.check(json))
    }

    @Test
    fun proxyRuleForPublicAddressIsAccepted() {
        val json = jsonWithRule("{\"ip\":[\"8.8.8.8/32\"],\"outboundTag\":\"proxy\"}")
        assertNull(XrayConfig.check(json))
    }

    @Test
    fun builtConfigRequiresLocalCredentialsWhenGiven() {
        val config =
            XrayConfig.buildConfig(
                XrayConfig.parseVlessLink(fullLink),
                auth = XrayConfig.LocalAuth("user1", "pass1"),
            )
        assertTrue(config.contains("\"auth\":\"password\""))
        assertTrue(config.contains("\"user\":\"user1\""))
        assertTrue(config.contains("\"pass\":\"pass1\""))
    }

    @Test
    fun rejectsMalformedUuid() {
        assertThrows(XrayConfig.InvalidLinkException::class.java) {
            XrayConfig.parseVlessLink(
                "vless://not-a-uuid@203.0.113.10:443?security=reality&sni=a.example&pbk=" +
                    "A".repeat(43)
            )
        }
    }

    @Test
    fun directDomainRuleIsRefusedBecauseNamesResolveOutsideTunnel() {
        assertEquals(
            XrayConfig.Problem.BAD_RULE,
            XrayConfig.check(
                filledJsonWith("\"routing\":{\"rules\":[{\"domain\":[\"example.org\"],\"outboundTag\":\"direct\"}]},")
            )
        )
    }
}
