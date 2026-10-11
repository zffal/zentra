package com.example.serverdashboard.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.RegisterRequest
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.ActivityRegisterBinding
import com.example.serverdashboard.ui.util.Formatters
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var tokenManager: SecureTokenManager
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenManager = SecureTokenManager.getInstance(this)
        serverUrl = intent.getStringExtra("server_url") ?: ""

        if (serverUrl.isEmpty()) {
            finish()
            return
        }

        binding.tvTargetServer.text = Formatters.extractHost(serverUrl)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCreateAccount.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirm = binding.etConfirmPassword.text.toString().trim()

            if (username.length < 3) {
                binding.etUsername.error = "Username must be at least 3 characters"
                return@setOnClickListener
            }
            if (password.length < 6) {
                binding.etPassword.error = "Password must be at least 6 characters"
                return@setOnClickListener
            }
            if (password != confirm) {
                binding.etConfirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }

            performRegistration(username, password)
        }

        binding.btnGoToLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java).apply {
                putExtra("server_url", serverUrl)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun performRegistration(username: String, password: String) {
        binding.tvRegisterError.visibility = View.GONE
        binding.btnCreateAccount.text = ""
        binding.btnCreateAccount.isEnabled = false
        binding.progressRegister.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = ApiClient.instance.register(
                baseUrl = serverUrl,
                req = RegisterRequest(username, password)
            )

            binding.progressRegister.visibility = View.GONE
            binding.btnCreateAccount.text = "Create Account"
            binding.btnCreateAccount.isEnabled = true

            if (result.isSuccess) {
                val tokenResp = result.getOrNull()
                val jwt = tokenResp?.getJwt() ?: ""
                if (jwt.isNotEmpty()) {
                    tokenManager.saveToken(serverUrl, jwt)
                    tokenManager.saveLastUser(serverUrl, username)

                    val serverStore = ServerStore.getInstance(this@RegisterActivity)
                    val config = serverStore.addOrUpdateServer(serverUrl)
                    serverStore.setActiveServer(config.id)

                    val intent = Intent(this@RegisterActivity, MainActivity::class.java).apply {
                        putExtra("server_url", serverUrl)
                        putExtra("auth_token", jwt)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    binding.tvRegisterError.text = "Account created. Please sign in."
                    binding.tvRegisterError.visibility = View.VISIBLE
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Registration failed"
                binding.tvRegisterError.text = errorMsg
                binding.tvRegisterError.visibility = View.VISIBLE
            }
        }
    }
}
