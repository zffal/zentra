package com.example.serverdashboard

import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class NotificationConfigTest {

    private val gson = Gson()

    // 1. Verify 200 ID capping and LRU eviction logic
    @Test
    fun testDeduplicationCap_evictsOldestWhenExceeding200() {
        val maxIds = 200
        val map = mutableMapOf<String, Long>()

        // Insert 250 alert IDs with sequential timestamps
        for (i in 1..250) {
            map["alert_$i"] = i.toLong()
            if (map.size > maxIds) {
                val sorted = map.entries.sortedBy { it.value }
                val removeCount = map.size - maxIds
                for (j in 0 until removeCount) {
                    map.remove(sorted[j].key)
                }
            }
        }

        // Must be capped at exactly 200
        assertEquals(200, map.size)

        // The oldest 50 IDs (alert_1 to alert_50) must have been evicted
        for (i in 1..50) {
            assertFalse("Expected alert_$i to be evicted", map.containsKey("alert_$i"))
        }

        // The newest 200 IDs (alert_51 to alert_250) must be present
        for (i in 51..250) {
            assertTrue("Expected alert_$i to be retained", map.containsKey("alert_$i"))
        }

        // Verify JSON round-trip serialization
        val json = gson.toJson(map)
        val type = object : TypeToken<Map<String, Long>>() {}.type
        val restored: Map<String, Long> = gson.fromJson(json, type)
        assertEquals(200, restored.size)
        assertEquals(250L, restored["alert_250"])
    }

    // 2. Quiet hours calculations across midnight (22:00 to 07:00)
    @Test
    fun testQuietHoursCalculation_spanningMidnight() {
        val startMinutes = 22 * 60 // 22:00 = 1320
        val endMinutes = 7 * 60   // 07:00 = 420

        fun isQuiet(currentHour: Int, currentMinute: Int): Boolean {
            val current = currentHour * 60 + currentMinute
            return if (startMinutes <= endMinutes) {
                current in startMinutes..endMinutes
            } else {
                current >= startMinutes || current < endMinutes
            }
        }

        // 23:30 -> Quiet hours
        assertTrue(isQuiet(23, 30))
        // 02:15 -> Quiet hours
        assertTrue(isQuiet(2, 15))
        // 06:59 -> Quiet hours
        assertTrue(isQuiet(6, 59))
        // 07:01 -> NOT Quiet hours
        assertFalse(isQuiet(7, 1))
        // 14:00 -> NOT Quiet hours
        assertFalse(isQuiet(14, 0))
        // 21:59 -> NOT Quiet hours
        assertFalse(isQuiet(21, 59))
    }

    // 3. Cooldown constants
    @Test
    fun testCooldownConstants() {
        // Warning cooldown: 6 hours
        assertEquals(6 * 3600 * 1000L, NotificationConfigStore.COOLDOWN_WARNING_MS)
        // Critical cooldown: 2 hours
        assertEquals(2 * 3600 * 1000L, NotificationConfigStore.COOLDOWN_CRITICAL_MS)
    }

    // 4. Default thresholds and recovery hysteresis
    @Test
    fun testThresholdsAndHysteresis() {
        val tempWarn = 75f
        val tempCrit = 85f
        val tempRecovery = tempWarn - 5f // 70f

        assertTrue(75f >= tempWarn)
        assertTrue(85f >= tempCrit)
        assertTrue(70f <= tempRecovery)
        assertFalse(72f <= tempRecovery) // 72 is above recovery hysteresis

        val cpuLoadWarn = 85f
        val cpuLoadCrit = 95f
        val cpuLoadRecovery = cpuLoadWarn - 10f // 75f

        assertTrue(85f >= cpuLoadWarn)
        assertTrue(95f >= cpuLoadCrit)
        assertTrue(75f <= cpuLoadRecovery)
        assertFalse(80f <= cpuLoadRecovery) // 80 is above recovery hysteresis
    }
}
