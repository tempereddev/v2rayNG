package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.DownloadState
import com.v2ray.ang.dto.DownloadStatus
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.DownloadStateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.service.DownloadApkService
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch
import java.io.File

class CheckUpdateActivity : BaseActivity(), DownloadStateManager.DownloadStateObserver {
    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var currentResult: CheckUpdateResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            val state = DownloadStateManager.getState()
            if (state.status != DownloadStatus.DOWNLOADING) {
                checkForUpdates(binding.checkPreRelease.isChecked)
            }
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
        binding.btnDownload.setOnClickListener { onDownloadButtonClicked() }
        binding.btnLater.setOnClickListener { finish() }
        binding.btnPause.setOnClickListener { DownloadApkService.pause() }
        binding.btnCancel.setOnClickListener {
            DownloadApkService.cancel(this)
            DownloadStateManager.markIdle()
            resetDownloadUI()
        }

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        // Check for existing download state first
        val state = DownloadStateManager.getState()
        if (state.status != DownloadStatus.IDLE) {
            // Restore cached update result
            currentResult = UpdateCheckerManager.getCachedUpdateResult()
            if (currentResult != null) {
                showUpdateCard(currentResult!!)
            }
            updateUIFromState(state)
        } else {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadStateManager.addObserver(this)
        val state = DownloadStateManager.getState()
        if (state.status != DownloadStatus.IDLE) {
            updateUIFromState(state)
        }
    }

    override fun onPause() {
        DownloadStateManager.removeObserver(this)
        super.onPause()
    }

    override fun onDownloadStateChanged(state: DownloadState) {
        updateUIFromState(state)
    }

    private fun updateUIFromState(state: DownloadState) {
        when (state.status) {
            DownloadStatus.IDLE -> resetDownloadUI()

            DownloadStatus.CHECKING -> {
                // Do nothing, regular check UI handles this
            }

            DownloadStatus.DOWNLOADING -> {
                val percent = if (state.totalBytes > 0) ((state.downloadedBytes * 100) / state.totalBytes).toInt() else 0
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.progress = percent
                binding.tvDownloadStatus.visibility = View.VISIBLE

                val speed = DownloadStateManager.formatSpeed(state.speedBytesPerSec)
                val eta = DownloadStateManager.formatEta(state.etaSeconds)
                val statusText = buildString {
                    append(getString(R.string.update_download_progress, percent))
                    if (speed.isNotEmpty()) append("  $speed")
                    if (eta.isNotEmpty()) append("  ~$eta")
                }
                binding.tvDownloadStatus.text = statusText

                binding.btnDownload.isEnabled = false
                binding.btnDownload.text = getString(R.string.update_downloading)
                binding.btnPause.visibility = View.VISIBLE
                binding.btnCancel.visibility = View.VISIBLE
                binding.btnLater.visibility = View.GONE
            }

            DownloadStatus.PAUSED -> {
                val percent = if (state.totalBytes > 0) ((state.downloadedBytes * 100) / state.totalBytes).toInt() else 0
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.progress = percent
                binding.tvDownloadStatus.visibility = View.VISIBLE
                binding.tvDownloadStatus.text = getString(R.string.update_paused)

                binding.btnDownload.isEnabled = true
                binding.btnDownload.text = getString(R.string.update_action_resume)
                binding.btnPause.visibility = View.GONE
                binding.btnCancel.visibility = View.VISIBLE
                binding.btnLater.visibility = View.GONE
            }

            DownloadStatus.COMPLETED -> {
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.progress = 100
                binding.tvDownloadStatus.visibility = View.VISIBLE
                binding.tvDownloadStatus.text = getString(R.string.update_download_complete)

                binding.btnDownload.isEnabled = true
                binding.btnDownload.text = getString(R.string.update_action_install)
                binding.btnPause.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.btnLater.visibility = View.VISIBLE
            }

            DownloadStatus.FAILED -> {
                binding.progressDownload.visibility = View.GONE
                binding.tvDownloadStatus.visibility = View.VISIBLE
                binding.tvDownloadStatus.text = state.errorMessage ?: getString(R.string.update_download_failed)

                binding.btnDownload.isEnabled = true
                binding.btnDownload.text = getString(R.string.update_action_retry)
                binding.btnPause.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.btnLater.visibility = View.VISIBLE
            }
        }
    }

