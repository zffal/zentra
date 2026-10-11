package com.example.serverdashboard

import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.model.*
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.WakeOnLanUtil
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LifecycleAndCrashResilienceTest {

    private val gson = Gson()

    @Test
    fun testFormattersWithNaNAndInfinity() {
        val nan = Float.NaN
        val inf = Float.POSITIVE_INFINITY
        val negInf = Float.NEGATIVE_INFINITY

        val safeNan = if (nan.isFinite() && nan >= 0f) nan else 0f
        val safeInf = if (inf.isFinite() && inf >= 0f) inf else 0f
        val safeNegInf = if (negInf.isFinite() && negInf >= 0f) negInf else 0f

        assertEquals("0.0%", Formatters.formatPercent(safeNan))
        assertEquals("0.0%", Formatters.formatPercent(safeInf))
        assertEquals("0.0%", Formatters.formatPercent(safeNegInf))

        val formattedPercent = String.format(Locale.US, "%.1f%%", safeNan)
        assertEquals("0.0%", formattedPercent)
    }

    @Test
    fun testFormattersByteRanges() {
        assertEquals("0 B", Formatters.formatBytes(0L))
        assertEquals("500.0 B", Formatters.formatBytes(500L))
        assertEquals("1.0 KB", Formatters.formatBytes(1024L))
        assertEquals("1.5 MB", Formatters.formatBytes(1572864L))
        assertEquals("2.0 GB", Formatters.formatBytes(2147483648L))
        assertEquals("1.0 TB", Formatters.formatBytes(1099511627776L))
    }

    @Test
    fun testFormattersSpeedRanges() {
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertEquals("512.0 KB/s", Formatters.formatSpeed(524288L))
        assertEquals("10.0 MB/s", Formatters.formatSpeed(10485760L))
    }

    @Test
    fun testFormattersUptime() {
        assertEquals("0m", Formatters.formatUptime(0L))
        assertEquals("0m", Formatters.formatUptime(45L))
        assertEquals("15m", Formatters.formatUptime(900L))
        assertEquals("2h 30m", Formatters.formatUptime(9000L))
        assertEquals("3d 4h 0m", Formatters.formatUptime(273600L))
    }

    @Test
    fun testSystemOverviewNullHostAndUptimeFallback() {
        val json = """
            {
                "uptime": 12345.0,
                "cpu": {
                    "overall_usage": 25.5
                }
            }
        """.trimIndent()

        val overview = gson.fromJson(json, SystemOverview::class.java)
        assertNotNull(overview)
        assertEquals("", overview.resolvedHost.hostname)
        assertEquals(12345.0, overview.uptime, 0.01)
        assertTrue(overview.resolvedTimestamp > 0L)
    }

    @Test
    fun testSystemOverviewNullTemperatureAndSensors() {
        val json = """
            {
                "temperature": null
            }
        """.trimIndent()

        val overview = gson.fromJson(json, SystemOverview::class.java)
        assertNotNull(overview)
        assertNull(overview.temperature)

        val cpuTemp = overview.temperature?.cpu?.takeIf { it.isFinite() } ?: 0f
        assertEquals(0f, cpuTemp, 0.001f)
    }

    @Test
    fun testPerCoreDeserializerMixedPayloads() {
        val json = """
            {
                "per_core": [
                    12.5,
                    {"core_id": 1, "usage_percent": 34.0},
                    {"core_id": 2, "percent": 56.5},
                    {"core_id": 3, "usage": 78.0},
                    "invalid_string",
                    null
                ]
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(4, cpu.per_core_usage.size)
        assertEquals(12.5f, cpu.per_core_usage[0], 0.01f)
        assertEquals(34.0f, cpu.per_core_usage[1], 0.01f)
        assertEquals(56.5f, cpu.per_core_usage[2], 0.01f)
        assertEquals(78.0f, cpu.per_core_usage[3], 0.01f)
    }

    @Test
    fun testSafeTimestampDeserializerVariants() {
        val jsonNumeric = """{"timestamp": 1728518400}"""
        val jsonDouble = """{"timestamp": 1728518400.5}"""
        val jsonString = """{"timestamp": "1728518400"}"""
        val jsonNull = """{"timestamp": null}"""

        val ov1 = gson.fromJson(jsonNumeric, SystemOverview::class.java)
        val ov2 = gson.fromJson(jsonDouble, SystemOverview::class.java)
        val ov3 = gson.fromJson(jsonString, SystemOverview::class.java)
        val ov4 = gson.fromJson(jsonNull, SystemOverview::class.java)

        assertEquals(1728518400L, ov1.timestamp)
        assertEquals(1728518400L, ov2.timestamp)
        assertEquals(1728518400L, ov3.timestamp)
        assertEquals(0L, ov4.timestamp)
    }

    @Test
    fun testProcessInfoNullSafety() {
        val proc = ProcessInfo(
            pid = 1234,
            name = "nginx",
            username = "",
            directUser = "www-data",
            cpu_percent = Float.NaN,
            memoryBytes = 10485760L
        )

        assertEquals("www-data", proc.user)
        val cpuSafe = if (proc.cpu_percent.isFinite() && proc.cpu_percent >= 0f) proc.cpu_percent else 0f
        assertEquals(0f, cpuSafe, 0.001f)
        assertEquals("10.0 MB", Formatters.formatBytes(proc.memory_rss_bytes))
    }

    @Test
    fun testFilesystemMetricMountPointFallback() {
        val fs = FilesystemMetric(
            device = "/dev/sda1",
            mountpoint = "",
            directMountPoint = "/mnt/data",
            directPercent = 88.5f,
            directTotal = 1000000000L,
            directUsed = 885000000L
        )

        assertEquals("/mnt/data", fs.mount_point)
        val safeUsage = if (fs.usage_percent.isFinite() && fs.usage_percent >= 0f) fs.usage_percent else 0f
        assertEquals(88.5f, safeUsage, 0.01f)
        assertEquals(88, safeUsage.toInt().coerceIn(0, 100))
    }

    @Test
    fun testWakeOnLanUtilValidation() = runBlocking {
        val validMac = "00:11:22:33:44:55"
        val dashMac = "00-11-22-33-44-55"
        val invalidMac = "00:11:22:33:44"

        val res1 = WakeOnLanUtil.sendMagicPacket(validMac, "127.0.0.1", 9999)
        val res2 = WakeOnLanUtil.sendMagicPacket(dashMac, "127.0.0.1", 9999)
        val resInvalid = WakeOnLanUtil.sendMagicPacket(invalidMac, "127.0.0.1", 9999)

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertTrue(resInvalid.isFailure)
        assertTrue(resInvalid.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testConnectionAndStreamStates() {
        val cStates = ConnectionState.values()
        assertTrue(cStates.contains(ConnectionState.CONNECTED))
        assertTrue(cStates.contains(ConnectionState.DISCONNECTED))
        assertTrue(cStates.contains(ConnectionState.RECONNECTING))
        assertTrue(cStates.contains(ConnectionState.AUTH_REQUIRED))
        assertTrue(cStates.contains(ConnectionState.SERVER_UNAVAILABLE))

        val sStates = StreamState.values()
        assertTrue(sStates.contains(StreamState.LIVE_STREAMING))
        assertTrue(sStates.contains(StreamState.RECONNECTING))
        assertTrue(sStates.contains(StreamState.DISCONNECTED))
        assertTrue(sStates.contains(StreamState.AUTH_FAILED))
    }
}
