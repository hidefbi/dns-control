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

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

/**
 * Builds an xray-core configuration for a single outbound. Supported: VLESS with REALITY or TLS,
 * Trojan over TLS, and Shadowsocks 2022. VLESS and Trojan can use tcp, ws or grpc.
 *
 * The generated config exposes a local SOCKS5 inbound on [LOCAL_HOST]:[LOCAL_PORT] with UDP
 * enabled, which the VPN tunnel uses as its upstream proxy.
 */
object XrayConfig {

    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 10808

    /** Credentials the local SOCKS5 inbound requires. A new pair is generated for every run. */
    class LocalAuth(val user: String, val pass: String)

    // a direct rule may only cover these; everything else must go through the proxy
    private val PRIVATE_NETS: List<IPAddress> =
        listOf(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "127.0.0.0/8",
                "169.254.0.0/16",
                "::1/128",
                "fc00::/7",
                "fe80::/10",
            )
            .map { IPAddressString(it).address }

    private val NET_RE = Regex("""[0-9A-Fa-f:.]+(/\d{1,3})?""")
    private val PORT_SPEC_RE = Regex("""\d{1,5}(-\d{1,5})?(,\d{1,5}(-\d{1,5})?)*""")
    private val UUID_RE =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val SHORT_ID_RE = Regex("""(?:[0-9a-fA-F]{2}){0,8}""")
    private val KEY_RE = Regex("""[A-Za-z0-9_-]{43}""")
    private val SERVER_NAME_RE = Regex("""[A-Za-z0-9.-]{1,253}""")
    private val FINGERPRINT_RE = Regex("""[a-z_]{1,32}""")

    private const val VLESS_SCHEME = "vless://"
    private const val TROJAN_SCHEME = "trojan://"
    private const val SHADOWSOCKS_SCHEME = "ss://"
    private const val DNS_PORT = 53
    private const val DEFAULT_FINGERPRINT = "chrome"
    private const val DEFAULT_TRANSPORT = "tcp"
    private const val REALITY = "reality"
    private const val TLS = "tls"
    private const val NONE = "none"
    private const val VISION = "xtls-rprx-vision"
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val TRANSPORTS = setOf("tcp", "ws", "grpc")
    private val SUPPORTED_PROTOCOLS = setOf("vless", "trojan", "shadowsocks")
    // Shadowsocks 2022 ciphers and the key length each one needs, in bytes
    private val SS_KEY_BYTES =
        mapOf(
            "2022-blake3-aes-128-gcm" to 16,
            "2022-blake3-aes-256-gcm" to 32,
            "2022-blake3-chacha20-poly1305" to 32,
        )
    private val VLESS_REALITY_FIELDS =
        listOf(
            Field.SERVER_ADDRESS,
            Field.UUID,
            Field.SERVER_NAME,
            Field.PUBLIC_KEY,
            Field.SHORT_ID,
        )
    private val VLESS_TLS_FIELDS = listOf(Field.SERVER_ADDRESS, Field.UUID, Field.SERVER_NAME)

    /** Values the user must replace before the config can be saved. */
    private val PLACEHOLDER = Regex("REPLACE_[A-Z_]+")

    /** A bad link. [problem] says what the input got wrong; the default covers any other fault. */
    class InvalidLinkException(message: String, val problem: Problem = Problem.INVALID_LINK) :
        IllegalArgumentException(message)

    /** A server the config connects to. The subclass says which protocol it speaks. */
    sealed class Server {
        abstract val name: String
        abstract val host: String
        abstract val port: Int
    }

    /** VLESS with REALITY or TLS. [security] is "reality" or "tls"; [network] is tcp, ws or grpc. */
    data class VlessLink(
        override val name: String,
        val uuid: String,
        override val host: String,
        override val port: Int,
        val flow: String,
        val serverName: String,
        val fingerprint: String,
        val publicKey: String,
        val shortId: String,
        val spiderX: String,
        val security: String = REALITY,
        val network: String = DEFAULT_TRANSPORT,
        val alpn: String = "",
        val wsPath: String = "",
        val wsHost: String = "",
        val grpcService: String = "",
    ) : Server()

    /** Trojan always runs over TLS. [network] is tcp, ws or grpc. */
    data class TrojanLink(
        override val name: String,
        val password: String,
        override val host: String,
        override val port: Int,
        val serverName: String,
        val fingerprint: String,
        val alpn: String,
        val network: String,
        val wsPath: String,
        val wsHost: String,
        val grpcService: String,
    ) : Server()

    /** Shadowsocks 2022. [method] is one of [SS_KEY_BYTES]; [password] is the base64 key. */
    data class ShadowsocksLink(
        override val name: String,
        val method: String,
        val password: String,
        override val host: String,
        override val port: Int,
    ) : Server()

