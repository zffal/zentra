package com.example.serverdashboard.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.api.ZentraWebSocketClient
import com.example.serverdashboard.data.model.*
import com.example.serverdashboard.data.util.CrashDiagnosticLogger
import com.example.serverdashboard.ui.alerts.AlertNotificationManager
import com.example.serverdashboard.ui.util.Event
import com.example.serverdashboard.ui.util.RefreshResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

    private val _systemOverview = MutableLiveData<SystemOverview>()
    val systemOverview: LiveData<SystemOverview> = _systemOverview

    // Authoritative overall connection state (REST + WebSocket reachability combined)
    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.CONNECTING)
    val connectionState: LiveData<ConnectionState> = _connectionState

    // Live stream WebSocket status
    private val _streamState = MutableLiveData<StreamState>(StreamState.CONNECTING)
    val streamState: LiveData<StreamState> = _streamState

    private val _activeAlertsCount = MutableLiveData<Int>(0)
    val activeAlertsCount: LiveData<Int> = _activeAlertsCount

    fun updateActiveAlertsCount(count: Int) {
        _activeAlertsCount.postValue(count.coerceAtLeast(0))
    }

    private val _authInvalidEvent = MutableLiveData<Boolean>()
    val authInvalidEvent: LiveData<Boolean> = _authInvalidEvent

    // Telemetry freshness & timing observables
    private val _metricsFreshnessText = MutableLiveData<String>("Connecting...")
    val metricsFreshnessText: LiveData<String> = _metricsFreshnessText

    private val _isMetricsStale = MutableLiveData<Boolean>(false)
    val isMetricsStale: LiveData<Boolean> = _isMetricsStale

    private val _lastMetricsTime = MutableLiveData<Long>(0L)
    val lastMetricsTime: LiveData<Long> = _lastMetricsTime

    // Network diagnostics & management observables
    private val _networkPorts = MutableLiveData<NetworkPortsResponse?>()
    val networkPorts: LiveData<NetworkPortsResponse?> = _networkPorts

    private val _firewallStatus = MutableLiveData<FirewallResponse?>()
    val firewallStatus: LiveData<FirewallResponse?> = _firewallStatus

    private val _isLoadingPorts = MutableLiveData<Boolean>(false)
    val isLoadingPorts: LiveData<Boolean> = _isLoadingPorts

    private val _isLoadingFirewall = MutableLiveData<Boolean>(false)
    val isLoadingFirewall: LiveData<Boolean> = _isLoadingFirewall

    private val _networkActionInProgress = MutableLiveData<Boolean>(false)
    val networkActionInProgress: LiveData<Boolean> = _networkActionInProgress

    // Coalesced telemetry refresh observables
    private val _isRefreshing = MutableLiveData<Boolean>(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    private val _refreshResultEvent = MutableLiveData<Event<RefreshResult>>()
    val refreshResultEvent: LiveData<Event<RefreshResult>> = _refreshResultEvent

    private var isRefreshingMetrics: Boolean = false
    private var isRestFetchInProgress: Boolean = false

    // Real telemetry history buffers (up to 60 samples, NO mock data)
    private val _cpuHistory = MutableLiveData<List<Float>>(emptyList())
    val cpuHistory: LiveData<List<Float>> = _cpuHistory

    private val _ramHistory = MutableLiveData<List<Float>>(emptyList())
    val ramHistory: LiveData<List<Float>> = _ramHistory

    private val _diskReadHistory = MutableLiveData<List<Float>>(emptyList())
    val diskReadHistory: LiveData<List<Float>> = _diskReadHistory

    private val _diskWriteHistory = MutableLiveData<List<Float>>(emptyList())
    val diskWriteHistory: LiveData<List<Float>> = _diskWriteHistory

    private val _diskReadRate = MutableLiveData<Long?>(null)
    val diskReadRate: LiveData<Long?> = _diskReadRate

    private val _diskWriteRate = MutableLiveData<Long?>(null)
    val diskWriteRate: LiveData<Long?> = _diskWriteRate

    private val _netDownloadRate = MutableLiveData<Long?>(null)
    val netDownloadRate: LiveData<Long?> = _netDownloadRate

    private val _netUploadRate = MutableLiveData<Long?>(null)
    val netUploadRate: LiveData<Long?> = _netUploadRate

    private val _netDownloadHistory = MutableLiveData<List<Float>>(emptyList())
    val netDownloadHistory: LiveData<List<Float>> = _netDownloadHistory

    private val _netUploadHistory = MutableLiveData<List<Float>>(emptyList())
    val netUploadHistory: LiveData<List<Float>> = _netUploadHistory

    private val _storageHistory = MutableLiveData<List<Float>>(emptyList())
    val storageHistory: LiveData<List<Float>> = _storageHistory

    private val cpuBuffer = ArrayDeque<Float>(60)
    private val ramBuffer = ArrayDeque<Float>(60)
    private val diskReadBuffer = ArrayDeque<Float>(60)
    private val diskWriteBuffer = ArrayDeque<Float>(60)
    private val netDownloadBuffer = ArrayDeque<Float>(60)
    private val netUploadBuffer = ArrayDeque<Float>(60)
    private val storageBuffer = ArrayDeque<Float>(60)

    var serverUrl: String = ""
    var authToken: String = ""

    private var wsClient: ZentraWebSocketClient? = null
    private var pollingJob: Job? = null
    private var freshnessJob: Job? = null
    private var networkRestartJob: Job? = null

    // Synchronization & state tracking
    @Volatile
    private var lastServerTimestamp: Long = 0L
    @Volatile
    private var lastReceiveTimestamp: Long = 0L
    private var consecutiveRestErrors: Int = 0
    private var isAppInForeground: Boolean = true

    // Disk cumulative counter baseline tracking
    @Volatile
    private var lastDiskSampleTime: Long = 0L
    @Volatile
    private var lastDiskReadBytes: Long? = null
    @Volatile
    private var lastDiskWriteBytes: Long? = null

    // Network cumulative counter baseline tracking
    @Volatile
    private var lastNetSampleTs: Long = 0L
    @Volatile
    private var lastNetRecvBytes: Long? = null
    @Volatile
    private var lastNetSentBytes: Long? = null

    fun initialize(url: String, token: String) {
        if (serverUrl == url && authToken == token && wsClient != null) {
            return
        }
        serverUrl = url
        authToken = token
        consecutiveRestErrors = 0
        lastServerTimestamp = 0L
        lastReceiveTimestamp = 0L
        lastDiskSampleTime = 0L
        lastDiskReadBytes = null
        lastDiskWriteBytes = null
        lastNetSampleTs = 0L
        lastNetRecvBytes = null
        lastNetSentBytes = null
        _diskReadRate.postValue(null)
        _diskWriteRate.postValue(null)
        _netDownloadRate.postValue(null)
        _netUploadRate.postValue(null)
        synchronized(diskReadBuffer) { diskReadBuffer.clear() }
        synchronized(diskWriteBuffer) { diskWriteBuffer.clear() }
        synchronized(netDownloadBuffer) { netDownloadBuffer.clear() }
        synchronized(netUploadBuffer) { netUploadBuffer.clear() }
        _diskReadHistory.postValue(emptyList())
        _diskWriteHistory.postValue(emptyList())
        _netDownloadHistory.postValue(emptyList())
        _netUploadHistory.postValue(emptyList())

        // Immediate REST fetch to populate UI right away
        fetchRestMetrics()

        // Connect WebSocket for real-time streaming
        startWebSocket()

        // Start fallback polling watcher
        startFallbackPolling()

        // Start freshness update ticker
        startFreshnessTicker()

        // Preload network diagnostics
        fetchNetworkPorts()
        fetchFirewallStatus()
    }

    fun onForeground() {
        isAppInForeground = true
        startFreshnessTicker()
        startFallbackPolling()
        if (_streamState.value != StreamState.LIVE_STREAMING) {
            fetchRestMetrics()
            startWebSocket()
        }
    }

    fun onBackground() {
        isAppInForeground = false
        freshnessJob?.cancel()
        // Slow down or pause polling in background to avoid battery and network drain
        pollingJob?.cancel()
    }

    private fun startWebSocket() {
        wsClient?.disconnect()
        wsClient = ZentraWebSocketClient(
            baseUrl = serverUrl,
            token = authToken,
            onStreamStateChanged = { state, msg ->
                _streamState.postValue(state)
                handleStreamStateChange(state, msg)
            },
            onMetricsReceived = { overview ->
                processIncomingOverview(overview, "WebSocket")
            },
            onAuthFailed = {
                handleAuthFailure()
            }
        )
        wsClient?.connect()
    }

    private fun handleStreamStateChange(state: StreamState, msg: String?) {
        when (state) {
            StreamState.LIVE_STREAMING -> {
                consecutiveRestErrors = 0
                _connectionState.postValue(ConnectionState.CONNECTED)
            }
            StreamState.RECONNECTING -> {
                Log.d(TAG, "Live streaming reconnecting in background: $msg")
                // Keep server as CONNECTED if REST is healthy, otherwise RECONNECTING
                if (consecutiveRestErrors >= 3) {
                    _connectionState.postValue(ConnectionState.RECONNECTING)
                }
            }
            StreamState.AUTH_FAILED -> {
                handleAuthFailure()
            }
            StreamState.DISCONNECTED -> {
                if (consecutiveRestErrors >= 3) {
                    _connectionState.postValue(ConnectionState.SERVER_UNAVAILABLE)
                }
            }
            StreamState.CONNECTING -> {
                // Do not downgrade overall connection state if already connected via REST
                if (_connectionState.value != ConnectionState.CONNECTED) {
                    _connectionState.postValue(ConnectionState.CONNECTING)
                }
            }
        }
    }

    private fun handleAuthFailure() {
        _connectionState.postValue(ConnectionState.AUTH_REQUIRED)
        _streamState.postValue(StreamState.AUTH_FAILED)
        pollingJob?.cancel()
        freshnessJob?.cancel()
        wsClient?.disconnect()
        _authInvalidEvent.postValue(true)
    }

    /**
     * Centralized telemetry processing preventing race conditions and stale overwrites.
     */
    @Synchronized
    private fun processIncomingOverview(overview: SystemOverview, source: String) {
        val serverTs = overview.resolvedTimestamp

        // Discard older packet arriving out-of-order from slower REST or network delays
        if (serverTs > 0L && serverTs < lastServerTimestamp) {
            Log.d(TAG, "[$source] Discarded older telemetry packet ($serverTs < $lastServerTimestamp)")
            return
        }

        if (serverTs > 0L) {
            lastServerTimestamp = serverTs
        }
        val now = System.currentTimeMillis()
        lastReceiveTimestamp = now
        consecutiveRestErrors = 0

        val (calcRead, calcWrite) = processDiskThroughput(overview.storage, now)
        _diskReadRate.postValue(calcRead)
        _diskWriteRate.postValue(calcWrite)

        val (calcDown, calcUp) = processNetworkThroughput(overview.network, serverTs, now)
        _netDownloadRate.postValue(calcDown)
        _netUploadRate.postValue(calcUp)

        _systemOverview.postValue(overview)
        _activeAlertsCount.postValue(overview.active_alerts_count)
        _lastMetricsTime.postValue(now)
        _connectionState.postValue(ConnectionState.CONNECTED)

        recordSample(overview, calcRead, calcWrite)
        try {
            AlertNotificationManager.getInstance(getApplication()).evaluateMetrics(overview, serverUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Alert evaluation warning: ${e.message}")
        }
    }

    fun processNetworkThroughput(
        network: NetworkMetrics,
        serverTsSec: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Long?, Long?> {
        val precalcDown = when {
            network.rates.downloadBytesPerSec > 0 -> network.rates.downloadBytesPerSec.toLong()
            network.rates.directDownloadSpeed > 0 -> network.rates.directDownloadSpeed.toLong()
            network.directDownload > 0 -> network.directDownload
            else -> null
        }
        val precalcUp = when {
            network.rates.uploadBytesPerSec > 0 -> network.rates.uploadBytesPerSec.toLong()
            network.rates.directUploadSpeed > 0 -> network.rates.directUploadSpeed.toLong()
            network.directUpload > 0 -> network.directUpload
            else -> null
        }

        var computedDown: Long? = null
        var computedUp: Long? = null

        if (precalcDown != null && precalcUp != null) {
            computedDown = precalcDown
            computedUp = precalcUp
        } else {
            val cumRecv = network.total_recv_bytes.takeIf { it > 0L }
            val cumSent = network.total_sent_bytes.takeIf { it > 0L }

            val lastTs = lastNetSampleTs
            val lastRecv = lastNetRecvBytes
            val lastSent = lastNetSentBytes

            val currentTs = if (serverTsSec > 0L) serverTsSec else (nowMs / 1000L)

            if (lastTs > 0L && (currentTs - lastTs) in 1..15) {
                val dtSec = (currentTs - lastTs).toDouble()
                if (cumRecv != null && lastRecv != null) {
                    val dRecv = cumRecv - lastRecv
                    computedDown = if (dRecv >= 0) (dRecv / dtSec).toLong() else null
                }
                if (cumSent != null && lastSent != null) {
                    val dSent = cumSent - lastSent
                    computedUp = if (dSent >= 0) (dSent / dtSec).toLong() else null
                }
            }

            if (cumRecv != null) lastNetRecvBytes = cumRecv
            if (cumSent != null) lastNetSentBytes = cumSent
            lastNetSampleTs = currentTs
        }

        val finalDown = computedDown ?: precalcDown
        val finalUp = computedUp ?: precalcUp

        network.calculatedDownloadBps = finalDown
        network.calculatedUploadBps = finalUp

        return Pair(finalDown, finalUp)
    }

    fun processDiskThroughput(storage: StorageMetrics, nowMs: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
        val precalcRead = storage.precalculatedReadRate
        val precalcWrite = storage.precalculatedWriteRate

        val cumRead = storage.cumulativeReadBytes
        val cumWrite = storage.cumulativeWriteBytes

        var computedRead: Long? = null
        var computedWrite: Long? = null

        if (precalcRead != null && precalcWrite != null) {
            computedRead = precalcRead
            computedWrite = precalcWrite
        } else if (cumRead != null || cumWrite != null) {
            val lastTime = lastDiskSampleTime
            val lastRead = lastDiskReadBytes
            val lastWrite = lastDiskWriteBytes

            if (lastTime > 0L && (nowMs - lastTime) >= 200L) {
                val dtSec = (nowMs - lastTime) / 1000.0
                if (cumRead != null && lastRead != null) {
                    val dRead = cumRead - lastRead
                    if (dRead >= 0) {
                        computedRead = (dRead / dtSec).toLong()
                    } else {
                        // Server reboot or counter reset; reset baseline
                        computedRead = null
                    }
                }
                if (cumWrite != null && lastWrite != null) {
                    val dWrite = cumWrite - lastWrite
                    if (dWrite >= 0) {
                        computedWrite = (dWrite / dtSec).toLong()
                    } else {
                        computedWrite = null
                    }
                }
            }
            if (cumRead != null) lastDiskReadBytes = cumRead
            if (cumWrite != null) lastDiskWriteBytes = cumWrite
            lastDiskSampleTime = nowMs
        }

        val finalRead = computedRead ?: precalcRead
        val finalWrite = computedWrite ?: precalcWrite

        storage.calculatedReadBps = finalRead
        storage.calculatedWriteBps = finalWrite

        return Pair(finalRead, finalWrite)
    }

    private fun recordSample(overview: SystemOverview, calcRead: Long? = null, calcWrite: Long? = null) {
        val rawCpu = overview.cpu.overall_usage
        val cpuVal = if (rawCpu.isFinite() && rawCpu >= 0f) rawCpu.coerceIn(0f, 100f) else 0f
        synchronized(cpuBuffer) {
            if (cpuBuffer.size >= 60) cpuBuffer.removeFirst()
            cpuBuffer.addLast(cpuVal)
            _cpuHistory.postValue(cpuBuffer.toList())
        }

        val rawRam = overview.memory.usage_percent
        val ramVal = if (rawRam.isFinite() && rawRam >= 0f) rawRam.coerceIn(0f, 100f) else 0f
        synchronized(ramBuffer) {
            if (ramBuffer.size >= 60) ramBuffer.removeFirst()
            ramBuffer.addLast(ramVal)
            _ramHistory.postValue(ramBuffer.toList())
        }

        if (calcRead != null) {
            val readVal = if (calcRead.toFloat().isFinite() && calcRead >= 0L) calcRead.toFloat() else 0f
            synchronized(diskReadBuffer) {
                if (diskReadBuffer.size >= 60) diskReadBuffer.removeFirst()
                diskReadBuffer.addLast(readVal)
                _diskReadHistory.postValue(diskReadBuffer.toList())
            }
        }

        if (calcWrite != null) {
            val writeVal = if (calcWrite.toFloat().isFinite() && calcWrite >= 0L) calcWrite.toFloat() else 0f
            synchronized(diskWriteBuffer) {
                if (diskWriteBuffer.size >= 60) diskWriteBuffer.removeFirst()
                diskWriteBuffer.addLast(writeVal)
                _diskWriteHistory.postValue(diskWriteBuffer.toList())
            }
        }

        val rawDown = overview.network.download_speed_bps.toFloat()
        val downVal = if (rawDown.isFinite() && rawDown >= 0f) rawDown else 0f
        synchronized(netDownloadBuffer) {
            if (netDownloadBuffer.size >= 60) netDownloadBuffer.removeFirst()
            netDownloadBuffer.addLast(downVal)
            _netDownloadHistory.postValue(netDownloadBuffer.toList())
        }

        val rawUp = overview.network.upload_speed_bps.toFloat()
        val upVal = if (rawUp.isFinite() && rawUp >= 0f) rawUp else 0f
        synchronized(netUploadBuffer) {
            if (netUploadBuffer.size >= 60) netUploadBuffer.removeFirst()
            netUploadBuffer.addLast(upVal)
            _netUploadHistory.postValue(netUploadBuffer.toList())
        }

        // Storage overall percentage: primary root fs '/' or first reported fs
        val rootFs = overview.storage.filesystems.firstOrNull { it.mount_point == "/" }
            ?: overview.storage.filesystems.firstOrNull()
        val rawStorage = rootFs?.usage_percent ?: 0f
        val storageVal = if (rawStorage.isFinite() && rawStorage >= 0f) rawStorage.coerceIn(0f, 100f) else 0f
        synchronized(storageBuffer) {
            if (storageBuffer.size >= 60) storageBuffer.removeFirst()
            storageBuffer.addLast(storageVal)
            _storageHistory.postValue(storageBuffer.toList())
        }
    }

    fun fetchRestMetrics() {
        if (serverUrl.isBlank() || authToken.isBlank()) return
        if (isRestFetchInProgress) return
        isRestFetchInProgress = true

        viewModelScope.launch {
            try {
                val result = ApiClient.instance.getSystemOverview(serverUrl, authToken)
                if (result.isSuccess) {
                    val overview = result.getOrNull()
                    if (overview != null) {
                        processIncomingOverview(overview, "REST")
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: ""
                    Log.w(TAG, "REST metrics fetch failed: $err")

                    if (err.contains("401") || err.contains("Unauthorized", ignoreCase = true)) {
                        handleAuthFailure()
                        return@launch
                    }

                    consecutiveRestErrors++
                    // Only downgrade connection if WebSocket stream is ALSO down
                    if (_streamState.value != StreamState.LIVE_STREAMING) {
                        if (consecutiveRestErrors in 3..5) {
                            _connectionState.postValue(ConnectionState.RECONNECTING)
                        } else if (consecutiveRestErrors > 5) {
                            _connectionState.postValue(ConnectionState.SERVER_UNAVAILABLE)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during REST metrics fetch: ${e.message}", e)
            } finally {
                isRestFetchInProgress = false
            }
        }
    }

    private fun startFallbackPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(3500)
                if (!isAppInForeground) continue

                // Only poll REST if live WebSocket stream is not currently streaming
                if (_streamState.value != StreamState.LIVE_STREAMING) {
                    fetchRestMetrics()
                }
            }
        }
    }

    private fun startFreshnessTicker() {
        freshnessJob?.cancel()
        freshnessJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (lastReceiveTimestamp == 0L) {
                    _metricsFreshnessText.postValue("Waiting for data...")
                    _isMetricsStale.postValue(false)
                    continue
                }

                val elapsedMs = System.currentTimeMillis() - lastReceiveTimestamp
                val elapsedSec = elapsedMs / 1000

                val isStale = elapsedSec >= 15
                _isMetricsStale.postValue(isStale)

                val text = when {
                    elapsedSec < 3 -> "Updated just now"
                    elapsedSec < 60 -> "Updated ${elapsedSec}s ago"
                    else -> "Updated ${elapsedSec / 60}m ago"
                }

                _metricsFreshnessText.postValue(
                    if (isStale) "Data may be outdated (${elapsedSec}s ago)" else text
                )
            }
        }
    }

    fun fetchNetworkPorts() {
        if (serverUrl.isBlank() || authToken.isBlank()) return
        _isLoadingPorts.postValue(true)
        viewModelScope.launch {
            val res = ApiClient.instance.getNetworkPorts(serverUrl, authToken)
            _isLoadingPorts.postValue(false)
            if (res.isSuccess) {
                _networkPorts.postValue(res.getOrNull())
            } else {
                Log.w(TAG, "Failed to load network ports: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun fetchFirewallStatus() {
        if (serverUrl.isBlank() || authToken.isBlank()) return
        _isLoadingFirewall.postValue(true)
        viewModelScope.launch {
            val res = ApiClient.instance.getFirewallStatus(serverUrl, authToken)
            _isLoadingFirewall.postValue(false)
            if (res.isSuccess) {
                _firewallStatus.postValue(res.getOrNull())
            } else {
                Log.w(TAG, "Failed to load firewall status: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Executes safe NetworkManager restart with bounded retry recovery.
     */
    fun restartNetworkService(onResult: (Boolean, String) -> Unit) {
        if (serverUrl.isBlank() || authToken.isBlank()) {
            onResult(false, "Server address or credentials missing.")
            return
        }

        if (_networkActionInProgress.value == true) {
            return
        }

        _networkActionInProgress.postValue(true)

        networkRestartJob?.cancel()
        networkRestartJob = viewModelScope.launch {
            Log.i(TAG, "Dispatching NetworkManager restart request...")
            val restartResult = ApiClient.instance.restartNetworkService(serverUrl, authToken)

            if (restartResult.isFailure) {
                val err = restartResult.exceptionOrNull()?.message ?: "Unknown error"
                // If it wasn't a connection drop during restart, report error immediately
                if (!err.contains("Connection", ignoreCase = true) && !err.contains("timeout", ignoreCase = true)) {
                    _networkActionInProgress.postValue(false)
                    onResult(false, err)
                    return@launch
                }
            }

            // Recovery phase: Server may temporarily disconnect while interfaces bounce
            Log.i(TAG, "Network restart initiated. Beginning bounded health-check recovery loop...")
            val retryDelays = listOf(2000L, 3000L, 4000L, 6000L, 8000L)
            var recovered = false

            for ((index, delayMs) in retryDelays.withIndex()) {
                delay(delayMs)
                Log.d(TAG, "Recovery probe attempt ${index + 1}/${retryDelays.size}...")
                val healthResult = ApiClient.instance.checkHealth(serverUrl)
                if (healthResult.isSuccess) {
                    recovered = true
                    break
                }
            }

            _networkActionInProgress.postValue(false)

            if (recovered) {
                Log.i(TAG, "Server connection successfully recovered after NetworkManager restart.")
                reconnect()
                fetchNetworkPorts()
                fetchFirewallStatus()
                onResult(true, "Network restarted successfully. Connection restored.")
            } else {
                Log.w(TAG, "Server did not recover within expected window after NetworkManager restart.")
                onResult(false, "Network restart was sent, but the server is not reachable yet. If your router assigned a new IP address, please verify the server address.")
            }
        }
    }

    /**
     * Coalesced single-flight telemetry refresh triggered by user action or manual pull.
     * Prevents duplicate in-flight requests, preserves last-known valid metrics on failure,
     * refreshes network diagnostics, and safely reconnects WebSocket only if not currently streaming.
     */
    fun refreshTelemetry(onResult: ((Boolean, String?) -> Unit)? = null) {
        if (serverUrl.isBlank() || authToken.isBlank()) {
            val msg = "Server address or credentials not configured."
            _refreshResultEvent.postValue(Event(RefreshResult(false, msg)))
            onResult?.invoke(false, msg)
            return
        }

        if (isRefreshingMetrics) {
            Log.d(TAG, "Telemetry refresh already in-flight. Skipping duplicate request.")
            return
        }

        isRefreshingMetrics = true
        _isRefreshing.postValue(true)

        viewModelScope.launch {
            var success = false
            var errorMsg: String? = null
            try {
                val result = ApiClient.instance.getSystemOverview(serverUrl, authToken)
                if (result.isSuccess) {
                    val overview = result.getOrNull()
                    if (overview != null) {
                        processIncomingOverview(overview, "Manual-Refresh")
                        success = true
                    }
                } else {
                    errorMsg = result.exceptionOrNull()?.message ?: "Telemetry refresh failed"
                    Log.w(TAG, "Telemetry refresh failure: $errorMsg")
                    CrashDiagnosticLogger.logDiagnostic(
                        operation = "manual_refresh",
                        protocol = "REST",
                        extraMessage = errorMsg
                    )
                    if (errorMsg.contains("401") || errorMsg.contains("Unauthorized", ignoreCase = true)) {
                        handleAuthFailure()
                        val authMsg = "Authentication required. Please sign in."
                        _refreshResultEvent.postValue(Event(RefreshResult(false, authMsg)))
                        onResult?.invoke(false, authMsg)
                        return@launch
                    }
                }

                // Also trigger diagnostics refresh
                fetchNetworkPorts()
                fetchFirewallStatus()

                // Reconnect live stream only if stream is down or disconnected
                if (_streamState.value != StreamState.LIVE_STREAMING) {
                    startWebSocket()
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unexpected error"
                Log.e(TAG, "Exception during telemetry refresh: $errorMsg", e)
                CrashDiagnosticLogger.logDiagnostic(
                    operation = "manual_refresh_exception",
                    protocol = "REST",
                    throwable = e
                )
            } finally {
                isRefreshingMetrics = false
                _isRefreshing.postValue(false)
                _refreshResultEvent.postValue(Event(RefreshResult(success, errorMsg)))
                onResult?.invoke(success, errorMsg)
            }
        }
    }

    fun reconnect() {
        consecutiveRestErrors = 0
        refreshTelemetry()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        freshnessJob?.cancel()
        networkRestartJob?.cancel()
        wsClient?.disconnect()
    }
}
