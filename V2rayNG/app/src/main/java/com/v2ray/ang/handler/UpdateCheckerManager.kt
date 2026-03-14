package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val httpPort = SettingsManager.getHttpPort()
        val response = HttpUtil.getUrlContent(url, 4000, httpPort)
            ?: HttpUtil.getUrlContent(url, 8000)
            ?: return@withContext CheckUpdateResult(hasUpdate = false)

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJson(response, Array<GitHubRelease>::class.java)?.firstOrNull()
        } else {
            JsonUtil.fromJson(response, GitHubRelease::class.java)
        } ?: return@withContext CheckUpdateResult(hasUpdate = false)

        val tagName = latestRelease.tagName ?: return@withContext CheckUpdateResult(hasUpdate = false)
        val latestVersion = tagName.removePrefix("v")
        Log.i(AppConfig.TAG, "Found version: $latestVersion (current: ${BuildConfig.VERSION_NAME})")

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0]),
                releasePageUrl = latestRelease.htmlUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        val httpPort = SettingsManager.getHttpPort()
        val ports = if (httpPort > 0) listOf(httpPort, 0) else listOf(0)
        for (port in ports) {
            val result = downloadApkWithPort(context, downloadUrl, port, onProgress)
            if (result != null) return@withContext result
        }
        null
    }

    private fun downloadApkWithPort(
        context: Context,
        downloadUrl: String,
        httpPort: Int,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): File? {
        var connection: HttpURLConnection? = null
        return try {
            connection = HttpUtil.createProxyConnection(downloadUrl, httpPort, 15000, 30000, true) ?: return null
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (responseCode in 300..399) {
                    val redirectUrl = HttpUtil.resolveLocation(connection)
                    connection.disconnect()
                    connection = null
                    if (redirectUrl != null) {
                        return downloadApkWithPort(context, redirectUrl, httpPort, onProgress)
                    }
                }
                throw IllegalStateException("HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val apkFile = File(context.cacheDir, "update.apk")
            FileOutputStream(apkFile).use { outputStream ->
                connection.inputStream.use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloaded = 0L
                    var lastPercent = -1
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent, downloaded, totalBytes)
                            }
                        }
                    }
                    if (totalBytes > 0) {
                        onProgress(100, downloaded, totalBytes)
                    }
                }
            }
            apkFile
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to download APK (port=$httpPort): ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.cache",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun shouldAutoCheck(): Boolean {
        val lastCheck = MmkvManager.decodeSettingsString(AppConfig.PREF_LAST_UPDATE_CHECK)?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - lastCheck > AppConfig.UPDATE_CHECK_INTERVAL_MS
    }

    fun markUpdateChecked() {
        MmkvManager.encodeSettings(AppConfig.PREF_LAST_UPDATE_CHECK, System.currentTimeMillis().toString())
    }

    fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].toIntOrNull() ?: 0 else 0
            val num2 = if (i < v2.size) v2[i].toIntOrNull() ?: 0 else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String? {
        val normalizedAbi = abi.lowercase()
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", true) }

        val abiMatch = apkAssets.firstOrNull { it.name.contains(normalizedAbi, true) }
        if (abiMatch != null) return abiMatch.browserDownloadUrl

        val universalMatch = apkAssets.firstOrNull { it.name.contains("universal", true) }
        if (universalMatch != null) return universalMatch.browserDownloadUrl

        return apkAssets.firstOrNull()?.browserDownloadUrl
    }
}
