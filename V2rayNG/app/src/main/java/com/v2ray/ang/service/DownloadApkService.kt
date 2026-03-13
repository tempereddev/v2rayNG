package com.v2ray.ang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.CheckUpdateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadApkService : Service() {
    interface DownloadListener {
        fun onProgress(percent: Int, downloaded: Long, total: Long)
        fun onComplete(apkPath: String)
        fun onFailed(error: String)
    }

    companion object {
        private const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val EXTRA_VERSION = "version"
        private const val NOTIFICATION_ID = 9528

        var listener: DownloadListener? = null

        fun start(context: Context, downloadUrl: String, version: String) {
            val intent = Intent(context, DownloadApkService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_VERSION, version)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadUrl = intent?.getStringExtra(EXTRA_DOWNLOAD_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "unknown"
        if (downloadUrl.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildProgressNotification(0))
        startDownload(downloadUrl, version)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(downloadUrl: String, version: String) {
        serviceScope.launch {
            try {
                val apkFile = UpdateCheckerManager.downloadApk(
                    context = this@DownloadApkService,
                    downloadUrl = downloadUrl
                ) { percent, downloaded, total ->
                    notificationManager?.notify(NOTIFICATION_ID, buildProgressNotification(percent))
                    mainHandler.post {
                        listener?.onProgress(percent, downloaded, total)
                    }
                }

                if (apkFile != null && apkFile.exists()) {
                    notificationManager?.notify(NOTIFICATION_ID, buildCompleteNotification(version))
                    mainHandler.post { listener?.onComplete(apkFile.absolutePath) }
                    installApk(apkFile)
                } else {
                    val errorMsg = getString(R.string.update_download_failed)
                    notificationManager?.notify(NOTIFICATION_ID, buildFailureNotification())
                    mainHandler.post { listener?.onFailed(errorMsg) }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Download service error: ${e.message}", e)
                val errorMsg = e.message ?: getString(R.string.update_download_failed)
                notificationManager?.notify(NOTIFICATION_ID, buildFailureNotification())
                mainHandler.post { listener?.onFailed(errorMsg) }
            } finally {
                stopSelf()
            }
        }
    }

    private fun installApk(apkFile: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.cache",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to launch installer: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.APP_UPDATE_CHANNEL,
                AppConfig.APP_UPDATE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(percent: Int): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CheckUpdateActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_downloading))
            .setContentText(getString(R.string.update_download_progress, percent))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun buildCompleteNotification(version: String): android.app.Notification {
        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_download_complete))
            .setContentText(version)
            .setOngoing(false)
            .build()
    }

    private fun buildFailureNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_download_failed))
            .setOngoing(false)
            .build()
    }
}
