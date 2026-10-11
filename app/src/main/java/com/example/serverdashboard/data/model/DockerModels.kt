package com.example.serverdashboard.data.model

import com.google.gson.annotations.SerializedName

data class ContainerStats(
    @SerializedName("cpu_percent") val cpuPercent: Float = 0f,
    @SerializedName("memory_usage") val memoryUsage: Long = 0L,
    @SerializedName("memory_limit") val memoryLimit: Long = 0L,
    @SerializedName("memory_percent") val memoryPercent: Float = 0f,
    @SerializedName("network_rx") val networkRx: Long = 0L,
    @SerializedName("network_tx") val networkTx: Long = 0L,
    @SerializedName("block_read") val blockRead: Long = 0L,
    @SerializedName("block_write") val blockWrite: Long = 0L
)

data class ContainerItem(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val state: String = "",
    val status: String = "",
    val created: Any? = null,
    val stats: ContainerStats = ContainerStats(),
    @SerializedName("cpu_percent") val directCpuPercent: Float? = null,
    @SerializedName("memory_usage_bytes") val directMemUsage: Long? = null,
    @SerializedName("memory_limit_bytes") val directMemLimit: Long? = null,
    @SerializedName("memory_percent") val directMemPercent: Float? = null,
    val ports: Any? = null,
    val restarts: Int? = 0
) {
    val cpu_percent: Float? get() = directCpuPercent ?: stats.cpuPercent
    val memory_usage_bytes: Long? get() = directMemUsage ?: stats.memoryUsage
    val memory_limit_bytes: Long? get() = directMemLimit ?: stats.memoryLimit
    val memory_percent: Float? get() = directMemPercent ?: stats.memoryPercent
}

data class DockerContainersResponse(
    val available: Boolean = true,
    val summary: DockerSummary = DockerSummary(),
    val containers: List<ContainerItem> = emptyList(),
    val timestamp: Long = 0L
)

data class ContainerLogsResponse(
    val id: String = "",
    val logs: List<String> = emptyList()
)

data class ActionResult(
    val success: Boolean = false,
    val message: String = "",
    val error: String? = null
)
