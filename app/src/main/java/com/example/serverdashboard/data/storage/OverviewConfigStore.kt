package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.serverdashboard.data.model.CardId
import com.example.serverdashboard.data.model.OverviewConfig
import com.google.gson.Gson

/**
 * OverviewConfigStore: Manages per-server persistent Overview layouts using SharedPreferences & Gson.
 * Features:
 * - Isolated config per server ID/URL.
 * - Restores cleanly after app restarts and reconnections.
 * - Unknown or removed card IDs are ignored.
 * - Missing or corrupt preferences safely fall back to default order.
 * - Tracks whether user has seen the one-time edit hint banner.
 */
class OverviewConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "zentra_overview_config_store"
        private const val KEY_PREFIX_CONFIG = "overview_config_"
        private const val KEY_SEEN_EDIT_HINT = "seen_edit_hint_v1"

        @Volatile
        private var INSTANCE: OverviewConfigStore? = null

        fun getInstance(context: Context): OverviewConfigStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverviewConfigStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun sanitizeServerKey(serverKey: String?): String {
            if (serverKey.isNullOrBlank()) return "default_server"
            val cleaned = serverKey.trim()
                .replace("http://", "")
                .replace("https://", "")
                .replace("ws://", "")
                .replace("wss://", "")
                .replace("/", "_")
                .replace(":", "_")
                .trim('_')
            return cleaned.ifEmpty { "default_server" }
        }
    }

    fun getConfig(serverKey: String?): OverviewConfig {
        val key = KEY_PREFIX_CONFIG + sanitizeServerKey(serverKey)
        val json = prefs.getString(key, null)
        if (json.isNullOrBlank()) {
            return OverviewConfig().validated()
        }

        return try {
            val parsed = gson.fromJson(json, OverviewConfig::class.java)
            if (parsed == null || parsed.cardOrder.isEmpty()) {
                OverviewConfig().validated()
            } else {
                parsed.validated()
            }
        } catch (_: Exception) {
            OverviewConfig().validated()
        }
    }

    fun saveConfig(serverKey: String?, config: OverviewConfig) {
        val key = KEY_PREFIX_CONFIG + sanitizeServerKey(serverKey)
        val validated = config.validated()
        val json = gson.toJson(validated)
        prefs.edit().putString(key, json).apply()
    }

    fun resetToDefaults(serverKey: String?): OverviewConfig {
        val defaults = OverviewConfig(
            cardOrder = CardId.defaultOrder(),
            hiddenCards = CardId.defaultHidden(),
            selectedMountPoint = null
        ).validated()
        saveConfig(serverKey, defaults)
        return defaults
    }

    fun hasSeenEditHint(): Boolean {
        return prefs.getBoolean(KEY_SEEN_EDIT_HINT, false)
    }

    fun setSeenEditHint(seen: Boolean = true) {
        prefs.edit().putBoolean(KEY_SEEN_EDIT_HINT, seen).apply()
    }
}
