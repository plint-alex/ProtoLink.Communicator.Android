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
    var onMessage: (() -> Unit)? = null

    fun start() {
        val token = tokenStore.load()?.accessToken ?: return
        val base = settingsStore.load().apiBaseAddress.trimEnd('/')
        stop()
        hub = HubConnectionBuilder.create("$base/hubs/commands")
            .withAccessTokenProvider(io.reactivex.rxjava3.core.Single.defer {
                io.reactivex.rxjava3.core.Single.just(token)
            })
            .build()
        hub?.on("ReceiveCommand", {
            CoroutineScope(Dispatchers.Main).launch { onMessage?.invoke() }
        }, String::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                hub?.start()?.blockingAwait()
            } catch (e: Exception) {
                Log.w("SignalR", "start failed", e)
            }
        }
    }

    fun stop() {
        try {
            if (hub?.connectionState == HubConnectionState.CONNECTED) hub?.stop()
        } catch (_: Exception) { }
        hub = null
    }
}
