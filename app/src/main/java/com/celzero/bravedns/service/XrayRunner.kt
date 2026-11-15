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
import android.util.Log
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.util.SecretBox
import com.celzero.bravedns.util.XrayConfig
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the user's xray profile (encrypted) and runs the xray-core binary that ships as
 * `libxray.so` in the native library directory.
 */
class XrayRunner(private val context: Context) {

    companion object {
        private const val TAG = "XrayRunner"
        // xray-core output goes to its own tag so it is visible in logcat
        private const val CORE_TAG = "XrayCore"
        private const val BINARY_NAME = "libxray.so"
        private const val DIR_NAME = "xray"
        // one file from before profiles existed; moved into the list on first use
        private const val PROFILE_NAME = "profile"
        // the whole list is one encrypted document: the profiles and which one is active
        private const val PROFILES_NAME = "profiles"
        private const val PROFILES_TMP_NAME = "profiles.tmp"
        // more profiles than this would clutter the switcher
        const val MAX_PROFILES = 10
        private const val NAME_MAX = 40
        private const val CONFIG_NAME = "config.json"
        private const val CONFIG_TMP_NAME = "config.json.tmp"
        // time xray gets to exit after a stop before it is killed
        private const val STOP_GRACE_MS = 2000L
        // time given to a previous xray to release the local port before it is killed
        private const val REAP_WAIT_MS = 3000L
        // the plaintext config is only needed until xray has read it
        private const val CONFIG_KEEP_MS = 2000L

        private val random = SecureRandom()
        // the profile list is shared by the service and the settings page, so it has one lock
        private val storeLock = Any()

        @Volatile private var process: Process? = null
        // a process that was stopped and may still hold the local port
        @Volatile private var exiting: Process? = null
        @Volatile private var auth: XrayConfig.LocalAuth? = null

        fun isRunning(): Boolean = process?.isAlive == true

        private fun randomHex(bytes: Int): String {
            val buf = ByteArray(bytes)
            random.nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }

    /** Called when xray exits without a stop. The argument is xray's exit status. */
    @Volatile var onUnexpectedExit: ((Int) -> Unit)? = null

    private val dir: File by lazy { File(context.filesDir, DIR_NAME).apply { mkdirs() } }
    private val profileFile: File
        get() = File(dir, PROFILE_NAME)
    private val configFile: File
        get() = File(dir, CONFIG_NAME)
    private val tmpConfigFile: File
        get() = File(dir, CONFIG_TMP_NAME)
    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /** Credentials of the running xray's local SOCKS5 inbound, or null when it is not running. */
    fun localAuth(): XrayConfig.LocalAuth? = if (isRunning()) auth else null

    /** A saved profile. The name is only shown in the list; the text is what xray runs. */
    data class Profile(val id: String, val name: String, val text: String)

    private class Store(var active: String?, val items: MutableList<Profile>)

    private val profilesFile: File
        get() = File(dir, PROFILES_NAME)
    private val profilesTmpFile: File
        get() = File(dir, PROFILES_TMP_NAME)

    /** All saved profiles, in the order they were added. */
    fun profiles(): List<Profile> {
        synchronized(storeLock) {
            return readStore().items.toList()
        }
    }

    /** The profile xray runs, or null when there is none. */
    fun activeProfile(): Profile? {
        synchronized(storeLock) {
            val s = readStore()
            return s.items.firstOrNull { it.id == s.active }
        }
    }

    /** Text of the active profile, or null when there is none. */
    fun savedProfile(): String? = activeProfile()?.text

    /** Replaces the active profile's text, or creates the first profile when there is none. */
    fun saveProfile(input: String): Boolean {
        synchronized(storeLock) {
            val s = readStore()
            val i = s.items.indexOfFirst { it.id == s.active }
            if (i >= 0) {
                s.items[i] = s.items[i].copy(text = input)
            } else {
                val added = newProfile(s, input)
                s.items.add(added)
                s.active = added.id
            }
            return writeStore(s)
        }
    }

    /** Adds a profile and makes it active. Refused while xray runs or when the list is full. */
    fun addProfile(input: String): Profile? {
        synchronized(storeLock) {
            if (isRunning()) return null
            val s = readStore()
            if (s.items.size >= MAX_PROFILES) return null
            val added = newProfile(s, input)
            s.items.add(added)
            s.active = added.id
            return if (writeStore(s)) added else null
        }
    }

    /** Makes a profile active. Refused while xray runs. */
    fun selectProfile(id: String): Boolean {
        synchronized(storeLock) {
            if (isRunning()) return false
            val s = readStore()
            if (s.items.none { it.id == id }) return false
            s.active = id
            return writeStore(s)
        }
    }

    /** Removes a profile; the first remaining one becomes active. Refused while xray runs. */
    fun deleteProfile(id: String): Boolean {
        synchronized(storeLock) {
            if (isRunning()) return false
            val s = readStore()
            if (!s.items.removeAll { it.id == id }) return false
            if (s.active == id) s.active = s.items.firstOrNull()?.id
            return writeStore(s)
        }
    }

    private fun newProfile(s: Store, input: String): Profile {
        var n = s.items.size + 1
        while (s.items.any { it.name == context.getString(R.string.xray_profile_name, n) }) n++
        val name = linkName(input) ?: context.getString(R.string.xray_profile_name, n)
        return Profile(randomHex(8), name, input)
    }

    /** The #name part of a vless link, used as the profile name when there is one. */
    private fun linkName(input: String): String? {
        if (!XrayConfig.isLink(input)) return null
        return try {
            XrayConfig.parseLink(input).name.trim().take(NAME_MAX).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readStore(): Store {
        if (!profilesFile.exists()) return migrateLegacy()
        return try {
            val plain = SecretBox.decrypt(profilesFile.readText())
            if (plain == null) Store(null, mutableListOf()) else parseStore(plain)
        } catch (e: Exception) {
            Log.w(TAG, "read profiles failed: ${e.message}", e)
            Store(null, mutableListOf())
        }
    }

    private fun parseStore(plain: String): Store {
        val root = JSONObject(plain)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<Profile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            items.add(Profile(id, o.optString("name"), o.optString("text")))
        }
        return Store(root.optString("active").takeIf { it.isNotEmpty() }, items)
    }

    private fun writeStore(s: Store): Boolean {
        val items = JSONArray()
        s.items.forEach {
            items.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text))
        }
        val json = JSONObject().put("active", s.active ?: "").put("items", items).toString()
        val encrypted = SecretBox.encrypt(json) ?: return false
        return try {
            // temp file and rename, so a crash never leaves a half-written list
            profilesTmpFile.writeText(encrypted)
            if (profilesTmpFile.renameTo(profilesFile)) {
                true
            } else {
                profilesTmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "write profiles failed: ${e.message}", e)
            false
        }
    }

