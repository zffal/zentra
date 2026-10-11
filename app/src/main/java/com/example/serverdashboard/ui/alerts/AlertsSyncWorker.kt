package com.example.serverdashboard.ui.alerts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker: polls active server for alerts every 15 minutes
 * when network is connected, without requiring a permanent ongoing notification.
 */
class AlertsSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "zentra_alerts_sync_worker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AlertsSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val configStore = NotificationConfigStore.getInstance(applicationContext)
        if (!configStore.notificationsEnabled) {
            return Result.success()
        }

        val serverStore = ServerStore.getInstance(applicationContext)
        val activeServer = serverStore.getActiveServer() ?: return Result.success()
        val tokenManager = SecureTokenManager.getInstance(applicationContext)
        val token = tokenManager.getToken(activeServer.url)

        if (token.isNullOrEmpty()) {
            return Result.success()
        }

        val alertManager = AlertNotificationManager.getInstance(applicationContext)

        // 1. Fetch alerts from server
        val result = ApiClient.instance.getAlerts(activeServer.url, token, "all")
        if (result.isSuccess) {
            val alerts = result.getOrNull().orEmpty()
            alertManager.processServerAlerts(activeServer.url, alerts)
            return Result.success()
        } else {
            val ex = result.exceptionOrNull()
            val msg = ex?.message.orEmpty()
            if (msg.contains("401") || msg.contains("403")) {
                // Post one quiet "Sign in again" notification and stop the worker
                alertManager.postAuthRequiredNotification(activeServer.url)
                cancel(applicationContext)
                return Result.failure()
            }

            // Check if phone itself has internet connection before diagnosing server outage
            if (isDeviceOnline()) {
                alertManager.checkServerUnreachable(activeServer.url)
            }
            return Result.retry()
        }
    }

    private fun isDeviceOnline(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
