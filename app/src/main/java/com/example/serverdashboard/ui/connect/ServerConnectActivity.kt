package com.example.serverdashboard.ui.connect

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.ServerConfig
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.ActivityServerConnectBinding
import com.example.serverdashboard.databinding.DialogAddServerBinding
import com.example.serverdashboard.ui.auth.LoginActivity
import com.example.serverdashboard.ui.auth.RegisterActivity
import com.example.serverdashboard.ui.util.GlassEffectHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServerConnectActivity : AppCompatActivity() {

    private val TAG = "ZentraNetwork"
    private lateinit var binding: ActivityServerConnectBinding
    private lateinit var serverStore: ServerStore
    private lateinit var tokenManager: SecureTokenManager
    private lateinit var adapter: SavedServersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverStore = ServerStore.getInstance(this)
        tokenManager = SecureTokenManager.getInstance(this)

        binding.cbSaveServer.isChecked = true

        setupSavedServersList()
        setupListeners()

        val active = serverStore.getActiveServer()
        if (active != null) {
            binding.etServerAddress.setText(active.url)
        }
    }

    private fun setupSavedServersList() {
        val servers = serverStore.getServers()
        adapter = SavedServersAdapter(
            servers = servers,
            activeId = serverStore.getActiveServerId(),
            onSelect = { server ->
                serverStore.setActiveServer(server.id)
                refreshSavedServers()
                binding.etServerAddress.setText(server.url)
                testAndConnect(server.url)
            },
            onEdit = { server -> showEditDialog(server) },
            onDelete = { server ->
                AlertDialog.Builder(this)
                    .setTitle("Remove Server")
                    .setMessage("Remove ${server.name} (${server.url}) from saved servers?")
                    .setPositiveButton("Remove") { _, _ ->
                        serverStore.deleteServer(server.id)
                        refreshSavedServers()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.recyclerSavedServers.layoutManager = LinearLayoutManager(this)
        binding.recyclerSavedServers.adapter = adapter
        updateSavedServersVisibility(servers)
    }

    private fun refreshSavedServers() {
        val servers = serverStore.getServers()
        adapter.updateData(servers, serverStore.getActiveServerId())
        updateSavedServersVisibility(servers)
    }

    private fun updateSavedServersVisibility(servers: List<ServerConfig>) {
        if (servers.isEmpty()) {
            binding.tvNoSavedServers.visibility = View.VISIBLE
            binding.recyclerSavedServers.visibility = View.GONE
        } else {
            binding.tvNoSavedServers.visibility = View.GONE
            binding.recyclerSavedServers.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            var url = binding.etServerAddress.text.toString().trim()
            if (url.isEmpty()) {
                val hint = binding.etServerAddress.hint?.toString() ?: ""
                if (hint.isNotEmpty() && hint.startsWith("http")) {
                    url = hint
                    binding.etServerAddress.setText(url)
                } else {
                    binding.etServerAddress.error = "Server address required"
                    return@setOnClickListener
                }
            }
            testAndConnect(url)
        }

        binding.btnRetry.setOnClickListener {
            val url = binding.etServerAddress.text.toString().trim()
            if (url.isNotEmpty()) {
                testAndConnect(url)
            }
        }

        binding.btnChangeAddress.setOnClickListener {
            showStateIdle()
            binding.etServerAddress.requestFocus()
            binding.etServerAddress.selectAll()
        }

        binding.tvToggleDetails.setOnClickListener {
            val isVisible = binding.tvErrorDetails.visibility == View.VISIBLE
            binding.tvErrorDetails.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvToggleDetails.text = if (isVisible) "Details" else "Hide details"
        }

        binding.btnWhereDoIFindThis.setOnClickListener {
            val isVisible = binding.layoutWhereDoIFindThis.visibility == View.VISIBLE
            binding.layoutWhereDoIFindThis.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnWhereDoIFindThis.text = if (isVisible) "Where do I find this?" else "Hide helper ▲"
        }

        binding.btnServerSetupGuide.setOnClickListener {
            showServerSetupGuide()
        }

        binding.btnAuthSignIn.setOnClickListener {
            val url = ApiClient.normalizeUrl(binding.etServerAddress.text.toString().trim())
            val intent = Intent(this, LoginActivity::class.java).apply {
                putExtra("server_url", url)
            }
            startActivity(intent)
        }

        binding.btnAuthRegister.setOnClickListener {
            val url = ApiClient.normalizeUrl(binding.etServerAddress.text.toString().trim())
            val intent = Intent(this, RegisterActivity::class.java).apply {
                putExtra("server_url", url)
            }
            startActivity(intent)
        }
    }

    private fun showServerSetupGuide() {
        AlertDialog.Builder(this)
            .setTitle("Set up Zentra")
            .setMessage(
                "1. Install the agent on your Ubuntu server\n" +
                "2. Start the service\n" +
                "3. Open the Zentra app\n" +
                "4. Enter your server address\n" +
                "5. Create your account\n" +
                "6. Done\n\n" +
                "Advanced setup:\n" +
                "On your server, run:\n" +
                "curl -sSL https://raw.githubusercontent.com/your-username/zentra/main/install.sh | bash"
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showStateIdle() {
        binding.layoutConnecting.visibility = View.GONE
        binding.layoutConnected.visibility = View.GONE
        binding.layoutFailed.visibility = View.GONE
        binding.layoutAuthRequired.visibility = View.GONE
        binding.btnConnect.isEnabled = true
    }

    private fun showStateConnecting(step: String = "Connecting…") {
        binding.layoutConnecting.visibility = View.VISIBLE
        binding.tvConnectingStatus.text = step
        binding.layoutConnected.visibility = View.GONE
        binding.layoutFailed.visibility = View.GONE
        binding.layoutAuthRequired.visibility = View.GONE
        binding.btnConnect.isEnabled = false
    }

    private fun showStateConnected() {
        binding.layoutConnecting.visibility = View.GONE
        binding.layoutConnected.visibility = View.VISIBLE
        binding.layoutFailed.visibility = View.GONE
        binding.layoutAuthRequired.visibility = View.GONE
        binding.btnConnect.isEnabled = true
    }

    private fun showStateFailed(detailMsg: String) {
        binding.layoutConnecting.visibility = View.GONE
        binding.layoutConnected.visibility = View.GONE
        binding.layoutFailed.visibility = View.VISIBLE
        binding.layoutAuthRequired.visibility = View.GONE
        binding.btnConnect.isEnabled = true
        binding.tvErrorDetails.text = detailMsg
        binding.tvErrorDetails.visibility = View.GONE
        binding.tvToggleDetails.text = "Technical details"
    }

    private fun showStateAuthRequired() {
        binding.layoutConnecting.visibility = View.GONE
        binding.layoutConnected.visibility = View.GONE
        binding.layoutFailed.visibility = View.GONE
        binding.layoutAuthRequired.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = true
    }

    private fun testAndConnect(rawUrl: String) {
        val cleanUrl = ApiClient.normalizeUrl(rawUrl)
        showStateConnecting("Checking server…")

        lifecycleScope.launch {
            val healthResult = ApiClient.instance.checkHealth(cleanUrl)

            if (healthResult.isSuccess) {
                showStateConnecting("Connecting…")
                delay(200)
                showStateConnected()

                // Save or set session
                val existing = serverStore.getServers().firstOrNull { it.url.equals(cleanUrl, ignoreCase = true) }
                if (existing != null) {
                    serverStore.setActiveServer(existing.id)
                    existing.lastConnected = System.currentTimeMillis()
                    serverStore.updateServer(existing)
                    refreshSavedServers()
                } else if (binding.cbSaveServer.isChecked) {
                    val saved = serverStore.addOrUpdateServer(cleanUrl)
                    serverStore.setActiveServer(saved.id)
                    refreshSavedServers()
                } else {
                    serverStore.sessionServerUrl = cleanUrl
                }

                delay(300)

                // Check authentication
                val token = tokenManager.getToken(cleanUrl)
                if (!token.isNullOrEmpty()) {
                    // Navigate straight to dashboard
                    val intent = Intent(this@ServerConnectActivity, MainActivity::class.java).apply {
                        putExtra("server_url", cleanUrl)
                        putExtra("auth_token", token)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    // Prompt sign in or registration
                    showStateAuthRequired()
                }
            } else {
                val err = healthResult.exceptionOrNull()
                val detailedMsg = err?.message ?: "Connection failed"
                showStateFailed(detailedMsg)
            }
        }
    }

    private fun showEditDialog(server: ServerConfig) {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater)
        dialogBinding.dialogTitle.text = "Edit Server"
        dialogBinding.etServerName.setText(server.name)
        dialogBinding.etServerUrl.setText(server.url)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            GlassEffectHelper.applyWindowBlur(w, 36)
        }

        dialogBinding.btnSaveServer.setOnClickListener {
            val name = dialogBinding.etServerName.text.toString().trim()
            val url = dialogBinding.etServerUrl.text.toString().trim()

            if (url.isEmpty()) {
                dialogBinding.etServerUrl.error = "URL required"
                return@setOnClickListener
            }

            server.name = name.ifEmpty { url }
            server.url = ApiClient.normalizeUrl(url)
            serverStore.updateServer(server)
            refreshSavedServers()
            dialog.dismiss()
        }

        dialogBinding.btnCancelServer.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
