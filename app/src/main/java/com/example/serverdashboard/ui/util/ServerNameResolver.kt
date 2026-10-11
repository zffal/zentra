package com.example.serverdashboard.ui.util

import android.content.Context
import com.example.serverdashboard.data.storage.ServerStore

/**
 * ServerNameResolver: Implements the strict 3-tier precedence for displaying the server name:
 * 1. The active ServerConfig.name from ServerStore (the user's saved display name).
 * 2. Otherwise the backend host.hostname from the system overview.
 * 3. Otherwise "Server".
 *
 * Rejects IP addresses, ports, tokens, and URLs.
 */
object ServerNameResolver {

    private val IPV4_PATTERN = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?$""")
    private val IPV6_PATTERN = Regex("""^\[?[0-9a-fA-F:]+\]?(?::\d+)?$""")

    /**
     * Checks if a candidate string looks like an IPv4/IPv6 address, port, URL, or empty token.
     */
    fun isIpUrlOrPort(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return true
        val trimmed = candidate.trim()

        // Schemes
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)) {
            return true
        }

        // IPv4 with optional port
        if (IPV4_PATTERN.matches(trimmed)) {
            return true
        }

        // IPv6 address (at least two colons and matches IPv6 hex pattern)
        if (trimmed.count { it == ':' } >= 2 && IPV6_PATTERN.matches(trimmed)) {
            return true
        }

        // Raw port (e.g. ":8765" or "8765")
        if (trimmed.startsWith(":") && trimmed.substring(1).all { it.isDigit() }) {
            return true
        }

        return false
    }

    /**
     * Resolves the server name based on saved name and backend hostname candidate values.
     */
    fun resolve(savedName: String?, backendHostname: String?): String {
        val cleanSaved = savedName?.trim()
        if (!cleanSaved.isNullOrBlank() && !isIpUrlOrPort(cleanSaved)) {
            return cleanSaved
        }

        val cleanBackend = backendHostname?.trim()
        if (!cleanBackend.isNullOrBlank() && !isIpUrlOrPort(cleanBackend)) {
            return cleanBackend
        }

        return "Server"
    }

    /**
     * Resolves the server display name following the strict 3-tier precedence:
     * 1. Active ServerConfig.name from ServerStore (user's saved display name).
     * 2. Backend host.hostname from system overview.
     * 3. Fallback to "Server".
     */
    fun resolveServerName(
        context: Context,
        serverUrl: String? = null,
        hostnameFromOverview: String? = null
    ): String {
        var savedName: String? = null
        try {
            val serverStore = ServerStore.getInstance(context)
            val config = if (!serverUrl.isNullOrBlank()) {
                val cleanUrl = serverUrl.trim().removeSuffix("/")
                serverStore.getServers().firstOrNull { it.url.equals(cleanUrl, ignoreCase = true) }
                    ?: serverStore.getActiveServer()
            } else {
                serverStore.getActiveServer()
            }
            savedName = config?.name
        } catch (_: Throwable) {
            // Defensive guard against storage failure
        }

        return resolve(savedName, hostnameFromOverview)
    }
}
