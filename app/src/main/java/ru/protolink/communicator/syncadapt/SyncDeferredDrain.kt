package ru.protolink.communicator.syncadapt

/**
 * Win [CloudMappedSyncCoordinator.DrainDeferredWhileHoldingExclusiveAsync] settle helpers.
 *
 * Live 1.0.120 DOWNLOAD residual: AutoPush drained once, painted "No local changes", then
 * Sync's coalesce set [SyncFlight.deferredFull] while the mutex was still held — orphaned
 * claim (interval AutoPush skips when deferredFull) so Api-only kids never Walk/ApplyRemote.
 *
 * Tip 1.0.122: keep holder re-drain / refuse-idle (DOWNLOAD PASS on 1.0.121). Do not add a
 * Sync WaitAsync second waiter — that raced upload idle + Force sticky on c01a45a2.
 */
object SyncDeferredDrain {
    /**
     * After a drain Exchange loop exits, Sync may still claim deferredFull before unlock.
     * @return true when the holder must drain again (and paint Syncing… / Sync complete).
     */
    fun shouldDrainAgain(
        deferredFull: Boolean,
        forcePreferred: Boolean,
        extraPassesUsed: Int,
        maxExtraPasses: Int
    ): Boolean =
        deferredFull && !forcePreferred && extraPassesUsed < maxExtraPasses

    /**
     * Refuse idle "No local changes" paint when a Sync coalesce claim is pending.
     */
    fun shouldRefuseIdlePaint(deferredFull: Boolean, forcePreferred: Boolean): Boolean =
        deferredFull && !forcePreferred
}
