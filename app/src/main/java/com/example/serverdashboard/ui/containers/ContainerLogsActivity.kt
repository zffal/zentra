package com.example.serverdashboard.ui.containers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.databinding.ActivityContainerLogsBinding
import kotlinx.coroutines.launch

class ContainerLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContainerLogsBinding
    private var containerId: String = ""
    private var containerName: String = ""
    private var serverUrl: String = ""
    private var authToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContainerLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        containerId = intent.getStringExtra("container_id") ?: ""
        containerName = intent.getStringExtra("container_name") ?: "Container"
        serverUrl = intent.getStringExtra("server_url") ?: ""
        authToken = intent.getStringExtra("auth_token") ?: ""

        binding.tvLogsContainerTitle.text = containerName.removePrefix("/")

        binding.btnLogsBack.setOnClickListener { finish() }
        binding.btnLogsRefresh.setOnClickListener { loadLogs() }
        binding.btnLogsCopy.setOnClickListener { copyLogs() }

        loadLogs()
    }

    private fun loadLogs() {
        if (containerId.isEmpty() || serverUrl.isEmpty()) return

        binding.progressLogs.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = ApiClient.instance.getContainerLogs(
                baseUrl = serverUrl,
                token = authToken,
                id = containerId,
                tail = 250
            )

            binding.progressLogs.visibility = View.GONE

            if (result.isSuccess) {
                val logs = result.getOrDefault(emptyList())
                if (logs.isEmpty()) {
                    binding.tvLogsContent.text = "(No log output available for this container)"
                } else {
                    binding.tvLogsContent.text = logs.joinToString("\n")
                    binding.scrollLogs.post {
                        binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                    }
                }
            } else {
                binding.tvLogsContent.text = "Failed to load logs: " + (result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    private fun copyLogs() {
        val text = binding.tvLogsContent.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Container Logs", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
