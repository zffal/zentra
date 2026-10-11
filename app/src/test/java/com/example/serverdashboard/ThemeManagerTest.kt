package com.example.serverdashboard

import androidx.appcompat.app.AppCompatDelegate
import com.example.serverdashboard.ui.theme.AppTheme
import com.example.serverdashboard.ui.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun testAppThemeFromKey_validKeys() {
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("system"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("SYSTEM"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromKey("light"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromKey("LIGHT"))
        assertEquals(AppTheme.DARK, AppTheme.fromKey("dark"))
        assertEquals(AppTheme.DARK, AppTheme.fromKey("DARK"))
    }

    @Test
    fun testAppThemeFromKey_fallbackToSystem() {
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("unknown_value"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("auto"))
    }

    @Test
    fun testAppThemeProperties() {
        assertEquals("system", AppTheme.SYSTEM.key)
        assertEquals("System", AppTheme.SYSTEM.displayName)

        assertEquals("light", AppTheme.LIGHT.key)
        assertEquals("Light", AppTheme.LIGHT.displayName)

        assertEquals("dark", AppTheme.DARK.key)
        assertEquals("Dark", AppTheme.DARK.displayName)
    }

    @Test
    fun testApplyTheme_updatesNightMode() {
        ThemeManager.applyTheme(AppTheme.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())

        ThemeManager.applyTheme(AppTheme.DARK)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        ThemeManager.applyTheme(AppTheme.SYSTEM)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }
}
