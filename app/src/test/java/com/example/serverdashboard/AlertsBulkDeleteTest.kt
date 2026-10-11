package com.example.serverdashboard

import com.example.serverdashboard.data.model.ClearAlertsResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AlertsBulkDeleteTest {

    private val gson = Gson()

    // 1. Verifies that the deployed backend bulk-delete response with "deleted" is parsed accurately
    @Test
    fun testClearAlertsResponse_parsesDeployedBackendFormat() {
        val json = """
            {
                "status": "ok",
                "deleted": 4,
                "remaining": 0,
                "timestamp": 1791714910
            }
        """.trimIndent()

        val resp = gson.fromJson(json, ClearAlertsResponse::class.java)
        assertNotNull(resp)
        assertEquals("ok", resp.status)
        assertEquals(4, resp.deleted)
        assertEquals(4, resp.countDeleted)
        assertEquals(0, resp.remaining)
        assertEquals(1791714910L, resp.timestamp)
    }

    // 2. Verifies idempotent bulk-delete when store is already empty (deleted = 0)
    @Test
    fun testClearAlertsResponse_idempotentEmptyStore() {
        val json = """
            {
                "status": "ok",
                "deleted": 0,
                "remaining": 0,
                "timestamp": 1791714920
            }
        """.trimIndent()

        val resp = gson.fromJson(json, ClearAlertsResponse::class.java)
        assertNotNull(resp)
        assertEquals("ok", resp.status)
        assertEquals(0, resp.deleted)
        assertEquals(0, resp.countDeleted)
        assertEquals(0, resp.remaining)
    }

    // 3. Verifies backward-compatible fallback for response payloads carrying "cleared"
    @Test
    fun testClearAlertsResponse_clearedFieldFallback() {
        val json = """
            {
                "status": "ok",
                "cleared": 7,
                "total": 10,
                "timestamp": 1791714930
            }
        """.trimIndent()

        val resp = gson.fromJson(json, ClearAlertsResponse::class.java)
        assertNotNull(resp)
        assertEquals(7, resp.cleared)
        assertEquals(7, resp.countDeleted)
        assertEquals(10, resp.total)
    }

    // 4. Verifies null safety when fields are missing or empty
    @Test
    fun testClearAlertsResponse_emptyPayloadNullSafety() {
        val json = "{}"
        val resp = gson.fromJson(json, ClearAlertsResponse::class.java)
        assertNotNull(resp)
        assertEquals(0, resp.countDeleted)
        assertNull(resp.deleted)
        assertNull(resp.cleared)
        assertEquals("", resp.status)
    }

    // 5. Verifies precedence: "deleted" takes precedence over "cleared" if both are provided
    @Test
    fun testClearAlertsResponse_precedence() {
        val json = """
            {
                "status": "ok",
                "deleted": 3,
                "cleared": 2
            }
        """.trimIndent()

        val resp = gson.fromJson(json, ClearAlertsResponse::class.java)
        assertNotNull(resp)
        assertEquals(3, resp.countDeleted)
    }
}
