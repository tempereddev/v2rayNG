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
import com.v2ray.ang.dto.DownloadStatus
import com.v2ray.ang.handler.DownloadCancelledException
import com.v2ray.ang.handler.DownloadPausedException
import com.v2ray.ang.handler.DownloadStateManager
import com.v2ray.ang.handler.NetworkMonitor
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.receiver.DownloadActionReceiver
import com.v2ray.ang.ui.CheckUpdateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DownloadApkService : Service() {

    companion object {
        private const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_RESUME = "resume"
        private const val NOTIFICATION_ID = 9528

        fun start(context: Context, downloadUrl: String, version: String) {
            val intent = Intent(context, DownloadApkService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_RESUME, false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startResume(context: Context, downloadUrl: String, version: String) {
            val intent = Intent(context, DownloadApkService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_RESUME, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause() {
            UpdateCheckerManager.isPaused = true
        }

        fun cancel(context: Context) {
            UpdateCheckerManager.isCancelled = true
            context.stopService(Intent(context, DownloadApkService::class.java))
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
        NetworkMonitor.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadUrl = intent?.getStringExtra(EXTRA_DOWNLOAD_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "unknown"
        val isResume = intent?.getBooleanExtra(EXTRA_RESUME, false) == true

        if (downloadUrl.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        UpdateCheckerManager.resetFlags()
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, "", ""))
        startDownload(downloadUrl, version, isResume)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        NetworkMonitor.stop()
        super.onDestroy()
    }

    private fun startDownload(downloadUrl: String, version: String, isResume: Boolean) {
        serviceScope.launch {
            try {
                val tempFile = File(cacheDir, "update_${version}.apk")
                val startByte: Long

                if (isResume && tempFile.exists()) {
                    startByte = tempFile.length()
                    Log.i(AppConfig.TAG, "Resuming download from byte $startByte")
                } else {
                    startByte = 0
                    if (tempFile.exists()) tempFile.delete()
                }

                DownloadStateManager.markDownloading(downloadUrl, version, tempFile.absolutePath, -1)

                val apkFile = UpdateCheckerManager.downloadApk(
                    context = this@DownloadApkService,
                    downloadUrl = downloadUrl,
                    startByte = startByte,
                    tempFile = tempFile
                ) { percent, downloaded, total ->
                    DownloadStateManager.updateProgress(downloaded, total)
                    val state = DownloadStateManager.getState()
                    val speedText = DownloadStateManager.formatSpeed(state.speedBytesPerSec)
                    val etaText = DownloadStateManager.formatEta(state.etaSeconds)
                    notificationManager?.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(percent, speedText, etaText)
                    )
                }

                if (apkFile != null && apkFile.exists()) {
                    DownloadStateManager.markCompleted(apkFile.absolutePath)
                    notificationManager?.notify(NOTIFICATION_ID, buildCompleteNotification(version, apkFile))
                    installApk(apkFile)
                } else {
                    handleFailure(getString(R.string.update_download_failed), downloadUrl, version)
                }
            } catch (e: DownloadPausedException) {
                Log.i(AppConfig.TAG, "Download paused by user/network")
                notificationManager?.notify(NOTIFICATION_ID, buildPausedNotification(version))
                stopSelf()
            } catch (e: DownloadCancelledException) {
                Log.i(AppConfig.TAG, "Download cancelled")
                DownloadStateManager.markIdle()
                notificationManager?.cancel(NOTIFICATION_ID)
                stopSelf()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Download error: ${e.message}", e)
                handleFailure(e.message ?: getString(R.string.update_download_failed), downloadUrl, version)
            }
        }
    }

    private suspend fun handleFailure(error: String, downloadUrl: String, version: String) {
        val state = DownloadStateManager.getState()
        if (state.retryCount < AppConfig.DOWNLOAD_MAX_RETRIES) {
            val retryNum = DownloadStateManager.incrementRetry()
            val delayMs = AppConfig.DOWNLOAD_RETRY_BASE_DELAY_MS * (1 shl (retryNum - 1))
            Log.i(AppConfig.TAG, "Retrying download (attempt $retryNum) in ${delayMs}ms")
            notificationManager?.notify(
                NOTIFICATION_ID,
                buildRetryNotification(retryNum)
            )
            delay(delayMs)
            startDownload(downloadUrl, version, true)
        } else {
            DownloadStateManager.markFailed(error)
            notificationManager?.notify(NOTIFICATION_ID, buildFailureNotification())
            stopSelf()
        }
    }

    private fun installApk(apkFile: File) {
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
        stopSelf()
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

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildProgressNotification(percent: Int, speed: String, eta: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, CheckUpdateActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val infoText = buildString {
            append(getString(R.string.update_download_progress, percent))
            if (speed.isNotEmpty()) append("  $speed")
            if (eta.isNotEmpty()) append("  ~$eta")
        }

        val builder = NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_downloading))
            .setContentText(infoText)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.update_action_pause),
                createActionPendingIntent(AppConfig.ACTION_PAUSE_DOWNLOAD, 1)
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.update_action_cancel),
                createActionPendingIntent(AppConfig.ACTION_CANCEL_DOWNLOAD, 2)
            )

        return builder.build()
    }

    private fun buildPausedNotification(version: String): android.app.Notification {
        val state = DownloadStateManager.getState()
        val percent = if (state.totalBytes > 0) ((state.downloadedBytes * 100) / state.totalBytes).toInt() else 0

        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_paused))
            .setContentText(getString(R.string.update_download_progress, percent))
            .setProgress(100, percent, false)
            .setOngoing(false)
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.update_action_resume),
                createActionPendingIntent(AppConfig.ACTION_RESUME_DOWNLOAD, 3)
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.update_action_cancel),
                createActionPendingIntent(AppConfig.ACTION_CANCEL_DOWNLOAD, 2)
            )
            .build()
    }

    private fun buildCompleteNotification(version: String, apkFile: File): android.app.Notification {
        val installIntent = try {
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.cache", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_download_complete))
            .setContentText("v$version")
            .setOngoing(false)
            .setAutoCancel(true)

        if (installIntent != null) {
            builder.setContentIntent(installIntent)
        }

        return builder.build()
    }

    private fun buildRetryNotification(retryNum: Int): android.app.Notification {
        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_downloading))
            .setContentText(getString(R.string.update_retry_attempt, retryNum, AppConfig.DOWNLOAD_MAX_RETRIES))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.update_action_cancel),
                createActionPendingIntent(AppConfig.ACTION_CANCEL_DOWNLOAD, 2)
            )
            .build()
    }

    private fun buildFailureNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.update_download_failed))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, CheckUpdateActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                android.R.drawable.ic_popup_sync,
                getString(R.string.update_action_retry),
                createActionPendingIntent(AppConfig.ACTION_RETRY_DOWNLOAD, 4)
            )
            .build()
    }
}
