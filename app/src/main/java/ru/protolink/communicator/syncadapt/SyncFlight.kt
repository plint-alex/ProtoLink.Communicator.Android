package ru.protolink.communicator.syncadapt

import kotlinx.coroutines.sync.Mutex

/** Single-flight lock shared by UI sync and [SyncWorker]. */
object SyncFlight {
    val mutex = Mutex()
}
