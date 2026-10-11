package com.example.serverdashboard

import com.example.serverdashboard.ui.util.ServerNameResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNameResolverTest {

    @Test
    fun testIsIpUrlOrPort_detectsIPv4() {
        assertTrue(ServerNameResolver.isIpUrlOrPort("192.168.1.1"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("10.0.0.1:8765"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("127.0.0.1"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("100.64.0.1:443"))
    }

    @Test
    fun testIsIpUrlOrPort_detectsIPv6() {
        assertTrue(ServerNameResolver.isIpUrlOrPort("[2001:db8::1]"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("[2001:db8::1]:8080"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("::1"))
    }

    @Test
    fun testIsIpUrlOrPort_detectsUrlsAndPorts() {
        assertTrue(ServerNameResolver.isIpUrlOrPort("http://192.168.1.5"))
        assertTrue(ServerNameResolver.isIpUrlOrPort("https://myserver.lan"))
        assertTrue(ServerNameResolver.isIpUrlOrPort(":8765"))
        assertTrue(ServerNameResolver.isIpUrlOrPort(""))
        assertTrue(ServerNameResolver.isIpUrlOrPort(null))
    }

    @Test
    fun testIsIpUrlOrPort_acceptsValidHostnames() {
        assertFalse(ServerNameResolver.isIpUrlOrPort("Homelab Primary"))
        assertFalse(ServerNameResolver.isIpUrlOrPort("raspi-nas"))
        assertFalse(ServerNameResolver.isIpUrlOrPort("ubuntu-server"))
        assertFalse(ServerNameResolver.isIpUrlOrPort("media-center"))
    }

    @Test
    fun testPrecedence_savedNameTakesPriority() {
        // Saved name is valid -> use it
        val result = ServerNameResolver.resolve(
            savedName = "Production NAS",
            backendHostname = "ubuntu-box"
        )
        assertEquals("Production NAS", result)
    }

    @Test
    fun testPrecedence_ipSavedNameFallsBackToBackendHostname() {
        // Saved name looks like an IP -> fall back to backend hostname
        val result = ServerNameResolver.resolve(
            savedName = "192.168.1.100:8765",
            backendHostname = "homeserver-ubuntu"
        )
        assertEquals("homeserver-ubuntu", result)
    }

    @Test
    fun testPrecedence_bothIpFallbackToServer() {
        // Both saved name and backend hostname look like IP addresses -> fallback to "Server"
        val result = ServerNameResolver.resolve(
            savedName = "192.168.1.100",
            backendHostname = "10.0.0.5:8080"
        )
        assertEquals("Server", result)
    }

    @Test
    fun testPrecedence_nullOrEmptySavedNameUsesHostname() {
        // No saved name -> use backend hostname
        val result = ServerNameResolver.resolve(
            savedName = null,
            backendHostname = "raspi-cluster"
        )
        assertEquals("raspi-cluster", result)
    }

    @Test
    fun testPrecedence_emptyBothFallsBackToServer() {
        val result = ServerNameResolver.resolve(
            savedName = "",
            backendHostname = ""
        )
        assertEquals("Server", result)
    }
}
