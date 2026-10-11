package com.example.serverdashboard.data.model

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
import java.util.Locale

data class PortProcess(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("pid")
    val pid: Int? = null
)

data class PortItem(
    @SerializedName("port")
    val port: Int = 0,

    @SerializedName("protocol")
    val protocol: String = "tcp",

    @SerializedName("state")
    val state: String = "LISTEN",

    @SerializedName("bind_address")
    val directBindAddress: String? = null,

    @SerializedName("address")
    val directAddress: String? = null,

    @SerializedName("process_name")
    val directProcessName: String? = null,

    @SerializedName("pid")
    val directPid: Int? = null,

    @SerializedName("process")
    val process: PortProcess? = null,

    @SerializedName("service_name")
    val serviceName: String? = null,

    @SerializedName("scope")
    val scope: String? = null,

    @SerializedName("description")
    val description: String? = null,

    @SerializedName("ip_version")
    val ipVersion: String? = null
) {
    val bindAddress: String?
        get() = directBindAddress ?: directAddress

    val processName: String?
        get() = directProcessName ?: process?.name

    val pid: Int?
        get() = directPid ?: process?.pid
}

data class NetworkPortsResponse(
    @SerializedName("active_ports")
    val directActivePorts: List<PortItem>? = null,

    @SerializedName("listening")
    val listening: List<PortItem>? = null,

    @SerializedName("inactive_ports")
    val directInactivePorts: List<PortItem>? = null,

    @SerializedName("active_count")
    val directActiveCount: Int? = null,

    @SerializedName("counts")
    val counts: Map<String, Int>? = null,

    @SerializedName("last_checked")
    val lastChecked: Long = 0L,

    @SerializedName("timestamp")
    val timestamp: Long = 0L,

    @SerializedName("available")
    val available: Boolean = true
) {
    val activePorts: List<PortItem>
        get() {
            val list = directActivePorts ?: listening
            return list?.filterNotNull() ?: emptyList()
        }

    val inactivePorts: List<PortItem>
        get() = directInactivePorts?.filterNotNull() ?: emptyList()

    val activeCount: Int
        get() = directActiveCount ?: counts?.get("total") ?: activePorts.size
}

class FirewallRuleDeserializer : JsonDeserializer<FirewallRule> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): FirewallRule {
        if (json == null || json.isJsonNull) return FirewallRule()
        if (json.isJsonPrimitive) {
            val str = json.asString
            val action = when {
                str.contains("ACCEPT", ignoreCase = true) || str.contains("ALLOW", ignoreCase = true) -> "ALLOW"
                str.contains("DROP", ignoreCase = true) || str.contains("REJECT", ignoreCase = true) || str.contains("DENY", ignoreCase = true) -> "DENY"
                else -> "RULE"
            }
            return FirewallRule(
                target = str,
                action = action,
                from = "Anywhere"
            )
        }
        if (json.isJsonObject) {
            val obj = json.asJsonObject
            val toVal = obj.get("to")?.takeIf { !it.isJsonNull }?.asString
            val targetVal = obj.get("target")?.takeIf { !it.isJsonNull }?.asString
            val actionVal = obj.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val fromVal = obj.get("from")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return FirewallRule(
                target = toVal ?: targetVal ?: "",
                action = actionVal,
                from = fromVal
            )
        }
        return FirewallRule()
    }
}

@JsonAdapter(FirewallRuleDeserializer::class)
data class FirewallRule(
    @SerializedName("target")
    val target: String = "",

    @SerializedName("action")
    val action: String = "",

    @SerializedName("from")
    val from: String = "",

    @SerializedName("to")
    val to: String? = null
)

data class FirewallResponse(
    @SerializedName("status")
    val directStatus: String? = null,

    @SerializedName("enabled")
    val enabled: Boolean? = null,

    @SerializedName("implementation")
    val directImplementation: String? = null,

    @SerializedName("backend")
    val backend: String? = null,

    @SerializedName("available")
    val available: Boolean? = null,

    @SerializedName("default_incoming")
    val directDefaultIncoming: String? = null,

    @SerializedName("default_outgoing")
    val directDefaultOutgoing: String? = null,

    @SerializedName("default_policies")
    val defaultPolicies: Map<String, String>? = null,

    @SerializedName("rules_available")
    val rulesAvailable: Boolean? = null,

    @SerializedName("rules")
    val rules: List<FirewallRule>? = null,

    @SerializedName("last_checked")
    val lastChecked: Long = 0L,

    @SerializedName("timestamp")
    val timestamp: Long = 0L
) {
    val implementation: String
        get() {
            val b = backend?.takeIf { it.isNotBlank() }
            val impl = directImplementation?.takeIf { it.isNotBlank() }
            return b ?: impl ?: "none"
        }

    val status: String
        get() {
            if (directStatus != null && directStatus.isNotBlank()) {
                return directStatus
            }
            return when (enabled) {
                true -> "active"
                false -> "inactive"
                null -> if (available == true) "active" else "unknown"
            }
        }

    val defaultIncoming: String?
        get() {
            if (!directDefaultIncoming.isNullOrBlank()) return directDefaultIncoming
            val fromPolicies = defaultPolicies?.get("DEFAULT_INPUT_POLICY")
                ?: defaultPolicies?.get("INPUT")
                ?: defaultPolicies?.get("input")
            return fromPolicies?.lowercase(Locale.US)
        }

    val defaultOutgoing: String?
        get() {
            if (!directDefaultOutgoing.isNullOrBlank()) return directDefaultOutgoing
            val fromPolicies = defaultPolicies?.get("DEFAULT_OUTPUT_POLICY")
                ?: defaultPolicies?.get("OUTPUT")
                ?: defaultPolicies?.get("output")
            return fromPolicies?.lowercase(Locale.US)
        }

    val isIdentified: Boolean
        get() = implementation.isNotBlank() &&
            !implementation.equals("none", ignoreCase = true) &&
            !implementation.equals("not detected", ignoreCase = true) &&
            !implementation.equals("unknown", ignoreCase = true)

    val isConfirmedActive: Boolean
        get() = isIdentified && (status.equals("active", ignoreCase = true) || enabled == true)

    val isConfirmedInactive: Boolean
        get() = status.equals("inactive", ignoreCase = true) || enabled == false || (isIdentified && !isConfirmedActive)

    val hasContradictoryState: Boolean
        get() = (status.equals("active", ignoreCase = true) || enabled == true) && !isIdentified

    val isRulesAvailable: Boolean
        get() = rulesAvailable == true || rules != null

    val effectivePolicyDisplay: String
        get() {
            val inPolicy = defaultIncoming?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            val outPolicy = defaultOutgoing?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            return when {
                inPolicy != null && outPolicy != null -> "in: $inPolicy • out: $outPolicy"
                inPolicy != null -> "in: $inPolicy"
                outPolicy != null -> "out: $outPolicy"
                else -> "Unknown"
            }
        }
}
