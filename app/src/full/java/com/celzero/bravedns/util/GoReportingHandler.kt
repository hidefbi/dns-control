package com.celzero.bravedns.util

import android.content.Context
import com.celzero.firestack.backend.LogConsumer
import com.celzero.firestack.intra.Console
import kotlinx.coroutines.CoroutineScope
import org.koin.core.component.KoinComponent

class GoReportingHandler private constructor(private val scope: CoroutineScope, private val ctx: Context): Console, KoinComponent {

    companion object {
        private var instance: GoReportingHandler? = null
        private const val TAG = "GoRptHandler"
        private const val WARNING_CHANNEL_ID = "warning"

        fun initialize(scope: CoroutineScope, ctx: Context) {
            if (instance != null) {
                return
            }

            instance = GoReportingHandler(scope, ctx.applicationContext)
        }

        fun getInstance(): GoReportingHandler = instance ?: error("GoReportingHandler not initialized")
    }

    init {
        // Logging and crash-output sinks are intentionally disabled.
    }

    // should never run in separate go-routine as msg is a bytearray in go and will be
    // released immediately
    override fun log(level: Int, m: String?) = Unit

    override fun logFD(p0: Long): Boolean = false

    override fun logMemFD(
        fda: Long,
        fdb: Long,
        bufferSize: Long,
        size: Long
    ): LogConsumer? = null

}
