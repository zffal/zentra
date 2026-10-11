package com.example.serverdashboard.ui.alerts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.api.ZentraWebSocketClient
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.ui.util.ServerNameResolver

/**
 * Optional foreground service for near-real-time WebSocket alert monitoring while app is backgrounded.
 * Shows low-importance ongoing notification and explains battery trade-off.
 */
class LiveMonitoringService : Service() {

    companion object {
        const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            val intent = Intent(context, LiveMonitoringService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveMonitoringService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    private var wsClient: ZentraWebSocketClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configStore = NotificationConfigStore.getInstance(this)
        if (!configStore.liveMonitoringEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        val activeServer = ServerStore.getInstance(this).getActiveServer()
        if (activeServer == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val token = SecureTokenManager.getInstance(this).getToken(activeServer.url)
        if (token.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val serverName = ServerNameResolver.resolveServerName(this, activeServer.url, activeServer.name)
        val notification = buildOngoingNotification(serverName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Establish background WebSocket stream
        wsClient?.disconnect()
        wsClient = ZentraWebSocketClient(
            baseUrl = activeServer.url,
            token = token,
            onStreamStateChanged = { state, _ ->
                if (state == StreamState.AUTH_FAILED) {
                    AlertNotificationManager.getInstance(this).postAuthRequiredNotification(activeServer.url)
                    configStore.liveMonitoringEnabled = false
                    stopSelf()
                }
            },
            onMetricsReceived = { overview ->
                AlertNotificationManager.getInstance(this).evaluateMetrics(overview, activeServer.url)
            },
            onAuthFailed = {
                AlertNotificationManager.getInstance(this).postAuthRequiredNotification(activeServer.url)
                configStore.liveMonitoringEnabled = false
                stopSelf()
            }
        )
        wsClient?.connect()

        return START_STICKY
    }

    private fun buildOngoingNotification(serverName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TAB, 4)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Live Monitoring Active")
            .setContentText("Monitoring $serverName in near-real-time")
            .setSubText("Low-power stream • Tap to view dashboard")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient?.disconnect()
        wsClient = null
    }
}
