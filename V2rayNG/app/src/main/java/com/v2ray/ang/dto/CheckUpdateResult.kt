package com.v2ray.ang.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val releasePageUrl: String? = null,
    val publishedAt: String? = null,
    val assetCount: Int = 0,
    val error: String? = null,
    val isPreRelease: Boolean = false
)
