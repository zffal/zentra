package com.example.serverdashboard.data.model

import com.google.gson.annotations.SerializedName

data class AlertsResponse(
    val count: Int = 0,
    val alerts: List<AlertHistoryItem> = emptyList(),
    val timestamp: Long = 0L
)

data class ClearAlertsResponse(
    val status: String = "",
    val deleted: Int? = null,
    val cleared: Int? = null,
    val remaining: Int? = null,
    val total: Int? = null,
    val timestamp: Long = 0L
) {
    val countDeleted: Int
        get() = deleted ?: cleared ?: 0
}

data class AlertHistoryItem(
    val id: String = "",
    val title: String? = null,
    val type: String? = null,
    val severity: String = "info",
    @SerializedName("alert_type") val alertType: String? = null,
    val source: String? = null,
    val message: String = "",
    val status: String = "active",
    val value: Any? = null,
    val threshold: Any? = null,
    val timestamp: Long = 0L,
    @SerializedName("created_at") val createdAt: Any? = null,
    @SerializedName("resolved_at") val resolvedAt: Any? = null
) {
    val displayTitle: String
        get() {
            val rawType = (type ?: alertType ?: "").uppercase()
            return when {
                rawType.contains("TEMP") -> "CPU temperature is high"
                rawType.contains("CONTAINER_STOPPED") -> "Service stopped unexpectedly"
                rawType.contains("CONTAINER_RESTARTED") -> "Service restarted"
                rawType.contains("DISK") || rawType.contains("STORAGE") -> "Storage almost full"
                rawType.contains("CPU") -> "High processor load"
                rawType.contains("MEM") -> "Memory almost full"
                !title.isNullOrBlank() -> title
                !type.isNullOrBlank() -> type.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                else -> "Server Alert"
            }
        }

    val displayExplanation: String
        get() {
            val rawType = (type ?: alertType ?: "").uppercase()
            return when {
                rawType.contains("TEMP") -> "The processor is running hotter than normal."
                rawType.contains("DISK") || rawType.contains("STORAGE") -> "You may need to free some space."
                rawType.contains("CONTAINER_STOPPED") -> {
                    val s = source ?: "application"
                    "Service '$s' changed state or stopped unexpectedly."
                }
                rawType.contains("CONTAINER_RESTARTED") -> {
                    val s = source ?: "application"
                    "Service '$s' restarted automatically."
                }
                rawType.contains("CPU") -> "The server processor is handling heavy workload."
                rawType.contains("MEM") -> "RAM is running near maximum capacity."
                message.isNotBlank() -> message
                else -> "System status requires review."
            }
        }

    val displayMetric: String
        get() = when {
            !source.isNullOrBlank() && !source.contains("Package id", ignoreCase = true) -> "Service: $source"
            !source.isNullOrBlank() -> "Hardware Sensor"
            else -> "System Metric"
        }

    val isResolved: Boolean
        get() = status.equals("resolved", ignoreCase = true) || resolvedAt != null
}
