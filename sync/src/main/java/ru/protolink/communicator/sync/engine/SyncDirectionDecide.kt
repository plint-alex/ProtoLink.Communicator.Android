package ru.protolink.communicator.sync.engine

import java.time.Instant

/**
 * At sync start, compare local size + remote size/time against last-sync meta
 * and decide whether to upload (Write) or download (Read).
 */
internal object SyncDirectionDecide {
    enum class Action { Write, Read, Skip }

    fun decide(
        localSize: Long?,
        remoteSize: Long?,
        metaSize: Long,
        remoteTime: Instant?,
        metaTime: Instant?
    ): Action {
        // Missing local → pull from cloud
        if (localSize == null) return Action.Read

        // Empty local stub: prefer reading remote (unknown size still means try download)
        if (localSize == 0L && (remoteSize == null || remoteSize > 0L)) {
            return Action.Read
        }

        val localChanged = localSize != metaSize
        val remoteSizeChanged = remoteSize != null && remoteSize != metaSize
        val remoteTimeNewer = remoteTime != null && metaTime != null && remoteTime > metaTime
        val remoteChanged = remoteSizeChanged || remoteTimeNewer

        return when {
            localChanged && remoteChanged ->
                if (localSize == 0L) Action.Read else Action.Write // local wins, except empty stubs
            localChanged -> Action.Write
            remoteChanged -> Action.Read
            else -> Action.Skip
        }
    }
}
