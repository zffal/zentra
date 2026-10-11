package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.serverdashboard.data.model.ServerConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ServerStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // In-memory session server URL (used when user does NOT check "Save server")
    var sessionServerUrl: String? = null

    companion object {
        private const val PREFS_NAME = "zentra_server_store"
        private const val LEGACY_PREFS_NAME = "omnis_server_store"
        private const val KEY_SERVERS = "saved_servers"
        private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        private const val KEY_CLEARED_DEFAULTS_V2 = "cleared_defaults_v2"

        @Volatile
        private var INSTANCE: ServerStore? = null

        fun getInstance(context: Context): ServerStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        // Migrate legacy preferences if present
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SERVERS) && legacyPrefs.contains(KEY_SERVERS)) {
            val legacyServers = legacyPrefs.getString(KEY_SERVERS, null)
            val legacyActive = legacyPrefs.getString(KEY_ACTIVE_SERVER_ID, null)
            prefs.edit()
                .putString(KEY_SERVERS, legacyServers)
                .putString(KEY_ACTIVE_SERVER_ID, legacyActive)
                .putBoolean(KEY_CLEARED_DEFAULTS_V2, true)
                .apply()
        }

        // Enforce user requirement: App must start with NO saved servers by default
        if (!prefs.getBoolean(KEY_CLEARED_DEFAULTS_V2, false)) {
            prefs.edit()
                .remove(KEY_SERVERS)
                .remove(KEY_ACTIVE_SERVER_ID)
                .putBoolean(KEY_CLEARED_DEFAULTS_V2, true)
                .apply()
        }
    }

    fun getServers(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        return try {
            gson.fromJson<List<ServerConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServers(servers: List<ServerConfig>) {
        val json = gson.toJson(servers)
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    fun addOrUpdateServer(url: String, name: String? = null): ServerConfig {
        val cleanUrl = url.trim().removeSuffix("/")
        val current = getServers().toMutableList()
        val existingIndex = current.indexOfFirst { it.url.equals(cleanUrl, ignoreCase = true) }

        val serverName = if (!name.isNullOrBlank()) {
            name.trim()
        } else {
            // Derive clean name from host
            cleanUrl.removePrefix("http://").removePrefix("https://").substringBefore(":")
        }

        val config = if (existingIndex != -1) {
            val existing = current[existingIndex]
            existing.lastConnected = System.currentTimeMillis()
            if (!name.isNullOrBlank()) existing.name = serverName
            existing
        } else {
            val newServer = ServerConfig(
                name = serverName,
                url = cleanUrl,
                isDefault = current.isEmpty(),
                lastConnected = System.currentTimeMillis()
            )
            current.add(newServer)
            newServer
        }

        saveServers(current)
        setActiveServer(config.id)
        return config
    }

    fun updateServer(updated: ServerConfig) {
        val current = getServers().toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            current[index] = updated
            saveServers(current)
        }
    }

    fun deleteServer(id: String) {
        val current = getServers().toMutableList()
        current.removeAll { it.id == id }
        saveServers(current)
        if (getActiveServerId() == id) {
            val next = current.firstOrNull()?.id
            if (next != null) {
                setActiveServer(next)
            } else {
                prefs.edit().remove(KEY_ACTIVE_SERVER_ID).apply()
            }
        }
    }

    fun getActiveServerId(): String? {
        val id = prefs.getString(KEY_ACTIVE_SERVER_ID, null)
        return if (id.isNullOrBlank()) null else id
    }

    fun setActiveServer(id: String) {
        if (id.isBlank()) {
            prefs.edit().remove(KEY_ACTIVE_SERVER_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_SERVER_ID, id).apply()
        }
    }

    fun getActiveServer(): ServerConfig? {
        val id = getActiveServerId() ?: return getServers().firstOrNull()
        return getServers().firstOrNull { it.id == id } ?: getServers().firstOrNull()
    }

    fun getEffectiveServerUrl(): String? {
        return sessionServerUrl ?: getActiveServer()?.url
    }
}
