package com.example.serverdashboard.ui.util

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SpeedHysteresisTracker {
    var lastUnitIndex: Int = -1

    fun reset() {
        lastUnitIndex = -1
    }
}

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    /**
     * Formats network speed with optional bits conversion, auto-scaling, one decimal,
     * null/NaN safety, and boundary hysteresis to prevent rapid flickering between units.
     */
    fun formatSpeed(
        bytesPerSec: Long?,
        useBits: Boolean = false,
        tracker: SpeedHysteresisTracker? = null
    ): String {
        if (bytesPerSec == null) return "--"
        val dBytes = bytesPerSec.toDouble()
        if (!dBytes.isFinite()) return "--"
        if (bytesPerSec <= 0L) {
            tracker?.reset()
            return if (useBits) "0 bps" else "0 B/s"
        }

        val byteUnits = arrayOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s")
        val bitUnits = arrayOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")

        val units = if (useBits) bitUnits else byteUnits
        val factor = if (useBits) 1000.0 else 1024.0
        val rawRate = if (useBits) dBytes * 8.0 else dBytes

        if (!rawRate.isFinite() || rawRate <= 0.0) {
            tracker?.reset()
            return if (useBits) "0 bps" else "0 B/s"
        }

        val prev = tracker?.lastUnitIndex ?: -1
        val unitIndex: Int

        if (prev in 0 until units.size) {
            val valueInPrev = rawRate / Math.pow(factor, prev.toDouble())
            unitIndex = when {
                // Must exceed 1.05 * factor to step up
                prev < units.size - 1 && valueInPrev >= (factor * 1.05) -> {
                    val candidate = (Math.log10(rawRate) / Math.log10(factor)).toInt()
                    candidate.coerceIn(prev + 1, units.size - 1)
                }
                // Must fall below 0.95 to step down
                prev > 0 && valueInPrev < 0.95 -> {
                    val candidate = (Math.log10(rawRate) / Math.log10(factor)).toInt()
                    candidate.coerceIn(0, prev - 1)
                }
                // Remain in current unit to eliminate flicker
                else -> prev
            }
        } else {
            // First evaluation or untracked: standard boundary
            val digitGroups = (Math.log10(rawRate) / Math.log10(factor)).toInt()
            unitIndex = digitGroups.coerceIn(0, units.size - 1)
        }

        tracker?.lastUnitIndex = unitIndex
        val value = rawRate / Math.pow(factor, unitIndex.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return formatSpeed(bytesPerSec, useBits = false, tracker = null)
    }

    /**
     * Formats an ISO-like date string (e.g. "2026-10-10") using the specified server timezone.
     */
    fun formatUsageDate(dateStr: String, tzId: String? = null): String {
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = if (!tzId.isNullOrBlank()) TimeZone.getTimeZone(tzId) else TimeZone.getDefault()
            }
            val d = inFormat.parse(dateStr) ?: return dateStr
            val outFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
                timeZone = inFormat.timeZone
            }
            outFormat.format(d)
        } catch (_: Exception) {
            dateStr
        }
    }

    /**
     * Short label for graph bars (e.g. "Oct 10").
     */
    fun formatUsageDateShort(dateStr: String, tzId: String? = null): String {
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = if (!tzId.isNullOrBlank()) TimeZone.getTimeZone(tzId) else TimeZone.getDefault()
            }
            val d = inFormat.parse(dateStr) ?: return dateStr
            val outFormat = SimpleDateFormat("MMM dd", Locale.US).apply {
                timeZone = inFormat.timeZone
            }
            outFormat.format(d)
        } catch (_: Exception) {
            dateStr
        }
    }

    fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun formatPercent(percent: Float): String {
        val safe = if (percent.isFinite() && percent >= 0f) percent else 0f
        return String.format(Locale.US, "%.1f%%", safe)
    }

    fun extractHost(rawUrl: String): String {
        return try {
            val uri = URI(rawUrl)
            val host = uri.host ?: rawUrl
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$host$port"
        } catch (e: Exception) {
            rawUrl.replace("http://", "").replace("https://", "").replace("/", "")
        }
    }

    fun formatEpochTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Just now"
        val seconds = if (timestamp > 1_000_000_000_000L) timestamp / 1000L else timestamp
        val now = System.currentTimeMillis() / 1000L
        val diff = (now - seconds).coerceAtLeast(0)

        return when {
            diff < 60 -> "${diff}s ago"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            else -> "${diff / 86400}d ago"
        }
    }

    fun formatIsoTime(isoString: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(isoString.substringBefore(".")) ?: return isoString
            val now = System.currentTimeMillis()
            val diff = (now - date.time) / 1000

            when {
                diff < 60 -> "${diff}s ago"
                diff < 3600 -> "${diff / 60}m ago"
                diff < 86400 -> "${diff / 3600}h ago"
                else -> "${diff / 86400}d ago"
            }
        } catch (e: Exception) {
            isoString
        }
    }

    fun formatTimestamp(timestamp: Long): String = formatEpochTime(timestamp)

    fun formatTimestamp(isoString: String): String = formatIsoTime(isoString)
}
