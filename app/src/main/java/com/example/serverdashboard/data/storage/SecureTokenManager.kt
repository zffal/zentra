package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenManager(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey = try {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    } catch (e: Exception) {
        null
    }

    private val prefs: SharedPreferences = try {
        if (masterKey != null) {
            EncryptedSharedPreferences.create(
                appContext,
                "zentra_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } else {
            appContext.getSharedPreferences("zentra_fallback_prefs", Context.MODE_PRIVATE)
        }
    } catch (e: Exception) {
        // Fallback for devices with keystore compatibility issues
        appContext.getSharedPreferences("zentra_fallback_prefs", Context.MODE_PRIVATE)
    }

    private val legacyPrefs: SharedPreferences? by lazy {
        try {
            if (masterKey != null) {
                EncryptedSharedPreferences.create(
                    appContext,
                    "omnis_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } else {
                appContext.getSharedPreferences("omnis_fallback_prefs", Context.MODE_PRIVATE)
            }
        } catch (e: Exception) {
            try {
                appContext.getSharedPreferences("omnis_fallback_prefs", Context.MODE_PRIVATE)
            } catch (e2: Exception) {
                null
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SecureTokenManager? = null

        fun getInstance(context: Context): SecureTokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureTokenManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun keyForServer(serverUrl: String): String {
            return "token_" + serverUrl.replace(Regex("[^a-zA-Z0-9]"), "_")
        }
    }

    fun saveToken(serverUrl: String, token: String) {
        prefs.edit().putString(keyForServer(serverUrl), token).apply()
    }

    fun getToken(serverUrl: String): String? {
        val key = keyForServer(serverUrl)
        val current = prefs.getString(key, null)
        if (!current.isNullOrEmpty()) return current

        val legacy = legacyPrefs?.getString(key, null)
        if (!legacy.isNullOrEmpty()) {
            saveToken(serverUrl, legacy)
            return legacy
        }
        return null
    }

    fun clearToken(serverUrl: String) {
        val key = keyForServer(serverUrl)
        prefs.edit().remove(key).apply()
        legacyPrefs?.edit()?.remove(key)?.apply()
    }

    fun saveLastUser(serverUrl: String, username: String) {
        prefs.edit().putString("user_" + keyForServer(serverUrl), username).apply()
    }

    fun getLastUser(serverUrl: String): String? {
        val key = "user_" + keyForServer(serverUrl)
        val current = prefs.getString(key, null)
        if (!current.isNullOrEmpty()) return current

        val legacy = legacyPrefs?.getString(key, null)
        if (!legacy.isNullOrEmpty()) {
            saveLastUser(serverUrl, legacy)
            return legacy
        }
        return null
    }
}
