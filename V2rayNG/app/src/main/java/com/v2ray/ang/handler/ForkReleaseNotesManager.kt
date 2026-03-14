package com.v2ray.ang.handler

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ForkReleaseCatalog
import com.v2ray.ang.dto.ForkReleaseEntry

object ForkReleaseNotesManager {
    private const val ASSET_FILE_NAME = "fork_release_notes.json"

    private val gson = Gson()
    private var cachedCatalog: ForkReleaseCatalog? = null

    fun getCurrentRelease(context: Context): ForkReleaseEntry? {
        val catalog = loadCatalog(context) ?: return null
        return catalog.releases.firstOrNull { it.version == BuildConfig.VERSION_NAME }
            ?: catalog.releases.firstOrNull { it.version == catalog.currentVersion }
    }

    fun formatForDisplay(context: Context, release: ForkReleaseEntry): String {
        val sections = mutableListOf<String>()

        release.summary.trim().takeIf { it.isNotBlank() }?.let(sections::add)

        buildSection(
            context.getString(R.string.release_section_highlights),
            release.highlights
        )?.let(sections::add)

        buildSection(
            context.getString(R.string.release_section_user_impact),
            release.userImpact
        )?.let(sections::add)

        return sections.joinToString("\n\n").trim()
    }

    fun getReleaseUrl(release: ForkReleaseEntry): String {
        val explicitUrl = release.releaseUrl?.trim().orEmpty()
        if (explicitUrl.isNotEmpty()) {
            return explicitUrl
        }
        return "${AppConfig.APP_RELEASES_URL}/tag/${release.version}"
    }

    private fun loadCatalog(context: Context): ForkReleaseCatalog? {
        cachedCatalog?.let { return it }

        return runCatching {
            context.assets.open(ASSET_FILE_NAME).bufferedReader().use { reader ->
                gson.fromJson(reader, ForkReleaseCatalog::class.java)
            }
        }.getOrNull()?.also { cachedCatalog = it }
    }

    private fun buildSection(title: String, items: List<String>): String? {
        val sanitizedItems = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (sanitizedItems.isEmpty()) {
            return null
        }

        return buildString {
            append(title)
            append('\n')
            sanitizedItems.forEachIndexed { index, item ->
                if (index > 0) {
                    append('\n')
                }
                append("- ")
                append(item)
            }
        }
    }
}
