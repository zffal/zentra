package com.example.serverdashboard

import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.CpuMetrics
import com.example.serverdashboard.data.model.SystemOverview
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class TelemetryAndTailscaleTest {

    private val gson = Gson()

    @Test
    fun testTailscaleUrlNormalization() {
        // IP with port
        assertEquals("http://100.87.90.69:8080", ApiClient.normalizeUrl("100.87.90.69:8080"))
        // Trailing slashes
        assertEquals("http://100.87.90.69:8080", ApiClient.normalizeUrl("http://100.87.90.69:8080/"))
        assertEquals("http://100.87.90.69:8080", ApiClient.normalizeUrl("http://100.87.90.69:8080///"))
        // Trailing /api
        assertEquals("http://100.87.90.69:8080", ApiClient.normalizeUrl("http://100.87.90.69:8080/api"))
        assertEquals("http://100.87.90.69:8080", ApiClient.normalizeUrl("http://100.87.90.69:8080/api/"))
        // Tailscale MagicDNS
        assertEquals("http://my-ubuntu.ts.net:8080", ApiClient.normalizeUrl("my-ubuntu.ts.net:8080"))
        assertEquals("https://my-ubuntu.ts.net:8080", ApiClient.normalizeUrl("https://my-ubuntu.ts.net:8080/"))
        assertEquals("http://homelab.tailscale.net:8765", ApiClient.normalizeUrl("homelab.tailscale.net:8765"))
    }

    @Test
    fun testPerCoreDeserializationFromObjectList() {
        // Backend system_metrics.py sends list of objects: [{"core_id": 0, "usage_percent": 15.2}]
        val json = """
            {
                "overall_usage": 22.5,
                "logical_cores": 4,
                "per_core": [
                    {"core_id": 0, "usage_percent": 15.2},
                    {"core_id": 1, "usage_percent": 30.0},
                    {"core_id": 2, "usage_percent": 10.5},
                    {"core_id": 3, "usage_percent": 34.3}
                ],
                "load_avg": [1.25, 0.95, 0.80]
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(22.5f, cpu.overall_usage, 0.01f)
        assertEquals(4, cpu.per_core_usage.size)
        assertEquals(15.2f, cpu.per_core_usage[0], 0.01f)
        assertEquals(30.0f, cpu.per_core_usage[1], 0.01f)
        assertEquals(10.5f, cpu.per_core_usage[2], 0.01f)
        assertEquals(34.3f, cpu.per_core_usage[3], 0.01f)
        assertEquals(3, cpu.load_avg.size)
        assertEquals(1.25f, cpu.load_avg[0], 0.01f)
    }

    @Test
    fun testPerCoreDeserializationFromFloatList() {
        // Other backends send list of floats directly: [15.2, 30.0]
        val json = """
            {
                "usage_percent": 18.0,
                "per_core": [12.0, 24.0]
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(18.0f, cpu.overall_usage, 0.01f)
        assertEquals(2, cpu.per_core_usage.size)
        assertEquals(12.0f, cpu.per_core_usage[0], 0.01f)
        assertEquals(24.0f, cpu.per_core_usage[1], 0.01f)
    }

    @Test
    fun testSystemOverviewTimestampFloatAndStringTolerance() {
        // Float timestamp from backend broadcaster or string timestamp
        val jsonWithFloatTs = """
            {
                "timestamp": 1728501234.56,
                "cpu": { "overall_usage": 10.0 }
            }
        """.trimIndent()

        val overviewFloat = gson.fromJson(jsonWithFloatTs, SystemOverview::class.java)
        assertNotNull(overviewFloat)
        assertEquals(1728501234L, overviewFloat.resolvedTimestamp)

        val jsonWithStringTs = """
            {
                "timestamp": "1728501234",
                "cpu": { "overall_usage": 10.0 }
            }
        """.trimIndent()

        val overviewString = gson.fromJson(jsonWithStringTs, SystemOverview::class.java)
        assertNotNull(overviewString)
        assertEquals(1728501234L, overviewString.resolvedTimestamp)

        val jsonWithNullTs = """
            {
                "timestamp": null,
                "cpu": { "overall_usage": 10.0 }
            }
        """.trimIndent()

        val overviewNull = gson.fromJson(jsonWithNullTs, SystemOverview::class.java)
        assertNotNull(overviewNull)
        assertEquals(0L, overviewNull.resolvedTimestamp)
    }

    @Test
    fun testNaNAndInfiniteCoercion() {
        val cpu = CpuMetrics(
            usagePercent = Float.NaN,
            directOverallUsage = Float.POSITIVE_INFINITY
        )
        // overall_usage must be finite and >= 0
        assertEquals(0f, cpu.overall_usage, 0.001f)
    }
}
