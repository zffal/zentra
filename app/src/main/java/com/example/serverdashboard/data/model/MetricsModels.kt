package com.example.serverdashboard.data.model

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

class PerCoreDeserializer : JsonDeserializer<List<Float>> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): List<Float> {
        if (json == null || !json.isJsonArray) return emptyList()
        val result = mutableListOf<Float>()
        for (elem in json.asJsonArray) {
            try {
                when {
                    elem.isJsonPrimitive -> {
                        val prim = elem.asJsonPrimitive
                        val num = if (prim.isNumber) prim.asFloat else prim.asString.toFloatOrNull()
                        if (num != null && num.isFinite() && num >= 0f) result.add(num)
                    }
                    elem.isJsonObject -> {
                        val obj = elem.asJsonObject
                        val candidate = when {
                            obj.has("usage_percent") && !obj.get("usage_percent").isJsonNull -> obj.get("usage_percent")
                            obj.has("usage") && !obj.get("usage").isJsonNull -> obj.get("usage")
                            obj.has("percent") && !obj.get("percent").isJsonNull -> obj.get("percent")
                            obj.has("usage_pct") && !obj.get("usage_pct").isJsonNull -> obj.get("usage_pct")
                            else -> null
                        }
                        if (candidate != null && candidate.isJsonPrimitive) {
                            val prim = candidate.asJsonPrimitive
                            val num = if (prim.isNumber) prim.asFloat else prim.asString.toFloatOrNull()
                            if (num != null && num.isFinite() && num >= 0f) result.add(num)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore malformed individual core element
            }
        }
        return result
    }
}

class SafeFloatListDeserializer : JsonDeserializer<List<Float>> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): List<Float> {
        if (json == null || !json.isJsonArray) return emptyList()
        val result = mutableListOf<Float>()
        for (elem in json.asJsonArray) {
            try {
                if (elem.isJsonPrimitive) {
                    val prim = elem.asJsonPrimitive
                    val num = if (prim.isNumber) prim.asFloat else prim.asString.toFloatOrNull()
                    if (num != null && num.isFinite()) result.add(num)
                }
            } catch (e: Exception) {
                // Ignore malformed item
            }
        }
        return result
    }
}

