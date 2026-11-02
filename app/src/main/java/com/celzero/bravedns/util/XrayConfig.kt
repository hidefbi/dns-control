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
    private const val DEFAULT_FINGERPRINT = "chrome"
    private const val DEFAULT_TRANSPORT = "tcp"
    private const val REALITY = "reality"

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

    fun inbound(): String {
        return "{\"tag\":\"socks-in\",\"listen\":${jsonString(LOCAL_HOST)}," +
            "\"port\":$LOCAL_PORT,\"protocol\":\"socks\"," +
            "\"settings\":{\"auth\":\"noauth\",\"udp\":true}}"
    }

    fun buildConfig(link: VlessLink): String {
        val flowField = if (link.flow.isEmpty()) "" else ",\"flow\":${jsonString(link.flow)}"
        val spiderField =
            if (link.spiderX.isEmpty()) "" else ",\"spiderX\":${jsonString(link.spiderX)}"

        return """
            {
              "log": {"loglevel": "warning"},
              "dns": {"servers": ["1.1.1.1"]},
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
                {"tag": "direct", "protocol": "freedom"}
              ],
              "routing": {"domainStrategy": "AsIs", "rules": []}
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
