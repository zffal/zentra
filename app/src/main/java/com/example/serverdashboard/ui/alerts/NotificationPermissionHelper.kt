package com.example.serverdashboard.ui.alerts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.serverdashboard.R
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object NotificationPermissionHelper {

    fun areNotificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun openNotificationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {}

        // Fallback to app details settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {}

        // Fallback to application details
        openNotificationSettings(context)
    }

    /**
     * Shows pre-permission explanation dialog before triggering system prompt.
     */
    fun requestPermissionWithRationale(
        activity: Activity,
        onProceed: () -> Unit
    ) {
        val configStore = NotificationConfigStore.getInstance(activity)
        configStore.hasNaggedThisLaunch = true

        MaterialAlertDialogBuilder(activity)
            .setTitle("Server Alerts & Monitoring")
            .setMessage("Zentra sends alerts for high temperature, high load, and failing disks when your server requires attention.")
            .setPositiveButton("Continue") { _, _ ->
                onProceed()
            }
            .setNegativeButton("Not Now", null)
            .show()
    }
}
