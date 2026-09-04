package ru.protolink.communicator.data

import ru.protolink.communicator.data.api.LoginRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ProtoLinkApi,
    private val tokenStore: TokenStore
) {
    val isAuthenticated: Boolean get() = tokenStore.load() != null
    fun current(): TokenData? = tokenStore.load()

    suspend fun login(login: String, password: String): Result<TokenData> = runCatching {
        val res = api.login(LoginRequest(login, password))
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

    fun logout() = tokenStore.clear()
}
