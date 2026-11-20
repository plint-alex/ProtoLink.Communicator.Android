package ru.protolink.communicator.util

/**
 * Sick-connection UI policy (#comm-win-sick-connection): prefer online when reachable,
 * paint last-good local cache when not, write cache ASAP on success.
 * Parity with Windows PreferOnlineUiCache.
 */
object PreferOnlineUiCache {
    /** Always attempt Api refresh after a cache paint (cache is never a stale-only mode). */
    const val ShouldRefreshOnlineAfterCachePaint: Boolean = true

    /**
     * After disk/memory paint, contact display names must be re-fetched from Api —
     * do not treat painted login strings as authoritative memory-cache hits.
     */
    val ShouldForceLoginRefreshAfterCachePaint: Boolean
        get() = ShouldRefreshOnlineAfterCachePaint

    /**
     * Interactive budget abandon is not a connectivity failure. Sticky OfflineBanner would
     * leave healthy Api sessions stuck on Waiting for network after a slow StartupFast.
     */
    const val ShouldShowOfflineBannerOnInteractiveBudgetElapsed: Boolean = false

    /**
     * Cold start may open last-good UserContacts bag id from disk before page-all escalate —
     * avoids self-only while ~100+ thin bags are listed.
     */
    const val ShouldPreferCachedUserContactsBag: Boolean = true

    /**
     * Persist bag id only when the list is partner-bearing — never stamp a self-only thin bag
     * as the cold-start authority.
     */
    fun shouldPersistUserContactsBagId(contactCount: Int): Boolean =
        contactCount >= UserContactsBagHelpers.PartnerRowThreshold

    /** Paint disk/memory cache before waiting on network when a prior payload exists. */
    fun shouldPaintCacheBeforeOnline(hasCachedPayload: Boolean): Boolean = hasCachedPayload

    /**
     * Cold Cloud LastOpen: when Api resolve fails/sick and a listing disk payload exists for
     * LastOpen, open that folder offline so TryPaintListingFromDisk can run (not blank Loading).
     */
    fun shouldOpenLastOpenFromListingDisk(hasListingDiskPayload: Boolean): Boolean =
        shouldPaintCacheBeforeOnline(hasListingDiskPayload)

    /**
     * After listing soft-fail, do not EnsureRoot/hub-fallback when last-good rows are painted —
     * hub navigate would wipe Cloud sick paint (CACHE_PAINT_CLOUD).
     */
    fun shouldFallbackToHubAfterListingSoftFail(
        isKnownCloudRoot: Boolean,
        hasPaintedListingRows: Boolean
    ): Boolean = isKnownCloudRoot && !hasPaintedListingRows

    /** Write/refresh local cache immediately when Api returns success (not deferred to idle/shutdown). */
    fun shouldWriteCacheAsap(onlineFetchSucceeded: Boolean): Boolean = onlineFetchSucceeded

    /**
     * Soft-fail expected connectivity: keep painted cache / do not blank chrome.
     * Unexpected failures may still clear or show error paths at the call site.
     */
    fun shouldKeepCacheOnSoftFail(
        isExpectedConnectivityFailure: Boolean,
        hasCachedPayload: Boolean
    ): Boolean = isExpectedConnectivityFailure && hasCachedPayload

    /**
     * Navigate/list start: prefer painting cache over clearing to empty when cache exists.
     */
    fun shouldClearChromeForLoad(hasCachedPayloadForTarget: Boolean): Boolean =
        !hasCachedPayloadForTarget

    /**
     * Replace painted Messenger contacts only when online is at least as complete.
     * Blocks thin StartupFast / self-only bags from wiping a richer disk paint.
     */
    fun shouldReplaceContactsList(onlineContactCount: Int, paintedContactCount: Int): Boolean =
        onlineContactCount >= paintedContactCount

    /**
     * Persist contacts cache only when the new list is not a shrink vs last-good disk.
     */
    fun shouldReplaceContactsCache(incomingCount: Int, existingCachedCount: Int): Boolean =
        incomingCount >= existingCachedCount

    /**
     * Hard Sync error OK modal only for unexpected failures — expected connectivity stays soft banner.
     */
    fun shouldShowHardSyncErrorModal(isExpectedConnectivityFailure: Boolean): Boolean =
        !isExpectedConnectivityFailure
}
