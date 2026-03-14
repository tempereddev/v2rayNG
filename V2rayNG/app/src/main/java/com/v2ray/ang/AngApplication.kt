package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.AppUpdateWorker
import com.v2ray.ang.handler.SettingsManager
import java.util.concurrent.TimeUnit

class AngApplication : MultiDexApplication(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        scheduleAppUpdateCheck()

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)
        SettingsManager.setNightMode()

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 300)
            .apply()
    }

    private fun scheduleAppUpdateCheck() {
        val request = PeriodicWorkRequest.Builder(
            AppUpdateWorker::class.java,
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AppConfig.APP_UPDATE_WORKER_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
