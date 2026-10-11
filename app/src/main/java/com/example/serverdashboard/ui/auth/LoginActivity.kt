package com.example.serverdashboard.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.LoginRequest
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.ActivityLoginBinding
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.ui.util.Formatters
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var tokenManager: SecureTokenManager
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenManager = SecureTokenManager.getInstance(this)
        serverUrl = intent.getStringExtra("server_url") ?: ""

        if (serverUrl.isEmpty()) {
            finish()
            return
        }

        val resolvedName = com.example.serverdashboard.ui.util.ServerNameResolver.resolveServerName(this, serverUrl, null)
        binding.tvTargetServer.text = if (resolvedName != "Server") resolvedName else Formatters.extractHost(serverUrl)

        val lastUser = tokenManager.getLastUser(serverUrl)
        if (!lastUser.isNullOrEmpty()) {
            binding.etUsername.setText(lastUser)
            binding.etPassword.requestFocus()
        }

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnSignIn.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (username.isEmpty()) {
                binding.etUsername.error = "Username required"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.etPassword.error = "Password required"
                return@setOnClickListener
            }

            performLogin(username, password)
        }

        binding.btnBackToConnect.setOnClickListener {
            finish()
        }

        binding.btnGoToRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java).apply {
                putExtra("server_url", serverUrl)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun performLogin(username: String, password: String) {
        binding.tvLoginError.visibility = View.GONE
        binding.btnSignIn.text = ""
        binding.btnSignIn.isEnabled = false
        binding.progressSignIn.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = ApiClient.instance.login(
                baseUrl = serverUrl,
                req = LoginRequest(username, password)
            )

            binding.progressSignIn.visibility = View.GONE
            binding.btnSignIn.text = "Sign In"
            binding.btnSignIn.isEnabled = true

            if (result.isSuccess) {
                val tokenResp = result.getOrNull()
                val jwt = tokenResp?.getJwt() ?: ""
                if (jwt.isNotEmpty()) {
                    tokenManager.saveToken(serverUrl, jwt)
                    tokenManager.saveLastUser(serverUrl, username)

                    val serverStore = ServerStore.getInstance(this@LoginActivity)
                    val config = serverStore.addOrUpdateServer(serverUrl)
                    serverStore.setActiveServer(config.id)

                    val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                        putExtra("server_url", serverUrl)
                        putExtra("auth_token", jwt)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    binding.tvLoginError.text = "Server did not return an access token"
                    binding.tvLoginError.visibility = View.VISIBLE
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Authentication failed"
                binding.tvLoginError.text = errorMsg
                binding.tvLoginError.visibility = View.VISIBLE
            }
        }
    }
}
