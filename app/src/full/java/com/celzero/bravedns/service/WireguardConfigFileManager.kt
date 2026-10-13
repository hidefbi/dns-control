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
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Encrypted file I/O for WireGuard configuration files.
 * WireGuard private keys are never persisted as plaintext on disk.
 */
object WireguardConfigFileManager {

    /**
     * Returns the directory used for WireGuard config files.
     * Creates the directory if it does not exist.
     */
    fun getConfigDirectory(ctx: Context): File {
        val dir = File(ctx.filesDir, WIREGUARD_FOLDER_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Writes a WireGuard config string to a plain file.
     *
     * @return the written [File]
     * @throws IOException if the file cannot be written
     */
    @Throws(IOException::class)
    fun write(ctx: Context, cfg: String, fileName: String): File {
        val dir = getConfigDirectory(ctx)
        val file = File(dir, fileName)
        write(ctx, file, cfg)
        return file
    }

    /**
     * Writes a WireGuard config string to the given file.
     *
     * @throws IOException if the file cannot be written
     */
    @Throws(IOException::class)
    fun write(ctx: Context, file: File, cfg: String) {
        file.parentFile?.mkdirs()
        EncryptedFileManager.write(ctx, cfg, file)
    }

    /**
     * Reads a WireGuard config file as a byte array.
     *
     * @throws IOException if the file cannot be read
     */
    @Throws(IOException::class)
    fun read(ctx: Context, file: File): ByteArray {
        return if (isPlaintextConfig(file)) {
            val bytes = file.readBytes()
            EncryptedFileManager.write(ctx, bytes, file)
            bytes
        } else {
            EncryptedFileManager.readByteArray(ctx, file)
        }
    }

    /**
     * Deletes a WireGuard config file if it exists.
     *
     * @return true if the file was deleted or did not exist
     */
    fun delete(file: File): Boolean {
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    /**
     * Returns true if the file looks like a plain WireGuard config (contains an [Interface]
     * section). This is used by the migration to skip files that are already plaintext.
     */
    fun isPlaintextConfig(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            file.readText(StandardCharsets.UTF_8).contains("[Interface]", ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }
}
