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

import java.net.URI
import java.net.URLDecoder
import org.json.JSONException
import org.json.JSONObject

/**
 * Builds an xray-core configuration for a single VLESS+Reality outbound.
 *
 * The generated config exposes a local SOCKS5 inbound on [LOCAL_HOST]:[LOCAL_PORT] with UDP
 * enabled, which the VPN tunnel uses as its upstream proxy.
 */
object XrayConfig {

    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 10808

    private const val SCHEME = "vless://"
    private const val DNS_PORT = 53
    private const val DEFAULT_FINGERPRINT = "chrome"
    private const val DEFAULT_TRANSPORT = "tcp"
    private const val REALITY = "reality"

    /** Values the user must replace before the config can be saved. */
    private val PLACEHOLDER = Regex("REPLACE_[A-Z_]+")

    /**
     * Built-in client template for a VLESS+Reality server. Every REPLACE_ value must be filled in
     * with the server's own data. Routing keeps private networks off the proxy; DNS is left to the
     * app so that the blocklist keeps intercepting queries.
     */
    val TEMPLATE: String =
        """
        {
          "log": {
            "loglevel": "warning"
          },
          "inbounds": [
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": 10808,
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              }
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "REPLACE_SERVER_IP",
                    "port": 443,
                    "users": [
                      {
                        "id": "REPLACE_UUID",
                        "encryption": "none",
                        "flow": "xtls-rprx-vision"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "REPLACE_SNI",
                  "fingerprint": "chrome",
                  "publicKey": "REPLACE_PUBLIC_KEY",
                  "shortId": "REPLACE_SHORT_ID"
                }
              }
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {
                "type": "field",
                "ip": [
                  "10.0.0.0/8",
                  "172.16.0.0/12",
                  "192.168.0.0/16",
                  "127.0.0.0/8",
                  "169.254.0.0/16",
                  "::1/128",
                  "fc00::/7",
                  "fe80::/10"
                ],
                "outboundTag": "direct"
              },
              {
                "type": "field",
                "network": "tcp,udp",
                "outboundTag": "proxy"
              }
            ]
          }
        }
        """.trimIndent()

    /** Number of REPLACE_ values still left in [input]. */
    fun placeholderCount(input: String): Int = PLACEHOLDER.findAll(input).count()

    /** Ranges of the REPLACE_ values in [input], used to highlight them in the editor. */
    fun placeholderRanges(input: String): List<IntRange> =
        PLACEHOLDER.findAll(input).map { it.range }.toList()

    class InvalidLinkException(message: String) : IllegalArgumentException(message)

    data class VlessLink(
        val name: String,
        val uuid: String,
        val host: String,
        val port: Int,
        val flow: String,
        val serverName: String,
        val fingerprint: String,
        val publicKey: String,
        val shortId: String,
        val spiderX: String,
    )

    fun isVlessLink(input: String): Boolean {
        return input.trim().startsWith(SCHEME, ignoreCase = true)
    }

