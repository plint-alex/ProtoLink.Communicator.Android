package ru.protolink.communicator.syncadapt

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** Single-flight lock shared by UI sync and [SyncWorker]. */
object SyncFlight {
    val mutex = Mutex()
    /** SignalR/full sync requested while another sync held the lock — run one full pass after. */
    val deferredFull = AtomicBoolean(false)
}
