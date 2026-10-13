package com.celzero.bravedns.download

import android.content.Context
import android.util.Base64
import com.celzero.bravedns.util.Constants
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies a downloaded blocklist set before it is activated. The manifest (index.json) must carry a
 * valid ECDSA P-256 / SHA-256 signature made with the private key matching [PUBLIC_KEY_B64]; each
 * payload file must match the size and sha256 recorded in the manifest; the manifest sequence must
 * equal the timestamp being downloaded and must not be older than the last accepted one.
 */
object BlocklistVerifier {
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsduDrMZU/AawN08qzrdZmV4+ruZ5qvpmWwEyaL0qvCVGdcuih91q5Sn9cuasebWxI3h1q5dpkJHKWax+EjvTUg=="
    private const val PREFS = "blocklist_verifier"
    private const val KEY_SEQ = "last_sequence"
    private val PAYLOAD = listOf("blocklists", "basicconfig", "rank", "trie")

    fun verify(context: Context, dir: File, expectedSequence: Long): Boolean {
        try {
            val manifestFile = File(dir, "index.json")
            val sigFile = File(dir, "index.json.sig")
            if (!manifestFile.isFile || !sigFile.isFile) return fail("manifest or signature missing")

            val manifestBytes = manifestFile.readBytes()
            val sig = Base64.decode(sigFile.readText().trim(), Base64.DEFAULT)
            val key =
                KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(manifestBytes)
            if (!verifier.verify(sig)) return fail("bad signature")

            val m = JSONObject(String(manifestBytes, Charsets.UTF_8))
            if (m.optInt("schema_version", 0) != 1) return fail("schema_version")
            val seq = m.optLong("sequence", -1L)
            if (seq <= 0L || seq != expectedSequence) return fail("sequence $seq != $expectedSequence")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (seq < prefs.getLong(KEY_SEQ, 0L)) return fail("rollback: $seq")

            val files = m.optJSONObject("files") ?: return fail("files missing")
            for (name in PAYLOAD) {
                val entry = files.optJSONObject(name) ?: return fail("no entry $name")
                val local =
                    Constants.ONDEVICE_BLOCKLISTS_IN_APP.firstOrNull { it.url == name }
                        ?: return fail("no local name $name")
                val f = File(dir.absolutePath + local.filename)
                if (!f.isFile || f.length() != entry.optLong("size", -1L)) return fail("size $name")
                if (!sha256(f).equals(entry.optString("sha256"), ignoreCase = true)) {
                    return fail("hash $name")
                }
            }
            prefs.edit().putLong(KEY_SEQ, seq).apply()
            return true
        } catch (e: Exception) {
            return fail("exception ${e.message}")
        }
    }

    private fun fail(why: String): Boolean {
        return false
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
