package ru.protolink.communicator.data

import com.google.gson.Gson
import ru.protolink.communicator.data.api.ChangePasswordRequest
import ru.protolink.communicator.data.api.ChangePasswordResponse
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

data class ChangePasswordOutcome(
    val success: Boolean,
    val error: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val api: ProtoLinkApi,
    private val tokenStore: TokenStore
) {
    private val gson = Gson()

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

    /** Authenticated POST changePassword; Bearer via OkHttp interceptor. */
    suspend fun changePassword(currentPassword: String, newPassword: String): ChangePasswordOutcome {
        if (tokenStore.load() == null) {
            return ChangePasswordOutcome(success = false, error = "Not signed in.")
        }
        return try {
            val res = api.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            val errorRaw = if (!res.isSuccessful) res.errorBody()?.string() else null
            val body = res.body() ?: parseChangePasswordBody(errorRaw)
            if (res.isSuccessful && body?.success == true) {
                ChangePasswordOutcome(success = true)
            } else {
                ChangePasswordOutcome(
                    success = false,
                    error = body?.error?.takeIf { it.isNotBlank() }
                        ?: errorRaw?.takeIf { it.isNotBlank() }
                        ?: "Unknown error"
                )
            }
        } catch (e: Exception) {
            ChangePasswordOutcome(success = false, error = e.message ?: "Unknown error")
        }
    }

    private fun parseChangePasswordBody(raw: String?): ChangePasswordResponse? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, ChangePasswordResponse::class.java) }.getOrNull()
    }

    fun logout() = tokenStore.clear()
}
