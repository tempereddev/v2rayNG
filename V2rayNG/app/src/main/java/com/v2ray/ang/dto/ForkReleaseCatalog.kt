package com.v2ray.ang.dto

data class ForkReleaseCatalog(
    val currentVersion: String,
    val releases: List<ForkReleaseEntry>
)

data class ForkReleaseEntry(
    val version: String,
    val title: String,
    val summary: String,
    val highlights: List<String> = emptyList(),
    val userImpact: List<String> = emptyList(),
    val releaseUrl: String? = null
)
