package com.v2ray.ang.dto

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val downloadUrl: String? = null,
    val version: String? = null,
    val tempFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val pausedByNetwork: Boolean = false,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    IDLE,
    CHECKING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}
