package com.v2ray.ang.handler

import android.os.Handler
import android.os.Looper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.DownloadState
import com.v2ray.ang.dto.DownloadStatus
import com.v2ray.ang.util.JsonUtil
import java.io.File
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArrayList

object DownloadStateManager {

    interface DownloadStateObserver {
        fun onDownloadStateChanged(state: DownloadState)
    }

    private val observers = CopyOnWriteArrayList<DownloadStateObserver>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speedSamples = LinkedList<Pair<Long, Long>>()
    private const val SPEED_WINDOW_MS = 5000L

    fun addObserver(observer: DownloadStateObserver) {
        if (!observers.contains(observer)) observers.add(observer)
    }

    fun removeObserver(observer: DownloadStateObserver) {
        observers.remove(observer)
    }

    fun getState(): DownloadState {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_DOWNLOAD_STATE) ?: return DownloadState()
        return try {
            JsonUtil.fromJson(json, DownloadState::class.java) ?: DownloadState()
        } catch (_: Exception) {
            DownloadState()
        }
    }

    private fun saveState(state: DownloadState) {
        MmkvManager.encodeSettings(AppConfig.PREF_DOWNLOAD_STATE, JsonUtil.toJson(state))
        notifyObservers(state)
    }

    private fun notifyObservers(state: DownloadState) {
        mainHandler.post {
            for (observer in observers) {
                observer.onDownloadStateChanged(state)
            }
        }
    }

    fun markChecking() {
        speedSamples.clear()
        saveState(DownloadState(status = DownloadStatus.CHECKING))
    }

    fun markDownloading(downloadUrl: String, version: String, tempFilePath: String, totalBytes: Long, resetRetryCount: Boolean = false) {
        val current = getState()
        saveState(
            current.copy(
                status = DownloadStatus.DOWNLOADING,
                downloadUrl = downloadUrl,
                version = version,
                tempFilePath = tempFilePath,
                totalBytes = totalBytes,
                retryCount = if (resetRetryCount) 0 else current.retryCount,
                pausedByNetwork = false,
                errorMessage = null,
                lastUpdatedMs = System.currentTimeMillis()
            )
        )
    }

    fun updateProgress(downloadedBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        speedSamples.add(now to downloadedBytes)
        while (speedSamples.isNotEmpty() && now - speedSamples.first.first > SPEED_WINDOW_MS) {
            speedSamples.removeFirst()
        }

        var speed = 0L
        var eta = -1L
        if (speedSamples.size >= 2) {
            val oldest = speedSamples.first
            val elapsedSec = (now - oldest.first) / 1000.0
            if (elapsedSec > 0) {
                val bytesDiff = downloadedBytes - oldest.second
                speed = (bytesDiff / elapsedSec).toLong().coerceAtLeast(0)
                if (speed > 0 && totalBytes > 0) {
                    eta = ((totalBytes - downloadedBytes) / speed)
                }
            }
        }

        val current = getState()
        saveState(
            current.copy(
                status = DownloadStatus.DOWNLOADING,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = speed,
                etaSeconds = eta,
                lastUpdatedMs = now
            )
        )
    }

    fun markPaused(byNetwork: Boolean = false) {
        val current = getState()
        speedSamples.clear()
        saveState(
            current.copy(
                status = DownloadStatus.PAUSED,
                pausedByNetwork = byNetwork,
                speedBytesPerSec = 0,
                etaSeconds = -1,
                lastUpdatedMs = System.currentTimeMillis()
            )
        )
    }

    fun markCompleted(filePath: String) {
        val current = getState()
        speedSamples.clear()
        saveState(
            current.copy(
                status = DownloadStatus.COMPLETED,
                tempFilePath = filePath,
                speedBytesPerSec = 0,
                etaSeconds = -1,
                errorMessage = null,
                lastUpdatedMs = System.currentTimeMillis()
            )
        )
    }

    fun markFailed(error: String) {
        val current = getState()
        speedSamples.clear()
        saveState(
            current.copy(
                status = DownloadStatus.FAILED,
                errorMessage = error,
                speedBytesPerSec = 0,
                etaSeconds = -1,
                lastUpdatedMs = System.currentTimeMillis()
            )
        )
    }

    fun incrementRetry(): Int {
        val current = getState()
        val newCount = current.retryCount + 1
        saveState(current.copy(retryCount = newCount, lastUpdatedMs = System.currentTimeMillis()))
        return newCount
    }

    fun markIdle() {
        speedSamples.clear()
        val current = getState()
        // Clean up temp file
        current.tempFilePath?.let {
            try {
                val f = File(it)
                if (f.exists()) f.delete()
            } catch (_: Exception) {
            }
        }
        saveState(DownloadState(status = DownloadStatus.IDLE))
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec <= 0 -> ""
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
            else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
        }
    }

    fun formatEta(seconds: Long): String {
        return when {
            seconds < 0 -> ""
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun getProgressPercent(): Int {
        val state = getState()
        return if (state.totalBytes > 0) {
            ((state.downloadedBytes * 100) / state.totalBytes).toInt().coerceIn(0, 100)
        } else 0
    }
}
