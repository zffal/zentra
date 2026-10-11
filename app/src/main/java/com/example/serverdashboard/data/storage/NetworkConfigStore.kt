package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Stores network configuration:
 * - Speed unit preference (Bytes/s vs Bits/s)
 * - Monthly data usage cap and billing cycle configuration
 * - Deduplication for monthly data cap alert notifications
 */
class NetworkConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "zentra_network_config"

        private const val KEY_SPEED_IN_BITS = "network_speed_in_bits"
        private const val KEY_DATA_CAP_ENABLED = "data_cap_enabled"
        private const val KEY_DATA_CAP_GB = "data_cap_gb"
        private const val KEY_BILLING_CYCLE_DAY = "billing_cycle_day"
        private const val KEY_LAST_WARNED_CYCLE = "last_warned_cycle"

        @Volatile
        private var INSTANCE: NetworkConfigStore? = null

        fun getInstance(context: Context): NetworkConfigStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkConfigStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Whether network speed should be displayed in bits (Mbps, Kbps) instead of bytes (MB/s, KB/s).
     * Defaults to false (bytes).
     */
    var speedInBits: Boolean
        get() = prefs.getBoolean(KEY_SPEED_IN_BITS, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_IN_BITS, value).apply()

    /**
     * Whether a monthly data usage cap warning is enabled.
     * Defaults to false.
     */
    var dataCapEnabled: Boolean
        get() = prefs.getBoolean(KEY_DATA_CAP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DATA_CAP_ENABLED, value).apply()

    /**
     * The monthly data allowance in gigabytes.
     * Defaults to 100 GB.
     */
    var dataCapGb: Float
        get() = prefs.getFloat(KEY_DATA_CAP_GB, 100f)
        set(value) = prefs.edit().putFloat(KEY_DATA_CAP_GB, if (value > 0f) value else 100f).apply()

    /**
     * Allowance in raw bytes.
     */
    val dataCapBytes: Long
        get() = (dataCapGb.toDouble() * 1024.0 * 1024.0 * 1024.0).toLong()

    /**
     * Billing cycle reset day of month (1 - 31).
     * Defaults to 1.
     */
    var billingCycleDay: Int
        get() = prefs.getInt(KEY_BILLING_CYCLE_DAY, 1).coerceIn(1, 31)
        set(value) = prefs.edit().putInt(KEY_BILLING_CYCLE_DAY, value.coerceIn(1, 31)).apply()

    /**
     * Key string of the last billing cycle for which an 80% cap warning was dispatched,
     * preventing repeated notifications within the same cycle.
     */
    var lastWarnedCycleKey: String?
        get() = prefs.getString(KEY_LAST_WARNED_CYCLE, null)
        set(value) = prefs.edit().putString(KEY_LAST_WARNED_CYCLE, value).apply()

    /**
     * Returns the ISO-like date string "yyyy-MM-dd" of the start of the current billing cycle.
     */
    fun getCurrentCycleStartDate(): String {
        val cal = Calendar.getInstance()
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)
        val cycleDay = billingCycleDay

        if (todayDay < cycleDay) {
            // Started in previous month
            cal.add(Calendar.MONTH, -1)
        }
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val targetDay = cycleDay.coerceAtMost(maxDays)
        cal.set(Calendar.DAY_OF_MONTH, targetDay)

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}
