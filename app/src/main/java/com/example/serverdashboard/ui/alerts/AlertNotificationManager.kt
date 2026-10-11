package com.example.serverdashboard.ui.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.AlertHistoryItem
import com.example.serverdashboard.data.model.ProcessInfo
import com.example.serverdashboard.data.model.SystemOverview
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.example.serverdashboard.data.storage.OverviewConfigStore.Companion.sanitizeServerKey
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.ServerNameResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class AlertState {
    NORMAL,
    TRIGGERED
}

class AlertNotificationManager(private val context: Context? = null) {

    private val notificationManager: NotificationManager? by lazy {
        try {
            context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (_: Throwable) {
            null
        }
    }

    private val configStore: NotificationConfigStore? by lazy {
        context?.let { NotificationConfigStore.getInstance(it) }
    }

    // In-memory config fallback for unit testing
    data class ConfigValues(
        var notificationsEnabled: Boolean = true,
        var notifyTemperature: Boolean = true,
        var notifyLoad: Boolean = true,
        var notifyDisk: Boolean = true,
        var notifyConnection: Boolean = true,
        var liveMonitoringEnabled: Boolean = false,
        var quietHoursEnabled: Boolean = false,
        var quietHoursStartMinutes: Int = 22 * 60,
        var quietHoursEndMinutes: Int = 7 * 60,
        var tempWarning: Float = 75f,
        var tempCritical: Float = 85f,
        var cpuLoadWarning: Float = 85f,
        var cpuLoadCritical: Float = 95f,
        var memoryWarning: Float = 90f,
        var diskWarning: Float = 85f,
        var diskCritical: Float = 95f
    )

    var inMemoryConfig = ConfigValues()

    // Test tracking maps when running unit tests without Android context
    private val testCooldownMap = ConcurrentHashMap<String, Long>()
    private val testNotifiedIds = ConcurrentHashMap<String, Long>()
    private val testSnoozeMap = ConcurrentHashMap<String, Long>()

    val cpuTempState: AlertState
        get() = if (activeAlertsMap.containsKey("cpu_temperature")) AlertState.TRIGGERED else AlertState.NORMAL

    val cpuUsageState: AlertState
        get() = if (activeAlertsMap.containsKey("cpu_usage")) AlertState.TRIGGERED else AlertState.NORMAL

    // App Foreground / Background Tracking
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    // Sustained load tracking
    private var cpuLoadHighStartTime: Long = 0L
    private var cpuLoadCritStartTime: Long = 0L

    // In-app active local alerts observable by UI
    private val _localActiveAlerts = MutableLiveData<List<AlertHistoryItem>>(emptyList())
    val localActiveAlerts: LiveData<List<AlertHistoryItem>> = _localActiveAlerts
    private val activeAlertsMap = ConcurrentHashMap<String, AlertHistoryItem>()

    // Track active notification IDs per server for grouping
    private val activeNotifIdsPerServer = ConcurrentHashMap<String, MutableSet<Int>>()

    // Test hook for unit testing
    var notificationDispatcher: ((id: Int, title: String, body: String) -> Unit)? = null

    companion object {
        const val NOTIFICATION_ID_GROUP_SUMMARY = 1000
        const val NOTIFICATION_ID_AUTH = 1010
        const val NOTIFICATION_ID_UNREACHABLE = 1020
        const val NOTIFICATION_ID_DATA_CAP = 1030
        const val NOTIFICATION_ID_TEST = 1099
        const val NOTIFICATION_ID_CPU_TEMP = 1101
        const val NOTIFICATION_ID_CPU_LOAD = 1102
        const val NOTIFICATION_ID_CPU_USAGE = 1102
        const val NOTIFICATION_ID_MEMORY = 1103
        const val NOTIFICATION_ID_DISK = 1104

        const val SUSTAINED_LOAD_WARN_MS = 120_000L // 2 minutes
        const val SUSTAINED_LOAD_CRIT_MS = 300_000L // 5 minutes
        const val UNREACHABLE_TIMEOUT_MS = 600_000L // 10 minutes

        @Volatile
        private var INSTANCE: AlertNotificationManager? = null

        fun getInstance(context: Context): AlertNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AlertNotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        context?.let { NotificationChannels.createAll(it) }
    }

    fun setAppInForeground(foreground: Boolean) {
        isAppInForeground = foreground
    }

    fun createNotificationChannel() {
        context?.let { NotificationChannels.createAll(it) }
    }

    private fun isNotificationsEnabled(): Boolean =
        configStore?.notificationsEnabled ?: inMemoryConfig.notificationsEnabled

    private fun isNotifyTemperature(): Boolean =
        configStore?.notifyTemperature ?: inMemoryConfig.notifyTemperature

    private fun isNotifyLoad(): Boolean =
        configStore?.notifyLoad ?: inMemoryConfig.notifyLoad

    private fun isNotifyDisk(): Boolean =
        configStore?.notifyDisk ?: inMemoryConfig.notifyDisk

    private fun isNotifyConnection(): Boolean =
        configStore?.notifyConnection ?: inMemoryConfig.notifyConnection

    private fun getTempWarning(): Float =
        configStore?.tempWarning ?: inMemoryConfig.tempWarning

    private fun getTempCritical(): Float =
        configStore?.tempCritical ?: inMemoryConfig.tempCritical

    private fun getCpuLoadWarning(): Float =
        configStore?.cpuLoadWarning ?: inMemoryConfig.cpuLoadWarning

    private fun getCpuLoadCritical(): Float =
        configStore?.cpuLoadCritical ?: inMemoryConfig.cpuLoadCritical

    private fun getMemoryWarning(): Float =
        configStore?.memoryWarning ?: inMemoryConfig.memoryWarning

    private fun getDiskWarning(): Float =
        configStore?.diskWarning ?: inMemoryConfig.diskWarning

    private fun getDiskCritical(): Float =
        configStore?.diskCritical ?: inMemoryConfig.diskCritical

    private fun isQuietHoursNow(): Boolean {
        return configStore?.isQuietHoursNow() ?: inMemoryConfig.quietHoursEnabled
    }

    private fun isAlertSnoozed(alertId: String): Boolean {
        configStore?.let { return it.isAlertSnoozed(alertId) }
        val until = testSnoozeMap[alertId] ?: return false
        return System.currentTimeMillis() < until
    }

    fun snoozeAlertForTesting(alertId: String, durationMs: Long = 3600_000L) {
        testSnoozeMap[alertId] = System.currentTimeMillis() + durationMs
    }

    private fun canNotifyKey(serverUrl: String?, alertKey: String, isCritical: Boolean): Boolean {
        configStore?.let { return it.canNotifyKey(serverUrl, alertKey, isCritical) }
        val key = (serverUrl ?: "") + "_" + alertKey
        val lastTime = testCooldownMap[key] ?: return true
        val cooldown = if (isCritical) 2 * 3600 * 1000L else 6 * 3600 * 1000L
        return System.currentTimeMillis() - lastTime >= cooldown
    }

    private fun recordKeyNotified(serverUrl: String?, alertKey: String) {
        configStore?.recordKeyNotified(serverUrl, alertKey)
        val key = (serverUrl ?: "") + "_" + alertKey
        testCooldownMap[key] = System.currentTimeMillis()
    }

    private fun recordAlertNotified(serverUrl: String?, alertId: String) {
        configStore?.recordAlertNotified(serverUrl, alertId)
        val key = (serverUrl ?: "") + "_" + alertId
        testNotifiedIds[key] = System.currentTimeMillis()
    }

    /**
     * Evaluates live telemetry packets against user thresholds and cooldowns.
     */
    fun evaluateMetrics(overview: SystemOverview, serverUrl: String? = null) {
        if (!isNotificationsEnabled()) return

        val serverName = if (context != null) {
            ServerNameResolver.resolveServerName(context, serverUrl, overview.resolvedHost.hostname)
        } else {
            overview.resolvedHost.hostname.ifEmpty { "server" }
        }

        // 1. CPU Temperature
        if (isNotifyTemperature()) {
            val cpuTemp = overview.temperature?.cpu
            if (cpuTemp != null && cpuTemp.isFinite() && cpuTemp > 0f) {
                evaluateCpuTemperature(cpuTemp, serverName, serverUrl)
            }
        }

        // 2. CPU Load (Sustained check)
        if (isNotifyLoad()) {
            val cpuUsage = overview.cpu.overall_usage
            if (cpuUsage.isFinite() && cpuUsage >= 0f) {
                evaluateCpuUsage(cpuUsage, serverName, serverUrl)
            }
        }

        // 3. Memory
        if (isNotifyLoad()) {
            val ramUsage = overview.memory.usage_percent
            if (ramUsage.isFinite() && ramUsage >= 0f) {
                evaluateMemoryUsage(ramUsage, serverName, serverUrl)
            }
        }

        // 4. Disk Usage
        if (isNotifyDisk()) {
            val filesystems = overview.storage.filesystems
            for (fs in filesystems) {
                val usage = fs.usage_percent
                if (usage.isFinite() && usage >= 0f) {
                    evaluateDiskUsage(fs.mount_point, usage, serverName, serverUrl)
                }
            }
        }
    }

    fun evaluateCpuTemperature(temp: Float, serverName: String, serverUrl: String? = null) {
        val alertKey = "cpu_temperature"
        val tempInt = temp.toInt()
        val tempCrit = getTempCritical()
        val tempWarn = getTempWarning()

        val isCrit = temp >= tempCrit
        val isWarn = temp >= tempWarn
        val isRecovered = temp <= (tempWarn - 5f)

        if (isCrit || isWarn) {
            val severity = if (isCrit) "critical" else "warning"
            val title = if (isCrit) "CPU overheating" else "CPU hot"
            val body = "$serverName — ${tempInt}°C"

            recordInAppAlert(alertKey, title, "TEMPERATURE_HIGH", severity, body, tempInt, tempWarn.toInt())

            if (!isAppInForeground) {
                dispatchTieredNotification(
                    id = NOTIFICATION_ID_CPU_TEMP,
                    title = title,
                    body = body,
                    serverName = serverName,
                    serverUrl = serverUrl,
                    severity = severity,
                    alertKey = alertKey,
                    alertId = alertKey
                )
            }
        } else if (isRecovered) {
            clearAlert(alertKey, serverUrl, serverName, "CPU temperature back to normal (${tempInt}°C)")
        }
    }

    fun evaluateCpuUsage(
        usage: Float,
        serverName: String,
        serverUrl: String? = null,
        topProcess: ProcessInfo? = null,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        val alertKey = "cpu_usage"
        val usageInt = usage.toInt()
        val now = currentTimeMs
        val cpuCrit = getCpuLoadCritical()
        val cpuWarn = getCpuLoadWarning()

        val isCritTrigger = usage >= cpuCrit
        val isWarnTrigger = usage >= cpuWarn
        val isRecovered = usage <= (cpuWarn - 10f)

        if (isCritTrigger) {
            if (cpuLoadCritStartTime == 0L) cpuLoadCritStartTime = now
            if (now - cpuLoadCritStartTime >= SUSTAINED_LOAD_CRIT_MS) {
                val title = "Extreme CPU load"
                val body = buildString {
                    append(serverName)
                    append(" — ")
                    append("${usageInt}% (5 min)")
                    if (topProcess != null) {
                        append(" (top: ${topProcess.name})")
                    }
                }
                recordInAppAlert(alertKey, title, "CPU_LOAD_HIGH", "critical", body, usageInt, cpuCrit.toInt(), topProcess?.name)
                if (!isAppInForeground) {
                    dispatchTieredNotification(NOTIFICATION_ID_CPU_LOAD, title, body, serverName, serverUrl, "critical", alertKey, alertKey)
                }
            }
        } else {
            cpuLoadCritStartTime = 0L
            if (isWarnTrigger) {
                if (cpuLoadHighStartTime == 0L) cpuLoadHighStartTime = now
                if (now - cpuLoadHighStartTime >= SUSTAINED_LOAD_WARN_MS) {
                    val title = "High CPU load"
                    val body = buildString {
                        append(serverName)
                        append(" — ")
                        append("${usageInt}% (2 min)")
                        if (topProcess != null) {
                            append(" (top: ${topProcess.name})")
                        }
                    }
                    recordInAppAlert(alertKey, title, "CPU_LOAD_HIGH", "warning", body, usageInt, cpuWarn.toInt(), topProcess?.name)
                    if (!isAppInForeground) {
                        dispatchTieredNotification(NOTIFICATION_ID_CPU_LOAD, title, body, serverName, serverUrl, "warning", alertKey, alertKey)
                    }
                }
            } else {
                cpuLoadHighStartTime = 0L
                if (isRecovered) {
                    clearAlert(alertKey, serverUrl, serverName, "CPU load normal (${usageInt}%)")
                }
            }
        }
    }

    fun evaluateMemoryUsage(ramUsage: Float, serverName: String, serverUrl: String? = null) {
        val alertKey = "memory_usage"
        val usageInt = ramUsage.toInt()
        val memWarn = getMemoryWarning()

        val isWarn = ramUsage >= memWarn
        val isRecovered = ramUsage <= (memWarn - 5f)

        if (isWarn) {
            val title = "Memory full"
            val body = "$serverName — ${usageInt}% RAM"
            recordInAppAlert(alertKey, title, "MEMORY_HIGH", "warning", body, usageInt, memWarn.toInt())
            if (!isAppInForeground) {
                dispatchTieredNotification(NOTIFICATION_ID_MEMORY, title, body, serverName, serverUrl, "warning", alertKey, alertKey)
            }
        } else if (isRecovered) {
            clearAlert(alertKey, serverUrl, serverName, "Memory usage normal (${usageInt}%)")
        }
    }

    fun evaluateDiskUsage(mountPoint: String, usage: Float, serverName: String, serverUrl: String? = null) {
        val alertKey = "disk_$mountPoint"
        val usageInt = usage.toInt()
        val diskCrit = getDiskCritical()
        val diskWarn = getDiskWarning()

        val isCrit = usage >= diskCrit
        val isWarn = usage >= diskWarn
        val isRecovered = usage <= (diskWarn - 5f)

        if (isCrit || isWarn) {
            val severity = if (isCrit) "critical" else "warning"
            val title = if (isCrit) "Disk full" else "Disk almost full"
            val body = "$serverName — $mountPoint (${usageInt}%)"

            recordInAppAlert(alertKey, title, "DISK_HIGH", severity, body, usageInt, diskWarn.toInt())
            if (!isAppInForeground) {
                dispatchTieredNotification(NOTIFICATION_ID_DISK, title, body, serverName, serverUrl, severity, alertKey, alertKey)
            }
        } else if (isRecovered) {
            clearAlert(alertKey, serverUrl, serverName, "Storage $mountPoint healthy")
        }
    }

    /**
     * Processes server-generated alerts (from periodic worker or WebSocket).
     */
    fun processServerAlerts(serverUrl: String, alerts: List<AlertHistoryItem>) {
        if (!isNotificationsEnabled()) return

        val serverName = if (context != null) {
            ServerNameResolver.resolveServerName(context, serverUrl, "")
        } else {
            "server"
        }
        val activeAlerts = alerts.filter { !it.isResolved }

        for (alert in activeAlerts) {
            val typeUpper = (alert.type ?: alert.alertType ?: "").uppercase()
            val isSmartFail = typeUpper.contains("SMART") || typeUpper.contains("DISK_FAILING") ||
                    alert.title?.contains("SMART", ignoreCase = true) == true ||
                    alert.message.contains("SMART", ignoreCase = true)

            // Category filters
            if (typeUpper.contains("TEMP") && !isNotifyTemperature()) continue
            if ((typeUpper.contains("CPU") || typeUpper.contains("MEM")) && !isNotifyLoad()) continue
            if ((typeUpper.contains("DISK") || isSmartFail) && !isNotifyDisk()) continue

            val isCritical = alert.severity.equals("critical", ignoreCase = true) || isSmartFail
            val severity = if (isCritical) "critical" else "warning"

            val title = when {
                isSmartFail -> "Disk failing"
                typeUpper.contains("TEMP") -> "CPU hot"
                typeUpper.contains("CPU") -> "High load"
                typeUpper.contains("MEM") -> "Memory high"
                typeUpper.contains("DISK") -> "Disk almost full"
                typeUpper.contains("CONTAINER_STOPPED") -> "Service stopped"
                else -> alert.title ?: "Server alert"
            }

            val body = buildString {
                append(serverName)
                if (!alert.source.isNullOrBlank()) {
                    append(" — ")
                    append(alert.source)
                } else if (alert.message.isNotBlank()) {
                    append(" — ")
                    append(alert.message.take(32))
                }
            }

            val alertKey = "${typeUpper}_${alert.source.orEmpty()}"
            val notifId = (alert.id.hashCode() and 0x7FFFFFFF) % 10000 + 2000

            dispatchTieredNotification(
                id = notifId,
                title = title,
                body = body,
                serverName = serverName,
                serverUrl = serverUrl,
                severity = severity,
                alertKey = alertKey,
                alertId = alert.id
            )
        }

        // Auto-clear resolved notifications
        val resolvedAlerts = alerts.filter { it.isResolved }
        for (resolved in resolvedAlerts) {
            val notifId = (resolved.id.hashCode() and 0x7FFFFFFF) % 10000 + 2000
            cancelNotification(serverUrl, notifId)
        }
    }

    /**
     * Dispatches notification with tiered channels, cooldowns, quiet hours, and lock screen privacy.
     */
    private fun dispatchTieredNotification(
        id: Int,
        title: String,
        body: String,
        serverName: String,
        serverUrl: String?,
        severity: String,
        alertKey: String,
        alertId: String
    ) {
        if (!isNotificationsEnabled()) return

        // Check snoozed
        if (isAlertSnoozed(alertId)) return

        val isCritical = severity.equals("critical", ignoreCase = true)

        // Quiet hours: suppress non-critical alerts
        if (isQuietHoursNow() && !isCritical) {
            return
        }

        // Deduplication & Cooldown: at most once per 6h (warning) or 2h (critical)
        if (!canNotifyKey(serverUrl, alertKey, isCritical)) {
            return
        }

        // Record cooldown and notification ID
        recordKeyNotified(serverUrl, alertKey)
        recordAlertNotified(serverUrl, alertId)

        // Track notification for group summary
        val serverKey = sanitizeServerKey(serverUrl)
        val set = activeNotifIdsPerServer.getOrPut(serverKey) { mutableSetOf() }
        set.add(id)

        notificationDispatcher?.let {
            it.invoke(id, title, body)
            updateGroupSummary(serverUrl, serverName, "zentra_alerts_group_${serverKey}")
            return
        }

        val ctx = context ?: return
        val config = configStore ?: return
        if (!config.notificationsEnabled) return

        // Permission check on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val channelId = if (isCritical) {
            NotificationChannels.CHANNEL_CRITICAL
        } else {
            NotificationChannels.CHANNEL_WARNING
        }

        val groupKey = "zentra_alerts_group_${sanitizeServerKey(serverUrl)}"

        // Tap opens MainActivity on Alerts tab (index 4)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TAB, 4)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accentColor = if (isCritical) {
            ContextCompat.getColor(ctx, R.color.critical)
        } else {
            ContextCompat.getColor(ctx, R.color.warning)
        }

