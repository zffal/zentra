package com.example.serverdashboard.data.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.serverdashboard.data.model.SystemOverview
import com.example.serverdashboard.data.util.CrashDiagnosticLogger
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_REQUIRED,
    SERVER_UNAVAILABLE
}

enum class StreamState {
    LIVE_STREAMING,
    CONNECTING,
    RECONNECTING,
    DISCONNECTED,
    AUTH_FAILED
}

class ZentraWebSocketClient(
    private val baseUrl: String,
    private val token: String,
    private val onStreamStateChanged: (StreamState, String?) -> Unit,
    private val onMetricsReceived: (SystemOverview) -> Unit,
    private val onAuthFailed: () -> Unit = {}
) {
    private val TAG = "ZentraWS"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isClosedManually = false
    private var reconnectAttempt = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val backoffIntervals = listOf(1500L, 3000L, 5000L, 10000L, 15000L)

    fun connect() {
        isClosedManually = false
        reconnectAttempt = 0
        mainHandler.removeCallbacksAndMessages(null)
        performConnect()
    }

    private fun performConnect() {
        if (isClosedManually) return

        // Cancel and tear down any stale connection instance before creating a new one
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null

        val cleanUrl = ApiClient.normalizeUrl(baseUrl)
        val wsBase = if (cleanUrl.startsWith("https://", ignoreCase = true)) {
            cleanUrl.replaceFirst("(?i)https://".toRegex(), "wss://")
        } else {
            cleanUrl.replaceFirst("(?i)http://".toRegex(), "ws://")
        }

        val encodedToken = try {
            java.net.URLEncoder.encode(token, "UTF-8")
        } catch (e: Exception) {
            token
        }
        val wsUrl = "$wsBase/ws/metrics?token=$encodedToken"
        val displayWs = "$wsBase/ws/metrics"

        Log.d(TAG, "Connecting to WebSocket: $displayWs")
        notifyState(StreamState.CONNECTING, null)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket streaming established.")
                reconnectAttempt = 0
                notifyState(StreamState.LIVE_STREAMING, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JsonParser.parseString(text).asJsonObject
                    val type = root.get("type")?.asString ?: ""
                    if (type == "metrics") {
                        val targetJson = if (root.has("data") && root.get("data").isJsonObject) {
                            root.getAsJsonObject("data")
                        } else {
                            root
                        }
                        val overview = gson.fromJson(targetJson, SystemOverview::class.java)
                        if (overview != null) {
                            val finalOverview = if (overview.timestamp == 0L && root.has("timestamp")) {
                                val rootTs = try {
                                    val tsElem = root.get("timestamp")
                                    if (tsElem.isJsonPrimitive) tsElem.asDouble.toLong() else 0L
                                } catch (e: Exception) {
                                    0L
                                }
                                overview.copy(timestamp = rootTs)
                            } else {
                                overview
                            }
                            mainHandler.post {
                                onMetricsReceived(finalOverview)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CrashDiagnosticLogger.logDiagnostic("websocket_message_parse", protocol = "WebSocket", throwable = e)
                    Log.e(TAG, "Error parsing telemetry packet: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(code, reason)
                } catch (e: Exception) {
                    // ignore
                }
                if (code == 1008 || code == 4001 || code == 4003) {
                    CrashDiagnosticLogger.logDiagnostic("websocket_auth_violation", protocol = "WebSocket", httpStatusCode = code, extraMessage = reason)
                    Log.e(TAG, "WebSocket closed by policy violation ($code). Halting reconnect.")
                    isClosedManually = true
                    mainHandler.removeCallbacksAndMessages(null)
                    notifyState(StreamState.AUTH_FAILED, "Authentication failed")
                    mainHandler.post { onAuthFailed() }
                } else if (!isClosedManually) {
                    scheduleReconnect()
                } else {
                    notifyState(StreamState.DISCONNECTED, reason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket onClosed (code=$code, reason=$reason)")
                if (code == 1008 || code == 4001 || code == 4003) {
                    CrashDiagnosticLogger.logDiagnostic("websocket_auth_violation", protocol = "WebSocket", httpStatusCode = code, extraMessage = reason)
                    isClosedManually = true
                    mainHandler.removeCallbacksAndMessages(null)
                    notifyState(StreamState.AUTH_FAILED, "Authentication failed")
                    mainHandler.post { onAuthFailed() }
                } else if (!isClosedManually) {
                    scheduleReconnect()
                } else {
                    notifyState(StreamState.DISCONNECTED, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val errMsg = t.message ?: "Connection error"
                CrashDiagnosticLogger.logDiagnostic("websocket_failure", protocol = "WebSocket", httpStatusCode = if (code != 0) code else null, throwable = t)
                Log.w(TAG, "WebSocket failure (code=$code): $errMsg")

                if (code == 401 || code == 403 || errMsg.contains("401") || errMsg.contains("403")) {
                    Log.e(TAG, "WebSocket rejected with HTTP $code. Halting reconnect.")
                    isClosedManually = true
                    mainHandler.removeCallbacksAndMessages(null)
                    notifyState(StreamState.AUTH_FAILED, "Authentication failed")
                    mainHandler.post { onAuthFailed() }
                    return
                }

                if (!isClosedManually) {
                    scheduleReconnect()
                } else {
                    notifyState(StreamState.DISCONNECTED, errMsg)
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (isClosedManually) return

        mainHandler.removeCallbacksAndMessages(null)
        notifyState(StreamState.RECONNECTING, "Live stream reconnecting...")
        val delay = backoffIntervals[minOf(reconnectAttempt, backoffIntervals.size - 1)]
        reconnectAttempt = minOf(reconnectAttempt + 1, backoffIntervals.size)

        mainHandler.postDelayed({
            if (!isClosedManually) {
                performConnect()
            }
        }, delay)
    }

    private fun notifyState(state: StreamState, message: String?) {
        mainHandler.post {
            onStreamStateChanged(state, message)
        }
    }

    fun disconnect() {
        isClosedManually = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
        notifyState(StreamState.DISCONNECTED, null)
    }
}

// Backward-compatibility alias
typealias OmnisWebSocketClient = ZentraWebSocketClient