    private fun resetDownloadUI() {
        binding.progressDownload.visibility = View.GONE
        binding.tvDownloadStatus.visibility = View.GONE
        binding.btnPause.visibility = View.GONE
        binding.btnCancel.visibility = View.GONE
        binding.btnLater.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = getString(R.string.update_download_and_install)
    }

    private fun onDownloadButtonClicked() {
        val state = DownloadStateManager.getState()
        when (state.status) {
            DownloadStatus.PAUSED -> {
                val url = state.downloadUrl ?: return
                val version = state.version ?: return
                DownloadApkService.startResume(this, url, version)
            }

            DownloadStatus.COMPLETED -> {
                val filePath = state.tempFilePath ?: return
                val file = File(filePath)
                if (file.exists()) {
                    UpdateCheckerManager.installApk(this, file)
                } else {
                    toastError(R.string.update_download_failed)
                    DownloadStateManager.markIdle()
                    resetDownloadUI()
                }
            }

            DownloadStatus.FAILED -> {
                val url = state.downloadUrl ?: currentResult?.downloadUrl ?: return
                val version = state.version ?: currentResult?.latestVersion ?: return
                DownloadApkService.start(this, url, version)
            }

            else -> startDownloadAndInstall()
        }
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        showLoading()
        binding.cardUpdateInfo.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                UpdateCheckerManager.markUpdateChecked()
                if (result.hasUpdate) {
                    currentResult = result
                    UpdateCheckerManager.cacheUpdateResult(result)
                    showUpdateCard(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            } finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateCard(result: CheckUpdateResult) {
        binding.cardUpdateInfo.visibility = View.VISIBLE
        binding.tvUpdateVersion.text = getString(R.string.update_new_version_found, result.latestVersion)
        binding.tvReleaseMeta.text = getString(
            R.string.update_release_meta,
            result.publishedAt ?: getString(R.string.update_release_meta_unknown_date),
            result.assetCount,
            getString(if (result.isPreRelease) R.string.update_channel_prerelease else R.string.update_channel_stable)
        )
        binding.tvReleaseMeta.visibility = View.VISIBLE

        val notes = buildReleaseNotes(result)
        if (!notes.isNullOrEmpty()) {
            binding.tvReleaseNotesLabel.visibility = View.VISIBLE
            binding.tvReleaseNotes.visibility = View.VISIBLE
            binding.tvReleaseNotes.text = notes
        } else {
            binding.tvReleaseNotesLabel.visibility = View.GONE
            binding.tvReleaseNotes.visibility = View.GONE
        }

        binding.layoutButtons.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = getString(
            if (result.downloadUrl != null) {
                R.string.update_download_and_install
            } else {
                R.string.update_open_release_page
            }
        )
    }

    private fun startDownloadAndInstall() {
        val result = currentResult ?: return
        val downloadUrl = result.downloadUrl

        if (downloadUrl == null) {
            val releasePageUrl = result.releasePageUrl ?: return
            Utils.openUri(this, releasePageUrl)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast(R.string.update_install_permission_required)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        DownloadApkService.start(this, downloadUrl, result.latestVersion ?: "unknown")
    }

    private fun buildReleaseNotes(result: CheckUpdateResult): String {
        val baseNotes = result.releaseNotes?.trim()?.let(::stripMarkdown).orEmpty()
        val readableNotes = baseNotes.ifBlank { getString(R.string.update_no_release_notes) }
        return if (result.downloadUrl != null) {
            readableNotes
        } else {
            listOf(
                getString(R.string.update_asset_pending),
                readableNotes
            ).filter { it.isNotBlank() }.joinToString("\n\n")
        }
    }

    private fun stripMarkdown(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[-*]\\s+"), "- ")
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
                .let { if (it.trim() == "---") "" else it }
        }.trim()
    }
}
