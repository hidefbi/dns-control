/*
 * Copyright 2021 RethinkDNS and its authors
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


object Logger {

    private var logLevel: Long = LoggerLevel.NONE.id

    var uiLogLevel = LoggerLevel.NONE.id

    const val LOG_TAG_APP_UPDATE = "AppUpdate"
    const val LOG_TAG_VPN = "RethinkDnsVpn"
    const val LOG_TAG_CONNECTION = "ConnectivityEvents"
    const val LOG_TAG_DNS = "DnsManager"
    const val LOG_TAG_FIREWALL = "FirewallManager"
    const val LOG_TAG_APP_DB = "AppDatabase"
    const val LOG_TAG_DOWNLOAD = "DownloadManager"
    const val LOG_TAG_UI = "RethinkUI"
    const val LOG_TAG_SCHEDULER = "JobScheduler"
    const val LOG_TAG_PROXY = "ProxyLogs"
    const val LOG_QR_CODE = "QrCodeFromFileScanner"
    const val LOG_TAG_APP = "ExceptionHandler"
    const val LOG_OKHTTP = "OkHttp"
    const val LOG_FIREBASE = "FirebaseErrorReporting"


    // github.com/celzero/firestack/blob/bce8de917f/intra/log/logger.go#L76
    enum class LoggerLevel(val id: Long) {
        // the order of the levels is important, do not change it, add new levels at the end
        VERY_VERBOSE(0),
        VERBOSE(1),
        DEBUG(2),
        INFO(3),
        WARN(4),
        ERROR(5),
        STACKTRACE(6),
        USR(7),
        NONE(8);

        companion object {
            fun fromId(id: Int): LoggerLevel? {
                return when (id.toLong()) {
                    VERY_VERBOSE.id -> VERY_VERBOSE
                    VERBOSE.id -> VERBOSE
                    DEBUG.id -> DEBUG
                    INFO.id -> INFO
                    WARN.id -> WARN
                    ERROR.id -> ERROR
                    STACKTRACE.id -> STACKTRACE
                    USR.id -> USR
                    NONE.id -> NONE
                    else -> null
                }
            }

            /**
             * Maps the single-character log-level prefix written by the Go runtime
             * to a [LoggerLevel].  The Go side encodes the level as a single ASCII
             * character rather than a numeric id:
             *
             *   'Y' → VERY_VERBOSE
             *   'V' → VERBOSE
             *   'D' → DEBUG
             *   'I' → INFO
             *   'W' → WARN
             *   'E' → ERROR
             *   'F' → STACKTRACE  (Fatal/stacktrace)
             *   'U' → USR
             *   ' ' → NONE
             *
             * Returns `null` for any unrecognised character so callers can fall back
             * to the previous known level.
             */
            fun fromChar(c: Char): LoggerLevel? {
                return when (c) {
                    'Y' -> VERY_VERBOSE
                    'V' -> VERBOSE
                    'D' -> DEBUG
                    'I' -> INFO
                    'W' -> WARN
                    'E' -> ERROR
                    'F' -> STACKTRACE
                    'U' -> USR
                    // NONE won't be sent from Go, commenting it from usage
                    // using NONE will result in switching the log levels as it has ' ' as the char
                    // below is the e.g., from stacktrace logs
                    /*  ns.forwarder.deliverPackets [11] ns: tun: forwarder: deliverPackets rand10pc [gobind@v20260304225152-87c7a25]
                        go log: unknown level char 'n' using ERROR
                         (#0) -- ideally the space here switching levels to NONE but its actually STACKTRACE
                        <===>
                        go log: unknown level char '<' using NONE*/
                    // ' ' -> NONE
                    else -> null
                }
            }
        }

        fun toLoggerLevel(): LoggerLevel {
            return when (this) {
                VERY_VERBOSE -> VERY_VERBOSE
                VERBOSE -> VERBOSE
                DEBUG -> DEBUG
                INFO -> INFO
                WARN -> WARN
                ERROR -> ERROR
                STACKTRACE -> STACKTRACE
                USR -> USR
                NONE -> NONE
            }
        }

        fun stacktrace(): Boolean {
            return this == STACKTRACE
        }

        fun isLessThanOrEqualTo(level: LoggerLevel): Boolean {
            return this.id <= level.id
        }

        fun user(): Boolean {
            return this == USR
        }
    }

    fun vv(tag: String, message: String) = Unit
    fun v(tag: String, message: String) = Unit
    fun d(tag: String, message: String) = Unit
    fun i(tag: String, message: String) = Unit
    fun w(tag: String, message: String, e: Exception? = null) = Unit
    fun e(tag: String, message: String, e: Exception? = null) = Unit
    fun crash(tag: String, message: String, e: Exception? = null) = Unit

    fun updateConfigLevel(level: Long) { logLevel = LoggerLevel.NONE.id }

    fun throwableToException(throwable: Throwable): Exception {
        return if (throwable is Exception) {
            throwable
        } else {
            Exception(throwable)
        }
    }

    fun goLog(message: String, type: LoggerLevel) = Unit

    fun goLog2(message: String, type: LoggerLevel) = Unit

    fun goLog3(message: String, type: LoggerLevel): Nothing? = null

    /**
     * Single-character prefix for a log line, matching the level chars written by the
     * Go runtime (see [LoggerLevel.fromChar]). Centralised so [dbWrite] and the
     * batch-oriented [makeGoLog] never drift apart.
     */
    private fun levelChar(level: LoggerLevel): String = when (level) {
        LoggerLevel.VERY_VERBOSE -> "Y"
        LoggerLevel.VERBOSE -> "V"
        LoggerLevel.DEBUG -> "D"
        LoggerLevel.INFO -> "I"
        LoggerLevel.WARN -> "W"
        LoggerLevel.ERROR -> "E"
        LoggerLevel.STACKTRACE -> "F"
        else -> "V"
    }

    suspend fun wireLog(message: String) = Unit

    fun log(tag: String, msg: String, type: LoggerLevel, e: Exception? = null) = Unit

    private fun dbWrite(tag: String, msg: String, level: LoggerLevel, e: Exception? = null) = Unit
}

