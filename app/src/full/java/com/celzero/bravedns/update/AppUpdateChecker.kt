/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Manual, user-triggered application update check.
 *
 * Nothing in this file runs in the background. Every network request is started from
 * the "Check for app updates" button in the miscellaneous settings, and the installer is
 * only opened after the user has confirmed the download. The release is taken from the
 * project's GitHub Releases; the APK is verified against the published SHA-256 checksum
 * and its package name and version code before it is handed to the system installer.
 * Android additionally refuses to install an APK whose signing certificate differs from
 * the installed app.
 */
package com.celzero.bravedns.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_APP_UPDATE
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateChecker {

    private const val OWNER = "hidefbi"
    private const val REPO = "dns-control"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/$OWNER/$REPO/releases/download/"
    private const val CHECKSUMS_ASSET_NAME = "SHA256SUMS.txt"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val UPDATE_DIR_NAME = "updates"
    private const val UPDATE_APK_NAME = "dns-control-update.apk"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_CHECKSUM_BYTES = 64 * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_NOTES_CHARS = 1200
    private const val BUFFER_SIZE = 64 * 1024

    data class Release(
        val tag: String,
        val versionCode: Int,
        val notes: String,
        val apkAssetName: String,
        val apkUrl: String,
        val checksumsUrl: String
    )

    sealed class CheckResult {
        data class Available(val release: Release) : CheckResult()
        object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    sealed class DownloadResult {
        data class Ready(val apk: File) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    /** Blocking call; run on a background dispatcher. */
    fun checkLatest(): CheckResult {
        return try {
            val conn = open(LATEST_RELEASE_URL, "application/vnd.github+json")
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return CheckResult.Failed("HTTP $code")
                }
                parseRelease(readText(conn.inputStream, MAX_JSON_BYTES), BuildConfig.BASE_VERSION_CODE)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_APP_UPDATE, "update check failed: ${e.javaClass.simpleName}")
            CheckResult.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * Parses the GitHub "latest release" payload. The release workflow sets the Android
     * versionCode to the patch number of the tag (vX.Y.Z -> Z), so the remote versionCode
     * is derived from the tag and compared with the installed versionCode.
     */
    internal fun parseRelease(json: String, installedVersionCode: Int): CheckResult {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name")
        val remoteCode = tag.removePrefix("v").substringAfterLast('.', "").toIntOrNull()
            ?: return CheckResult.Failed("unrecognised release tag")

        if (remoteCode <= installedVersionCode) return CheckResult.UpToDate

        var apkName: String? = null
        var apkUrl: String? = null
        var checksumsUrl: String? = null
        val assets = obj.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            // accept only assets served from this project's own release downloads
            if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) continue
            if (apkName == null && name.endsWith(".apk")) {
                apkName = name
                apkUrl = url
            }
            if (name == CHECKSUMS_ASSET_NAME) checksumsUrl = url
        }

        val name = apkName
        val apk = apkUrl
        val sums = checksumsUrl
        if (name == null || apk == null || sums == null) {
            return CheckResult.Failed("release has no APK or checksum file")
        }

        val notes = obj.optString("body").trim().take(MAX_NOTES_CHARS)
        return CheckResult.Available(
            Release(
                tag = tag,
                versionCode = remoteCode,
                notes = notes,
                apkAssetName = name,
                apkUrl = apk,
                checksumsUrl = sums
            )
        )
    }

    /** Blocking call; downloads, verifies and returns the APK in the app's cache. */
    fun download(
        context: Context,
        release: Release,
        shouldStop: () -> Boolean,
        onProgress: (Int) -> Unit
    ): DownloadResult {
        val dir = File(context.cacheDir, UPDATE_DIR_NAME)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, UPDATE_APK_NAME)

        return try {
            val expected = fetchExpectedSha256(release)
                ?: return DownloadResult.Failed("checksum for ${release.apkAssetName} not found")

            downloadTo(release.apkUrl, target, shouldStop, onProgress)

            if (!sha256(target).equals(expected, ignoreCase = true)) {
                target.delete()
                return DownloadResult.Failed("checksum mismatch")
            }
            if (!matchesInstalledPackage(context, target, release.versionCode)) {
                target.delete()
                return DownloadResult.Failed("package name or version does not match")
            }
            DownloadResult.Ready(target)
        } catch (e: Exception) {
            target.delete()
            Logger.w(LOG_TAG_APP_UPDATE, "update download failed: ${e.javaClass.simpleName}")
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun canRequestInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun unknownSourcesIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${BuildConfig.APPLICATION_ID}")
        )
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "DNS-Control/${BuildConfig.BASE_VERSION_CODE}")
        return conn
    }

    private fun fetchExpectedSha256(release: Release): String? {
        val conn = open(release.checksumsUrl, "text/plain")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = readText(conn.inputStream, MAX_CHECKSUM_BYTES)
            for (line in text.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts.last().removePrefix("*") == release.apkAssetName) {
                    val hash = parts.first()
                    return if (hash.matches(Regex("[0-9a-fA-F]{64}"))) hash else null
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadTo(
        url: String,
        target: File,
        shouldStop: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        val conn = open(url, "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (shouldStop()) throw IOException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > MAX_APK_BYTES) throw IOException("file too large")
                        out.write(buffer, 0, n)
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun matchesInstalledPackage(context: Context, apk: File, versionCode: Int): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        // PackageInfo.versionCode is used because longVersionCode needs API 28 and minSdk is 23
        @Suppress("DEPRECATION")
        val archiveCode = info.versionCode
        return info.packageName == BuildConfig.APPLICATION_ID && archiveCode >= versionCode
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readText(stream: InputStream, limit: Int): String {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("response too large")
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
