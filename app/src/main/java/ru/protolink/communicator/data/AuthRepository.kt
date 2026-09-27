package ru.protolink.communicator.data

import ru.protolink.communicator.data.api.LoginRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.api.RegisterRequest
import javax.inject.Inject
import javax.inject.Singleton

data class RegisterOutcome(
    val success: Boolean,
    val emailError: String? = null,
    val error: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val api: ProtoLinkApi,
    private val tokenStore: TokenStore
) {
    val isAuthenticated: Boolean get() = tokenStore.load() != null
    fun current(): TokenData? = tokenStore.load()

    suspend fun login(login: String, password: String): Result<TokenData> = runCatching {
        val res = api.login(LoginRequest(login, password))
        if (!res.error.isNullOrBlank()) error(res.error)
        val access = res.accessToken ?: res.token ?: error("No access token")
        val userId = res.userId ?: res.id ?: error("No user id")
        val data = TokenData(
            accessToken = access,
            refreshToken = res.refreshToken,
            expirationTime = res.expirationTime,
            userId = userId,
            login = res.login ?: login
        )
        tokenStore.save(data)
        data
    }

    suspend fun register(email: String, password: String, lang: String): Result<RegisterOutcome> = runCatching {
        val res = api.register(RegisterRequest(email.trim(), password), lang.ifBlank { "en-US" })
        RegisterOutcome(
            success = res.success == true && res.emailError.isNullOrBlank() && res.error.isNullOrBlank(),
            emailError = res.emailError,
            error = res.error
        )
    }

    fun logout() = tokenStore.clear()
}
