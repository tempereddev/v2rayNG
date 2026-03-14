package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.CheckUpdateActivity

class AppUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        Log.i(AppConfig.TAG, "AppUpdateWorker: checking for app update")

        return try {
            val result = UpdateCheckerManager.checkForUpdate(false)
            if (result.hasUpdate) {
                UpdateCheckerManager.cacheUpdateResult(result)
                sendUpdateNotification(result.latestVersion ?: "")
                Log.i(AppConfig.TAG, "AppUpdateWorker: update found v${result.latestVersion}")
            } else {
                UpdateCheckerManager.clearCachedUpdateResult()
                Log.i(AppConfig.TAG, "AppUpdateWorker: already up to date")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "AppUpdateWorker: check failed: ${e.message}")
            Result.retry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendUpdateNotification(version: String) {
        val nm = NotificationManagerCompat.from(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.APP_UPDATE_CHANNEL,
                AppConfig.APP_UPDATE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, CheckUpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, AppConfig.APP_UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(applicationContext.getString(R.string.update_new_version_found, version))
            .setContentText(applicationContext.getString(R.string.update_tap_to_download))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(AppConfig.NOTIFICATION_ID_APP_UPDATE, notification)
    }
}