    /** Moves the single profile of an older version into the list. The old file goes after a good write. */
    private fun migrateLegacy(): Store {
        val s = Store(null, mutableListOf())
        val text = legacyProfile() ?: return s
        val added = newProfile(s, text)
        s.items.add(added)
        s.active = added.id
        if (writeStore(s)) profileFile.delete()
        return s
    }

    private fun legacyProfile(): String? {
        return try {
            if (!profileFile.exists()) return null
            SecretBox.decrypt(profileFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "read profile failed: ${e.message}", e)
            null
        }
    }

    /**
     * Accepts a vless:// link or a full xray JSON config. Either way only the server parameters
     * are used, and the config that runs is generated by [XrayConfig.canonicalConfig].
     */
    fun buildConfig(input: String, creds: XrayConfig.LocalAuth? = null): String =
        XrayConfig.canonicalConfig(input, creds)

    @Synchronized
    fun start(input: String): Result<Unit> {
        if (isRunning()) return Result.success(Unit)
        if (!binary.exists() || !binary.canExecute()) {
            return Result.failure(IllegalStateException("xray binary not found"))
        }
        reapExiting()

        val creds = XrayConfig.LocalAuth(randomHex(8), randomHex(24))
        val config =
            try {
                buildConfig(input, creds)
            } catch (e: Exception) {
                return Result.failure(e)
            }

        return try {
            // written to a temp file and renamed into place, so a crash never leaves a partial file
            tmpConfigFile.writeText(config)
            tmpConfigFile.setReadable(false, false)
            tmpConfigFile.setReadable(true, true)
            if (!tmpConfigFile.renameTo(configFile)) {
                tmpConfigFile.delete()
                return Result.failure(IllegalStateException("config write failed"))
            }

            val started =
                ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            process = started
            auth = creds
            streamOutput(started)
            watchExit(started)
            dropConfigLater(started)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        val proc = process
        process = null
        auth = null
        // the config holds credentials: its plaintext copy goes away with the process
        configFile.delete()
        if (proc == null) return
        proc.destroy()
        exiting = proc
        // a process that ignores the stop is killed after a grace period, off the caller's thread
        Thread {
                if (!proc.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
            .apply {
                isDaemon = true
                start()
            }
    }

    /** Waits for a stopped xray to release the local port; kills it if it does not. */
    private fun reapExiting() {
        val old = exiting ?: return
        exiting = null
        if (!old.isAlive) return
        if (!old.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)) {
            old.destroyForcibly()
            old.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun streamOutput(proc: Process) {
        Thread {
                // xray output can name destinations, so it is only shown in debug builds.
                // the stream closes when the process exits, which ends this reader
                try {
                    proc.inputStream.bufferedReader().forEachLine {
                        if (BuildConfig.DEBUG) Log.i(CORE_TAG, it)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "xray output closed: ${e.message}")
                }
            }
            .apply {
                isDaemon = true
                start()
            }
    }

    /** Notices an xray that exits on its own, forgets it and removes its plaintext config. */
    private fun watchExit(proc: Process) {
        Thread {
                val code =
                    try {
                        proc.waitFor()
                    } catch (e: InterruptedException) {
                        -1
                    }
                var unexpected = false
                synchronized(this@XrayRunner) {
                    if (process === proc) {
                        process = null
                        auth = null
                        configFile.delete()
                        unexpected = true
                    }
                }
                if (unexpected) {
                    Log.w(TAG, "xray exited on its own, code: $code")
                    onUnexpectedExit?.invoke(code)
                }
            }
            .apply {
                isDaemon = true
                start()
            }
    }

    /** Removes the plaintext config once xray has had time to read it. */
    private fun dropConfigLater(proc: Process) {
        Thread {
                try {
                    Thread.sleep(CONFIG_KEEP_MS)
                } catch (e: InterruptedException) {
                    // nothing to do: the config is removed by stop() or by the exit watcher
                }
                synchronized(this@XrayRunner) {
                    if (process === proc) configFile.delete()
                }
            }
            .apply {
                isDaemon = true
                start()
            }
    }
}
