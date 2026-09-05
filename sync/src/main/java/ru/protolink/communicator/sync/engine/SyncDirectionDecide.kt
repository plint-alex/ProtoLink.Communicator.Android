package ru.protolink.communicator.sync.engine

import java.time.Instant

/**
 * At sync start, compare local size + remote size/time against last-sync meta
 * and decide whether to upload (Write) or download (Read).
 */
internal object SyncDirectionDecide {
    enum class Action { Write, Read, Skip, Conflict }

    fun decide(
        localSize: Long?,
        remoteSize: Long?,
        metaSize: Long,
        remoteTime: Instant?,
        metaTime: Instant?,
        localContentChanged: Boolean = false
    ): Action {
        // Missing local → pull from cloud
        if (localSize == null) return Action.Read

        // Empty local stub: always try download. Do not trust remoteSize==0 from a bad
        // Content-Length probe — that used to Skip and leave Films empty forever.
        if (localSize == 0L) return Action.Read

        // Tiny editor stubs (e.g. "<p><br></p>" ≈ 11 bytes) stuck in meta while cloud is larger.
        // Only when local did not grow past meta (not a real small edit winning a conflict).
        if (remoteSize != null && remoteSize > localSize && localSize < 64L &&
            (localSize == metaSize || metaSize > localSize)
        ) {
            return Action.Read
        }

        val localChanged = localSize != metaSize || localContentChanged
        // Size vs last-sync meta. Also remote≠local while local still matches meta
        // (blob replaced on server without a reliable UpdateTime bump).
        val remoteSizeChanged = remoteSize != null &&
            (remoteSize != metaSize || (!localChanged && remoteSize != localSize))
        val remoteTimeNewer = remoteTime != null && metaTime != null && remoteTime!! > metaTime
        val remoteChanged = remoteSizeChanged || remoteTimeNewer

        return when {
            localChanged && remoteChanged -> Action.Write // local wins when both changed
            localChanged -> Action.Write
            remoteChanged -> Action.Read
            else -> Action.Skip
        }
    }
}
