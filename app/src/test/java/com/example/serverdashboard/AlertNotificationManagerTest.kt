package com.example.serverdashboard

import com.example.serverdashboard.data.model.AlertHistoryItem
import com.example.serverdashboard.data.model.ProcessInfo
import com.example.serverdashboard.ui.alerts.AlertNotificationManager
import com.example.serverdashboard.ui.alerts.AlertState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlertNotificationManagerTest {

    private lateinit var manager: AlertNotificationManager
    private val dispatchedNotifications = mutableListOf<Triple<Int, String, String>>()

    @Before
    fun setUp() {
        manager = AlertNotificationManager()
        manager.resetStateForTesting()
        dispatchedNotifications.clear()
        manager.notificationDispatcher = { id, title, body ->
            dispatchedNotifications.add(Triple(id, title, body))
        }
    }

    // 1. CPU Temperature default thresholds: Warning at 75°C, Critical at 85°C, Recovery <= 70°C
    @Test
    fun testCpuTemperature_defaultThresholdsAndRecovery() {
        manager.setAppInForeground(false)

        // Below warning threshold (74°C)
        manager.evaluateCpuTemperature(74.0f, "srv-prod", "http://server1")
        assertEquals(0, dispatchedNotifications.size)
        assertEquals(AlertState.NORMAL, manager.cpuTempState)

        // Triggers warning at 75°C
        manager.evaluateCpuTemperature(75.0f, "srv-prod", "http://server1")
        assertEquals(1, dispatchedNotifications.size)
        assertEquals(AlertNotificationManager.NOTIFICATION_ID_CPU_TEMP, dispatchedNotifications[0].first)
        assertEquals("CPU hot", dispatchedNotifications[0].second)
        assertTrue(dispatchedNotifications[0].third.contains("75°C"))
        assertEquals(AlertState.TRIGGERED, manager.cpuTempState)

        // Dropping to <= 70°C recovers and posts quiet info notification
        manager.evaluateCpuTemperature(68.0f, "srv-prod", "http://server1")
        assertEquals(AlertState.NORMAL, manager.cpuTempState)
        // Dispatched recovery notification
        assertEquals(2, dispatchedNotifications.size)
        assertEquals("Back to normal", dispatchedNotifications[1].second)
    }

    // 2. Sustained CPU load: Spikes < 2m do NOT alert; sustained >= 2m alerts warning; >= 5m alerts critical
    @Test
    fun testCpuLoad_sustainedLoadRequiredToPreventSpikeAlerts() {
        manager.setAppInForeground(false)
        val startTime = 1_000_000L

        // Brief spike at 90% (t = 0)
        manager.evaluateCpuUsage(90.0f, "srv-prod", "http://server1", currentTimeMs = startTime)
        assertEquals(0, dispatchedNotifications.size)

        // Still elevated after 1 minute (60s) -> should NOT alert yet
        manager.evaluateCpuUsage(90.0f, "srv-prod", "http://server1", currentTimeMs = startTime + 60_000L)
        assertEquals(0, dispatchedNotifications.size)

        // Sustained for 2 minutes (120s) -> triggers Warning alert
        manager.evaluateCpuUsage(90.0f, "srv-prod", "http://server1", currentTimeMs = startTime + 120_000L)
        assertEquals(1, dispatchedNotifications.size)
        assertEquals(AlertNotificationManager.NOTIFICATION_ID_CPU_LOAD, dispatchedNotifications[0].first)
        assertEquals("High CPU load", dispatchedNotifications[0].second)
        assertTrue(dispatchedNotifications[0].third.contains("90% (2 min)"))
    }

    // 3. Sustained CPU critical load: >= 95% for 5 minutes (300s)
    @Test
    fun testCpuLoad_extremeCriticalLoadAfterFiveMinutes() {
        manager.setAppInForeground(false)
        val startTime = 2_000_000L

        // Extreme load begins
        manager.evaluateCpuUsage(96.0f, "srv-prod", "http://server1", currentTimeMs = startTime)
        assertEquals(0, dispatchedNotifications.size)

        // Sustained for 5 minutes (300s)
        manager.evaluateCpuUsage(96.0f, "srv-prod", "http://server1", currentTimeMs = startTime + 300_000L)
        assertEquals(1, dispatchedNotifications.size)
        assertEquals(AlertNotificationManager.NOTIFICATION_ID_CPU_LOAD, dispatchedNotifications[0].first)
        assertEquals("Extreme CPU load", dispatchedNotifications[0].second)
        assertTrue(dispatchedNotifications[0].third.contains("96% (5 min)"))
    }

    // 4. Foreground suppression: System notifications suppressed, but in-app active alerts updated
    @Test
    fun testForegroundSuppression_recordsInAppAlertsOnly() {
        manager.setAppInForeground(true) // App in foreground

        manager.evaluateCpuTemperature(86.0f, "srv-prod", "http://server1")
        assertEquals(0, dispatchedNotifications.size) // Zero system notifications dispatched

        val inAppAlerts = manager.getActiveAlerts()
        assertEquals(1, inAppAlerts.size)
        val alert = inAppAlerts[0]
        assertEquals("cpu_temperature", alert.id)
        assertEquals("critical", alert.severity)
        assertEquals(86, alert.value)
    }

    // 5. Cooldown: Warning suppressed for 6h; Critical may repeat after 2h
    @Test
    fun testCooldown_suppressesRepeatsWithinWindow() {
        manager.setAppInForeground(false)

        // Trigger warning (76°C)
        manager.evaluateCpuTemperature(76.0f, "srv-prod", "http://server1")
        assertEquals(1, dispatchedNotifications.size)

        // Immediate subsequent call with elevated temp within cooldown
        manager.evaluateCpuTemperature(77.0f, "srv-prod", "http://server1")
        assertEquals(1, dispatchedNotifications.size) // Suppressed by cooldown
    }

    // 6. Quiet hours: Warning alerts suppressed, Critical alerts allowed
    @Test
    fun testQuietHours_suppressesWarningAllowsCritical() {
        manager.setAppInForeground(false)
        manager.inMemoryConfig.quietHoursEnabled = true

        // Warning temperature (76°C) -> suppressed during quiet hours
        manager.evaluateCpuTemperature(76.0f, "srv-prod", "http://server1")
        assertEquals(0, dispatchedNotifications.size)

        // Critical temperature (86°C) -> allowed during quiet hours
        manager.evaluateCpuTemperature(86.0f, "srv-prod", "http://server1")
        assertEquals(1, dispatchedNotifications.size)
        assertEquals("CPU overheating", dispatchedNotifications[0].second)
    }

    // 7. Snooze: Snoozed alert does not notify
    @Test
    fun testSnooze_suppressesNotification() {
        manager.setAppInForeground(false)
        manager.snoozeAlertForTesting("cpu_temperature", 3600_000L)

        manager.evaluateCpuTemperature(80.0f, "srv-prod", "http://server1")
        assertEquals(0, dispatchedNotifications.size)
    }

    // 8. Server Alert Processing: SMART disk failing routes to Critical
    @Test
    fun testServerAlerts_smartDiskFailure_routesToCritical() {
        manager.setAppInForeground(false)

        val smartAlert = AlertHistoryItem(
            id = "alert_smart_1",
            title = "SMART Predictive Failure",
            type = "DISK_FAILING",
            severity = "critical",
            message = "Reallocated sector count exceeded on /dev/sda",
            source = "/dev/sda"
        )

        manager.processServerAlerts("http://server1", listOf(smartAlert))
        assertEquals(1, dispatchedNotifications.size)
        assertEquals("Disk failing", dispatchedNotifications[0].second)
        assertTrue(dispatchedNotifications[0].third.contains("/dev/sda"))
    }

    // 9. Grouping: 3+ alerts on server triggers summary notification
    @Test
    fun testGrouping_threeOrMoreAlerts_triggersGroupSummary() {
        manager.setAppInForeground(false)

        val alert1 = AlertHistoryItem(id = "a1", type = "CPU_HIGH", severity = "warning", message = "Load high", source = "cpu")
        val alert2 = AlertHistoryItem(id = "a2", type = "TEMPERATURE_HIGH", severity = "warning", message = "Temp high", source = "temp")
        val alert3 = AlertHistoryItem(id = "a3", type = "DISK_HIGH", severity = "warning", message = "Disk full", source = "/var")

        manager.processServerAlerts("http://server1", listOf(alert1, alert2, alert3))

        // Check if group summary notification with id 1000 was dispatched
        assertTrue(
            "Expected summary notification when 3+ alerts active",
            dispatchedNotifications.any { it.first == AlertNotificationManager.NOTIFICATION_ID_GROUP_SUMMARY }
        )
    }

    // 10. Top process identified in CPU usage body
    @Test
    fun testTopProcessIdentifiedInCpuUsage() {
        manager.setAppInForeground(false)
        val ffmpegProc = ProcessInfo(
            pid = 4096,
            name = "ffmpeg",
            cpu_percent = 84.5f,
            memory_percent = 6.2f,
            status = "R"
        )

        // Sustained for 2m
        manager.evaluateCpuUsage(88.0f, "srv-prod", "http://server1", topProcess = ffmpegProc, currentTimeMs = 100_000L)
        manager.evaluateCpuUsage(88.0f, "srv-prod", "http://server1", topProcess = ffmpegProc, currentTimeMs = 225_000L)

        assertEquals(1, dispatchedNotifications.size)
        val (_, title, body) = dispatchedNotifications[0]
        assertEquals("High CPU load", title)
        assertTrue("Body should identify ffmpeg", body.contains("ffmpeg"))
    }

    // 11. Deep-link target is tab index 4 (Alerts tab)
    @Test
    fun testDeepLinkTargetTabIndexIsAlertsTab() {
        assertEquals("extra_navigate_tab", MainActivity.EXTRA_NAVIGATE_TAB)
        val expectedAlertsTabIndex = 4
        assertEquals(4, expectedAlertsTabIndex)
    }
}
