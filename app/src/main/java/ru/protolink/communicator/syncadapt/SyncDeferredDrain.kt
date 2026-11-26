package ru.protolink.communicator.syncadapt

/**
 * Win [CloudMappedSyncCoordinator.DrainDeferredWhileHoldingExclusiveAsync] settle helpers.
 *
 * Live 1.0.120 DOWNLOAD residual: AutoPush drained once, painted "No local changes", then
 * Sync's coalesce set [SyncFlight.deferredFull] while the mutex was still held — orphaned
 * claim (interval AutoPush skips when deferredFull) so Api-only kids never Walk/ApplyRemote.
 *
 * Tip 1.0.122: keep holder re-drain / refuse-idle; Sync stays Win coalesce-only (no waitForLane).
 *
 * Tip 1.0.123: after soft pass-cap, Win never unlocks with deferredFull still set. Android's
 * SAF/SignalR soft cap may exit Exchange early — [shouldHandoffDeferred] re-enters
 * requestFullSync after unlock so DOWNLOAD/delete-plant Sync taps are not orphaned into
 * idle "No local changes" (1.0.122 FAIL_CLASS DOWNLOAD_API_ONLY_NOT_ON_SAF on c01a45a2).
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

    /**
     * After exclusive release: if deferredFull remains (soft cap / TOCTOU), start a new
     * full Sync holder — Win keeps DrainDeferred on the same hold; Android soft-cap must
     * not orphan the claim (interval AutoPush skips while deferredFull stays true).
     */
    fun shouldHandoffDeferred(deferredFull: Boolean, forcePreferred: Boolean): Boolean =
        deferredFull && !forcePreferred
}
