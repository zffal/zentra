package com.example.serverdashboard.data.model

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String
)

data class UserInfo(
    val id: Int? = null,
    val username: String = "",
    val created_at: String? = null,
    val last_login: String? = null
)

data class TokenResponse(
    val access_token: String? = null,
    val token: String? = null,
    val token_type: String = "bearer",
    val expires_in: Long = 0L,
    val user: UserInfo? = null
) {
    fun getJwt(): String = access_token ?: token ?: ""
}
