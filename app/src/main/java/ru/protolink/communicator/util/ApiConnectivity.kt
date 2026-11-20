package ru.protolink.communicator.util

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import retrofit2.HttpException

/**
 * Transient network / unreachable API failures that Messenger/Cloud loads should soft-fail
 * (keep cache UI; soft Waiting for network). Parity with Win ApiExceptionHelper.IsExpectedConnectivityFailure.
 */
object ApiConnectivity {
    fun isExpectedConnectivityFailure(ex: Throwable?): Boolean {
        if (ex == null) return false
        var cur: Throwable? = ex
        while (cur != null) {
            when (cur) {
                is TimeoutCancellationException -> return true
                is SocketTimeoutException -> return true
                is UnknownHostException -> return true
                is ConnectException -> return true
                is SocketException -> return true
                is java.io.InterruptedIOException -> return true
                is FileNotFoundException -> { /* path-gone is NFR-SYNC-1, not connectivity */ }
                is IOException -> {
                    // Generic transport IO — treat as connectivity unless path-not-found.
                    val msg = cur.message.orEmpty()
                    if (!msg.contains("No such file", ignoreCase = true) &&
                        !msg.contains("ENOENT", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                is HttpException -> {
                    // 408 / 502 / 503 / 504 are transient; 5xx gateway often means Api unreachable.
                    val code = cur.code()
                    if (code == 408 || code == 502 || code == 503 || code == 504) return true
                }
                is CancellationException -> {
                    // Cooperative cancel is not connectivity; TimeoutCancellationException handled above.
                }
            }
            cur = cur.cause
        }
        return false
    }
}
