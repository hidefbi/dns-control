/*
 * Copyright 2025 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger.LOG_TAG_APP
import android.content.Context
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Global Exception Handler for catching all uncaught exceptions
 * and preserving crash context locally
 */
class GlobalExceptionHandler private constructor(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    contextRef: Context?
) : Thread.UncaughtExceptionHandler, KoinComponent {

    private val contextRef: WeakReference<Context>? = contextRef?.let { WeakReference(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistentState by inject<PersistentState>()
    companion object {
        private var instance: GlobalExceptionHandler? = null

        /**
         * Initialize the global exception handler
         * This should be called early in the application lifecycle
         */
        fun initialize(ctx: Context) {
            if (instance != null) {
                Logger.w(LOG_TAG_APP, "err-handler already initialized")
                return
            }

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            instance = GlobalExceptionHandler(defaultHandler, ctx.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(instance)

            Logger.i(LOG_TAG_APP, "err-handler initialized successfully")
        }

        /**
         * Get the current instance of the global exception handler
         */
        fun getInstance(): GlobalExceptionHandler? = instance
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val exception = Logger.throwableToException(throwable)
            Logger.e(LOG_TAG_APP, "uncaught exception in thread ${thread.name}", exception)

        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while handling uncaught exception", e)
        } finally {
            // Call the default handler to ensure proper app termination
            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                // If no default handler, terminate the process
                Logger.e(LOG_TAG_APP, "no default exception handler found, terminating process")
                exitProcess(1)
            }
        }
    }

    /**
     * Log additional context information about the exception
     */
    private fun logExceptionContext(thread: Thread, exception: Throwable) = Unit

    /**
     * Attempt to write logs to file with fallback options when context is unavailable
     */
    private fun writeLogsToFileWithFallback(msg: String) = Unit
}
