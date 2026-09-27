package ru.protolink.communicator.data

import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalRService @Inject constructor(
    private val settingsStore: SettingsStore,
    private val tokenStore: TokenStore
) {
    private var hub: HubConnection? = null
    /** Invoked on main thread with commandType and optional parameters map. */
    var onCommand: ((String?, Map<String, Any?>?) -> Unit)? = null

    fun start() {
        val token = tokenStore.load()?.accessToken ?: return
        val base = settingsStore.load().apiBaseAddress.trimEnd('/')
        stop()
        hub = HubConnectionBuilder.create("$base/hubs/commands")
            .withAccessTokenProvider(io.reactivex.rxjava3.core.Single.defer {
                io.reactivex.rxjava3.core.Single.just(tokenStore.load()?.accessToken ?: token)
            })
            .build()

        hub?.on("ReceiveCommand", { payload: Any? ->
            val type = extractCommandType(payload)
            val params = extractParameters(payload)
            CoroutineScope(Dispatchers.Main).launch { onCommand?.invoke(type, params) }
        }, Any::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                hub?.start()?.blockingAwait()
                try {
                    hub?.invoke("SubscribeToCommands")?.blockingAwait()
                } catch (_: Exception) { /* optional on older servers */ }
            } catch (e: Exception) {
                Log.w("SignalR", "start failed", e)
            }
        }
    }

    private fun extractCommandType(payload: Any?): String? = when (payload) {
        is String -> payload
        is Map<*, *> -> (payload["commandType"] ?: payload["CommandType"])?.toString()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractParameters(payload: Any?): Map<String, Any?>? {
        if (payload !is Map<*, *>) return null
        val raw = payload["parameters"] ?: payload["Parameters"] ?: return null
        return when (raw) {
            is Map<*, *> -> raw.entries.associate { (k, v) -> k.toString() to v }
            else -> null
        }
    }

    fun stop() {
        try {
            if (hub?.connectionState == HubConnectionState.CONNECTED) hub?.stop()
        } catch (_: Exception) { }
        hub = null
    }
}
