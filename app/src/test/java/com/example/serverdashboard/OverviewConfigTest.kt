package com.example.serverdashboard

import com.example.serverdashboard.data.model.CardId
import com.example.serverdashboard.data.model.CardSpan
import com.example.serverdashboard.data.model.OverviewConfig
import com.example.serverdashboard.data.storage.OverviewConfigStore
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewConfigTest {

    private val gson = Gson()

    @Test
    fun defaultConfiguration_matchesSpecification() {
        val config = OverviewConfig()
        val visible = config.getVisibleCards()

        // 8 visible cards by default
        assertEquals(8, visible.size)
        assertEquals(CardId.HERO, visible[0])
        assertEquals(CardId.CPU, visible[1])
        assertEquals(CardId.MEMORY, visible[2])
        assertEquals(CardId.STORAGE, visible[3])
        assertEquals(CardId.THERMALS, visible[4])
        assertEquals(CardId.NETWORK, visible[5])
        assertEquals(CardId.SERVICES, visible[6])
        assertEquals(CardId.INCIDENTS, visible[7])

        // 3 hidden cards by default
        assertTrue(config.hiddenCards.contains(CardId.DISK_IO))
        assertTrue(config.hiddenCards.contains(CardId.PORTS))
        assertTrue(config.hiddenCards.contains(CardId.CONTAINERS))

        assertNull(config.selectedMountPoint)
    }

    @Test
    fun defaultSpans_matchSpecification() {
        assertEquals(CardSpan.FULL, CardId.HERO.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.CPU.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.MEMORY.defaultSpan)
        assertEquals(CardSpan.FULL, CardId.STORAGE.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.THERMALS.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.NETWORK.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.SERVICES.defaultSpan)
        assertEquals(CardSpan.HALF, CardId.INCIDENTS.defaultSpan)
        assertEquals(CardSpan.FULL, CardId.DISK_IO.defaultSpan)
        assertEquals(CardSpan.FULL, CardId.PORTS.defaultSpan)
        assertEquals(CardSpan.FULL, CardId.CONTAINERS.defaultSpan)
    }

    @Test
    fun getVisibleCards_excludesHiddenCards() {
        val config = OverviewConfig(
            cardOrder = listOf(CardId.HERO, CardId.CPU, CardId.MEMORY, CardId.STORAGE),
            hiddenCards = setOf(CardId.CPU)
        )
        val visible = config.getVisibleCards()

        assertEquals(3, visible.size)
        assertEquals(listOf(CardId.HERO, CardId.MEMORY, CardId.STORAGE), visible)
        assertFalse(visible.contains(CardId.CPU))
    }

    @Test
    fun getVisibleCards_neverEmpty() {
        val config = OverviewConfig(
            cardOrder = emptyList(),
            hiddenCards = CardId.values().toSet()
        )
        val visible = config.getVisibleCards()
        assertTrue(visible.isNotEmpty())
        assertEquals(CardId.defaultOrder(), visible)
    }

    @Test
    fun validation_removesDuplicatesAndAppendsMissing() {
        val raw = OverviewConfig(
            cardOrder = listOf(CardId.HERO, CardId.CPU, CardId.HERO, CardId.CPU),
            hiddenCards = emptySet()
        )
        val validated = raw.validated()

        // Duplicates removed
        assertEquals(CardId.values().size, validated.cardOrder.size)
        assertEquals(CardId.HERO, validated.cardOrder[0])
        assertEquals(CardId.CPU, validated.cardOrder[1])
        assertEquals(validated.cardOrder.distinct(), validated.cardOrder)
    }

    @Test
    fun serialization_roundtripsCorrectly() {
        val original = OverviewConfig(
            cardOrder = listOf(CardId.CPU, CardId.HERO, CardId.DISK_IO),
            hiddenCards = setOf(CardId.PORTS),
            selectedMountPoint = "/mnt/storage"
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, OverviewConfig::class.java).validated()

        assertEquals("/mnt/storage", restored.selectedMountPoint)
        assertTrue(restored.hiddenCards.contains(CardId.PORTS))
        assertEquals(CardId.CPU, restored.cardOrder[0])
        assertEquals(CardId.HERO, restored.cardOrder[1])
        assertEquals(CardId.DISK_IO, restored.cardOrder[2])
    }

    @Test
    fun sanitizeServerKey_cleansProtocolAndSpecialCharacters() {
        assertEquals("default_server", OverviewConfigStore.sanitizeServerKey(null))
        assertEquals("default_server", OverviewConfigStore.sanitizeServerKey(""))
        assertEquals("192.168.1.100_8080", OverviewConfigStore.sanitizeServerKey("http://192.168.1.100:8080/"))
        assertEquals("myserver.ts.net", OverviewConfigStore.sanitizeServerKey("https://myserver.ts.net"))
        assertEquals("node1.local_api", OverviewConfigStore.sanitizeServerKey("ws://node1.local/api"))
    }
}