    enum class Problem {
        EMPTY,
        PLACEHOLDER,
        INVALID_JSON,
        INVALID_LINK,
        NO_OUTBOUND,
        NO_SUPPORTED_OUTBOUND,
        MISSING_SERVER,
        MISSING_USER,
        MISSING_PASSWORD,
        MISSING_SERVER_NAME,
        MISSING_PUBLIC_KEY,
        UNSUPPORTED_SECURITY,
        UNSUPPORTED_TRANSPORT,
        BAD_METHOD,
        BAD_KEY,
        BAD_SERVER,
        HOST_NOT_IP,
        DNS_BLOCK,
        BAD_RULE,
    }

    /** The values the user must fill in for a server. Which ones apply depends on the protocol. */
    enum class Field {
        SERVER_ADDRESS,
        UUID,
        PASSWORD,
        METHOD,
        SERVER_NAME,
        PUBLIC_KEY,
        SHORT_ID,
    }

    // raised while reading a JSON outbound; check() reports the problem it carries
    private class BadServer(val problem: Problem) : RuntimeException()

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

    fun isLink(input: String): Boolean = schemeOf(input) != null

    fun isVlessLink(input: String): Boolean =
        input.trim().startsWith(VLESS_SCHEME, ignoreCase = true)

    private fun schemeOf(input: String): String? {
        val text = input.trim()
        return listOf(VLESS_SCHEME, TROJAN_SCHEME, SHADOWSOCKS_SCHEME).firstOrNull {
            text.startsWith(it, ignoreCase = true)
        }
    }

    /** Parses a vless://, trojan:// or ss:// link into the server it describes. */
    fun parseLink(input: String): Server {
        return when (schemeOf(input)) {
            VLESS_SCHEME -> parseVlessLink(input)
            TROJAN_SCHEME -> parseTrojanLink(input)
            SHADOWSOCKS_SCHEME -> parseShadowsocksLink(input)
            else -> throw InvalidLinkException("not a supported link")
        }
    }

    fun parseVlessLink(input: String): VlessLink {
        val link = parseVlessUnchecked(input)
        validateVless(link)
        return link
    }

    private fun parseVlessUnchecked(input: String): VlessLink {
        val text = input.trim()
        if (!isVlessLink(text)) throw InvalidLinkException("not a vless link")

        val uri = uriOf(text)
        val uuid = uri.rawUserInfo?.let { decode(it) }.orEmpty()
        val host = hostOf(uri)
        val port = uri.port
        if (uuid.isEmpty() || host.isEmpty() || port !in 1..65535) {
            throw InvalidLinkException("missing uuid, host or port")
        }

        val params = parseQuery(uri.rawQuery.orEmpty())
        val security = params["security"].orEmpty()
        if (security != REALITY && security != TLS) {
            throw InvalidLinkException("unsupported security", Problem.UNSUPPORTED_SECURITY)
        }
        val serverName = params["sni"].orEmpty()
        if (serverName.isEmpty()) {
            throw InvalidLinkException("missing sni", Problem.MISSING_SERVER_NAME)
        }
        val publicKey = params["pbk"].orEmpty()
        if (security == REALITY && publicKey.isEmpty()) {
            throw InvalidLinkException("missing pbk", Problem.MISSING_PUBLIC_KEY)
        }

        return VlessLink(
            name = uri.rawFragment?.let { decode(it) }.orEmpty(),
            uuid = uuid,
            host = host,
            port = port,
            flow = params["flow"].orEmpty(),
            serverName = serverName,
            fingerprint = fingerprintOf(params["fp"].orEmpty(), security),
            publicKey = publicKey,
            shortId = params["sid"].orEmpty(),
            spiderX = params["spx"].orEmpty(),
            security = security,
            network = transportOf(params["type"]),
            alpn = params["alpn"].orEmpty(),
            wsPath = params["path"].orEmpty(),
            wsHost = params["host"].orEmpty(),
            grpcService = params["serviceName"].orEmpty(),
        )
    }

