package com.example.serverdashboard

import com.example.serverdashboard.data.util.CrashDiagnosticLogger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CrashDiagnosticLoggerTest {

    @Before
    fun setUp() {
        CrashDiagnosticLogger.clearRecords()
        CrashDiagnosticLogger.setAppVersionForTesting("1.0.0-test")
    }

    @Test
    fun testIpAddressRedaction() {
        val input = "Connection refused to 192.168.1.100:8765 and 100.87.90.69"
        val sanitized = CrashDiagnosticLogger.sanitize(input)
        assertFalse(sanitized.contains("192.168.1.100"))
        assertFalse(sanitized.contains("100.87.90.69"))
        assertTrue(sanitized.contains("[REDACTED_IP]"))
    }

    @Test
    fun testTailscaleHostnameRedaction() {
        val input = "Failed to resolve ubuntu-server.ts.net:8765 or lab.tailscale.net"
        val sanitized = CrashDiagnosticLogger.sanitize(input)
        assertFalse(sanitized.contains("ubuntu-server.ts.net"))
        assertFalse(sanitized.contains("lab.tailscale.net"))
        assertTrue(sanitized.contains("[REDACTED_HOST]"))
    }

    @Test
    fun testTokenAndJwtRedaction() {
        val input = "Header: Bearer mySecretToken123 with jwt eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature"
        val sanitized = CrashDiagnosticLogger.sanitize(input)
        assertFalse(sanitized.contains("mySecretToken123"))
        assertFalse(sanitized.contains("doNotLeakThisSignature"))
        assertTrue(sanitized.contains("Bearer [REDACTED_TOKEN]"))
        assertTrue(sanitized.contains("[REDACTED_JWT]"))
    }

    @Test
    fun testQueryParamAndJsonSecretRedaction() {
        val url = "http://server/api?token=secretKey999&password=mySuperPassword"
        val sanitizedUrl = CrashDiagnosticLogger.sanitize(url)
        assertFalse(sanitizedUrl.contains("secretKey999"))
        assertFalse(sanitizedUrl.contains("mySuperPassword"))
        assertTrue(sanitizedUrl.contains("token=[REDACTED]"))
        assertTrue(sanitizedUrl.contains("password=[REDACTED]"))

        val json = """{"username": "admin", "password": "supersecretpassword123"}"""
        val sanitizedJson = CrashDiagnosticLogger.sanitize(json)
        assertFalse(sanitizedJson.contains("supersecretpassword123"))
        assertTrue(sanitizedJson.contains(""""password": "[REDACTED]""""))
    }

    @Test
    fun testUserPathRedaction() {
        val winPath = """Error in file C:\Users\safal\AndroidStudioProjects\ServerDashBoard\ApiClient.kt"""
        val sanitizedWin = CrashDiagnosticLogger.sanitize(winPath)
        assertFalse(sanitizedWin.contains("safal"))
        assertTrue(sanitizedWin.contains("[REDACTED_USER_PATH]"))

        val linuxPath = "Error in file /home/ubuntu/workspace/server.py"
        val sanitizedLinux = CrashDiagnosticLogger.sanitize(linuxPath)
        assertFalse(sanitizedLinux.contains("ubuntu"))
        assertTrue(sanitizedLinux.contains("[REDACTED_USER_PATH]"))
    }

    @Test
    fun testLogDiagnosticStructuredFields() {
        val exc = IOException("Failed connecting to 192.168.1.50:8765")
        val record = CrashDiagnosticLogger.logDiagnostic(
            operation = "telemetry_refresh",
            protocol = "REST",
            httpStatusCode = 500,
            throwable = exc,
            extraMessage = "Attempt failed on host 100.87.90.69"
        )

        assertEquals("1.0.0-test", record.appVersion)
        assertEquals("telemetry_refresh", record.operation)
        assertEquals("REST", record.protocol)
        assertEquals(500, record.httpStatusCode)
        assertEquals("java.io.IOException", record.exceptionType)

        // Sensitive IPs must be stripped from both message and stack trace
        assertFalse(record.sanitizedMessage?.contains("100.87.90.69") == true)
        assertFalse(record.sanitizedStackTrace?.contains("192.168.1.50") == true)

        val recent = CrashDiagnosticLogger.getRecentRecords()
        assertEquals(1, recent.size)
        assertEquals(record, recent[0])
    }

    @Test
    fun testBoundedLogSize() {
        for (i in 1..40) {
            CrashDiagnosticLogger.logDiagnostic("op_$i")
        }
        val records = CrashDiagnosticLogger.getRecentRecords()
        assertTrue(records.size <= 30)
    }
}
