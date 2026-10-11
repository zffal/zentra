package com.example.serverdashboard

import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.HealthResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class HealthEndpointTest {

    @Test
    fun testUrlNormalization() {
        assertEquals("http://192.168.1.100:8765", ApiClient.normalizeUrl("192.168.1.100:8765"))
        assertEquals("http://192.168.1.100:8765", ApiClient.normalizeUrl("http://192.168.1.100:8765/"))
        assertEquals("https://myserver.example.com:8765", ApiClient.normalizeUrl("https://myserver.example.com:8765/"))
    }

    @Test
    fun testHealthResponseDeserialization() {
        val sampleJson = """
            {
                "status": "healthy",
                "service": "omnis",
                "version": "1.0.0"
            }
        """.trimIndent()

        val health = Gson().fromJson(sampleJson, HealthResponse::class.java)
        assertNotNull(health)
        assertEquals("healthy", health.status)
        assertEquals("omnis", health.service)
        assertEquals("1.0.0", health.version)
    }
}
