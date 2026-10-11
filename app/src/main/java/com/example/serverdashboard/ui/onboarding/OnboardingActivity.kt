package com.example.serverdashboard.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.ActivityOnboardingBinding
import com.example.serverdashboard.ui.connect.ServerConnectActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Proceed regardless of whether the user granted or denied permission
            finishOnboardingAndNavigate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("zentra_app_state", Context.MODE_PRIVATE)
        val legacyPrefs = getSharedPreferences("omnis_app_state", Context.MODE_PRIVATE)
        val isOnboarded = prefs.getBoolean("is_onboarded", false) || legacyPrefs.getBoolean("is_onboarded", false)
        if (!prefs.contains("is_onboarded") && legacyPrefs.contains("is_onboarded")) {
            prefs.edit().putBoolean("is_onboarded", isOnboarded).apply()
        }

        if (isOnboarded) {
            checkExistingSessionAndNavigate()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.btnEnableNotifications.text = "Enable Notifications"
        } else {
            binding.btnEnableNotifications.text = "Get Started"
            binding.btnSkipNotifications.visibility = android.view.View.GONE
        }

        binding.btnEnableNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    finishOnboardingAndNavigate()
                } else {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                finishOnboardingAndNavigate()
            }
        }

        binding.btnSkipNotifications.setOnClickListener {
            finishOnboardingAndNavigate()
        }
    }

    private fun finishOnboardingAndNavigate() {
        val prefs = getSharedPreferences("zentra_app_state", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_onboarded", true).apply()
        checkExistingSessionAndNavigate()
    }

    private fun checkExistingSessionAndNavigate() {
        val serverStore = ServerStore.getInstance(this)
        val tokenManager = SecureTokenManager.getInstance(this)

        val activeServer = serverStore.getActiveServer()
        if (activeServer != null) {
            val token = tokenManager.getToken(activeServer.url)
            if (!token.isNullOrEmpty()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("server_url", activeServer.url)
                    putExtra("auth_token", token)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                return
            }
        }

        val intent = Intent(this, ServerConnectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