    fun parseVlessLink(input: String): VlessLink {
        val link = input.trim()
        if (!isVlessLink(link)) throw InvalidLinkException("not a vless link")

        val uri =
            try {
                URI(link)
            } catch (e: Exception) {
                throw InvalidLinkException("malformed vless link")
            }

        val uuid = uri.rawUserInfo?.let { decode(it) }.orEmpty()
        // IPv6 literals come back bracketed; xray expects the bare address
        val host = uri.host.orEmpty().removePrefix("[").removeSuffix("]")
        val port = uri.port
        if (uuid.isEmpty() || host.isEmpty() || port !in 1..65535) {
            throw InvalidLinkException("missing uuid, host or port")
        }

        val params = parseQuery(uri.rawQuery.orEmpty())
        if (params["security"] != REALITY) {
            throw InvalidLinkException("only reality security is supported")
        }
        val transport = params["type"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_TRANSPORT
        if (transport != DEFAULT_TRANSPORT) {
            throw InvalidLinkException("only tcp transport is supported")
        }

        val publicKey = params["pbk"].orEmpty()
        val serverName = params["sni"].orEmpty()
        if (publicKey.isEmpty() || serverName.isEmpty()) {
            throw InvalidLinkException("missing pbk or sni")
        }

        return VlessLink(
            name = uri.rawFragment?.let { decode(it) }.orEmpty(),
            uuid = uuid,
            host = host,
            port = port,
            flow = params["flow"].orEmpty(),
            serverName = serverName,
            fingerprint = params["fp"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_FINGERPRINT,
            publicKey = publicKey,
            shortId = params["sid"].orEmpty(),
            spiderX = params["spx"].orEmpty(),
        )
    }

    enum class Problem {
        EMPTY,
        PLACEHOLDER,
        INVALID_JSON,
        INVALID_LINK,
        NO_OUTBOUND,
        NOT_VLESS,
        MISSING_SERVER,
        MISSING_USER,
        NOT_REALITY,
        MISSING_PUBLIC_KEY,
        HOST_NOT_IP,
        DNS_BLOCK,
        BAD_RULE,
    }

    /** First problem that would keep the input from producing a working config, or null. */
    fun check(input: String): Problem? {
        val text = input.trim()
        if (text.isEmpty()) return Problem.EMPTY
        if (PLACEHOLDER.containsMatchIn(text)) return Problem.PLACEHOLDER
        if (isVlessLink(text)) {
            return try {
                val link = parseVlessLink(text)
                if (isIpLiteral(link.host)) null else Problem.HOST_NOT_IP
            } catch (e: InvalidLinkException) {
                Problem.INVALID_LINK
            }
        }

        val root =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                return Problem.INVALID_JSON
            }
        // dns is always handled by DNS Control; a dns block inside xray would resolve outside it
        if (root.has("dns")) return Problem.DNS_BLOCK
        if (userRules(root).any { !isCatchAll(it) && !isAllowedRule(it) }) return Problem.BAD_RULE

        val outbounds = root.optJSONArray("outbounds")
        if (outbounds == null || outbounds.length() == 0) return Problem.NO_OUTBOUND

        val vless =
            (0 until outbounds.length())
                .mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { it.optString("protocol") == "vless" }
                ?: return Problem.NOT_VLESS

        val server = vless.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
        if (server == null || server.optString("address").isEmpty()) return Problem.MISSING_SERVER
        if (!isIpLiteral(server.optString("address"))) return Problem.HOST_NOT_IP
        if (server.optInt("port") !in 1..65535) return Problem.MISSING_SERVER

        val user = server.optJSONArray("users")?.optJSONObject(0)
        if (user == null || user.optString("id").isEmpty()) return Problem.MISSING_USER

        val stream = vless.optJSONObject("streamSettings")
        if (stream == null || stream.optString("security") != REALITY) return Problem.NOT_REALITY
        val reality = stream.optJSONObject("realitySettings")
        if (reality == null || reality.optString("publicKey").isEmpty()) {
            return Problem.MISSING_PUBLIC_KEY
        }
        return null
    }

    /** The values the user must fill in for a VLESS+Reality server. */
    enum class Field {
        SERVER_ADDRESS,
        UUID,
        SERVER_NAME,
        PUBLIC_KEY,
        SHORT_ID,
    }

    /**
     * Required fields of the first vless outbound that are still empty or still REPLACE_ values.
     * Null when the input is not an xray JSON object (a vless link, or JSON that does not parse).
     */
    fun missingFields(input: String): List<Field>? {
        val text = input.trim()
        if (text.isEmpty() || isVlessLink(text)) return null
        val root =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
        val outbounds = root.optJSONArray("outbounds")
        val vless =
            (0 until (outbounds?.length() ?: 0))
                .mapNotNull { outbounds?.optJSONObject(it) }
                .firstOrNull { it.optString("protocol") == "vless" }
        val server = vless?.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
        val user = server?.optJSONArray("users")?.optJSONObject(0)
        val reality = vless?.optJSONObject("streamSettings")?.optJSONObject("realitySettings")

        val values =
            mapOf(
                Field.SERVER_ADDRESS to server?.optString("address").orEmpty(),
                Field.UUID to user?.optString("id").orEmpty(),
                Field.SERVER_NAME to reality?.optString("serverName").orEmpty(),
                Field.PUBLIC_KEY to reality?.optString("publicKey").orEmpty(),
                Field.SHORT_ID to reality?.optString("shortId").orEmpty(),
            )
        return Field.values().filter { field ->
            val value = values.getValue(field)
            value.isEmpty() || value.contains("REPLACE_")
        }
    }

    /** Where a JSON syntax error is, counted from 1. */
    data class ErrorSpot(val line: Int, val column: Int)

    // org.json on the JVM reports "... [character 6 line 1]"; Android reports "... at character 5 of"
    private val LINE_AND_COLUMN = Regex("character (\\d+) line (\\d+)")
    private val CHARACTER_INDEX = Regex("character (\\d+)")

    /** Line and column of the JSON syntax error in [input], or null when the JSON is valid. */
    fun errorSpot(input: String): ErrorSpot? {
        val message =
            try {
                JSONObject(input)
                return null
            } catch (e: JSONException) {
                e.message.orEmpty()
            }
        LINE_AND_COLUMN.find(message)?.let {
            return ErrorSpot(line = it.groupValues[2].toInt(), column = it.groupValues[1].toInt())
        }
        val index = CHARACTER_INDEX.find(message)?.groupValues?.get(1)?.toIntOrNull()
        return positionOf(input, index ?: 0)
    }

    private fun positionOf(input: String, index: Int): ErrorSpot {
        val end = index.coerceIn(0, input.length)
        var line = 1
        var column = 1
        for (i in 0 until end) {
            if (input[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return ErrorSpot(line, column)
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6_CHARS = Regex("""[0-9A-Fa-f:.]+""")

    /**
     * True for an IPv4 or IPv6 literal. Domain names are refused on purpose: resolving the server
     * name would send a query outside the tunnel.
     */
    fun isIpLiteral(host: String): Boolean {
        if (IPV4.matches(host)) return true
        return host.contains(':') && IPV6_CHARS.matches(host)
    }

    /**
     * The config xray actually runs. It is built only from the server parameters, so any dns,
     * routing, logging or other settings the user pasted are dropped. Every start path uses this.
     */
    fun canonicalConfig(input: String): String {
        val text = input.trim()
        if (isVlessLink(text)) return buildConfig(parseVlessLink(text))
        val root = JSONObject(text)
        val extra =
            userRules(root)
                .filter { !isCatchAll(it) && isAllowedRule(it) }
                .joinToString("") { ", " + it.toString() }
        return buildConfig(linkFromJson(root), extra)
    }

    /** Routing rules the user wrote, in order. Anything but an object is ignored. */
    private fun userRules(root: JSONObject): List<JSONObject> {
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: return emptyList()
        return (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
    }

    /**
     * A user rule must be narrow: it needs an ip, domain or port selector, and it must send to
     * proxy or direct. A rule that matches everything would take traffic away from the catch-all.
     */
    /** The catch-all that every config ends with; a pasted copy is ignored, never refused. */
    private fun isCatchAll(rule: JSONObject): Boolean {
        return rule.optString("network") == "tcp,udp" &&
            rule.optString("outboundTag") == "proxy" &&
            !rule.has("ip") && !rule.has("domain") && !rule.has("port")
    }

    private fun isAllowedRule(rule: JSONObject): Boolean {
        val narrow = rule.has("ip") || rule.has("domain") || rule.has("port")
        val tag = rule.optString("outboundTag")
        // a direct rule that covers port 53 would send dns around the proxy, so it is refused
        return narrow && (tag == "proxy" || (tag == "direct" && !coversDnsPort(rule)))
    }

    private fun coversDnsPort(rule: JSONObject): Boolean {
        if (!rule.has("port")) return false
        val spec = rule.opt("port")?.toString().orEmpty()
        return spec.split(",").any { part ->
            val bounds = part.trim().split("-")
            when (bounds.size) {
                1 -> bounds[0].trim().toIntOrNull() == DNS_PORT
                2 -> {
                    val low = bounds[0].trim().toIntOrNull()
                    val high = bounds[1].trim().toIntOrNull()
                    low != null && high != null && DNS_PORT in low..high
                }
                else -> false
            }
        }
    }

    private fun linkFromJson(root: JSONObject): VlessLink {
        val outbounds = root.optJSONArray("outbounds")
        val vless =
            (0 until (outbounds?.length() ?: 0))
                .mapNotNull { outbounds?.optJSONObject(it) }
                .firstOrNull { it.optString("protocol") == "vless" }
                ?: throw InvalidLinkException("no vless outbound")
        val server =
            vless.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                ?: throw InvalidLinkException("no server")
        val user =
            server.optJSONArray("users")?.optJSONObject(0)
                ?: throw InvalidLinkException("no user")
        val stream = vless.optJSONObject("streamSettings")
        val reality = stream?.optJSONObject("realitySettings")
        if (stream?.optString("security") != REALITY || reality == null) {
            throw InvalidLinkException("only reality security is supported")
        }

        val link =
            VlessLink(
                name = "",
                uuid = user.optString("id"),
                host = server.optString("address"),
                port = server.optInt("port"),
                flow = user.optString("flow"),
                serverName = reality.optString("serverName"),
                fingerprint = reality.optString("fingerprint").ifEmpty { DEFAULT_FINGERPRINT },
                publicKey = reality.optString("publicKey"),
                shortId = reality.optString("shortId"),
                spiderX = reality.optString("spiderX"),
            )
        if (link.uuid.isEmpty() || link.host.isEmpty() || link.port !in 1..65535) {
            throw InvalidLinkException("missing uuid, host or port")
        }
        if (link.publicKey.isEmpty() || link.serverName.isEmpty()) {
            throw InvalidLinkException("missing publicKey or serverName")
        }
        return link
    }

    /** Pretty-prints a JSON config. vless links are returned trimmed and unchanged. */
    fun format(input: String): String {
        val text = input.trim()
        if (isVlessLink(text)) return text
        return JSONObject(text).toString(2)
    }

    fun inbound(): String {
        return "{\"tag\":\"socks-in\",\"listen\":${jsonString(LOCAL_HOST)}," +
            "\"port\":$LOCAL_PORT,\"protocol\":\"socks\"," +
            "\"settings\":{\"auth\":\"noauth\",\"udp\":true}}"
    }

    fun buildConfig(link: VlessLink, extraRules: String = ""): String {
        val flowField = if (link.flow.isEmpty()) "" else ",\"flow\":${jsonString(link.flow)}"
        val spiderField =
            if (link.spiderX.isEmpty()) "" else ",\"spiderX\":${jsonString(link.spiderX)}"

        return """
            {
              "log": {"loglevel": "warning"},
              "inbounds": [${inbound()}],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": ${jsonString(link.host)},
                        "port": ${link.port},
                        "users": [{"id": ${jsonString(link.uuid)}, "encryption": "none"$flowField}]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "reality",
                    "realitySettings": {
                      "serverName": ${jsonString(link.serverName)},
                      "fingerprint": ${jsonString(link.fingerprint)},
                      "publicKey": ${jsonString(link.publicKey)},
                      "shortId": ${jsonString(link.shortId)}$spiderField
                    }
                  }
                },
                {"tag": "direct", "protocol": "freedom"},
                {"tag": "block", "protocol": "blackhole"}
              ],
              "routing": {"domainStrategy": "AsIs", "rules": [{"type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10"], "port": "53", "network": "tcp,udp", "outboundTag": "block"}, {"type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"], "outboundTag": "direct"}$extraRules, {"type": "field", "network": "tcp,udp", "outboundTag": "proxy"}]}
            }
            """.trimIndent()
    }

    fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.append('"').toString()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw
            .split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
                }
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}
