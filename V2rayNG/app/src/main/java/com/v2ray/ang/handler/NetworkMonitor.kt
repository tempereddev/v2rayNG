package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.DownloadStatus

object NetworkMonitor {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    fun start(context: Context) {
        if (isRegistered) return
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.i(AppConfig.TAG, "Network lost during download")
                val state = DownloadStateManager.getState()
                if (state.status == DownloadStatus.DOWNLOADING) {
                    UpdateCheckerManager.isPaused = true
                    DownloadStateManager.markPaused(byNetwork = true)
                }
            }

            override fun onAvailable(network: Network) {
                Log.i(AppConfig.TAG, "Network restored")
                val state = DownloadStateManager.getState()
                if (state.status == DownloadStatus.PAUSED && state.pausedByNetwork) {
                    val url = state.downloadUrl ?: return
                    val version = state.version ?: return
                    com.v2ray.ang.service.DownloadApkService.startResume(context, url, version)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            isRegistered = true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
        connectivityManager = null
        isRegistered = false
    }
}
