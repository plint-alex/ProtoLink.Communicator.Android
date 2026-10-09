package ru.protolink.communicator.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.protolink.communicator.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent JWT refresh via a bare OkHttp client (no Authenticator) so we never recurse.
 * Mirrors Windows AuthService.RefreshTokenAsync + AuthHandler gate.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val tokenStore: TokenStore,
    private val settingsStore: SettingsStore
) {
    private val lock = Any()
    private val gson = Gson()
    private val bareClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun refreshBlocking(): Boolean = synchronized(lock) {
        val current = tokenStore.load() ?: return false
        val refresh = current.refreshToken?.takeIf { it.isNotBlank() } ?: return false
        val base = settingsStore.load().apiBaseAddress.ifBlank { BuildConfig.API_BASE_URL }
            .let { if (it.endsWith("/")) it else "$it/" }
        val json = gson.toJson(
            mapOf(
                "accessToken" to current.accessToken,
                "refreshToken" to refresh
            )
        )
        val req = Request.Builder()
            .url(base + "api/Authentication/refreshtoken")
            .post(json.toRequestBody(JSON))
            .build()
        return try {
            bareClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "token refresh HTTP ${resp.code}")
                    return false
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank() || text.trim().equals("null", ignoreCase = true)) {
                    Log.w(TAG, "token refresh empty body")
                    return false
                }
                val parsed = gson.fromJson(text, RefreshTokenResponse::class.java)
                val access = parsed.accessToken?.takeIf { it.isNotBlank() }
                if (access == null) {
                    Log.w(TAG, "token refresh missing accessToken")
                    return false
                }
                tokenStore.save(
                    current.copy(
                        accessToken = access,
                        refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                        expirationTime = parsed.expirationTime ?: current.expirationTime
                    )
                )
                Log.i(TAG, "token refreshed")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "token refresh failed", e)
            false
        }
    }

    private data class RefreshTokenResponse(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("refreshToken") val refreshToken: String?,
        @SerializedName("expirationTime") val expirationTime: String?
    )

    companion object {
        private const val TAG = "ProtoLinkSync"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
