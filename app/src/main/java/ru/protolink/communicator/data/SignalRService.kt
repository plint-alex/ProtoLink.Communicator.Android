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
    /** Invoked on main thread with optional commandType (e.g. message_sent, data_changed). */
    var onCommand: ((String?) -> Unit)? = null

    fun start() {
        val token = tokenStore.load()?.accessToken ?: return
        val base = settingsStore.load().apiBaseAddress.trimEnd('/')
        stop()
        hub = HubConnectionBuilder.create("$base/hubs/commands")
            .withAccessTokenProvider(io.reactivex.rxjava3.core.Single.defer {
                io.reactivex.rxjava3.core.Single.just(tokenStore.load()?.accessToken ?: token)
            })
            .build()

        // Server sends a CommandMessage JSON object; Gson typically yields LinkedTreeMap / Map.
        hub?.on("ReceiveCommand", { payload: Any? ->
            val type = extractCommandType(payload)
            CoroutineScope(Dispatchers.Main).launch { onCommand?.invoke(type) }
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

    fun stop() {
        try {
            if (hub?.connectionState == HubConnectionState.CONNECTED) hub?.stop()
        } catch (_: Exception) { }
        hub = null
    }
}
