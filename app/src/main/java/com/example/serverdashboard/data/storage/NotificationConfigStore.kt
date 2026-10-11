package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.serverdashboard.data.storage.OverviewConfigStore.Companion.sanitizeServerKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

/**
 * Manages notification settings, channels configuration, quiet hours,
 * custom thresholds, and per-server deduplication/cooldown state.
 */
class NotificationConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "zentra_notification_config"

        private const val KEY_NOTIF_ENABLED = "notifications_enabled"
        private const val KEY_NOTIF_TEMP = "notify_temperature"
        private const val KEY_NOTIF_LOAD = "notify_load"
        private const val KEY_NOTIF_DISK = "notify_disk"
        private const val KEY_NOTIF_CONN = "notify_connection"

        private const val KEY_LIVE_MONITORING = "live_monitoring_enabled"

        private const val KEY_QUIET_HOURS = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_hours_start_min"
        private const val KEY_QUIET_END = "quiet_hours_end_min"

        private const val KEY_TEMP_WARN = "thresh_temp_warn"
        private const val KEY_TEMP_CRIT = "thresh_temp_crit"
        private const val KEY_CPU_WARN = "thresh_cpu_warn"
        private const val KEY_CPU_CRIT = "thresh_cpu_crit"
        private const val KEY_MEM_WARN = "thresh_mem_warn"
        private const val KEY_DISK_WARN = "thresh_disk_warn"
        private const val KEY_DISK_CRIT = "thresh_disk_crit"

        private const val KEY_PREFIX_NOTIFIED_IDS = "notified_ids_"
        private const val KEY_PREFIX_COOLDOWN = "cooldown_"
        private const val KEY_PREFIX_SNOOZE = "snooze_"
        private const val KEY_PREFIX_UNREACHABLE_START = "unreachable_start_"
        private const val KEY_PREFIX_UNREACHABLE_NOTIFIED = "unreachable_notified_"

        private const val MAX_NOTIFIED_IDS = 200
        const val COOLDOWN_WARNING_MS = 6 * 3600 * 1000L // 6 hours
        const val COOLDOWN_CRITICAL_MS = 2 * 3600 * 1000L // 2 hours

        @Volatile
        private var INSTANCE: NotificationConfigStore? = null

        fun getInstance(context: Context): NotificationConfigStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationConfigStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Volatile session flag to prevent nagging more than once per app launch
    @Volatile
    var hasNaggedThisLaunch: Boolean = false

    @Volatile
    var hasDismissedBannerThisLaunch: Boolean = false

    // Master toggle
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ENABLED, value).apply()

    // Per-category toggles
    var notifyTemperature: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_TEMP, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_TEMP, value).apply()

    var notifyLoad: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_LOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_LOAD, value).apply()

    var notifyDisk: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_DISK, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_DISK, value).apply()

    var notifyConnection: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_CONN, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_CONN, value).apply()

    // Live WebSocket monitoring
    var liveMonitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_MONITORING, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_MONITORING, value).apply()

    // Quiet hours
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_HOURS, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_HOURS, value).apply()

    var quietHoursStartMinutes: Int
        get() = prefs.getInt(KEY_QUIET_START, 22 * 60) // 22:00
        set(value) = prefs.edit().putInt(KEY_QUIET_START, value).apply()

    var quietHoursEndMinutes: Int
        get() = prefs.getInt(KEY_QUIET_END, 7 * 60) // 07:00
        set(value) = prefs.edit().putInt(KEY_QUIET_END, value).apply()

    fun isQuietHoursNow(): Boolean {
        if (!quietHoursEnabled) return false
        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (quietHoursStartMinutes <= quietHoursEndMinutes) {
            currentMinutes in quietHoursStartMinutes..quietHoursEndMinutes
        } else {
            currentMinutes >= quietHoursStartMinutes || currentMinutes < quietHoursEndMinutes
        }
    }

    // Thresholds
    var tempWarning: Float
        get() = prefs.getFloat(KEY_TEMP_WARN, 75f)
        set(value) = prefs.edit().putFloat(KEY_TEMP_WARN, value).apply()

    var tempCritical: Float
        get() = prefs.getFloat(KEY_TEMP_CRIT, 85f)
        set(value) = prefs.edit().putFloat(KEY_TEMP_CRIT, value).apply()

    var cpuLoadWarning: Float
        get() = prefs.getFloat(KEY_CPU_WARN, 85f)
        set(value) = prefs.edit().putFloat(KEY_CPU_WARN, value).apply()

    var cpuLoadCritical: Float
        get() = prefs.getFloat(KEY_CPU_CRIT, 95f)
        set(value) = prefs.edit().putFloat(KEY_CPU_CRIT, value).apply()

    var memoryWarning: Float
        get() = prefs.getFloat(KEY_MEM_WARN, 90f)
        set(value) = prefs.edit().putFloat(KEY_MEM_WARN, value).apply()

    var diskWarning: Float
        get() = prefs.getFloat(KEY_DISK_WARN, 85f)
        set(value) = prefs.edit().putFloat(KEY_DISK_WARN, value).apply()

    var diskCritical: Float
        get() = prefs.getFloat(KEY_DISK_CRIT, 95f)
        set(value) = prefs.edit().putFloat(KEY_DISK_CRIT, value).apply()

    // Deduplication & ID Tracking (Capped at 200 IDs per server)
    @Synchronized
    fun isAlertAlreadyNotified(serverKey: String?, alertId: String): Boolean {
        val key = KEY_PREFIX_NOTIFIED_IDS + sanitizeServerKey(serverKey)
        val json = prefs.getString(key, null) ?: return false
        val map = parseNotifiedMap(json)
        return map.containsKey(alertId)
    }

    @Synchronized
    fun recordAlertNotified(serverKey: String?, alertId: String) {
        val key = KEY_PREFIX_NOTIFIED_IDS + sanitizeServerKey(serverKey)
        val json = prefs.getString(key, null)
        val map = parseNotifiedMap(json).toMutableMap()
        map[alertId] = System.currentTimeMillis()

        // Evict oldest if exceeding capacity
        if (map.size > MAX_NOTIFIED_IDS) {
            val sorted = map.entries.sortedBy { it.value }
            val removeCount = map.size - MAX_NOTIFIED_IDS
            for (i in 0 until removeCount) {
                map.remove(sorted[i].key)
            }
        }
        prefs.edit().putString(key, gson.toJson(map)).apply()
    }

    private fun parseNotifiedMap(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // Cooldown tracking per (alert_type + source)
    fun canNotifyKey(serverKey: String?, alertKey: String, isCritical: Boolean): Boolean {
        val key = KEY_PREFIX_COOLDOWN + sanitizeServerKey(serverKey) + "_" + alertKey
        val lastTime = prefs.getLong(key, 0L)
        if (lastTime == 0L) return true
        val cooldown = if (isCritical) COOLDOWN_CRITICAL_MS else COOLDOWN_WARNING_MS
        return System.currentTimeMillis() - lastTime >= cooldown
    }

    fun recordKeyNotified(serverKey: String?, alertKey: String) {
        val key = KEY_PREFIX_COOLDOWN + sanitizeServerKey(serverKey) + "_" + alertKey
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun clearKeyCooldown(serverKey: String?, alertKey: String) {
        val key = KEY_PREFIX_COOLDOWN + sanitizeServerKey(serverKey) + "_" + alertKey
        prefs.edit().remove(key).apply()
    }

    // Snooze tracking
    fun isAlertSnoozed(alertId: String): Boolean {
        val key = KEY_PREFIX_SNOOZE + alertId
        val until = prefs.getLong(key, 0L)
        if (until == 0L) return false
        if (System.currentTimeMillis() >= until) {
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    fun snoozeAlert(alertId: String, durationMs: Long = 3600_000L) {
        val key = KEY_PREFIX_SNOOZE + alertId
        prefs.edit().putLong(key, System.currentTimeMillis() + durationMs).apply()
    }

    // Server unreachable tracking (10 min threshold)
    fun getUnreachableStartTime(serverKey: String?): Long {
        val key = KEY_PREFIX_UNREACHABLE_START + sanitizeServerKey(serverKey)
        return prefs.getLong(key, 0L)
    }

    fun setUnreachableStartTime(serverKey: String?, timeMs: Long) {
        val key = KEY_PREFIX_UNREACHABLE_START + sanitizeServerKey(serverKey)
        prefs.edit().putLong(key, timeMs).apply()
    }

    fun isUnreachableNotified(serverKey: String?): Boolean {
        val key = KEY_PREFIX_UNREACHABLE_NOTIFIED + sanitizeServerKey(serverKey)
        return prefs.getBoolean(key, false)
    }

    fun setUnreachableNotified(serverKey: String?, notified: Boolean) {
        val key = KEY_PREFIX_UNREACHABLE_NOTIFIED + sanitizeServerKey(serverKey)
        prefs.edit().putBoolean(key, notified).apply()
    }
}
