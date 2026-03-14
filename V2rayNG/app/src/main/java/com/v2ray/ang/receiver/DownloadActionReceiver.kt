package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.DownloadStateManager
import com.v2ray.ang.service.DownloadApkService
import com.v2ray.ang.ui.CheckUpdateActivity

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
                val intent = Intent(context, CheckUpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(intent)
            }
        }
    }
}
