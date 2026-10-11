package com.example.serverdashboard.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(val key: String, val displayName: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): AppTheme {
            return values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SYSTEM
        }
    }
}

object ThemeManager {
    private const val PREFS_NAME = "zentra_appearance_prefs"
    private const val KEY_THEME = "app_theme"

    fun getSavedTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_THEME, AppTheme.SYSTEM.key)
        return AppTheme.fromKey(key)
    }

    fun setTheme(context: Context, theme: AppTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.key).apply()
        applyTheme(theme)
    }

    fun applySavedTheme(context: Context) {
        val theme = getSavedTheme(context)
        applyTheme(theme)
    }

    fun applyTheme(theme: AppTheme) {
        val mode = when (theme) {
            AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
