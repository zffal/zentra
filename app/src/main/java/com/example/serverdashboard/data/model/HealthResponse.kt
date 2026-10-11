package com.example.serverdashboard.data.model

data class HealthResponse(
    val status: String = "",
    val service: String = "",
    val version: String = "",
    val hostname: String? = null,
    val uptime: Long? = null,
    val timestamp: Long? = null
)
