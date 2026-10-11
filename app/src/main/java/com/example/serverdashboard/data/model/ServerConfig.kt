package com.example.serverdashboard.data.model

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var isDefault: Boolean = false,
    var lastConnected: Long = 0L
)