        // Public version hiding server details on lock screen
        val publicNotification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Server Alert")
            .setContentText("A server requires review")
            .setColor(accentColor)
            .build()

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(serverName)
            .setColor(accentColor)
            .setPriority(if (isCritical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(groupKey)
            .setPublicVersion(publicNotification)

        // Attach action buttons on warning notifications
        if (!isCritical && alertId.isNotEmpty() && !serverUrl.isNullOrEmpty()) {
            val resolveIntent = Intent(ctx, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_RESOLVE
                putExtra(AlertActionReceiver.EXTRA_SERVER_URL, serverUrl)
                putExtra(AlertActionReceiver.EXTRA_ALERT_ID, alertId)
                putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, id)
            }
            val resolvePending = PendingIntent.getBroadcast(
                ctx,
                id * 10 + 1,
                resolveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val snoozeIntent = Intent(ctx, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_SNOOZE
                putExtra(AlertActionReceiver.EXTRA_ALERT_ID, alertId)
                putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, id)
            }
            val snoozePending = PendingIntent.getBroadcast(
                ctx,
                id * 10 + 2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(0, "Resolve", resolvePending)
            builder.addAction(0, "Snooze 1h", snoozePending)
        }

        notificationManager?.notify(id, builder.build())
        updateGroupSummary(serverUrl, serverName, groupKey)
    }

    private fun updateGroupSummary(serverUrl: String?, serverName: String, groupKey: String) {
        val serverKey = sanitizeServerKey(serverUrl)
        val ids = activeNotifIdsPerServer[serverKey] ?: return

        if (ids.size >= 3) {
            notificationDispatcher?.let {
                it.invoke(NOTIFICATION_ID_GROUP_SUMMARY, "3 alerts on $serverName", "${ids.size} alerts on $serverName")
                return
            }
            val ctx = context ?: return
            val intent = Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_NAVIGATE_TAB, 4)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                NOTIFICATION_ID_GROUP_SUMMARY,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val summaryNotif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_WARNING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${ids.size} alerts on $serverName")
                .setContentText("Multiple issues require attention")
                .setSubText(serverName)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager?.notify(NOTIFICATION_ID_GROUP_SUMMARY + serverKey.hashCode() % 100, summaryNotif)
        }
    }

