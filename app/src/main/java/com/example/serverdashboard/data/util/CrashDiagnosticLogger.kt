package com.example.serverdashboard.data.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.regex.Pattern

/**
 * Privacy-safe diagnostic logger for network errors, telemetry parsing failures,
 * and uncaught exceptions. Strips all personal IP addresses, hostnames, auth tokens,
 * passwords, and local filesystem user paths before recording.
 */
object CrashDiagnosticLogger {

    private const val TAG = "CrashDiagnostics"
    private const val MAX_HISTORY = 30

    data class DiagnosticRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String,
        val androidVersion: String,
        val deviceModel: String,
        val operation: String,
        val protocol: String?,
        val httpStatusCode: Int?,
        val exceptionType: String?,
        val sanitizedMessage: String?,
        val sanitizedStackTrace: String?
    )

    private val records = ConcurrentLinkedDeque<DiagnosticRecord>()

    // Redaction patterns
    private val IPV4_PATTERN = Pattern.compile("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b""")
    private val IPV6_PATTERN = Pattern.compile("""\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}(?::\d+)?\b""")
    private val TAILSCALE_PATTERN = Pattern.compile("""\b[\w.-]+(?:\.ts\.net|\.tailscale\.net|\.local|\.lan)(?::\d+)?\b""", Pattern.CASE_INSENSITIVE)
    private val JWT_PATTERN = Pattern.compile("""ey[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+""")
    private val BEARER_PATTERN = Pattern.compile("""Bearer\s+[A-Za-z0-9._-]+""", Pattern.CASE_INSENSITIVE)
    private val QUERY_SECRET_PATTERN = Pattern.compile("""([?&](?:token|password|secret|auth|api_key|access_token)=)[^&\s]+""", Pattern.CASE_INSENSITIVE)
    private val JSON_SECRET_PATTERN = Pattern.compile("""("(?:password|token|secret|access_token)"\s*:\s*")[^"]+(")""", Pattern.CASE_INSENSITIVE)
    private val USER_PATH_PATTERN = Pattern.compile("""(?:[a-zA-Z]:[\\/]|/)(?:Users|home)[\\/]([^\\/\s]+)""", Pattern.CASE_INSENSITIVE)

    @Volatile
    private var customAppVersion: String? = null
    @Volatile
    private var isHandlerInstalled = false

    fun setAppVersionForTesting(version: String) {
        customAppVersion = version
    }

    /**
     * Sanitizes strings by removing IPs, tokens, passwords, and sensitive paths.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var sanitized = input

        // 1. Bearer tokens and JWTs
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED_TOKEN]")
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]")

        // 2. Query param secrets and JSON secrets
        sanitized = QUERY_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]")
        sanitized = JSON_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$2")

        // 3. Tailscale & local hostnames
        sanitized = TAILSCALE_PATTERN.matcher(sanitized).replaceAll("[REDACTED_HOST]")

        // 4. IP Addresses
        sanitized = IPV4_PATTERN.matcher(sanitized).replaceAll("[REDACTED_IP]")
        sanitized = IPV6_PATTERN.matcher(sanitized).replaceAll("[REDACTED_IPV6]")

        // 5. User home paths
        val userMatcher = USER_PATH_PATTERN.matcher(sanitized)
        if (userMatcher.find()) {
            sanitized = userMatcher.replaceAll("[REDACTED_USER_PATH]")
        }

        return sanitized
    }

    /**
     * Sanitizes and bounds a stack trace to relevant frames.
     */
    fun sanitizeStackTrace(throwable: Throwable?): String? {
        if (throwable == null) return null
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val fullTrace = sw.toString()
            sanitize(fullTrace)
        } catch (e: Exception) {
            "${throwable.javaClass.simpleName}: [Trace unavailable]"
        }
    }

    /**
     * Logs a privacy-safe diagnostic record for an operation.
     */
    fun logDiagnostic(
        operation: String,
        protocol: String? = null,
        httpStatusCode: Int? = null,
        throwable: Throwable? = null,
        extraMessage: String? = null
    ): DiagnosticRecord {
        val appVer = customAppVersion ?: try {
            "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        val osVer = try {
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        } catch (e: Exception) {
            "Android (Unknown)"
        }

        val model = try {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            "Unknown Device"
        }

        val sanitizedMsg = sanitize(extraMessage ?: throwable?.message)
        val sanitizedTrace = sanitizeStackTrace(throwable)
        val excType = throwable?.javaClass?.name

        val record = DiagnosticRecord(
            appVersion = appVer,
            androidVersion = osVer,
            deviceModel = model,
            operation = operation,
            protocol = protocol,
            httpStatusCode = httpStatusCode,
            exceptionType = excType,
            sanitizedMessage = sanitizedMsg.ifEmpty { null },
            sanitizedStackTrace = sanitizedTrace
        )

        records.add(record)
        while (records.size > MAX_HISTORY) {
            records.pollFirst()
        }

        val logSummary = buildString {
            append("[$operation]")
            if (protocol != null) append(" Proto: $protocol")
            if (httpStatusCode != null) append(" HTTP: $httpStatusCode")
            if (excType != null) append(" Exc: $excType")
            if (sanitizedMsg.isNotEmpty()) append(" Msg: $sanitizedMsg")
        }

        if (throwable != null) {
            Log.e(TAG, logSummary)
        } else {
            Log.i(TAG, logSummary)
        }

        return record
    }

    /**
     * Installs global uncaught exception handler that sanitizes crash logs
     * before delegating to the platform default handler.
     */
    @Synchronized
    fun initUncaughtExceptionHandler(context: Context? = null) {
        if (isHandlerInstalled) return
        isHandlerInstalled = true

        if (context != null) {
            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                customAppVersion = "${pkgInfo.versionName} ($verCode)"
            } catch (e: Exception) {
                // Keep default
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logDiagnostic(
                    operation = "uncaught_exception",
                    protocol = null,
                    httpStatusCode = null,
                    throwable = throwable,
                    extraMessage = "Thread: ${thread.name}"
                )
            } catch (e: Exception) {
                // Fail-safe: do not mask the primary exception
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getRecentRecords(): List<DiagnosticRecord> = records.toList()

    fun clearRecords() {
        records.clear()
    }
}