    fun parseTrojanLink(input: String): TrojanLink {
        val text = input.trim()
        if (!text.startsWith(TROJAN_SCHEME, ignoreCase = true)) {
            throw InvalidLinkException("not a trojan link")
        }
        val uri = uriOf(text)
        val password = uri.rawUserInfo?.let { decode(it) }.orEmpty()
        if (password.isEmpty()) throw InvalidLinkException("missing password", Problem.MISSING_PASSWORD)
        val host = hostOf(uri)
        val port = uri.port
        if (host.isEmpty() || port !in 1..65535) throw InvalidLinkException("missing host or port")

        val params = parseQuery(uri.rawQuery.orEmpty())
        // trojan is TLS only; the security parameter is optional and defaults to tls
        val security = params["security"]?.takeIf { it.isNotEmpty() } ?: TLS
        if (security != TLS) throw InvalidLinkException("trojan needs tls", Problem.UNSUPPORTED_SECURITY)
        val serverName = params["sni"].orEmpty()
        if (serverName.isEmpty()) throw InvalidLinkException("missing sni", Problem.MISSING_SERVER_NAME)

        val link =
            TrojanLink(
                name = uri.rawFragment?.let { decode(it) }.orEmpty(),
                password = password,
                host = host,
                port = port,
                serverName = serverName,
                fingerprint = params["fp"].orEmpty(),
                alpn = params["alpn"].orEmpty(),
                network = transportOf(params["type"]),
                wsPath = params["path"].orEmpty(),
                wsHost = params["host"].orEmpty(),
                grpcService = params["serviceName"].orEmpty(),
            )
        if (!SERVER_NAME_RE.matches(serverName)) throw InvalidLinkException("invalid serverName")
        if (!fingerprintValid(link.fingerprint)) throw InvalidLinkException("invalid fingerprint")
        return link
    }

