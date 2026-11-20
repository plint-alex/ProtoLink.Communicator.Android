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

    /** True when every online id already appears in painted (Tina thin-bag ⊆ rich paint). */
    fun isOnlineSubsetOfPainted(
        onlineIds: Collection<String>,
        paintedIds: Collection<String>
    ): Boolean {
        if (onlineIds.isEmpty()) return true
        val painted = paintedIds.map { it.lowercase() }.toHashSet()
        return onlineIds.all { painted.contains(it.lowercase()) }
    }

    /**
     * Replace painted Messenger contacts when online is at least as complete, or when online
     * is not a subset of painted (planted/foreign ids — prefer live even if count shrinks).
     * Blocks thin StartupFast / self-only bags from wiping a richer disk paint (Tina) when
     * online ⊆ painted.
     */
    fun shouldReplaceContactsList(
        onlineContactCount: Int,
        paintedContactCount: Int,
        onlineIsSubsetOfPainted: Boolean = true
    ): Boolean {
        if (onlineContactCount <= 0) return false
        if (onlineContactCount >= paintedContactCount) return true
        return !onlineIsSubsetOfPainted
    }

    /**
     * Persist contacts cache when incoming is not a shrink, or when incoming is not a subset
     * of existing disk (overwrite planted/foreign stale rows).
     */
    fun shouldReplaceContactsCache(
        incomingCount: Int,
        existingCachedCount: Int,
        incomingIsSubsetOfExisting: Boolean = true
    ): Boolean {
        if (incomingCount <= 0) return false
        if (incomingCount >= existingCachedCount) return true
        return !incomingIsSubsetOfExisting
    }

    /**
     * Prefer-online reconcile after a completed bag fetch (not interactive timeout).
     * Empty online keeps paint (Tina / last-good); non-empty uses subset-aware replace;
     * online names win on id match. Callers must not pass timeout-as-empty here.
     */
    fun <T> reconcileContactsAfterOnline(
        painted: List<T>,
        online: List<T>,
        idOf: (T) -> String
    ): List<T> {
        if (online.isEmpty()) return painted
        val onlineIds = online.map(idOf)
        val paintedIds = painted.map(idOf)
        val subset = isOnlineSubsetOfPainted(onlineIds, paintedIds)
        if (shouldReplaceContactsList(online.size, painted.size, subset)) {
            return distinctById(online, idOf)
        }
        // Keep painted partners; upsert online ids/names (prefer-online name win).
        val merged = LinkedHashMap<String, T>()
        for (p in painted) merged[idOf(p).lowercase()] = p
        for (o in online) merged[idOf(o).lowercase()] = o
        return merged.values.toList()
    }

    private fun <T> distinctById(items: List<T>, idOf: (T) -> String): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (item in items) {
            val key = idOf(item).lowercase()
            if (seen.add(key)) out.add(item)
        }
        return out
    }

    /**
     * After force login refresh, drop painted contacts Api does not recognize (planted fakes).
     * Keep on expected connectivity failure so sick paint is not wiped mid-enrich.
     */
    fun shouldDropUnknownContactAfterLoginRefresh(
        forceLoginRefresh: Boolean,
        userLookupSucceeded: Boolean,
        isExpectedConnectivityFailure: Boolean
    ): Boolean =
        forceLoginRefresh && !userLookupSucceeded && !isExpectedConnectivityFailure

    /**
     * Hard Sync error OK modal only for unexpected failures — expected connectivity stays soft banner.
     */
    fun shouldShowHardSyncErrorModal(isExpectedConnectivityFailure: Boolean): Boolean =
        !isExpectedConnectivityFailure
}
