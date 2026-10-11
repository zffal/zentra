package com.example.serverdashboard.ui.alerts

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.example.serverdashboard.data.storage.SecureTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlertActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESOLVE = "com.example.serverdashboard.ACTION_RESOLVE_ALERT"
        const val ACTION_SNOOZE = "com.example.serverdashboard.ACTION_SNOOZE_ALERT"

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_ALERT_ID = "extra_alert_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID).orEmpty()
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()

        when (intent.action) {
            ACTION_SNOOZE -> {
                if (alertId.isNotEmpty()) {
                    NotificationConfigStore.getInstance(context).snoozeAlert(alertId, 3600_000L)
                }
                if (notifId != -1) {
                    notifManager?.cancel(notifId)
                }
            }
            ACTION_RESOLVE -> {
                if (notifId != -1) {
                    notifManager?.cancel(notifId)
                }
                if (serverUrl.isNotEmpty() && alertId.isNotEmpty()) {
                    val token = SecureTokenManager.getInstance(context).getToken(serverUrl)
                    if (!token.isNullOrEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            ApiClient.instance.resolveAlert(serverUrl, token, alertId)
                        }
                    }
                }
            }
        }
    }
}
