package ru.protolink.communicator.data

import android.util.Log
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On HTTP 401, refresh the session once and retry (parity with Windows AuthHandler).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val path = response.request.url.encodedPath
        if (path.contains("/api/Authentication/", ignoreCase = true)) return null
        if (response.request.header("Authorization").isNullOrBlank()) return null

        synchronized(refresher) {
            val latest = tokenStore.load() ?: return null
            val failedBearer = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
            // Another caller already refreshed while we waited.
            if (!failedBearer.isNullOrBlank() && latest.accessToken != failedBearer) {
                Log.i(TAG, "401: using token refreshed by peer request")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${latest.accessToken}")
                    .build()
            }
            if (!refresher.refreshBlocking()) {
                Log.w(TAG, "401: refresh failed — re-login required")
                return null
            }
            val access = tokenStore.load()?.accessToken ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $access")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var n = 1
        var prior = response.priorResponse
        while (prior != null) {
            n++
            prior = prior.priorResponse
        }
        return n
    }

    companion object {
        private const val TAG = "ProtoLinkSync"
    }
}
