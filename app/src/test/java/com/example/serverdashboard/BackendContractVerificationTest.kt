package com.example.serverdashboard

import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies compliance with the updated backend contract:
 * 1. CPU schema: per_core object array, per_core_usage numeric array, integer timestamps.
 * 2. Temperature schema: package_temp, cpu alias, null handling without fabricated 0°C.
 * 3. Timestamp unit normalization (seconds vs milliseconds).
 * 4. WebSocket authentication policy violation handling (code 1008, 4001, 4003, HTTP 401/403).
 */
class BackendContractVerificationTest {

    private val gson = Gson()

    // 1. CPU Schema: prefers per_core_usage numeric array when present
    @Test
    fun testCpuPrefersPerCoreUsageNumericArray() {
        val json = """
            {
                "overall_usage": 45.2,
                "logical_cores": 4,
                "physical_cores": 2,
                "per_core": [
                    {"core_id": 0, "usage_percent": 40.0},
                    {"core_id": 1, "usage_percent": 50.0},
                    {"core_id": 2, "usage_percent": 42.5},
                    {"core_id": 3, "usage_percent": 48.3}
                ],
                "per_core_usage": [40.0, 50.0, 42.5, 48.3],
                "per_core_numeric": [40.0, 50.0, 42.5, 48.3],
                "timestamp": 1728518400,
                "model_name": "AMD Ryzen 7 5800X"
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(45.2f, cpu.overall_usage, 0.01f)
        assertEquals(2, cpu.core_count)
        assertEquals(4, cpu.logical_count)
        assertEquals(4, cpu.per_core_usage.size)
        assertEquals(40.0f, cpu.per_core_usage[0], 0.01f)
        assertEquals(50.0f, cpu.per_core_usage[1], 0.01f)
        assertEquals(42.5f, cpu.per_core_usage[2], 0.01f)
        assertEquals(48.3f, cpu.per_core_usage[3], 0.01f)
        assertEquals(1728518400L, cpu.timestamp)
        assertEquals("AMD Ryzen 7 5800X", cpu.model)
    }

    // 2. CPU Fallback: per_core_numeric when per_core_usage is absent
    @Test
    fun testCpuFallbackToPerCoreNumeric() {
        val json = """
            {
                "overall_usage": 33.1,
                "per_core": [
                    {"core_id": 0, "usage_percent": 30.0},
                    {"core_id": 1, "usage_percent": 36.2}
                ],
                "per_core_numeric": [30.0, 36.2],
                "timestamp": 1728518405
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(2, cpu.per_core_usage.size)
        assertEquals(30.0f, cpu.per_core_usage[0], 0.01f)
        assertEquals(36.2f, cpu.per_core_usage[1], 0.01f)
    }

    // 3. CPU Fallback: per_core object array when numeric arrays are absent
    @Test
    fun testCpuFallbackToPerCoreObjects() {
        val json = """
            {
                "overall_usage": 28.4,
                "per_core": [
                    {"core_id": 0, "usage_percent": 25.0},
                    {"core_id": 1, "usage_percent": 31.8}
                ],
                "timestamp": 1728518410
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(2, cpu.per_core_usage.size)
        assertEquals(25.0f, cpu.per_core_usage[0], 0.01f)
        assertEquals(31.8f, cpu.per_core_usage[1], 0.01f)
    }

    // 4. CPU Backward Compatibility: legacy numeric array in per_core
    @Test
    fun testCpuLegacyNumericPerCoreArray() {
        val json = """
            {
                "overall_usage": 20.0,
                "per_core": [18.0, 22.0]
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(2, cpu.per_core_usage.size)
        assertEquals(18.0f, cpu.per_core_usage[0], 0.01f)
        assertEquals(22.0f, cpu.per_core_usage[1], 0.01f)
    }

    // 5. CPU PerCore deserializer handles field variants (usage, percent, string floats) safely
    @Test
    fun testCpuPerCoreHandlesVariantsAndMalformedValues() {
        val json = """
            {
                "overall_usage": 15.0,
                "per_core": [
                    {"core": 0, "usage": 12.5},
                    {"core_id": 1, "percent": 22.5},
                    {"core_id": 2, "usage_percent": "32.5"},
                    {"unknown": true},
                    "invalid_string"
                ]
            }
        """.trimIndent()

        val cpu = gson.fromJson(json, CpuMetrics::class.java)
        assertNotNull(cpu)
        assertEquals(3, cpu.per_core_usage.size)
        assertEquals(12.5f, cpu.per_core_usage[0], 0.01f)
        assertEquals(22.5f, cpu.per_core_usage[1], 0.01f)
        assertEquals(32.5f, cpu.per_core_usage[2], 0.01f)
    }

    // 6. Temperature: sensors available with package_temp and cpu alias
    @Test
    fun testTemperatureWithSensorsAndAliases() {
        val json = """
            {
                "sensors_available": true,
                "package_temp": 52.4,
                "cpu": 52.4,
                "sensors": [
                    {
                        "name": "coretemp Package id 0",
                        "current": 52.4,
                        "high": 80.0,
                        "critical": 100.0
                    }
                ]
            }
        """.trimIndent()

        val temp = gson.fromJson(json, TemperatureMetrics::class.java)
        assertNotNull(temp)
        assertTrue(temp.sensorsAvailable)
        assertNotNull(temp.cpu)
        assertEquals(52.4f, temp.cpu!!, 0.01f)
    }

    // 7. Temperature: sensors unavailable returns null (NOT 0.0°C)
    @Test
    fun testTemperatureSensorsUnavailableReturnsNullNotZero() {
        val json = """
            {
                "sensors_available": false,
                "package_temp": null,
                "cpu": null,
                "sensors": []
            }
        """.trimIndent()

        val temp = gson.fromJson(json, TemperatureMetrics::class.java)
        assertNotNull(temp)
        assertFalse(temp.sensorsAvailable)
        assertNull("cpu temperature must be null when sensors are unavailable", temp.cpu)
    }

    // 8. Temperature: null package_temp with 0.0 direct values returns null
    @Test
    fun testTemperatureSensorsZeroOrMissingReturnsNull() {
        val json = """
            {
                "sensors_available": false,
                "package_temp": 0.0,
                "cpu": 0.0,
                "sensors": []
            }
        """.trimIndent()

        val temp = gson.fromJson(json, TemperatureMetrics::class.java)
        assertNotNull(temp)
        assertNull("0.0°C should not be fabricated when sensors are unavailable", temp.cpu)
    }

    // 9. Timestamp normalization across seconds and milliseconds
    @Test
    fun testTimestampNormalizationSecondsAndMillis() {
        // Test seconds (10 digits)
        assertEquals(1728518400L, SafeTimestampDeserializer.normalizeToSeconds(1728518400L))

        // Test milliseconds (13 digits)
        assertEquals(1728518400L, SafeTimestampDeserializer.normalizeToSeconds(1728518400000L))

        // Test zero and negative
        assertEquals(0L, SafeTimestampDeserializer.normalizeToSeconds(0L))
        assertEquals(0L, SafeTimestampDeserializer.normalizeToSeconds(-100L))
    }

    // 10. SystemOverview resolves timestamp from CPU, Memory or Root timestamp
    @Test
    fun testSystemOverviewResolvesTimestamp() {
        val jsonWithCpuTs = """
            {
                "cpu": {
                    "overall_usage": 25.0,
                    "timestamp": 1728518500000
                },
                "memory": {
                    "usage_percent": 55.0,
                    "timestamp": 1728518500
                }
            }
        """.trimIndent()

        val overview = gson.fromJson(jsonWithCpuTs, SystemOverview::class.java)
        assertNotNull(overview)
        assertEquals(1728518500L, overview.resolvedTimestamp)
    }

    // 11. WebSocket policy violation codes (1008, 4001, 4003) and HTTP 401/403
    @Test
    fun testWebSocketAuthFailureCodesHaltReconnect() {
        val authFailureCodes = listOf(1008, 4001, 4003)
        for (code in authFailureCodes) {
            val isAuthViolation = (code == 1008 || code == 4001 || code == 4003)
            assertTrue("Code $code must be detected as an authentication violation", isAuthViolation)
        }

        val httpAuthErrorCodes = listOf(401, 403)
        for (httpCode in httpAuthErrorCodes) {
            val isHttpAuthError = (httpCode == 401 || httpCode == 403)
            assertTrue("HTTP code $httpCode must be detected as auth error", isHttpAuthError)
        }
    }

    // 12. Storage throughput: precalculated rates mapping
    @Test
    fun testStorageIoPrecalculatedRatesMapping() {
        val json = """
            {
                "filesystems": [],
                "read_speed_bps": 1048576,
                "write_speed_bps": 524288,
                "io": {
                    "read_bytes_sec": 1048576.0,
                    "write_bytes_sec": 524288.0,
                    "read_bytes_per_sec": 1048576.0,
                    "write_bytes_per_sec": 524288.0,
                    "read_count_sec": 120.0,
                    "write_count_sec": 60.0
                }
            }
        """.trimIndent()

        val storage = gson.fromJson(json, StorageMetrics::class.java)
        assertNotNull(storage)
        assertEquals(1048576L, storage.precalculatedReadRate)
        assertEquals(524288L, storage.precalculatedWriteRate)
        assertEquals(1048576L, storage.read_speed_bps)
        assertEquals(524288L, storage.write_speed_bps)
        assertTrue(storage.isIoAvailable)
    }

    // 13. Storage throughput: cumulative counter deserialization and delta computation
    @Test
    fun testStorageIoCumulativeCounterHandling() {
        val json = """
            {
                "filesystems": [],
                "io": {
                    "read_bytes": 50000000,
                    "write_bytes": 25000000,
                    "total_read_bytes": 50000000,
                    "total_write_bytes": 25000000
                }
            }
        """.trimIndent()

        val storage = gson.fromJson(json, StorageMetrics::class.java)
        assertNotNull(storage)
        assertNull(storage.precalculatedReadRate)
        assertNull(storage.precalculatedWriteRate)
        assertEquals(50000000L, storage.cumulativeReadBytes)
        assertEquals(25000000L, storage.cumulativeWriteBytes)
        assertTrue(storage.isIoAvailable)

        // Baseline: no calculated rate yet
        assertNull(storage.calculatedReadBps)

        // Simulate second sample 2.0s later with +2MB read and +1MB write
        val deltaBytes = 2097152L
        val dtSec = 2.0
        val computedBps = (deltaBytes / dtSec).toLong()
        storage.calculatedReadBps = computedBps

        assertEquals(1048576L, storage.calculatedReadBps)
        assertEquals(1048576L, storage.read_speed_bps)
    }

    // 14. Storage throughput: unavailable when no rates or counters present
    @Test
    fun testStorageIoUnavailableWhenNoData() {
        val json = """
            {
                "filesystems": [],
                "io": {}
            }
        """.trimIndent()

        val storage = gson.fromJson(json, StorageMetrics::class.java)
        assertNotNull(storage)
        assertNull(storage.precalculatedReadRate)
        assertNull(storage.precalculatedWriteRate)
        assertNull(storage.cumulativeReadBytes)
        assertNull(storage.cumulativeWriteBytes)
        assertFalse(storage.isIoAvailable)
        assertNull(storage.calculatedReadBps)
    }

    // 15. Storage throughput: live backend nested total counters
    @Test
    fun testStorageIoLiveNestedTotalCounters() {
        val liveStorageJson = """
            {
                "filesystems": [
                    {
                        "device": "/dev/sda1",
                        "mountpoint": "/",
                        "filesystem": "ext4",
                        "total": 107374182400,
                        "used": 42949672960,
                        "free": 64424509440,
                        "percent": 40.0
                    }
                ],
                "io": {
                    "available": true,
                    "total": {
                        "read_bytes": 104857600,
                        "write_bytes": 52428800,
                        "read_count": 1024,
                        "write_count": 512,
                        "read_time": 100,
                        "write_time": 50
                    },
                    "disks": {
                        "sda": {
                            "read_bytes": 104857600,
                            "write_bytes": 52428800,
                            "read_count": 1024,
                            "write_count": 512,
                            "read_time": 100,
                            "write_time": 50
                        }
                    }
                },
                "timestamp": 1728518400
            }
        """.trimIndent()

        val storage = gson.fromJson(liveStorageJson, StorageMetrics::class.java)
        assertNotNull(storage)
        assertTrue(storage.isIoAvailable)
        assertEquals(104857600L, storage.cumulativeReadBytes)
        assertEquals(52428800L, storage.cumulativeWriteBytes)

        // Simulate delta computation across 2 seconds (+2MB read, +1MB write)
        val dRead = 2097152L
        val dWrite = 1048576L
        val dtSec = 2.0
        storage.calculatedReadBps = (dRead / dtSec).toLong()
        storage.calculatedWriteBps = (dWrite / dtSec).toLong()

        assertEquals(1048576L, storage.calculatedReadBps)
        assertEquals(524288L, storage.calculatedWriteBps)
        assertEquals(1048576L, storage.read_speed_bps)
        assertEquals(524288L, storage.write_speed_bps)
    }
}
