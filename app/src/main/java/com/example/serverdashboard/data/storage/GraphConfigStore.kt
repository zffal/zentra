package com.example.serverdashboard.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.serverdashboard.data.model.GraphConfig
import com.example.serverdashboard.data.model.GraphMetric
import com.example.serverdashboard.data.model.GraphStyle
import com.example.serverdashboard.data.model.GraphTimeRange
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GraphConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getConfigs(): List<GraphConfig> {
        val json = prefs.getString(KEY_CONFIGS, null)
        if (json.isNullOrBlank()) {
            val defaults = getDefaultConfigs()
            saveConfigs(defaults)
            return defaults
        }

        return try {
            val type = object : TypeToken<List<GraphConfig>>() {}.type
            val list: List<GraphConfig>? = gson.fromJson(json, type)
            if (list.isNullOrEmpty()) {
                val defaults = getDefaultConfigs()
                saveConfigs(defaults)
                defaults
            } else {
                list.map { it.validated() }
            }
        } catch (e: Exception) {
            val defaults = getDefaultConfigs()
            saveConfigs(defaults)
            defaults
        }
    }

    fun saveConfigs(configs: List<GraphConfig>) {
        val validated = configs.map { it.validated() }
        val json = gson.toJson(validated)
        prefs.edit().putString(KEY_CONFIGS, json).apply()
    }

    fun updateConfig(updated: GraphConfig) {
        val list = getConfigs().toMutableList()
        val index = list.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            list[index] = updated.validated()
        } else {
            list.add(updated.validated())
        }
        saveConfigs(list)
    }

    fun resetToDefaults(): List<GraphConfig> {
        val defaults = getDefaultConfigs()
        saveConfigs(defaults)
        return defaults
    }

    companion object {
        private const val PREFS_NAME = "zentra_graph_prefs"
        private const val KEY_CONFIGS = "graph_configs_json"

        @Volatile
        private var instance: GraphConfigStore? = null

        fun getInstance(context: Context): GraphConfigStore {
            return instance ?: synchronized(this) {
                instance ?: GraphConfigStore(context).also { instance = it }
            }
        }

        fun getDefaultConfigs(): List<GraphConfig> {
            return listOf(
                GraphConfig(
                    id = "default_disk_read",
                    metric = GraphMetric.DISK_READ,
                    style = GraphStyle.LINE,
                    timeRange = GraphTimeRange.ONE_MIN,
                    isCompact = false,
                    showSecondaryValue = true
                ),
                GraphConfig(
                    id = "default_disk_write",
                    metric = GraphMetric.DISK_WRITE,
                    style = GraphStyle.LINE,
                    timeRange = GraphTimeRange.ONE_MIN,
                    isCompact = false,
                    showSecondaryValue = true
                ),
                GraphConfig(
                    id = "default_net_throughput",
                    metric = GraphMetric.NET_DOWNLOAD,
                    style = GraphStyle.LINE,
                    timeRange = GraphTimeRange.ONE_MIN,
                    isCompact = false,
                    showSecondaryValue = true
                ),
                GraphConfig(
                    id = "default_storage_gauge",
                    metric = GraphMetric.STORAGE,
                    style = GraphStyle.SEMI_CIRCLE,
                    timeRange = GraphTimeRange.ONE_MIN,
                    isCompact = false,
                    showSecondaryValue = true
                ),
                GraphConfig(
                    id = "default_memory_ring",
                    metric = GraphMetric.MEMORY,
                    style = GraphStyle.RING,
                    timeRange = GraphTimeRange.ONE_MIN,
                    isCompact = false,
                    showSecondaryValue = true
                )
            )
        }
    }
}
