package com.example.serverdashboard

import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.model.FirewallResponse
import com.example.serverdashboard.data.model.NetworkPortsResponse
import com.example.serverdashboard.ui.util.Formatters
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NetworkDiagnosticsTest {

    private val gson = Gson()

    @Test
    fun testNetworkPortsDeserialization() {
        val sampleJson = """
            {
                "active_ports": [
                    {
                        "port": 8765,
                        "protocol": "tcp",
                        "state": "LISTEN",
                        "bind_address": "0.0.0.0",
                        "process_name": "python3",
                        "pid": 1420,
                        "service_name": "Zentra API",
                        "scope": "all"
                    },
                    {
                        "port": 22,
                        "protocol": "tcp",
                        "state": "LISTEN",
                        "bind_address": "0.0.0.0",
                        "process_name": "sshd",
                        "pid": 890,
                        "service_name": "SSH",
                        "scope": "all"
                    }
                ],
                "inactive_ports": [
                    {
                        "port": 5432,
                        "protocol": "tcp",
                        "state": "INACTIVE",
                        "service_name": "PostgreSQL",
                        "description": "PostgreSQL Database Server"
                    }
                ],
                "active_count": 2,
                "last_checked": 1728518400000
            }
        """.trimIndent()

        val parsed = gson.fromJson(sampleJson, NetworkPortsResponse::class.java)
        assertNotNull(parsed)
        assertEquals(2, parsed.activePorts.size)
        assertEquals(1, parsed.inactivePorts.size)
        assertEquals(2, parsed.activeCount)

        val port8765 = parsed.activePorts[0]
        assertEquals(8765, port8765.port)
        assertEquals("tcp", port8765.protocol)
        assertEquals("LISTEN", port8765.state)
        assertEquals("0.0.0.0", port8765.bindAddress)
        assertEquals("python3", port8765.processName)
        assertEquals(1420, port8765.pid)
        assertEquals("Zentra API", port8765.serviceName)
        assertEquals("all", port8765.scope)

        val inactivePort = parsed.inactivePorts[0]
        assertEquals(5432, inactivePort.port)
        assertEquals("INACTIVE", inactivePort.state)
        assertEquals("PostgreSQL", inactivePort.serviceName)
    }

    @Test
    fun testNetworkPortsNullListsDeserialization() {
        val jsonWithNulls = """
            {
                "active_ports": null,
                "inactive_ports": null,
                "active_count": 0
            }
        """.trimIndent()

        val parsed = gson.fromJson(jsonWithNulls, NetworkPortsResponse::class.java)
        assertNotNull(parsed)
        assertNotNull("activePorts getter must return non-null empty list", parsed.activePorts)
        assertNotNull("inactivePorts getter must return non-null empty list", parsed.inactivePorts)
        assertTrue(parsed.activePorts.isEmpty())
        assertTrue(parsed.inactivePorts.isEmpty())
    }

    @Test
    fun testFirewallResponseDeserialization() {
        val sampleJson = """
            {
                "status": "active",
                "implementation": "ufw",
                "default_incoming": "deny",
                "default_outgoing": "allow",
                "rules": [
                    {
                        "target": "22/tcp",
                        "action": "ALLOW",
                        "from": "Anywhere"
                    },
                    {
                        "target": "8765/tcp",
                        "action": "ALLOW",
                        "from": "192.168.1.0/24"
                    }
                ],
                "last_checked": 1728518400000
            }
        """.trimIndent()

        val parsed = gson.fromJson(sampleJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("active", parsed.status)
        assertEquals("ufw", parsed.implementation)
        assertEquals("deny", parsed.defaultIncoming)
        assertEquals("allow", parsed.defaultOutgoing)
        assertNotNull(parsed.rules)
        assertEquals(2, parsed.rules?.size)
        assertEquals("22/tcp", parsed.rules?.get(0)?.target)
        assertEquals("ALLOW", parsed.rules?.get(0)?.action)
        assertEquals("Anywhere", parsed.rules?.get(0)?.from)
    }

    @Test
    fun testFirewallInactiveDeserialization() {
        val sampleJson = """
            {
                "status": "inactive",
                "implementation": "ufw",
                "default_incoming": "allow",
                "default_outgoing": "allow",
                "rules": []
            }
        """.trimIndent()

        val parsed = gson.fromJson(sampleJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("inactive", parsed.status)
        assertNotNull(parsed.rules)
        assertEquals(0, parsed.rules?.size)
    }

    @Test
    fun testFirewallNullRulesDeserialization() {
        val jsonWithNullRules = """
            {
                "status": "active",
                "implementation": "ufw",
                "default_incoming": "deny",
                "default_outgoing": "allow",
                "rules": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(jsonWithNullRules, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("active", parsed.status)
        assertEquals("ufw", parsed.implementation)
        assertNull("rules must be null when API explicitly returns null rules", parsed.rules)
    }

    @Test
    fun testFirewallMissingRulesKeyDeserialization() {
        val jsonMissingRules = """
            {
                "status": "active",
                "implementation": "firewalld",
                "default_incoming": "reject"
            }
        """.trimIndent()

        val parsed = gson.fromJson(jsonMissingRules, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("active", parsed.status)
        assertEquals("firewalld", parsed.implementation)
        assertNull("rules must be null when API omits rules key", parsed.rules)
    }

    @Test
    fun testFirewallNullStatusAndImplementation() {
        val jsonWithNullStatus = """
            {
                "status": null,
                "implementation": null,
                "rules": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(jsonWithNullStatus, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("unknown", parsed.status)
        assertEquals("none", parsed.implementation)
        assertNull(parsed.rules)
    }

    @Test
    fun testConnectionStates() {
        // Verify all authoritative connection states exist
        val states = ConnectionState.values()
        assertTrue(states.contains(ConnectionState.CONNECTED))
        assertTrue(states.contains(ConnectionState.CONNECTING))
        assertTrue(states.contains(ConnectionState.RECONNECTING))
        assertTrue(states.contains(ConnectionState.DISCONNECTED))
        assertTrue(states.contains(ConnectionState.AUTH_REQUIRED))
        assertTrue(states.contains(ConnectionState.SERVER_UNAVAILABLE))

        // Verify WebSocket stream states
        val streamStates = StreamState.values()
        assertTrue(streamStates.contains(StreamState.LIVE_STREAMING))
        assertTrue(streamStates.contains(StreamState.CONNECTING))
        assertTrue(streamStates.contains(StreamState.RECONNECTING))
        assertTrue(streamStates.contains(StreamState.DISCONNECTED))
        assertTrue(streamStates.contains(StreamState.AUTH_FAILED))
    }

    @Test
    fun testFirewallContradictoryActiveWithNoneImplementation() {
        val contradictoryJson = """
            {
                "status": "active",
                "implementation": "none",
                "default_incoming": "unknown",
                "default_outgoing": "unknown",
                "rules": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(contradictoryJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertFalse("Must never confirm active when implementation is none", parsed.isConfirmedActive)
        assertTrue("Must detect contradictory state when active but none implementation", parsed.hasContradictoryState)
        assertFalse(parsed.isIdentified)
        assertEquals("Unknown", parsed.effectivePolicyDisplay)
        assertNull(parsed.rules)
    }

    @Test
    fun testFirewallConfirmedActiveWithUfw() {
        val activeUfwJson = """
            {
                "status": "active",
                "implementation": "ufw",
                "default_incoming": "deny",
                "default_outgoing": "allow",
                "rules": []
            }
        """.trimIndent()

        val parsed = gson.fromJson(activeUfwJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertTrue(parsed.isConfirmedActive)
        assertFalse(parsed.hasContradictoryState)
        assertTrue(parsed.isIdentified)
        assertFalse(parsed.isConfirmedInactive)
        assertEquals("in: deny • out: allow", parsed.effectivePolicyDisplay)
        assertNotNull(parsed.rules)
        assertEquals(0, parsed.rules?.size)
    }

    @Test
    fun testFirewallConfirmedInactive() {
        val inactiveJson = """
            {
                "status": "inactive",
                "implementation": "ufw",
                "rules": []
            }
        """.trimIndent()

        val parsed = gson.fromJson(inactiveJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertFalse(parsed.isConfirmedActive)
        assertTrue(parsed.isConfirmedInactive)
        assertFalse(parsed.hasContradictoryState)
        assertTrue(parsed.isIdentified)
    }

    @Test
    fun testFormatters() {
        assertEquals("192.168.1.50:8765", Formatters.extractHost("http://192.168.1.50:8765/api"))
        assertEquals("myserver.net", Formatters.extractHost("https://myserver.net"))
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertTrue(Formatters.formatSpeed(1048576L).contains("MB/s") || Formatters.formatSpeed(1048576L).contains("M"))
    }

    @Test
    fun testBackendFirewallLiveSchemaDeserialization() {
        val liveBackendJson = """
            {
                "available": true,
                "backend": "ufw",
                "enabled": true,
                "default_policies": {
                    "DEFAULT_INPUT_POLICY": "DROP",
                    "DEFAULT_OUTPUT_POLICY": "ACCEPT",
                    "DEFAULT_FORWARD_POLICY": "DROP"
                },
                "rules_available": true,
                "rules": [
                    {
                        "to": "22/tcp",
                        "action": "ALLOW",
                        "from": "Anywhere"
                    },
                    {
                        "to": "8765/tcp",
                        "action": "ALLOW",
                        "from": "192.168.1.0/24"
                    }
                ],
                "application_port": 8765,
                "timestamp": 1728518400
            }
        """.trimIndent()

        val parsed = gson.fromJson(liveBackendJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("ufw", parsed.implementation)
        assertEquals("active", parsed.status)
        assertTrue(parsed.isIdentified)
        assertTrue(parsed.isConfirmedActive)
        assertFalse(parsed.isConfirmedInactive)
        assertFalse(parsed.hasContradictoryState)
        assertEquals("drop", parsed.defaultIncoming)
        assertEquals("accept", parsed.defaultOutgoing)
        assertEquals("in: drop • out: accept", parsed.effectivePolicyDisplay)
        assertTrue(parsed.isRulesAvailable)
        assertNotNull(parsed.rules)
        assertEquals(2, parsed.rules?.size)
        assertEquals("22/tcp", parsed.rules?.get(0)?.target)
        assertEquals("ALLOW", parsed.rules?.get(0)?.action)
        assertEquals("Anywhere", parsed.rules?.get(0)?.from)
        assertEquals("8765/tcp", parsed.rules?.get(1)?.target)
    }

    @Test
    fun testBackendFirewallStringRulesHandling() {
        val stringRulesJson = """
            {
                "available": true,
                "backend": "iptables",
                "enabled": true,
                "rules_available": true,
                "rules": [
                    "-A INPUT -p tcp -m tcp --dport 22 -j ACCEPT",
                    "-A INPUT -p tcp -m tcp --dport 8765 -j DROP"
                ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(stringRulesJson, FirewallResponse::class.java)
        assertNotNull(parsed)
        assertEquals("iptables", parsed.implementation)
        assertTrue(parsed.isConfirmedActive)
        assertEquals(2, parsed.rules?.size)
        assertEquals("-A INPUT -p tcp -m tcp --dport 22 -j ACCEPT", parsed.rules?.get(0)?.target)
        assertEquals("ALLOW", parsed.rules?.get(0)?.action)
        assertEquals("-A INPUT -p tcp -m tcp --dport 8765 -j DROP", parsed.rules?.get(1)?.target)
        assertEquals("DENY", parsed.rules?.get(1)?.action)
    }

    @Test
    fun testBackendNetworkPortsLiveSchemaDeserialization() {
        val livePortsJson = """
            {
                "available": true,
                "tool": "ss",
                "counts": {
                    "tcp": 2,
                    "udp": 0,
                    "total": 2
                },
                "listening": [
                    {
                        "protocol": "tcp",
                        "state": "LISTEN",
                        "address": "0.0.0.0",
                        "port": 8765,
                        "ip_version": "ipv4",
                        "process": {
                            "name": "python3",
                            "pid": 1420
                        }
                    },
                    {
                        "protocol": "tcp",
                        "state": "LISTEN",
                        "address": "127.0.0.1",
                        "port": 22,
                        "ip_version": "ipv4",
                        "process": null
                    }
                ],
                "process_info": "available",
                "timestamp": 1728518400
            }
        """.trimIndent()

        val parsed = gson.fromJson(livePortsJson, NetworkPortsResponse::class.java)
        assertNotNull(parsed)
        assertEquals(2, parsed.activePorts.size)
        assertEquals(2, parsed.activeCount)

        val port8765 = parsed.activePorts[0]
        assertEquals(8765, port8765.port)
        assertEquals("tcp", port8765.protocol)
        assertEquals("LISTEN", port8765.state)
        assertEquals("0.0.0.0", port8765.bindAddress)
        assertEquals("python3", port8765.processName)
        assertEquals(1420, port8765.pid)

        val port22 = parsed.activePorts[1]
        assertEquals(22, port22.port)
        assertEquals("127.0.0.1", port22.bindAddress)
        assertNull(port22.processName)
        assertNull(port22.pid)
    }
}