    private fun cancelNotification(serverUrl: String?, id: Int) {
        notificationManager?.cancel(id)
        val serverKey = sanitizeServerKey(serverUrl)
        activeNotifIdsPerServer[serverKey]?.remove(id)
    }

    private fun clearAlert(alertKey: String, serverUrl: String?, serverName: String, recoveryMessage: String) {
        val removed = activeAlertsMap.remove(alertKey)
        updateLocalAlertsList()
        configStore?.clearKeyCooldown(serverUrl, alertKey)

        if (removed != null && !isAppInForeground) {
            postRecoveryNotification(serverUrl, serverName, recoveryMessage)
        }
    }

    fun postRecoveryNotification(serverUrl: String?, serverName: String, message: String) {
        notificationDispatcher?.let {
            it.invoke(1001, "Back to normal", "$serverName — $message")
            return
        }
        val ctx = context ?: return
        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Back to normal")
            .setContentText(message)
            .setSubText(serverName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(1030 + (serverUrl?.hashCode() ?: 0) % 50, notif)
    }

    fun postAuthRequiredNotification(serverUrl: String?) {
        val ctx = context ?: return
        val serverName = ServerNameResolver.resolveServerName(ctx, serverUrl, "")
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID_AUTH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sign in again")
            .setContentText("Session expired on $serverName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager?.notify(NOTIFICATION_ID_AUTH, notif)
    }

    fun checkServerUnreachable(serverUrl: String?) {
        val ctx = context ?: return
        val config = configStore ?: return
        if (!config.notifyConnection) return

        val serverKey = sanitizeServerKey(serverUrl)
        val startTime = config.getUnreachableStartTime(serverKey)
        val now = System.currentTimeMillis()

        if (startTime == 0L) {
            config.setUnreachableStartTime(serverKey, now)
            return
        }

        if (now - startTime >= UNREACHABLE_TIMEOUT_MS && !config.isUnreachableNotified(serverKey)) {
            val serverName = ServerNameResolver.resolveServerName(ctx, serverUrl, "")
            val notif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_CRITICAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Server unreachable")
                .setContentText("$serverName has been offline for 10 minutes")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(ContextCompat.getColor(ctx, R.color.critical))
                .setAutoCancel(true)
                .build()
            notificationManager?.notify(NOTIFICATION_ID_UNREACHABLE, notif)
            config.setUnreachableNotified(serverKey, true)
        }
    }

    fun dispatchTestNotification() {
        val ctx = context ?: return
        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_CRITICAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test Alert")
            .setContentText("Notifications and channels are configured properly.")
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(NOTIFICATION_ID_TEST, notif)
    }

    fun recordInAppAlert(
        key: String,
        title: String,
        type: String,
        severity: String,
        message: String,
        value: Int,
        threshold: Int,
        source: String? = null
    ) {
        val alertItem = AlertHistoryItem(
            id = key,
            title = title,
            type = type,
            severity = severity,
            message = message,
            status = "active",
            source = source,
            value = value,
            threshold = threshold,
            timestamp = System.currentTimeMillis()
        )
        activeAlertsMap[key] = alertItem
        updateLocalAlertsList()
    }

    /**
     * Dispatches a quiet warning notification when network usage exceeds 80% of the monthly data cap.
     */
    fun notifyDataCapWarning(serverUrl: String, percentage: Float, usedBytes: Long, capBytes: Long) {
        val title = "Data Cap Warning"
        val body = "${String.format(Locale.US, "%.0f", percentage)}% of monthly data allowance used (${Formatters.formatBytes(usedBytes)} / ${Formatters.formatBytes(capBytes)})"

        notificationDispatcher?.let {
            it.invoke(NOTIFICATION_ID_DATA_CAP, title, body)
            return
        }

        val ctx = context ?: return
        val serverName = ServerNameResolver.resolveServerName(ctx, serverUrl, null)

        val config = configStore ?: return
        if (!config.notificationsEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        if (isAppInForeground) {
            return
        }

        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TAB, 2)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID_DATA_CAP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(serverName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(ctx, R.color.warning))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager?.notify(NOTIFICATION_ID_DATA_CAP, notif)
    }

    private fun updateLocalAlertsList() {
        try {
            _localActiveAlerts.postValue(activeAlertsMap.values.toList())
        } catch (_: Throwable) {}
    }

    fun getActiveAlerts(): List<AlertHistoryItem> {
        return activeAlertsMap.values.toList()
    }

    fun clearAllAlerts() {
        activeAlertsMap.clear()
        activeNotifIdsPerServer.clear()
        try {
            _localActiveAlerts.postValue(emptyList())
        } catch (_: Throwable) {}
        try {
            notificationManager?.cancelAll()
        } catch (_: Throwable) {}
    }

    fun resetStateForTesting() {
        activeAlertsMap.clear()
        activeNotifIdsPerServer.clear()
        testCooldownMap.clear()
        testNotifiedIds.clear()
        testSnoozeMap.clear()
        cpuLoadHighStartTime = 0L
        cpuLoadCritStartTime = 0L
        inMemoryConfig = ConfigValues()
        try {
            _localActiveAlerts.postValue(emptyList())
        } catch (_: Throwable) {}
        isAppInForeground = false
    }
}
