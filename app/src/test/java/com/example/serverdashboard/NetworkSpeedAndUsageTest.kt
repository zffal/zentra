package com.example.serverdashboard

import com.example.serverdashboard.data.model.DailyUsageItem
import com.example.serverdashboard.data.model.NetworkUsageResponse
import com.example.serverdashboard.data.model.PeakUsageDay
import com.example.serverdashboard.data.model.UsageTotals
import com.example.serverdashboard.ui.alerts.AlertNotificationManager
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.SpeedHysteresisTracker
import org.junit.Assert.*
import org.junit.Test

class NetworkSpeedAndUsageTest {

    // 1. Formatters.formatSpeed basic bytes and bits
    @Test
    fun testFormatSpeed_bytesBasics() {
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertEquals("512.0 B/s", Formatters.formatSpeed(512L))
        assertEquals("1.0 KB/s", Formatters.formatSpeed(1024L))
        assertEquals("1.0 MB/s", Formatters.formatSpeed(1024L * 1024L))
        assertEquals("1.0 GB/s", Formatters.formatSpeed(1024L * 1024L * 1024L))
    }

    @Test
    fun testFormatSpeed_bitsBasics() {
        assertEquals("0 bps", Formatters.formatSpeed(0L, useBits = true))
        // 125 bytes = 1000 bits = 1.0 Kbps
        assertEquals("1.0 Kbps", Formatters.formatSpeed(125L, useBits = true))
        // 125,000 bytes = 1,000,000 bits = 1.0 Mbps
        assertEquals("1.0 Mbps", Formatters.formatSpeed(125_000L, useBits = true))
        // 125,000,000 bytes = 1,000,000,000 bits = 1.0 Gbps
        assertEquals("1.0 Gbps", Formatters.formatSpeed(125_000_000L, useBits = true))
    }

    @Test
    fun testFormatSpeed_nullAndGuards() {
        assertEquals("--", Formatters.formatSpeed(null))
        assertEquals("0 B/s", Formatters.formatSpeed(-50L))
        assertEquals("0 bps", Formatters.formatSpeed(-50L, useBits = true))
    }

    // 2. Formatters.formatSpeed hysteresis boundary stabilization
    @Test
    fun testFormatSpeed_hysteresisPreventsFlickering() {
        val tracker = SpeedHysteresisTracker()

        // Initial sample: 950 KB/s
        val rate950KB = 950L * 1024L
        val formatted1 = Formatters.formatSpeed(rate950KB, tracker = tracker)
        assertEquals("950.0 KB/s", formatted1)
        assertEquals(1, tracker.lastUnitIndex) // KB/s is index 1

        // Fluctuation slightly above 1024 KB/s: 1030 KB/s
        // 1030 < 1024 * 1.05 = 1075.2 KB/s, so hysteresis keeps it at KB/s!
        val rate1030KB = 1030L * 1024L
        val formatted2 = Formatters.formatSpeed(rate1030KB, tracker = tracker)
        assertEquals("1030.0 KB/s", formatted2)
        assertEquals(1, tracker.lastUnitIndex)

        // Clear step-up to 1200 KB/s (> 1.05 * 1024 = 1075.2 KB/s)
        val rate1200KB = 1200L * 1024L
        val formatted3 = Formatters.formatSpeed(rate1200KB, tracker = tracker)
        assertEquals("1.2 MB/s", formatted3)
        assertEquals(2, tracker.lastUnitIndex) // MB/s is index 2

        // Fluctuation back down slightly below 1.0 MB/s: 1000 KB/s = ~0.976 MB/s
        // 0.976 >= 0.95, so hysteresis retains MB/s and prevents flicker!
        val rate1000KB = 1000L * 1024L
        val formatted4 = Formatters.formatSpeed(rate1000KB, tracker = tracker)
        assertEquals("1.0 MB/s", formatted4)
        assertEquals(2, tracker.lastUnitIndex)

        // Substantial drop below 0.95 factor: 850 KB/s = ~0.83 MB/s (< 0.95)
        // Transitions cleanly back down to KB/s
        val rate850KB = 850L * 1024L
        val formatted5 = Formatters.formatSpeed(rate850KB, tracker = tracker)
        assertEquals("850.0 KB/s", formatted5)
        assertEquals(1, tracker.lastUnitIndex)
    }

    // 3. Formatters.formatUsageDate timezone and formatting
    @Test
    fun testFormatUsageDate_validAndFallback() {
        val formattedDate = Formatters.formatUsageDate("2026-10-15", "UTC")
        assertEquals("Oct 15, 2026", formattedDate)

        val formattedShort = Formatters.formatUsageDateShort("2026-10-15", "UTC")
        assertEquals("Oct 15", formattedShort)

        // Malformed date returns raw string without throwing exception
        val malformed = "not-a-date"
        assertEquals(malformed, Formatters.formatUsageDate(malformed, "UTC"))
        assertEquals(malformed, Formatters.formatUsageDateShort(malformed, "UTC"))
    }

    // 4. NetworkUsageResponse models and null-safety
    @Test
    fun testNetworkUsageResponse_defaultsAndCalculations() {
        val days = listOf(
            DailyUsageItem("2026-10-09", rxBytes = 2_000_000L, txBytes = 1_000_000L, totalBytes = 3_000_000L),
            DailyUsageItem("2026-10-10", rxBytes = 5_000_000L, txBytes = 3_000_000L, totalBytes = 8_000_000L),
            DailyUsageItem("2026-10-11", rxBytes = 1_000_000L, txBytes = 500_000L, totalBytes = 1_500_000L)
        )
        val totals = UsageTotals(rxBytes = 8_000_000L, txBytes = 4_500_000L, totalBytes = 12_500_000L)
        val peak = PeakUsageDay(date = "2026-10-10", totalBytes = 8_000_000L)

        val response = NetworkUsageResponse(
            timezone = "UTC",
            interfaces = listOf("eth0", "wlan0"),
            days = days,
            totals = totals,
            peak = peak,
            trackingSince = "2026-09-01"
        )

        assertEquals("UTC", response.timezone)
        assertEquals(2, response.interfaces.size)
        assertEquals(3, response.days.size)
        assertEquals(12_500_000L, response.totals.total)
        assertEquals("2026-10-10", response.peak?.date)
        assertEquals(8_000_000L, response.peak?.total)
        assertEquals("2026-09-01", response.trackingSince)

        // Verify max by total matches peak
        val maxDay = response.days.maxByOrNull { it.total }
        assertNotNull(maxDay)
        assertEquals("2026-10-10", maxDay?.date)
        assertEquals(8_000_000L, maxDay?.total)
    }

    // 5. AlertNotificationManager.notifyDataCapWarning
    @Test
    fun testAlertNotificationManager_notifyDataCapWarning() {
        val manager = AlertNotificationManager()
        manager.resetStateForTesting()

        val captured = mutableListOf<Triple<Int, String, String>>()
        manager.notificationDispatcher = { id, title, body ->
            captured.add(Triple(id, title, body))
        }

        manager.notifyDataCapWarning(
            serverUrl = "http://192.168.1.42",
            percentage = 85.5f,
            usedBytes = 85_500_000_000L,
            capBytes = 100_000_000_000L
        )

        assertEquals(1, captured.size)
        val notif = captured[0]
        assertEquals(AlertNotificationManager.NOTIFICATION_ID_DATA_CAP, notif.first)
        assertEquals("Data Cap Warning", notif.second)
        assertTrue("Body should contain 86%", notif.third.contains("86%"))
        assertTrue("Body should mention monthly data allowance", notif.third.contains("monthly data allowance"))
    }
}
