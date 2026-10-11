package com.example.serverdashboard.data.model

import java.util.UUID

enum class GraphMetric(
    val displayName: String,
    val unit: String,
    val isRate: Boolean
) {
    CPU("CPU Usage", "%", false),
    MEMORY("Memory Usage", "%", false),
    STORAGE("Storage Usage", "%", false),
    DISK_READ("Disk Read", "B/s", true),
    DISK_WRITE("Disk Write", "B/s", true),
    NET_DOWNLOAD("Network Download", "B/s", true),
    NET_UPLOAD("Network Upload", "B/s", true);

    val allowedStyles: List<GraphStyle>
        get() = if (isRate) {
            // Rates do not have a natural 100% maximum, so Ring gauge is excluded
            listOf(GraphStyle.LINE, GraphStyle.SEMI_CIRCLE, GraphStyle.BAR)
        } else {
            listOf(GraphStyle.RING, GraphStyle.SEMI_CIRCLE, GraphStyle.LINE, GraphStyle.BAR)
        }
}

enum class GraphStyle(
    val displayName: String,
    val description: String
) {
    SEMI_CIRCLE("Semi-circle Gauge", "Arc showing current value vs min/max"),
    LINE("Line Graph", "Historical trend over time"),
    RING("Ring Gauge", "Circular progress indicator"),
    BAR("Bar Graph", "Component comparison or intervals")
}

enum class GraphTimeRange(
    val displayName: String,
    val seconds: Int
) {
    ONE_MIN("1m", 60),
    FIVE_MIN("5m", 300),
    FIFTEEN_MIN("15m", 900),
    ONE_HOUR("1h", 3600),
    TWENTY_FOUR_HOUR("24h", 86400)
}

data class GraphConfig(
    val id: String = UUID.randomUUID().toString(),
    val metric: GraphMetric = GraphMetric.CPU,
    val style: GraphStyle = GraphStyle.RING,
    val timeRange: GraphTimeRange = GraphTimeRange.ONE_MIN,
    val isCompact: Boolean = false,
    val showSecondaryValue: Boolean = true
) {
    fun validated(): GraphConfig {
        val validStyle = if (metric.allowedStyles.contains(style)) {
            style
        } else {
            metric.allowedStyles.first()
        }
        return copy(style = validStyle)
    }
}

data class BarItem(
    val label: String,
    val value: Float,
    val maxValue: Float = 100f
)
