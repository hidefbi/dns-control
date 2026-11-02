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
package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.SecretBox
import com.celzero.bravedns.util.XrayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the user's xray profile (encrypted) and runs the xray-core binary that ships as
 * `libxray.so` in the native library directory.
 */
class XrayRunner(private val context: Context) {

    companion object {
        private const val TAG = "XrayRunner"
        private const val BINARY_NAME = "libxray.so"
        private const val DIR_NAME = "xray"
        private const val PROFILE_NAME = "profile"
        private const val CONFIG_NAME = "config.json"

        @Volatile private var process: Process? = null

        fun isRunning(): Boolean = process?.isAlive == true
    }

    private val dir: File by lazy { File(context.filesDir, DIR_NAME).apply { mkdirs() } }
    private val profileFile: File
        get() = File(dir, PROFILE_NAME)
    private val configFile: File
        get() = File(dir, CONFIG_NAME)
    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun savedProfile(): String? {
        return try {
            if (!profileFile.exists()) return null
            SecretBox.decrypt(profileFile.readText())
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: read profile failed: ${e.message}", e)
            null
        }
    }

    fun saveProfile(input: String): Boolean {
        val encrypted = SecretBox.encrypt(input) ?: return false
        return try {
            profileFile.writeText(encrypted)
            true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: write profile failed: ${e.message}", e)
            false
        }
    }

    /**
     * Accepts either a vless:// link or a full xray JSON config. For JSON, only the outbounds
     * and routing are kept; the inbound is replaced by the local SOCKS5 inbound.
     */
    fun buildConfig(input: String): String {
        val text = input.trim()
        if (XrayConfig.isVlessLink(text)) {
            return XrayConfig.buildConfig(XrayConfig.parseVlessLink(text))
        }

        val root = JSONObject(text)
        val outbounds = root.optJSONArray("outbounds")
        require(outbounds != null && outbounds.length() > 0) { "config has no outbounds" }
        root.put("inbounds", JSONArray().put(JSONObject(XrayConfig.inbound())))
        return root.toString(2)
    }

    @Synchronized
    fun start(input: String): Result<Unit> {
        if (isRunning()) return Result.success(Unit)
        if (!binary.exists() || !binary.canExecute()) {
            return Result.failure(IllegalStateException("xray binary not found"))
        }

        val config =
            try {
                buildConfig(input)
            } catch (e: Exception) {
                return Result.failure(e)
            }

        return try {
            configFile.writeText(config)
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)

            val started =
                ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            process = started

            Thread {
                    started.inputStream.bufferedReader().forEachLine {
                        Logger.i(LOG_TAG_PROXY, "$TAG: $it")
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        // the config holds credentials; remove the plaintext copy once the process is gone
        configFile.delete()
    }
}
