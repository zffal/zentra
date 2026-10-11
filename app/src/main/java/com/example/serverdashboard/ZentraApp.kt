package com.example.serverdashboard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.serverdashboard.data.util.CrashDiagnosticLogger
import com.example.serverdashboard.ui.alerts.AlertNotificationManager

class ZentraApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply persistent user appearance theme (Light, Dark, or System)
        com.example.serverdashboard.ui.theme.ThemeManager.applySavedTheme(this)

        // Privacy-safe crash diagnostics handler
        CrashDiagnosticLogger.initUncaughtExceptionHandler(this)

        // Create alert notification channels at app startup
        AlertNotificationManager.getInstance(this).createNotificationChannel()

        // Enqueue periodic background alert monitoring worker (15 min interval)
        com.example.serverdashboard.ui.alerts.AlertsSyncWorker.enqueue(this)

        // Start live monitoring service if enabled by user
        if (com.example.serverdashboard.data.storage.NotificationConfigStore.getInstance(this).liveMonitoringEnabled) {
            com.example.serverdashboard.ui.alerts.LiveMonitoringService.start(this)
        }

        // Register Activity Lifecycle callbacks to track foreground/background state
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivityCount = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                AlertNotificationManager.getInstance(applicationContext).setAppInForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = maxOf(0, startedActivityCount - 1)
                if (startedActivityCount == 0) {
                    AlertNotificationManager.getInstance(applicationContext).setAppInForeground(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