    /**
     * ss://BASE64(method:password)@host:port#name, the SIP002 form. The older form, where the
     * whole part before the host is base64, and a plain method:password@host:port are accepted too.
     */
    fun parseShadowsocksLink(input: String): ShadowsocksLink {
        val text = input.trim()
        if (!text.startsWith(SHADOWSOCKS_SCHEME, ignoreCase = true)) {
            throw InvalidLinkException("not an ss link")
        }
        val name = decode(text.substringAfter('#', ""))
        val body = text.substring(SHADOWSOCKS_SCHEME.length).substringBefore('#').trimEnd('/')
        // a query carries a plugin, which this app does not run
        if (body.contains('?')) throw InvalidLinkException("plugins are not supported")

        val at = body.lastIndexOf('@')
        val (userinfo, hostPort) =
            if (at >= 0) {
                body.substring(0, at) to body.substring(at + 1)
            } else {
                val whole = base64Text(body) ?: throw InvalidLinkException("malformed ss link")
                val inner = whole.lastIndexOf('@')
                if (inner < 0) throw InvalidLinkException("malformed ss link")
                whole.substring(0, inner) to whole.substring(inner + 1)
            }

        // a plain userinfo has its colon in it; a base64 one never does
        val pair =
            if (userinfo.contains(':')) decodeNoPlus(userinfo)
            else base64Text(userinfo) ?: throw InvalidLinkException("malformed ss link")
        val sep = pair.indexOf(':')
        if (sep <= 0) throw InvalidLinkException("malformed ss link")
        val method = pair.substring(0, sep)
        val password = pair.substring(sep + 1)
        val keyBytes =
            SS_KEY_BYTES[method] ?: throw InvalidLinkException("unsupported cipher", Problem.BAD_METHOD)
        if (base64Bytes(password)?.size != keyBytes) {
            throw InvalidLinkException("bad key length", Problem.BAD_KEY)
        }

        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) throw InvalidLinkException("missing port")
        val host = hostPort.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 0
        if (host.isEmpty() || port !in 1..65535) throw InvalidLinkException("missing host or port")
        return ShadowsocksLink(name, method, password, host, port)
    }

    private fun uriOf(link: String): URI =
        try {
            URI(link)
        } catch (e: Exception) {
            throw InvalidLinkException("malformed link")
        }

    // IPv6 literals come back bracketed; xray expects the bare address
    private fun hostOf(uri: URI): String = uri.host.orEmpty().removePrefix("[").removeSuffix("]")

    /** tcp, ws or grpc. xray also calls tcp "raw" and ws "websocket". */
    private fun transportOf(raw: String?): String {
        val value = raw?.takeIf { it.isNotEmpty() } ?: DEFAULT_TRANSPORT
        val network =
            when (value) {
                "raw" -> DEFAULT_TRANSPORT
                "websocket" -> "ws"
                else -> value
            }
        if (network !in TRANSPORTS) {
            throw InvalidLinkException("unsupported transport", Problem.UNSUPPORTED_TRANSPORT)
        }
        return network
    }

    private fun fingerprintOf(fp: String, security: String): String =
        if (fp.isNotEmpty()) fp else if (security == REALITY) DEFAULT_FINGERPRINT else ""

    private fun fingerprintValid(fingerprint: String): Boolean =
        fingerprint.isEmpty() || FINGERPRINT_RE.matches(fingerprint)

    /** Rejects server parameters that are not in the shape REALITY or TLS uses. */
    private fun validateVless(link: VlessLink) {
        if (!UUID_RE.matches(link.uuid)) throw InvalidLinkException("invalid uuid")
        if (!SERVER_NAME_RE.matches(link.serverName)) throw InvalidLinkException("invalid serverName")
        if (!fingerprintValid(link.fingerprint)) throw InvalidLinkException("invalid fingerprint")
        if (link.security == REALITY) {
            if (!SHORT_ID_RE.matches(link.shortId)) throw InvalidLinkException("invalid shortId")
            if (!KEY_RE.matches(link.publicKey)) throw InvalidLinkException("invalid publicKey")
        }
        // vision only works over plain tcp
        if (link.flow.isNotEmpty() && (link.flow != VISION || link.network != DEFAULT_TRANSPORT)) {
            throw InvalidLinkException("unsupported flow")
        }
    }

    private fun decodeNoPlus(value: String): String = decode(value.replace("+", "%2B"))

    /** Bytes of base64 or base64url text, or null when it is not base64. Padding is optional. */
    private fun base64Bytes(text: String): ByteArray? {
        val clean = text.trimEnd('=').replace('-', '+').replace('_', '/')
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val index = BASE64_ALPHABET.indexOf(c)
            if (index < 0) return null
            buffer = ((buffer shl 6) or index) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun base64Text(text: String): String? = base64Bytes(text)?.toString(Charsets.UTF_8)

    /** base64url without padding, the form share links use. */
    private fun base64UrlEncode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(BASE64_ALPHABET[(n shr 18) and 63])
            sb.append(BASE64_ALPHABET[(n shr 12) and 63])
            if (i + 1 < bytes.size) sb.append(BASE64_ALPHABET[(n shr 6) and 63])
            if (i + 2 < bytes.size) sb.append(BASE64_ALPHABET[n and 63])
            i += 3
        }
        return sb.toString().replace('+', '-').replace('/', '_')
    }

    /** First problem that would keep the input from producing a working config, or null. */
    fun check(input: String): Problem? {
        val text = input.trim()
        if (text.isEmpty()) return Problem.EMPTY
        if (PLACEHOLDER.containsMatchIn(text)) return Problem.PLACEHOLDER
        if (isLink(text)) {
            return try {
                val server = parseLink(text)
                if (isIpLiteral(server.host)) null else Problem.HOST_NOT_IP
            } catch (e: InvalidLinkException) {
                e.problem
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

        return try {
            jsonServer(root)
            null
        } catch (e: BadServer) {
            e.problem
        }
    }

    /** The first outbound this app can run, or null. Other outbounds in the JSON are ignored. */
    private fun supportedOutbound(root: JSONObject): JSONObject? {
        val outbounds = root.optJSONArray("outbounds") ?: return null
        return (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") in SUPPORTED_PROTOCOLS }
    }

    private fun supportedOutboundOf(input: String): JSONObject? {
        val text = input.trim()
        if (text.isEmpty() || isLink(text)) return null
        return try {
            supportedOutbound(JSONObject(text))
        } catch (e: JSONException) {
            null
        }
    }

    /** The fields the first outbound needs. Without an outbound yet, the VLESS+REALITY set. */
    fun requiredFields(input: String): List<Field> {
        val outbound = supportedOutboundOf(input) ?: return VLESS_REALITY_FIELDS
        return when (outbound.optString("protocol")) {
            "trojan" -> listOf(Field.SERVER_ADDRESS, Field.PASSWORD, Field.SERVER_NAME)
            "shadowsocks" -> listOf(Field.SERVER_ADDRESS, Field.METHOD, Field.PASSWORD)
            else ->
                if (securityOf(streamOf(outbound)) == TLS) VLESS_TLS_FIELDS else VLESS_REALITY_FIELDS
        }
    }

    /**
     * Required fields that are still empty or still REPLACE_ values. Null when the input is not an
     * xray JSON object (a link, or JSON that does not parse).
     */
    fun missingFields(input: String): List<Field>? {
        val text = input.trim()
        if (text.isEmpty() || isLink(text)) return null
        val root =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
        val values = valuesOf(supportedOutbound(root))
        return requiredFields(text).filter { field ->
            val value = values[field].orEmpty()
            value.isEmpty() || value.contains("REPLACE_")
        }
    }

    private fun valuesOf(outbound: JSONObject?): Map<Field, String> {
        if (outbound == null) return emptyMap()
        val node = serverNode(outbound)
        val stream = streamOf(outbound)
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val tlsLike = if (securityOf(stream) == REALITY) reality else tls
        val user = node?.optJSONArray("users")?.optJSONObject(0)
        val uuid = if (outbound.optString("protocol") == "vless") user?.optString("id").orEmpty() else ""
        return mapOf(
            Field.SERVER_ADDRESS to node?.optString("address").orEmpty(),
            Field.UUID to uuid,
            Field.PASSWORD to node?.optString("password").orEmpty(),
            Field.METHOD to node?.optString("method").orEmpty(),
            Field.SERVER_NAME to tlsLike?.optString("serverName").orEmpty(),
            Field.PUBLIC_KEY to reality?.optString("publicKey").orEmpty(),
            Field.SHORT_ID to reality?.optString("shortId").orEmpty(),
        )
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


    /**
     * True for an IPv4 or IPv6 literal. Domain names are refused on purpose: resolving the server
     * name would send a query outside the tunnel.
     */
    fun isIpLiteral(host: String): Boolean {
        if (!NET_RE.matches(host)) return false
        if (!host.contains(':') && host.count { it == '.' } != 3) return false
        return IPAddressString(host).isValid
    }

    /** Reads the first supported outbound of a JSON config. Throws [BadServer] with the reason. */
    private fun jsonServer(root: JSONObject): Server {
        val outbound = supportedOutbound(root) ?: throw BadServer(Problem.NO_SUPPORTED_OUTBOUND)
        return when (outbound.optString("protocol")) {
            "vless" -> vlessFromJson(outbound)
            "trojan" -> trojanFromJson(outbound)
            else -> shadowsocksFromJson(outbound)
        }
    }

    /** The first server entry: vnext for VLESS, servers for the others. */
    private fun serverNode(outbound: JSONObject): JSONObject? {
        val settings = outbound.optJSONObject("settings") ?: return null
        val list =
            if (outbound.optString("protocol") == "vless") settings.optJSONArray("vnext") else settings.optJSONArray("servers")
        return list?.optJSONObject(0)
    }

    private fun streamOf(outbound: JSONObject): JSONObject =
        outbound.optJSONObject("streamSettings") ?: JSONObject()

    private fun securityOf(stream: JSONObject): String = stream.optString("security").ifEmpty { NONE }

    /** Address must be present, an IP literal, with a usable port. */
    private fun checkedHost(node: JSONObject): String {
        val address = node.optString("address")
        if (address.isEmpty()) throw BadServer(Problem.MISSING_SERVER)
        if (!isIpLiteral(address)) throw BadServer(Problem.HOST_NOT_IP)
        if (node.optInt("port") !in 1..65535) throw BadServer(Problem.MISSING_SERVER)
        return address
    }

    private fun transportFromJson(stream: JSONObject): String {
        val raw = stream.optString("network").ifEmpty { DEFAULT_TRANSPORT }
        return try {
            transportOf(raw)
        } catch (e: InvalidLinkException) {
            throw BadServer(e.problem)
        }
    }

    private fun alpnFromJson(tls: JSONObject?): String {
        val list = tls?.optJSONArray("alpn") ?: return tls?.optString("alpn").orEmpty()
        return (0 until list.length()).joinToString(",") { list.optString(it) }
    }

    private fun vlessFromJson(outbound: JSONObject): VlessLink {
        val node = serverNode(outbound) ?: throw BadServer(Problem.MISSING_SERVER)
        val host = checkedHost(node)
        val user = node.optJSONArray("users")?.optJSONObject(0) ?: throw BadServer(Problem.MISSING_USER)
        val uuid = user.optString("id")
        if (uuid.isEmpty()) throw BadServer(Problem.MISSING_USER)

        val stream = streamOf(outbound)
        val security = securityOf(stream)
        if (security != REALITY && security != TLS) throw BadServer(Problem.UNSUPPORTED_SECURITY)
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val tlsLike = if (security == REALITY) reality else tls
        val publicKey = reality?.optString("publicKey").orEmpty()
        if (security == REALITY && publicKey.isEmpty()) throw BadServer(Problem.MISSING_PUBLIC_KEY)
        val serverName = tlsLike?.optString("serverName").orEmpty()
        if (serverName.isEmpty()) throw BadServer(Problem.MISSING_SERVER_NAME)
        val ws = stream.optJSONObject("wsSettings")

        val link =
            VlessLink(
                name = "",
                uuid = uuid,
                host = host,
                port = node.optInt("port"),
                flow = user.optString("flow"),
                serverName = serverName,
                fingerprint = fingerprintOf(tlsLike?.optString("fingerprint").orEmpty(), security),
                publicKey = publicKey,
                shortId = reality?.optString("shortId").orEmpty(),
                spiderX = reality?.optString("spiderX").orEmpty(),
                security = security,
                network = transportFromJson(stream),
                alpn = if (security == TLS) alpnFromJson(tls) else "",
                wsPath = ws?.optString("path").orEmpty(),
                wsHost = ws?.optString("host").orEmpty(),
                grpcService = stream.optJSONObject("grpcSettings")?.optString("serviceName").orEmpty(),
            )
        try {
            validateVless(link)
        } catch (e: InvalidLinkException) {
            throw BadServer(if (e.problem == Problem.INVALID_LINK) Problem.BAD_SERVER else e.problem)
        }
        return link
    }

    private fun trojanFromJson(outbound: JSONObject): TrojanLink {
        val node = serverNode(outbound) ?: throw BadServer(Problem.MISSING_SERVER)
        val host = checkedHost(node)
        val password = node.optString("password")
        if (password.isEmpty()) throw BadServer(Problem.MISSING_PASSWORD)

        val stream = streamOf(outbound)
        if (securityOf(stream) != TLS) throw BadServer(Problem.UNSUPPORTED_SECURITY)
        val tls = stream.optJSONObject("tlsSettings")
        val serverName = tls?.optString("serverName").orEmpty()
        if (serverName.isEmpty()) throw BadServer(Problem.MISSING_SERVER_NAME)
        if (!SERVER_NAME_RE.matches(serverName)) throw BadServer(Problem.BAD_SERVER)
        val ws = stream.optJSONObject("wsSettings")

        val link =
            TrojanLink(
                name = "",
                password = password,
                host = host,
                port = node.optInt("port"),
                serverName = serverName,
                fingerprint = tls?.optString("fingerprint").orEmpty(),
                alpn = alpnFromJson(tls),
                network = transportFromJson(stream),
                wsPath = ws?.optString("path").orEmpty(),
                wsHost = ws?.optString("host").orEmpty(),
                grpcService = stream.optJSONObject("grpcSettings")?.optString("serviceName").orEmpty(),
            )
        if (!fingerprintValid(link.fingerprint)) throw BadServer(Problem.BAD_SERVER)
        return link
    }

    private fun shadowsocksFromJson(outbound: JSONObject): ShadowsocksLink {
        val node = serverNode(outbound) ?: throw BadServer(Problem.MISSING_SERVER)
        val host = checkedHost(node)
        val method = node.optString("method")
        val keyBytes = SS_KEY_BYTES[method] ?: throw BadServer(Problem.BAD_METHOD)
        val password = node.optString("password")
        if (password.isEmpty()) throw BadServer(Problem.MISSING_PASSWORD)
        if (base64Bytes(password)?.size != keyBytes) throw BadServer(Problem.BAD_KEY)
        return ShadowsocksLink("", method, password, host, node.optInt("port"))
    }

    /**
     * The config xray actually runs. It is built only from the server parameters, so any dns,
     * routing, logging or other settings the user pasted are dropped. Every start path uses this.
     */
    fun canonicalConfig(input: String, auth: LocalAuth? = null): String {
        val text = input.trim()
        if (isLink(text)) return buildConfig(parseLink(text), auth = auth)
        val root = JSONObject(text)
        val extra =
            userRules(root)
                .filter { !isCatchAll(it) && isAllowedRule(it) }
                .joinToString("") { ", " + it.toString() }
        return buildConfig(jsonServer(root), extra, auth)
    }

    /** Routing rules the user wrote, in order. Anything but an object is ignored. */
    private fun userRules(root: JSONObject): List<JSONObject> {
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: return emptyList()
        return (0 until rules.length())
            .mapNotNull { rules.optJSONObject(it) }
            .filterNot { isGeneratedRule(it) }
    }

    private val GENERATED_BLOCK_NETS =
        setOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10")
    private val GENERATED_DIRECT_NETS =
        setOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "::1/128",
            "fc00::/7",
            "fe80::/10",
        )

    /**
     * The two rules this app writes into every config. A config copied back from the app keeps
     * them, so they are recognised and left out of the user rules.
     */
    private fun isGeneratedRule(rule: JSONObject): Boolean {
        val ips = rule.optJSONArray("ip")
        val nets =
            if (ips == null) emptySet() else (0 until ips.length()).map { ips.optString(it) }.toSet()
        return when (rule.optString("outboundTag")) {
            "block" -> rule.optString("port") == "53" && nets == GENERATED_BLOCK_NETS
            "direct" -> !rule.has("port") && nets == GENERATED_DIRECT_NETS
            else -> false
        }
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
        if (!narrow || !selectorsValid(rule)) return false
        return when (rule.optString("outboundTag")) {
            "proxy" -> true
            // a direct rule may only cover private and loopback addresses. Anything else, dns on
            // port 53 included, would leave the tunnel without going through the proxy
            "direct" -> isPrivateOnly(rule) && !coversDnsPort(rule)
            else -> false
        }
    }

    /** Every ip entry must be a literal or CIDR, and every port entry a valid port or range. */
    private fun selectorsValid(rule: JSONObject): Boolean {
        if (rule.has("ip")) {
            val ips = rule.optJSONArray("ip") ?: return false
            if (ips.length() == 0) return false
            for (i in 0 until ips.length()) {
                if (parseNet(ips.opt(i) as? String ?: return false) == null) return false
            }
        }
        if (rule.has("port")) {
            val spec = rule.opt("port")?.toString().orEmpty()
            if (!PORT_SPEC_RE.matches(spec)) return false
            val bad =
                spec.split(",").any { part ->
                    part.split("-").any { (it.toIntOrNull() ?: -1) !in 0..65535 }
                }
            if (bad) return false
        }
        return true
    }

    private fun isPrivateOnly(rule: JSONObject): Boolean {
        val ips = rule.optJSONArray("ip") ?: return false
        if (ips.length() == 0) return false
        for (i in 0 until ips.length()) {
            val net = parseNet(ips.opt(i) as? String ?: return false) ?: return false
            if (PRIVATE_NETS.none { it.contains(net) }) return false
        }
        return true
    }

    /** Parses an address or CIDR. Names are never resolved here, so nothing leaves the device. */
    private fun parseNet(text: String): IPAddress? {
        val trimmed = text.trim()
        if (!NET_RE.matches(trimmed)) return null
        return try {
            val address = IPAddressString(trimmed)
            if (address.isValid) address.address else null
        } catch (e: Exception) {
            null
        }
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

    /**
     * A share link for the server in the input, named [name] when one is given. Takes a link or a
     * JSON config. Throws when the input is not usable.
     */
    fun shareLink(input: String, name: String = ""): String {
        val text = input.trim()
        val server = if (isLink(text)) parseLink(text) else jsonServer(JSONObject(text))
        val label = name.ifEmpty { server.name }
        val fragment = if (label.isEmpty()) "" else "#${encodePart(label)}"
        val body =
            when (server) {
                is VlessLink -> vlessShare(server)
                is TrojanLink -> trojanShare(server)
                is ShadowsocksLink -> shadowsocksShare(server)
            }
        return body + fragment
    }

    private fun vlessShare(link: VlessLink): String {
        val params =
            query(
                "encryption" to "none",
                "flow" to link.flow,
                "security" to link.security,
                "type" to link.network,
                "sni" to link.serverName,
                "fp" to link.fingerprint,
                "alpn" to link.alpn,
                "pbk" to link.publicKey,
                "sid" to link.shortId,
                "spx" to link.spiderX,
                "path" to link.wsPath,
                "host" to link.wsHost,
                "serviceName" to link.grpcService,
            )
        return "$VLESS_SCHEME${encodePart(link.uuid)}@${hostPart(link.host)}:${link.port}?$params"
    }

    private fun trojanShare(link: TrojanLink): String {
        val params =
            query(
                "security" to TLS,
                "type" to link.network,
                "sni" to link.serverName,
                "fp" to link.fingerprint,
                "alpn" to link.alpn,
                "path" to link.wsPath,
                "host" to link.wsHost,
                "serviceName" to link.grpcService,
            )
        return "$TROJAN_SCHEME${encodePart(link.password)}@${hostPart(link.host)}:${link.port}?$params"
    }

    private fun shadowsocksShare(link: ShadowsocksLink): String {
        val userinfo = base64UrlEncode("${link.method}:${link.password}".toByteArray(Charsets.UTF_8))
        return "$SHADOWSOCKS_SCHEME$userinfo@${hostPart(link.host)}:${link.port}"
    }

    /** Joins the non-empty pairs into a query string. */
    private fun query(vararg pairs: Pair<String, String>): String =
        pairs
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { "${it.first}=${encodePart(it.second)}" }

    private fun hostPart(host: String): String = if (host.contains(':')) "[$host]" else host

    // URLEncoder writes spaces as "+"; a link is safer with %20
    private fun encodePart(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun buildConfig(server: Server, extraRules: String = "", auth: LocalAuth? = null): String {
        return """
            {
              "log": {"loglevel": "warning"},
              "inbounds": [${inbound(auth)}],
              "outbounds": [
                ${outboundJson(server)},
                {"tag": "direct", "protocol": "freedom"},
                {"tag": "block", "protocol": "blackhole"}
              ],
              "routing": {"domainStrategy": "AsIs", "rules": [{"type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10"], "port": "53", "network": "tcp,udp", "outboundTag": "block"}, {"type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10"], "outboundTag": "direct"}$extraRules, {"type": "field", "network": "tcp,udp", "outboundTag": "proxy"}]}
            }
            """.trimIndent()
    }

    private fun outboundJson(server: Server): String =
        when (server) {
            is VlessLink -> vlessOutbound(server)
            is TrojanLink -> trojanOutbound(server)
            is ShadowsocksLink -> shadowsocksOutbound(server)
        }

    private fun vlessOutbound(link: VlessLink): String {
        val flowField = if (link.flow.isEmpty()) "" else ",\"flow\":${jsonString(link.flow)}"
        val stream =
            streamJson(
                network = link.network,
                security = link.security,
                serverName = link.serverName,
                fingerprint = link.fingerprint,
                alpn = link.alpn,
                wsPath = link.wsPath,
                wsHost = link.wsHost,
                grpcService = link.grpcService,
                publicKey = link.publicKey,
                shortId = link.shortId,
                spiderX = link.spiderX,
            )
        return "{\"tag\": \"proxy\", \"protocol\": \"vless\", \"settings\": {\"vnext\": [{" +
            "\"address\": ${jsonString(link.host)}, \"port\": ${link.port}, " +
            "\"users\": [{\"id\": ${jsonString(link.uuid)}, \"encryption\": \"none\"$flowField}]}]}, " +
            "\"streamSettings\": $stream}"
    }

    private fun trojanOutbound(link: TrojanLink): String {
        val stream =
            streamJson(
                network = link.network,
                security = TLS,
                serverName = link.serverName,
                fingerprint = link.fingerprint,
                alpn = link.alpn,
                wsPath = link.wsPath,
                wsHost = link.wsHost,
                grpcService = link.grpcService,
                publicKey = "",
                shortId = "",
                spiderX = "",
            )
        return "{\"tag\": \"proxy\", \"protocol\": \"trojan\", \"settings\": {\"servers\": [{" +
            "\"address\": ${jsonString(link.host)}, \"port\": ${link.port}, " +
            "\"password\": ${jsonString(link.password)}}]}, " +
            "\"streamSettings\": $stream}"
    }

    // shadowsocks 2022 runs on its own protocol, so it has no transport settings
    private fun shadowsocksOutbound(link: ShadowsocksLink): String =
        "{\"tag\": \"proxy\", \"protocol\": \"shadowsocks\", \"settings\": {\"servers\": [{" +
            "\"address\": ${jsonString(link.host)}, \"port\": ${link.port}, " +
            "\"method\": ${jsonString(link.method)}, \"password\": ${jsonString(link.password)}}]}}"

    /** The streamSettings object for a VLESS or Trojan outbound, as one line of JSON. */
    private fun streamJson(
        network: String,
        security: String,
        serverName: String,
        fingerprint: String,
        alpn: String,
        wsPath: String,
        wsHost: String,
        grpcService: String,
        publicKey: String,
        shortId: String,
        spiderX: String,
    ): String {
        val parts = mutableListOf("\"network\": ${jsonString(network)}", "\"security\": ${jsonString(security)}")
        if (security == REALITY) {
            val spider = if (spiderX.isEmpty()) "" else ", \"spiderX\": ${jsonString(spiderX)}"
            parts.add(
                "\"realitySettings\": {\"serverName\": ${jsonString(serverName)}, " +
                    "\"fingerprint\": ${jsonString(fingerprint)}, " +
                    "\"publicKey\": ${jsonString(publicKey)}, " +
                    "\"shortId\": ${jsonString(shortId)}$spider}"
            )
        } else if (security == TLS) {
            val tls = mutableListOf("\"serverName\": ${jsonString(serverName)}")
            if (alpn.isNotEmpty()) tls.add("\"alpn\": ${alpnJson(alpn)}")
            if (fingerprint.isNotEmpty()) tls.add("\"fingerprint\": ${jsonString(fingerprint)}")
            parts.add("\"tlsSettings\": {${tls.joinToString(", ")}}")
        }
        when (network) {
            "ws" ->
                parts.add(
                    "\"wsSettings\": {\"path\": ${jsonString(wsPath.ifEmpty { "/" })}, " +
                        "\"host\": ${jsonString(wsHost)}}"
                )
            "grpc" -> parts.add("\"grpcSettings\": {\"serviceName\": ${jsonString(grpcService)}}")
        }
        return "{" + parts.joinToString(", ") + "}"
    }

    private fun alpnJson(alpn: String): String =
        "[" + alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") {
            jsonString(it)
        } + "]"

    /** Pretty-prints a JSON config. vless links are returned trimmed and unchanged. */
    fun format(input: String): String {
        val text = input.trim()
        if (isLink(text)) return text
        return JSONObject(text).toString(2)
    }

    fun inbound(auth: LocalAuth? = null): String {
        val settings =
            if (auth == null) {
                "\"auth\":\"noauth\""
            } else {
                "\"auth\":\"password\",\"accounts\":[{\"user\":${jsonString(auth.user)}," +
                    "\"pass\":${jsonString(auth.pass)}}]"
            }
        return "{\"tag\":\"socks-in\",\"listen\":${jsonString(LOCAL_HOST)}," +
            "\"port\":$LOCAL_PORT,\"protocol\":\"socks\"," +
            "\"settings\":{$settings,\"udp\":true}}"
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
