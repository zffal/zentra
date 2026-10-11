package com.example.serverdashboard.ui.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {

    const val CHANNEL_CRITICAL = "zentra_critical"
    const val CHANNEL_WARNING = "zentra_warning"
    const val CHANNEL_INFO = "zentra_info"
    const val CHANNEL_LIVE = "zentra_live_monitoring"

    private const val LEGACY_CHANNEL = "zentra_server_alerts"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 1. Critical alerts: IMPORTANCE_HIGH (sound + vibration + heads-up)
        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            "Critical alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High temperature, failing disks, and prolonged outages"
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }

        // 2. Warnings: IMPORTANCE_DEFAULT with NO sound and NO vibration (silent in shade/status bar)
        val warning = NotificationChannel(
            CHANNEL_WARNING,
            "Warnings",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "High CPU load, memory thresholds, and container warnings"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
        }

        // 3. Status: IMPORTANCE_LOW, silent for recoveries
        val info = NotificationChannel(
            CHANNEL_INFO,
            "Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Recovery notices and normal status updates"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

        // 4. Live monitoring: IMPORTANCE_LOW for ongoing foreground service
        val live = NotificationChannel(
            CHANNEL_LIVE,
            "Live monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing notification while near-real-time WebSocket monitoring is active"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(critical, warning, info, live))

        // Clean up legacy channel to avoid cluttering system settings
        try {
            manager.deleteNotificationChannel(LEGACY_CHANNEL)
        } catch (_: Exception) {}
    }
}
