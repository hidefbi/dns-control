/*
 * Copyright 2026 hidefbi
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

import java.io.ByteArrayOutputStream

/**
 * Converts DNS stamps (sdns://) for ODoH targets and ODoH relays into https URLs.
 *
 * Supported formats (after base64url decoding), per draft-denis-dns-stamps, all length
 * prefixes are one byte:
 *  - 0x05 ODoH target: props(8), LP(hostname), LP(path)
 *  - 0x85 ODoH relay:  props(8), LP(addr), VLP(certificate hashes), LP(hostname), LP(path),
 *                      optional VLP(bootstrap addresses) which are ignored
 *
 * Any other input (including plain https URLs) returns null.
 */
object DnsStamp {
    private const val SCHEME = "sdns://"
    private const val PROTO_ODOH_TARGET = 0x05
    private const val PROTO_ODOH_RELAY = 0x85
    private const val PROPS_LEN = 8
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Returns the https URL for an ODoH target or relay stamp, or null if it is not one. */
    fun toHttpsUrl(input: String): String? {
        val text = input.trim()
        if (!text.startsWith(SCHEME, ignoreCase = true)) return null
        return try {
            val bytes = decodeBase64Url(text.substring(SCHEME.length)) ?: return null
            val reader = Reader(bytes)
            val proto = reader.byte()
            reader.skip(PROPS_LEN)
            val host: String
            val path: String
            when (proto) {
                PROTO_ODOH_TARGET -> {
                    host = reader.str(reader.byte())
                    path = reader.str(reader.byte())
                }
                PROTO_ODOH_RELAY -> {
                    reader.str(reader.byte()) // addr, not needed for an https URL
                    reader.skipVlp() // certificate hashes, not needed for an https URL
                    host = reader.str(reader.byte())
                    path = reader.str(reader.byte())
                }
                else -> return null
            }
            if (host.isEmpty()) return null
            "https://$host${path.ifEmpty { "/" }}"
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeBase64Url(s: String): ByteArray? {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun byte(): Int {
            if (pos >= b.size) throw IllegalArgumentException("stamp too short")
            return b[pos++].toInt() and 0xFF
        }

        // variable-length list: each item is prefixed by a length byte whose high bit means
        // another item follows; the items themselves are skipped
        fun skipVlp() {
            while (true) {
                val l = byte()
                skip(l and 0x7F)
                if (l and 0x80 == 0) break
            }
        }

        fun skip(n: Int) {
            if (pos + n > b.size) throw IllegalArgumentException("stamp too short")
            pos += n
        }

        fun str(len: Int): String {
            if (pos + len > b.size) throw IllegalArgumentException("stamp too short")
            val s = String(b, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
    }
}
