package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.DownloadStateManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.service.DownloadApkService
import java.io.File

class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(AppConfig.TAG, "Download action received: $action")

        when (action) {
            AppConfig.ACTION_PAUSE_DOWNLOAD -> {
                DownloadApkService.pause()
            }

            AppConfig.ACTION_RESUME_DOWNLOAD -> {
                val state = DownloadStateManager.getState()
                val url = state.downloadUrl ?: return
                val version = state.version ?: return
                DownloadApkService.startResume(context, url, version)
            }

            AppConfig.ACTION_CANCEL_DOWNLOAD -> {
                DownloadApkService.cancel(context)
                DownloadStateManager.markIdle()
            }

            AppConfig.ACTION_RETRY_DOWNLOAD -> {
                val state = DownloadStateManager.getState()
                val url = state.downloadUrl ?: return
                val version = state.version ?: return
                DownloadApkService.start(context, url, version)
            }

            AppConfig.ACTION_INSTALL_UPDATE -> {
                val state = DownloadStateManager.getState()
                val filePath = state.tempFilePath ?: return
                val file = File(filePath)
                if (file.exists()) {
                    UpdateCheckerManager.installApk(context, file)
                }
            }
        }
    }
}
