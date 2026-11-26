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
 *
 * Tip 1.0.124: soft-cap handoff alone left DOWNLOAD idle on 1.0.123 — Sync coalesce can set
 * deferredFull after the holder already unlocked (or CancelBudgeted reclaim misses), then
 * interval AutoPush skips forever while chrome stays "No local changes". Win never parks
 * `_deferredFullSync` without a holder. [shouldReclaimOrphanedDeferred] re-enters full Sync
 * when the claim is pending and the lane is free (no waitForLane spin — keep upload PASS).
 *
 * Tip 1.0.126: paint "Full sync queued…" before heal on Sync tap + staggered orphan reclaim
 * deadlines (250ms/1s/3s) so WaitBusyClear does not treat heal latency as idle; map-root
 * re-list lag stays in SyncEngine (Win RequestFullSync scheduling class).
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

    /**
     * Coalesce claimed deferredFull but no holder owns the lane — reclaim like a fresh Win
     * [RequestFullSyncCoreAsync] when `_syncActive==0` (WaitAsync/run). Do not spin-wait a
     * busy lane (1.0.121 waitForLane → UPLOAD/Force race).
     */
    fun shouldReclaimOrphanedDeferred(
        deferredFull: Boolean,
        forcePreferred: Boolean,
        laneHeld: Boolean
    ): Boolean =
        deferredFull && !forcePreferred && !laneHeld
}
