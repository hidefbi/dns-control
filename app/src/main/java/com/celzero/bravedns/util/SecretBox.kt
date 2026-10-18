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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (for example proxy passwords) at rest using an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it.
 *
 * Format: "ks1:" + base64(iv) + ":" + base64(ciphertext+tag). Values without the prefix are
 * treated as legacy plaintext and returned unchanged, so rows written before encryption was
 * introduced still read correctly; they are re-encrypted the next time they are saved.
 */
object SecretBox {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bravedns.secretbox.aes256gcm.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "ks1:"
    private const val SEPARATOR = ":"

    fun encrypt(plain: String?): String? {
        if (plain == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // no IV supplied: the Keystore generates a fresh random IV for every encryption
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored // legacy plaintext
        return try {
            val body = stored.removePrefix(PREFIX)
            val parts = body.split(SEPARATOR)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Do not log the value itself; only the failure type.
            Logger.w(LOG_TAG_PROXY, "secret decryption failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // the VPN reads these secrets in the background, so no user-auth gate
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
