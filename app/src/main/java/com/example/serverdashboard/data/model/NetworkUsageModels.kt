package com.example.serverdashboard.data.model

import com.google.gson.annotations.SerializedName

/**
 * Thrown when the server returns 404 for usage endpoints (e.g. running an older backend version).
 */
class ServerOutdatedException(message: String = "Update server to see usage") : Exception(message)

/**
 * Daily network data usage item returned by /api/network/usage.
 */
data class DailyUsageItem(
    val date: String = "",
    @SerializedName("rx_bytes") val rxBytes: Long = 0L,
    @SerializedName("tx_bytes") val txBytes: Long = 0L,
    @SerializedName("total_bytes") val totalBytes: Long = 0L
) {
    val rx: Long get() = if (rxBytes >= 0L) rxBytes else 0L
    val tx: Long get() = if (txBytes >= 0L) txBytes else 0L
    val total: Long get() {
        val t = if (totalBytes > 0L) totalBytes else (rx + tx)
        return if (t >= 0L) t else 0L
    }
}

/**
 * Aggregated usage totals for the requested period.
 */
data class UsageTotals(
    @SerializedName("rx") val rxBytes: Long = 0L,
    @SerializedName("tx") val txBytes: Long = 0L,
    @SerializedName("total") val totalBytes: Long = 0L
) {
    val rx: Long get() = if (rxBytes >= 0L) rxBytes else 0L
    val tx: Long get() = if (txBytes >= 0L) txBytes else 0L
    val total: Long get() {
        val t = if (totalBytes > 0L) totalBytes else (rx + tx)
        return if (t >= 0L) t else 0L
    }
}

/**
 * Heaviest usage day within the requested period.
 */
data class PeakUsageDay(
    val date: String = "",
    @SerializedName("total_bytes") val totalBytes: Long = 0L
) {
    val total: Long get() = if (totalBytes >= 0L) totalBytes else 0L
}

/**
 * Complete response payload from /api/network/usage.
 */
data class NetworkUsageResponse(
    val timezone: String? = null,
    val interfaces: List<String> = emptyList(),
    val days: List<DailyUsageItem> = emptyList(),
    val totals: UsageTotals = UsageTotals(),
    val peak: PeakUsageDay? = null,
    @SerializedName("tracking_since") val trackingSince: String? = null
)
