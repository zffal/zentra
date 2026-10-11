package com.example.serverdashboard.data.api

import android.util.Log
import com.example.serverdashboard.data.model.*
import com.example.serverdashboard.data.util.CrashDiagnosticLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

class ApiClient private constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "ZentraNetwork"
        val instance by lazy { ApiClient() }

        fun normalizeUrl(rawUrl: String): String {
            var url = rawUrl.trim()
            while (url.endsWith("/")) {
                url = url.substring(0, url.length - 1).trim()
            }
            if (url.endsWith("/api", ignoreCase = true)) {
                url = url.substring(0, url.length - 4).trim()
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length - 1).trim()
            }
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "http://$url"
            }
            return url
        }

        fun handleNetworkException(targetUrl: String, e: Exception): Exception {
            val sanitizedUrl = CrashDiagnosticLogger.sanitize(targetUrl.substringBefore("?"))
            CrashDiagnosticLogger.logDiagnostic(
                operation = "rest_network_call",
                protocol = "REST",
                throwable = e,
                extraMessage = "Endpoint: $sanitizedUrl"
            )
            val msg = e.message ?: ""
            val isTailscale = targetUrl.contains("100.") || targetUrl.contains(".ts.net") || targetUrl.contains("tailscale", ignoreCase = true)
            return when {
                e is UnknownServiceException || msg.contains("CLEARTEXT", ignoreCase = true) -> {
                    val err = "Cleartext HTTP traffic not permitted by network security policy"
                    Log.e(TAG, "[CLEARTEXT ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
                e is SocketTimeoutException -> {
                    val err = if (isTailscale) {
                        "Connection timed out. Tailscale server is unreachable. Verify Tailscale is active on this device and your server."
                    } else {
                        "Connection timed out after 15s"
                    }
                    Log.e(TAG, "[TIMEOUT ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
                e is ConnectException -> {
                    val err = if (isTailscale) {
                        "Connection refused ($msg). Verify the Zentra agent service is running on your Tailscale server."
                    } else {
                        "Connection refused or server unreachable ($msg)"
                    }
                    Log.e(TAG, "[CONNECT ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
                e is UnknownHostException -> {
                    val err = if (isTailscale) {
                        "DNS error: Cannot resolve Tailscale host '$msg'. Verify MagicDNS is enabled."
                    } else {
                        "DNS error: Cannot resolve host '$msg'"
                    }
                    Log.e(TAG, "[DNS ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
                e is NoRouteToHostException -> {
                    val err = if (isTailscale) {
                        "No route to host ($msg). Verify Tailscale VPN connection is connected."
                    } else {
                        "No route to host ($msg)"
                    }
                    Log.e(TAG, "[ROUTING ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
                else -> {
                    val err = "${e.javaClass.simpleName}: $msg"
                    Log.e(TAG, "[NETWORK ERROR] $err for $sanitizedUrl", e)
                    Exception(err, e)
                }
            }
        }
    }

    suspend fun checkHealth(baseUrl: String): Result<HealthResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/health"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Body: $body")

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP $code Error from $targetUrl | Body: $body")
                    return@withContext Result.failure(IOException("Server returned HTTP $code: $body"))
                }
                val health = gson.fromJson(body, HealthResponse::class.java)
                Result.success(health)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun register(baseUrl: String, req: RegisterRequest): Result<TokenResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/auth/register"
        val json = gson.toJson(req)
        Log.d(TAG, "--> POST $targetUrl (user: ${req.username})")

        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl (success=${response.isSuccessful})")

                if (!response.isSuccessful) {
                    val msg = if (code == 404) {
                        "Server registration not enabled (HTTP 404). Please update the backend service or sign in with existing credentials."
                    } else {
                        try {
                            val errObj = gson.fromJson(responseBody, Map::class.java)
                            val detail = errObj["detail"]
                            if (detail is List<*>) {
                                detail.joinToString(", ") { (it as? Map<*, *>)?.get("msg")?.toString() ?: it.toString() }
                            } else {
                                detail?.toString() ?: "Registration failed: HTTP $code"
                            }
                        } catch (e: Exception) {
                            "Registration failed: HTTP $code"
                        }
                    }
                    Log.e(TAG, "Registration failed: $msg")
                    return@withContext Result.failure(Exception(msg))
                }
                val tokenResponse = gson.fromJson(responseBody, TokenResponse::class.java)
                Result.success(tokenResponse)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun logout(baseUrl: String, token: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/auth/logout"
        Log.d(TAG, "--> POST $targetUrl")

        val builder = Request.Builder()
            .url(targetUrl)
            .post("{}".toRequestBody(jsonMediaType))

        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                Log.d(TAG, "<-- HTTP ${response.code} from $targetUrl")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.success(Unit) // Logout is client-authoritative
        }
    }

    suspend fun login(baseUrl: String, req: LoginRequest): Result<TokenResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/auth/login"
        val json = gson.toJson(req)
        Log.d(TAG, "--> POST $targetUrl (user: ${req.username})")

        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl (success=${response.isSuccessful})")

                if (!response.isSuccessful) {
                    val msg = try {
                        val errObj = gson.fromJson(responseBody, Map::class.java)
                        val detail = errObj["detail"]
                        if (detail is List<*>) {
                            detail.joinToString(", ") { (it as? Map<*, *>)?.get("msg")?.toString() ?: it.toString() }
                        } else {
                            detail?.toString() ?: "Invalid username or password"
                        }
                    } catch (e: Exception) {
                        "Authentication failed: HTTP $code"
                    }
                    Log.e(TAG, "Login failed: $msg")
                    return@withContext Result.failure(Exception(msg))
                }
                val tokenResponse = gson.fromJson(responseBody, TokenResponse::class.java)
                Result.success(tokenResponse)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getMe(baseUrl: String, token: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/auth/me"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl (success=${response.isSuccessful})")

                if (!response.isSuccessful) {
                    Log.e(TAG, "getMe failed HTTP $code")
                    return@withContext Result.failure(Exception("HTTP $code"))
                }
                val user = gson.fromJson(body, UserInfo::class.java)
                Result.success(user)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getSystemOverview(baseUrl: String, token: String): Result<SystemOverview> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/system"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val baseOverview = client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Body length: ${body.length}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "getSystemOverview failed: HTTP $code")
                    return@withContext Result.failure(Exception("HTTP $code"))
                }
                gson.fromJson(body, SystemOverview::class.java) ?: SystemOverview()
            }

            // Only fetch sub-resources as fallback if they were missing from the base overview
            var storage = baseOverview.storage
            var network = baseOverview.network
            var temperature = baseOverview.temperature
            var docker = baseOverview.docker

            if (storage.filesystems.isEmpty()) {
                try {
                    val storageReq = Request.Builder().url("$cleanUrl/api/system/storage").header("Authorization", "Bearer $token").build()
                    client.newCall(storageReq).execute().use { r ->
                        if (r.isSuccessful) {
                            storage = gson.fromJson(r.body?.string() ?: "", StorageMetrics::class.java) ?: storage
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Optional storage fetch failed: ${e.message}")
                }
            }

            if (network.interfaces.isEmpty()) {
                try {
                    val netReq = Request.Builder().url("$cleanUrl/api/system/network").header("Authorization", "Bearer $token").build()
                    client.newCall(netReq).execute().use { r ->
                        if (r.isSuccessful) {
                            network = gson.fromJson(r.body?.string() ?: "", NetworkMetrics::class.java) ?: network
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Optional network fetch failed: ${e.message}")
                }
            }

            if (temperature == null || (temperature.sensors.isEmpty() && temperature.cpu == null)) {
                try {
                    val tempReq = Request.Builder().url("$cleanUrl/api/system/temperature").header("Authorization", "Bearer $token").build()
                    client.newCall(tempReq).execute().use { r ->
                        if (r.isSuccessful) {
                            temperature = gson.fromJson(r.body?.string() ?: "", TemperatureMetrics::class.java) ?: temperature
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Optional temperature fetch failed: ${e.message}")
                }
            }

            try {
                val dockReq = Request.Builder().url("$cleanUrl/api/docker/containers").header("Authorization", "Bearer $token").build()
                client.newCall(dockReq).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        if (body.trimStart().startsWith("{")) {
                            val resp = gson.fromJson(body, DockerContainersResponse::class.java)
                            docker = DockerData(available = resp.available, summary = resp.summary, containers = resp.containers)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Optional docker fetch failed: ${e.message}")
            }

            val fullOverview = baseOverview.copy(
                storage = storage,
                network = network,
                temperature = temperature,
                docker = docker
            )
            Result.success(fullOverview)
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getProcesses(baseUrl: String, token: String, sortBy: String = "cpu", limit: Int = 25): Result<List<ProcessInfo>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/system/processes?sort_by=$sortBy&limit=$limit"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Length: ${body.length}")

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code"))
                }

                val list: List<ProcessInfo> = if (body.trimStart().startsWith("{")) {
                    val resp = gson.fromJson(body, ProcessesResponse::class.java)
                    resp.processes
                } else {
                    val type = object : TypeToken<List<ProcessInfo>>() {}.type
                    gson.fromJson(body, type) ?: emptyList()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getContainers(baseUrl: String, token: String): Result<List<ContainerItem>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/docker/containers?all=true"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Length: ${body.length}")

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code"))
                }

                val list: List<ContainerItem> = if (body.trimStart().startsWith("{")) {
                    val resp = gson.fromJson(body, DockerContainersResponse::class.java)
                    resp.containers
                } else {
                    val type = object : TypeToken<List<ContainerItem>>() {}.type
                    gson.fromJson(body, type) ?: emptyList()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun containerAction(baseUrl: String, token: String, id: String, action: String): Result<ActionResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/docker/containers/$id/$action"
        Log.d(TAG, "--> POST $targetUrl")

        val body = "{}".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Body: $responseBody")

                if (!response.isSuccessful) {
                    val detail = try {
                        val map = gson.fromJson(responseBody, Map::class.java)
                        map["detail"]?.toString() ?: "Action failed"
                    } catch (e: Exception) {
                        "Action failed: HTTP $code"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(responseBody, ActionResult::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getContainerLogs(baseUrl: String, token: String, id: String, tail: Int = 150): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/docker/containers/$id/logs?tail=$tail"

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code"))
                }
                val resp = gson.fromJson(body, ContainerLogsResponse::class.java)
                Result.success(resp.logs)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getAlerts(baseUrl: String, token: String, filter: String = "all"): Result<List<AlertHistoryItem>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/alerts?status_filter=$filter&limit=100"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl | Length: ${body.length}")

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code"))
                }

                val list: List<AlertHistoryItem> = if (body.trimStart().startsWith("{")) {
                    val resp = gson.fromJson(body, AlertsResponse::class.java)
                    resp.alerts
                } else {
                    val type = object : TypeToken<List<AlertHistoryItem>>() {}.type
                    gson.fromJson(body, type) ?: emptyList()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun resolveAlert(baseUrl: String, token: String, id: Any): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/alerts/$id/resolve"

        val body = "{}".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun clearAllAlerts(baseUrl: String, token: String): Result<ClearAlertsResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/alerts/clear-all"

        val body = "{}".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string().orEmpty()
                    val clearResp = try {
                        gson.fromJson(respBody, ClearAlertsResponse::class.java) ?: ClearAlertsResponse(status = "ok")
                    } catch (_: Exception) {
                        ClearAlertsResponse(status = "ok")
                    }
                    Result.success(clearResp)
                } else {
                    val errBody = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
                    val detail = try {
                        val obj = gson.fromJson(errBody, Map::class.java)
                        obj["detail"]?.toString() ?: ""
                    } catch (_: Exception) { "" }
                    val msg = if (detail.isNotBlank()) detail else response.message.ifEmpty { "Server rejected clear all" }
                    Result.failure(Exception("HTTP ${response.code}: $msg"))
                }
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun serverPower(baseUrl: String, token: String, action: String, confirm: Boolean = true): Result<ActionResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/system/power"

        val payload = mapOf("action" to action, "confirm" to confirm)
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val detail = try {
                        val map = gson.fromJson(responseBody, Map::class.java)
                        map["detail"]?.toString() ?: "Power action rejected"
                    } catch (e: Exception) {
                        "HTTP $code"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(responseBody, ActionResult::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun restartNetworkService(baseUrl: String, token: String): Result<ActionResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/network/restart"

        val emptyBody = "".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .post(emptyBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val detail = try {
                        val map = gson.fromJson(responseBody, Map::class.java)
                        map["detail"]?.toString() ?: "Network restart rejected"
                    } catch (e: Exception) {
                        "HTTP $code"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(responseBody, ActionResult::class.java)
                    ?: ActionResult(success = true, message = "Network restart initiated.")
                Result.success(res)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getNetworkPorts(baseUrl: String, token: String): Result<NetworkPortsResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/network/ports"

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val detail = try {
                        val map = gson.fromJson(responseBody, Map::class.java)
                        map["detail"]?.toString() ?: "Failed to fetch network ports"
                    } catch (e: Exception) {
                        "HTTP $code"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(responseBody, NetworkPortsResponse::class.java)
                Result.success(res ?: NetworkPortsResponse())
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getFirewallStatus(baseUrl: String, token: String): Result<FirewallResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/network/firewall"

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val detail = try {
                        val map = gson.fromJson(responseBody, Map::class.java)
                        map["detail"]?.toString() ?: "Failed to fetch firewall status"
                    } catch (e: Exception) {
                        "HTTP $code"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(responseBody, FirewallResponse::class.java)
                Result.success(res ?: FirewallResponse())
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getNetworkUsage(
        baseUrl: String,
        token: String,
        days: Int = 30,
        iface: String = "all"
    ): Result<NetworkUsageResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val encodedIface = java.net.URLEncoder.encode(iface, "UTF-8")
        val targetUrl = "$cleanUrl/api/network/usage?days=$days&iface=$encodedIface"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl (len=${responseBody.length})")

                if (code == 404) {
                    return@withContext Result.failure(ServerOutdatedException())
                }

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code: $responseBody"))
                }

                val usageResponse = gson.fromJson(responseBody, NetworkUsageResponse::class.java)
                Result.success(usageResponse ?: NetworkUsageResponse())
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }

    suspend fun getNetworkUsageInterfaces(
        baseUrl: String,
        token: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(baseUrl)
        val targetUrl = "$cleanUrl/api/network/usage/interfaces"
        Log.d(TAG, "--> GET $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "<-- HTTP $code from $targetUrl (len=${responseBody.length})")

                if (code == 404) {
                    return@withContext Result.failure(ServerOutdatedException())
                }

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP $code: $responseBody"))
                }

                val type = object : TypeToken<List<String>>() {}.type
                val list = gson.fromJson<List<String>>(responseBody, type) ?: emptyList()
                Result.success(list)
            }
        } catch (e: Exception) {
            val categorized = handleNetworkException(targetUrl, e)
            Result.failure(categorized)
        }
    }
}