class SafeTimestampDeserializer : JsonDeserializer<Long> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Long {
        if (json == null || json.isJsonNull) return 0L
        return try {
            if (json.isJsonPrimitive) {
                val prim = json.asJsonPrimitive
                val raw = when {
                    prim.isNumber -> prim.asDouble.toLong()
                    prim.isString -> {
                        val str = prim.asString.trim()
                        str.toLongOrNull() ?: str.toDoubleOrNull()?.toLong() ?: 0L
                    }
                    else -> 0L
                }
                normalizeToSeconds(raw)
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        fun normalizeToSeconds(raw: Long): Long {
            if (raw <= 0L) return 0L
            return if (raw > 100_000_000_000L) {
                raw / 1000L
            } else {
                raw
            }
        }

        fun normalizeToMillis(raw: Long): Long {
            if (raw <= 0L) return 0L
            return if (raw > 100_000_000_000L) {
                raw
            } else {
                raw * 1000L
            }
        }
    }
}

data class HostInfo(
    val hostname: String = "",
    @SerializedName("os") val directOs: String = "",
    @SerializedName("os_name") val osName: String = "",
    @SerializedName("os_version") val osVersion: String = "",
    val kernel: String = "",
    @SerializedName("kernel_version") val kernelVersion: String = "",
    val architecture: String = "",
    val arch: String = "",
    @SerializedName("cpu_model") val cpuModel: String = "",
    @SerializedName("cpu_cores") val cpuCores: Int = 0,
    @SerializedName("logical_cores") val logicalCores: Int = 0,
    @SerializedName("boot_time") val bootTime: Any? = null,
    val uptime: Double = 0.0,
    @SerializedName("uptime_seconds") val uptimeSeconds: Double = 0.0,
    val uptime_formatted: String = "",
    val server_time: String = "",
    @JsonAdapter(SafeTimestampDeserializer::class)
    @SerializedName("timestamp") val timestamp: Long = 0L
) {
    val os: String get() = if (directOs.isNotEmpty()) directOs else osName
    val effectiveUptime: Double get() = if (uptimeSeconds > 0) uptimeSeconds else uptime
}

data class CpuFrequency(
    @SerializedName("current_mhz") val currentMhz: Float = 0f,
    @SerializedName("min_mhz") val minMhz: Float? = null,
    @SerializedName("max_mhz") val maxMhz: Float? = null
)

data class CpuMetrics(
    @SerializedName("usage_percent") val usagePercent: Float = 0f,
    val cores: Int = 0,
    @SerializedName("logical_cores") val logicalCores: Int = 0,
    @SerializedName("physical_cores") val physicalCores: Int = 0,
    @JsonAdapter(PerCoreDeserializer::class)
    @SerializedName("per_core") val perCore: List<Float> = emptyList(),
    @JsonAdapter(SafeFloatListDeserializer::class)
    @SerializedName("per_core_usage") val directPerCoreUsage: List<Float> = emptyList(),
    @JsonAdapter(SafeFloatListDeserializer::class)
    @SerializedName("per_core_numeric") val perCoreNumeric: List<Float> = emptyList(),
    @JsonAdapter(SafeTimestampDeserializer::class)
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @JsonAdapter(SafeFloatListDeserializer::class)
    @SerializedName("per_core_frequency_mhz") val perCoreFreqMhz: List<Float> = emptyList(),
    val frequency: CpuFrequency = CpuFrequency(),
    @JsonAdapter(SafeFloatListDeserializer::class)
    @SerializedName("load_average") val loadAverage: List<Float> = emptyList(),
    @JsonAdapter(SafeFloatListDeserializer::class)
    @SerializedName("load_avg") val directLoadAvg: List<Float> = emptyList(),
    // Backward compatibility
    @SerializedName("overall_usage") val directOverallUsage: Float = 0f,
    @SerializedName("current_freq_mhz") val directFreqMhz: Float = 0f,
    @SerializedName("core_count") val directCoreCount: Int = 0,
    @SerializedName("logical_count") val directLogicalCount: Int = 0,
    @SerializedName("model") val directModel: String = "",
    @SerializedName("model_name") val modelName: String = ""
) {
    val overall_usage: Float get() {
        val u = if (usagePercent > 0) usagePercent else directOverallUsage
        return if (u.isFinite() && u >= 0f) u else 0f
    }
    val per_core_usage: List<Float> get() {
        val fromUsage = (directPerCoreUsage as List<Float?>?)?.filterNotNull()?.filter { it.isFinite() && it >= 0f }
        if (!fromUsage.isNullOrEmpty()) return fromUsage
        val fromNumeric = (perCoreNumeric as List<Float?>?)?.filterNotNull()?.filter { it.isFinite() && it >= 0f }
        if (!fromNumeric.isNullOrEmpty()) return fromNumeric
        return (perCore as List<Float?>?)?.filterNotNull()?.filter { it.isFinite() && it >= 0f } ?: emptyList()
    }
    val current_freq_mhz: Float get() {
        val f = (frequency as CpuFrequency?)?.currentMhz?.takeIf { it > 0 } ?: directFreqMhz
        return if (f.isFinite() && f >= 0f) f else 0f
    }
    val load_avg: List<Float> get() {
        val list1 = (loadAverage as List<Float?>?)?.filterNotNull()?.filter { it.isFinite() }
        if (!list1.isNullOrEmpty()) return list1
        return (directLoadAvg as List<Float?>?)?.filterNotNull()?.filter { it.isFinite() } ?: emptyList()
    }
    val core_count: Int get() = when {
        cores > 0 -> cores
        directCoreCount > 0 -> directCoreCount
        physicalCores > 0 -> physicalCores
        logicalCores > 0 -> logicalCores
        else -> 0
    }
    val logical_count: Int get() = when {
        logicalCores > 0 -> logicalCores
        directLogicalCount > 0 -> directLogicalCount
        else -> core_count
    }
    val model: String get() = if (directModel.isNotEmpty()) directModel else modelName
}

data class MemoryInner(
    val total: Long = 0L,
    val used: Long = 0L,
    val available: Long = 0L,
    val free: Long = 0L,
    val cached: Long = 0L,
    val buffers: Long = 0L,
    val percent: Float = 0f
)

data class SwapInner(
    val total: Long = 0L,
    val used: Long = 0L,
    val free: Long = 0L,
    val percent: Float = 0f
)

data class MemoryMetrics(
    val memory: MemoryInner = MemoryInner(),
    val swap: SwapInner = SwapInner(),
    // Direct/fallback fields
    @SerializedName("total_bytes") val directTotal: Long = 0L,
    @SerializedName("used_bytes") val directUsed: Long = 0L,
    @SerializedName("available_bytes") val directAvailable: Long = 0L,
    @SerializedName("cached_bytes") val directCached: Long = 0L,
    @SerializedName("swap_total_bytes") val directSwapTotal: Long = 0L,
    @SerializedName("swap_used_bytes") val directSwapUsed: Long = 0L,
    @SerializedName("usage_percent") val directPercent: Float = 0f,
    @SerializedName("swap_percent") val directSwapPercent: Float = 0f,
    @JsonAdapter(SafeTimestampDeserializer::class)
    @SerializedName("timestamp") val timestamp: Long = 0L
) {
    val total_bytes: Long get() = if (memory.total > 0) memory.total else directTotal
    val used_bytes: Long get() = if (memory.used > 0) memory.used else directUsed
    val available_bytes: Long get() = if (memory.available > 0) memory.available else directAvailable
    val cached_bytes: Long get() = if (memory.cached > 0) memory.cached else directCached
    val swap_total_bytes: Long get() = if (swap.total > 0) swap.total else directSwapTotal
    val swap_used_bytes: Long get() = if (swap.used > 0) swap.used else directSwapUsed
    val usage_percent: Float get() = if (memory.percent > 0) memory.percent else directPercent
    val swap_percent: Float get() = if (swap.percent > 0) swap.percent else directSwapPercent
}

data class FilesystemMetric(
    val device: String = "",
    val mountpoint: String = "",
    @SerializedName("mount_point") val directMountPoint: String = "",
    val filesystem: String = "",
    val fstype: String = "",
    val total: Long = 0L,
    val used: Long = 0L,
    val free: Long = 0L,
    val percent: Float = 0f,
    @SerializedName("total_bytes") val directTotal: Long = 0L,
    @SerializedName("used_bytes") val directUsed: Long = 0L,
    @SerializedName("free_bytes") val directFree: Long = 0L,
    @SerializedName("usage_percent") val directPercent: Float = 0f
) {
    val mount_point: String get() = mountpoint.ifEmpty { directMountPoint }
    val total_bytes: Long get() = if (total > 0) total else directTotal
    val used_bytes: Long get() = if (used > 0) used else directUsed
    val free_bytes: Long get() = if (free > 0) free else directFree
    val usage_percent: Float get() = if (percent > 0) percent else directPercent
}

data class DiskIoCounters(
    @SerializedName("read_bytes") val readBytes: Long = 0L,
    @SerializedName("write_bytes") val writeBytes: Long = 0L,
    @SerializedName("read_count") val readCount: Long = 0L,
    @SerializedName("write_count") val writeCount: Long = 0L,
    @SerializedName("read_time") val readTime: Long = 0L,
    @SerializedName("write_time") val writeTime: Long = 0L
)

data class StorageIo(
    @SerializedName("available") val available: Boolean? = null,
    @SerializedName("total") val total: DiskIoCounters? = null,
    @SerializedName("disks") val disks: Map<String, DiskIoCounters>? = null,
    @SerializedName("read_bytes_per_sec") val readBytesPerSec: Double = 0.0,
    @SerializedName("write_bytes_per_sec") val writeBytesPerSec: Double = 0.0,
    @SerializedName("read_bytes_sec") val directReadSec: Double = 0.0,
    @SerializedName("write_bytes_sec") val directWriteSec: Double = 0.0,
    @SerializedName("read_speed") val directReadSpeed: Double = 0.0,
    @SerializedName("write_speed") val directWriteSpeed: Double = 0.0,
    @SerializedName("read_speed_bps") val directReadSpeedBps: Long = 0L,
    @SerializedName("write_speed_bps") val directWriteSpeedBps: Long = 0L,
    @SerializedName("read_bps") val directReadBps: Long = 0L,
    @SerializedName("write_bps") val directWriteBps: Long = 0L,
    // Cumulative counters (e.g. from psutil.disk_io_counters)
    @SerializedName("read_bytes") val readBytes: Long? = null,
    @SerializedName("write_bytes") val writeBytes: Long? = null,
    @SerializedName("total_read_bytes") val totalReadBytes: Long = 0L,
    @SerializedName("total_write_bytes") val totalWriteBytes: Long = 0L,
    @SerializedName("bytes_read") val bytesRead: Long? = null,
    @SerializedName("bytes_written") val bytesWritten: Long? = null
) {
    val precalculatedReadRate: Long?
        get() = when {
            readBytesPerSec > 0.0 -> readBytesPerSec.toLong()
            directReadSec > 0.0 -> directReadSec.toLong()
            directReadSpeed > 0.0 -> directReadSpeed.toLong()
            directReadSpeedBps > 0L -> directReadSpeedBps
            directReadBps > 0L -> directReadBps
            else -> null
        }

    val precalculatedWriteRate: Long?
        get() = when {
            writeBytesPerSec > 0.0 -> writeBytesPerSec.toLong()
            directWriteSec > 0.0 -> directWriteSec.toLong()
            directWriteSpeed > 0.0 -> directWriteSpeed.toLong()
            directWriteSpeedBps > 0L -> directWriteSpeedBps
            directWriteBps > 0L -> directWriteBps
            else -> null
        }

    val cumulativeReadBytes: Long?
        get() = total?.readBytes ?: readBytes ?: bytesRead ?: totalReadBytes.takeIf { it > 0L }

    val cumulativeWriteBytes: Long?
        get() = total?.writeBytes ?: writeBytes ?: bytesWritten ?: totalWriteBytes.takeIf { it > 0L }

    val isAvailable: Boolean
        get() = when {
            available == false -> false
            available == true -> true
            total != null -> true
            cumulativeReadBytes != null -> true
            precalculatedReadRate != null -> true
            else -> false
        }

    val effectiveReadSpeed: Double get() = precalculatedReadRate?.toDouble() ?: 0.0
    val effectiveWriteSpeed: Double get() = precalculatedWriteRate?.toDouble() ?: 0.0
}

data class StorageMetrics(
    val filesystems: List<FilesystemMetric> = emptyList(),
    val io: StorageIo = StorageIo(),
    @SerializedName("read_speed_bps") val directReadSpeed: Long = 0L,
    @SerializedName("write_speed_bps") val directWriteSpeed: Long = 0L,
    @SerializedName("read_bytes_per_sec") val directReadBytesSec: Double = 0.0,
    @SerializedName("write_bytes_per_sec") val directWriteBytesSec: Double = 0.0,
    @SerializedName("read_speed") val directReadSpeedVal: Double = 0.0,
    @SerializedName("write_speed") val directWriteSpeedVal: Double = 0.0,
    @SerializedName("read_bytes") val directReadBytes: Long? = null,
    @SerializedName("write_bytes") val directWriteBytes: Long? = null,
    @SerializedName("total_read_bytes") val directTotalReadBytes: Long? = null,
    @SerializedName("total_write_bytes") val directTotalWriteBytes: Long? = null
) {
    // Computed rate from ViewModel delta calculations
    var calculatedReadBps: Long? = null
    var calculatedWriteBps: Long? = null

    val precalculatedReadRate: Long?
        get() = io.precalculatedReadRate
            ?: directReadSpeed.takeIf { it > 0L }
            ?: directReadBytesSec.takeIf { it > 0.0 }?.toLong()
            ?: directReadSpeedVal.takeIf { it > 0.0 }?.toLong()

    val precalculatedWriteRate: Long?
        get() = io.precalculatedWriteRate
            ?: directWriteSpeed.takeIf { it > 0L }
            ?: directWriteBytesSec.takeIf { it > 0.0 }?.toLong()
            ?: directWriteSpeedVal.takeIf { it > 0.0 }?.toLong()

    val cumulativeReadBytes: Long?
        get() = io.cumulativeReadBytes
            ?: directReadBytes
            ?: directTotalReadBytes?.takeIf { it > 0L }

    val cumulativeWriteBytes: Long?
        get() = io.cumulativeWriteBytes
            ?: directWriteBytes
            ?: directTotalWriteBytes?.takeIf { it > 0L }

    val isIoAvailable: Boolean
        get() = when {
            io.available == false -> false
            io.available == true -> true
            io.isAvailable -> true
            calculatedReadBps != null -> true
            precalculatedReadRate != null -> true
            cumulativeReadBytes != null -> true
            else -> false
        }

    val read_speed_bps: Long
        get() = calculatedReadBps ?: precalculatedReadRate ?: 0L

    val write_speed_bps: Long
        get() = calculatedWriteBps ?: precalculatedWriteRate ?: 0L

    val total_read_bytes: Long
        get() = cumulativeReadBytes ?: 0L

    val total_write_bytes: Long
        get() = cumulativeWriteBytes ?: 0L
}

data class NetworkInterface(
    val name: String = "",
    val state: String = "",
    @SerializedName("is_up") val isUpDirect: Boolean = false,
    val ipv4: String? = null,
    val ipv6: String? = null,
    @SerializedName("ip_address") val ipAddressDirect: String? = null,
    @SerializedName("bytes_sent") val bytes_sent: Long = 0L,
    @SerializedName("bytes_recv") val bytes_recv: Long = 0L,
    @SerializedName("speed_mbps") val speedMbps: Long? = null,
    @SerializedName("download_bytes_per_sec") val downloadBytesPerSec: Double? = null,
    @SerializedName("upload_bytes_per_sec") val uploadBytesPerSec: Double? = null
) {
    val is_up: Boolean get() = isUpDirect || state.equals("up", ignoreCase = true)
    val ip_address: String? get() = ipv4 ?: ipAddressDirect
}

data class InterfaceRateItem(
    @SerializedName("download_bytes_per_sec") val downloadBytesPerSec: Double = 0.0,
    @SerializedName("upload_bytes_per_sec") val uploadBytesPerSec: Double = 0.0,
    @SerializedName("is_up") val isUp: Boolean = false,
    @SerializedName("speed_mbps") val speedMbps: Long? = null
)

data class NetworkRates(
    @SerializedName("upload_bytes_per_sec") val uploadBytesPerSec: Double = 0.0,
    @SerializedName("download_bytes_per_sec") val downloadBytesPerSec: Double = 0.0,
    @SerializedName("raw_upload_bytes_per_sec") val rawUploadBytesPerSec: Double? = null,
    @SerializedName("raw_download_bytes_per_sec") val rawDownloadBytesPerSec: Double? = null,
    @SerializedName("included_interfaces") val includedInterfaces: List<String>? = null,
    @SerializedName("interfaces") val perInterfaceRates: Map<String, InterfaceRateItem>? = null,
    @SerializedName("upload_speed") val directUploadSpeed: Double = 0.0,
    @SerializedName("download_speed") val directDownloadSpeed: Double = 0.0
)

data class NetworkTotals(
    @SerializedName("bytes_sent") val bytesSent: Long = 0L,
    @SerializedName("bytes_recv") val bytesRecv: Long = 0L
)

data class NetworkMetrics(
    val rates: NetworkRates = NetworkRates(),
    val total: NetworkTotals = NetworkTotals(),
    val interfaces: List<NetworkInterface> = emptyList(),
    @SerializedName("upload_speed_bps") val directUpload: Long = 0L,
    @SerializedName("download_speed_bps") val directDownload: Long = 0L,
    @SerializedName("total_sent_bytes") val directSent: Long = 0L,
    @SerializedName("total_recv_bytes") val directRecv: Long = 0L
) {
    @Transient var calculatedDownloadBps: Long? = null
    @Transient var calculatedUploadBps: Long? = null

    val upload_speed_bps: Long get() {
        val calc = calculatedUploadBps
        if (calc != null) return if (calc > 0L) calc else 0L
        val r = if (rates.uploadBytesPerSec > 0) rates.uploadBytesPerSec.toLong()
                else if (rates.directUploadSpeed > 0) rates.directUploadSpeed.toLong()
                else directUpload
        return if (r > 0) r else 0L
    }
    val download_speed_bps: Long get() {
        val calc = calculatedDownloadBps
        if (calc != null) return if (calc > 0L) calc else 0L
        val r = if (rates.downloadBytesPerSec > 0) rates.downloadBytesPerSec.toLong()
                else if (rates.directDownloadSpeed > 0) rates.directDownloadSpeed.toLong()
                else directDownload
        return if (r > 0) r else 0L
    }
    val total_sent_bytes: Long get() = if (total.bytesSent > 0) total.bytesSent else directSent
    val total_recv_bytes: Long get() = if (total.bytesRecv > 0) total.bytesRecv else directRecv
}

data class TemperatureSensor(
    val name: String = "",
    val chip: String = "",
    val label: String = "",
    @SerializedName("current") val directCurrent: Float? = null,
    @SerializedName("current_c") val currentC: Float = 0f,
    @SerializedName("current_temp") val currentTempDirect: Float = 0f,
    @SerializedName("high") val directHigh: Float? = null,
    @SerializedName("high_c") val highC: Float? = null,
    @SerializedName("high_temp") val highTempDirect: Float? = null,
    @SerializedName("critical") val directCritical: Float? = null,
    @SerializedName("critical_c") val criticalC: Float? = null,
    @SerializedName("critical_temp") val criticalTempDirect: Float? = null
) {
    val current_temp: Float get() {
        val c = directCurrent ?: if (currentC > 0) currentC else currentTempDirect
        return if (c.isFinite() && c >= 0f) c else 0f
    }
    val high_temp: Float? get() = directHigh ?: highC ?: highTempDirect
    val critical_temp: Float? get() = directCritical ?: criticalC ?: criticalTempDirect
}

data class TemperatureMetrics(
    @SerializedName("sensors_available") val sensorsAvailable: Boolean = true,
    val available: Boolean = true,
    @SerializedName("max_c") val maxC: Float = 0f,
    val sensors: List<TemperatureSensor> = emptyList(),
    @SerializedName("cpu") val directCpu: Float? = null,
    @SerializedName("package_temp") val packageTemp: Float? = null,
    @SerializedName("gpu") val directGpu: Float? = null,
    @SerializedName("nvme") val directNvme: Float? = null
) {
    val cpu: Float? get() {
        val candidate = directCpu ?: packageTemp
        if (candidate != null && candidate.isFinite() && candidate > 0f) return candidate
        // If sensors are unavailable or empty, do not fabricate or return 0°C
        if (!sensorsAvailable || sensors.isNullOrEmpty()) {
            return null
        }
        val pkg = sensors.firstOrNull {
            it.label.contains("Package", ignoreCase = true) ||
            it.chip.contains("coretemp", ignoreCase = true) ||
            it.name.contains("package", ignoreCase = true) ||
            it.name.contains("cpu", ignoreCase = true)
        }
        val candidateSensor = pkg?.current_temp ?: sensors.firstOrNull()?.current_temp
        if (candidateSensor != null && candidateSensor.isFinite() && candidateSensor > 0f) return candidateSensor
        return if (maxC.isFinite() && maxC > 0f) maxC else null
    }
    val gpu: Float? get() {
        if (directGpu != null && directGpu.isFinite() && directGpu > 0f) return directGpu
        val s = (sensors ?: emptyList()).firstOrNull { it.chip.contains("gpu", ignoreCase = true) || it.label.contains("gpu", ignoreCase = true) || it.name.contains("gpu", ignoreCase = true) }
        return s?.current_temp?.takeIf { it.isFinite() && it > 0f }
    }
    val nvme: Float? get() {
        if (directNvme != null && directNvme.isFinite() && directNvme > 0f) return directNvme
        val s = (sensors ?: emptyList()).firstOrNull { it.chip.contains("nvme", ignoreCase = true) || it.label.contains("nvme", ignoreCase = true) || it.name.contains("nvme", ignoreCase = true) }
        return s?.current_temp?.takeIf { it.isFinite() && it > 0f }
    }
}

data class DockerSummary(
    val total: Int = 0,
    val running: Int = 0,
    val stopped: Int = 0,
    val unhealthy: Int = 0
)

data class ContainersSummary(
    val total: Int = 0,
    val running: Int = 0,
    val stopped: Int = 0
)

data class DockerData(
    val available: Boolean = true,
    val summary: DockerSummary = DockerSummary(),
    val containers: List<ContainerItem> = emptyList()
)

data class SystemOverview(
    val host: HostInfo = HostInfo(),
    val system: HostInfo = HostInfo(),
    val cpu: CpuMetrics = CpuMetrics(),
    val memory: MemoryMetrics = MemoryMetrics(),
    val storage: StorageMetrics = StorageMetrics(),
    val network: NetworkMetrics = NetworkMetrics(),
    val temperature: TemperatureMetrics? = TemperatureMetrics(),
    val docker: DockerData = DockerData(),
    val summary: DockerSummary = DockerSummary(),
    @SerializedName("containers_summary") val directContainersSummary: ContainersSummary = ContainersSummary(),
    val uptime: Double = 0.0,
    @JsonAdapter(SafeTimestampDeserializer::class)
    val timestamp: Long = 0L,
    val active_alerts_count: Int = 0
) {
    val resolvedHost: HostInfo get() = if (host.hostname.isNotEmpty()) host else system
    val resolvedTimestamp: Long get() {
        if (timestamp > 0L) return SafeTimestampDeserializer.normalizeToSeconds(timestamp)
        if (cpu.timestamp > 0L) return SafeTimestampDeserializer.normalizeToSeconds(cpu.timestamp)
        if (memory.timestamp > 0L) return SafeTimestampDeserializer.normalizeToSeconds(memory.timestamp)
        if (resolvedHost.timestamp > 0L) return SafeTimestampDeserializer.normalizeToSeconds(resolvedHost.timestamp)
        if (resolvedHost.effectiveUptime > 0 || uptime > 0.0) return (System.currentTimeMillis() / 1000L)
        return 0L
    }
    val containers_summary: ContainersSummary get() {
        if (docker.summary.total > 0) {
            return ContainersSummary(docker.summary.total, docker.summary.running, docker.summary.stopped)
        }
        if (summary.total > 0) {
            return ContainersSummary(summary.total, summary.running, summary.stopped)
        }
        return directContainersSummary
    }
}

data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val username: String = "",
    @SerializedName("user") val directUser: String = "",
    @SerializedName("cpu_percent") val cpu_percent: Float = 0f,
    @SerializedName("memory_percent") val memory_percent: Float = 0f,
    @SerializedName("memory_bytes") val memoryBytes: Long = 0L,
    @SerializedName("memory_rss_bytes") val directMemoryBytes: Long = 0L,
    val status: String = "",
    val threads: Int = 0
) {
    val user: String get() = username.ifEmpty { directUser }
    val memory_rss_bytes: Long get() = if (memoryBytes > 0) memoryBytes else directMemoryBytes
}

data class ProcessesResponse(
    val total: Int = 0,
    val sort: String = "cpu",
    val limit: Int = 25,
    val processes: List<ProcessInfo> = emptyList(),
    val timestamp: Long = 0L
)

data class MetricsPacket(
    val type: String = "",
    val timestamp: Any? = null,
    val uptime: Double? = null,
    val cpu: CpuMetrics? = null,
    val memory: MemoryMetrics? = null,
    val storage: StorageMetrics? = null,
    val network: NetworkMetrics? = null,
    val temperature: TemperatureMetrics? = null,
    val docker: DockerData? = null,
    val data: SystemOverview? = null
)
