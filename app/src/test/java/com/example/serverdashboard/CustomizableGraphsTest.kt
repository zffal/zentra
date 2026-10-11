package com.example.serverdashboard

import com.example.serverdashboard.data.model.BarItem
import com.example.serverdashboard.data.model.GraphConfig
import com.example.serverdashboard.data.model.GraphMetric
import com.example.serverdashboard.data.model.GraphStyle
import com.example.serverdashboard.data.model.GraphTimeRange
import com.example.serverdashboard.data.storage.GraphConfigStore
import com.example.serverdashboard.ui.util.Formatters
import org.junit.Assert.*
import org.junit.Test

class CustomizableGraphsTest {

    @Test
    fun testRateMetricsExcludeRingGauge() {
        val rateMetrics = listOf(
            GraphMetric.DISK_READ,
            GraphMetric.DISK_WRITE,
            GraphMetric.NET_DOWNLOAD,
            GraphMetric.NET_UPLOAD
        )

        for (metric in rateMetrics) {
            assertTrue("${metric.name} must be identified as rate metric", metric.isRate)
            assertFalse("${metric.name} must not allow RING gauge", metric.allowedStyles.contains(GraphStyle.RING))
            assertTrue("${metric.name} must allow LINE graph", metric.allowedStyles.contains(GraphStyle.LINE))
            assertTrue("${metric.name} must allow SEMI_CIRCLE gauge", metric.allowedStyles.contains(GraphStyle.SEMI_CIRCLE))
            assertTrue("${metric.name} must allow BAR graph", metric.allowedStyles.contains(GraphStyle.BAR))
        }
    }

    @Test
    fun testPercentageMetricsAllowAllStyles() {
        val percentMetrics = listOf(
            GraphMetric.CPU,
            GraphMetric.MEMORY,
            GraphMetric.STORAGE
        )

        for (metric in percentMetrics) {
            assertFalse("${metric.name} must not be a rate metric", metric.isRate)
            assertTrue("${metric.name} must allow RING gauge", metric.allowedStyles.contains(GraphStyle.RING))
            assertTrue("${metric.name} must allow SEMI_CIRCLE gauge", metric.allowedStyles.contains(GraphStyle.SEMI_CIRCLE))
            assertTrue("${metric.name} must allow LINE graph", metric.allowedStyles.contains(GraphStyle.LINE))
            assertTrue("${metric.name} must allow BAR graph", metric.allowedStyles.contains(GraphStyle.BAR))
        }
    }

    @Test
    fun testConfigValidationCorrectsInvalidStyleCombinations() {
        // Disk Read + RING is invalid (rates have no fixed 100% capacity)
        val invalidConfig = GraphConfig(
            id = "test_1",
            metric = GraphMetric.DISK_READ,
            style = GraphStyle.RING
        )

        val validated = invalidConfig.validated()
        assertNotEquals("Invalid style RING must be corrected", GraphStyle.RING, validated.style)
        assertTrue("Corrected style must be allowed for DISK_READ", GraphMetric.DISK_READ.allowedStyles.contains(validated.style))
    }

    @Test
    fun testConfigValidationPreservesValidCombinations() {
        val validConfig1 = GraphConfig(
            id = "test_2",
            metric = GraphMetric.CPU,
            style = GraphStyle.RING
        )
        assertEquals(GraphStyle.RING, validConfig1.validated().style)

        val validConfig2 = GraphConfig(
            id = "test_3",
            metric = GraphMetric.DISK_READ,
            style = GraphStyle.LINE
        )
        assertEquals(GraphStyle.LINE, validConfig2.validated().style)
    }

    @Test
    fun testDefaultConfigsAreAllSelfConsistent() {
        val defaults = GraphConfigStore.getDefaultConfigs()
        assertFalse(defaults.isEmpty())
        assertEquals(5, defaults.size)

        for (config in defaults) {
            val validated = config.validated()
            assertEquals("Default config ${config.id} must already be valid", config.style, validated.style)
            assertTrue("Default config style must be allowed by metric", config.metric.allowedStyles.contains(config.style))
        }
    }

    @Test
    fun testSpeedFormatterAcrossMagnitudes() {
        assertEquals("0 B/s", Formatters.formatSpeed(0L))
        assertEquals("0 B/s", Formatters.formatSpeed(-100L))
        assertEquals("512.0 B/s", Formatters.formatSpeed(512L))
        assertEquals("1.0 KB/s", Formatters.formatSpeed(1024L))
        assertEquals("1.5 MB/s", Formatters.formatSpeed((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB/s", Formatters.formatSpeed((2.0 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testBarItemSafety() {
        val bar1 = BarItem("Core 0", 45f, 100f)
        assertEquals("Core 0", bar1.label)
        assertEquals(45f, bar1.value, 0.001f)
        assertEquals(100f, bar1.maxValue, 0.001f)
    }

    @Test
    fun testViewPager2PageLayoutSpecificationNoWrapContent() {
        // Verify item_customizable_graph.xml root layout parameters to prevent ViewPager2 IllegalStateException
        val layoutFile = java.io.File("src/main/res/layout/item_customizable_graph.xml")
        val altFile = java.io.File("app/src/main/res/layout/item_customizable_graph.xml")
        val target = if (layoutFile.exists()) layoutFile else altFile
        if (target.exists()) {
            val content = target.readText()
            val cardTag = content.substringAfter("<com.google.android.material.card.MaterialCardView").substringBefore(">")
            assertTrue("Root card layout must specify match_parent width", cardTag.contains("android:layout_width=\"match_parent\""))
            assertTrue("Root card layout must specify match_parent height for ViewPager2", cardTag.contains("android:layout_height=\"match_parent\""))
            assertFalse("Root card layout must not specify wrap_content height", cardTag.contains("android:layout_height=\"wrap_content\""))
        }
    }
}
