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

    /**
     * Trust disk bag id on cold start only when last-good contacts are partner-bearing.
     * Sticky thin priorBag (self-only) must not skip StartupFast escalate (Tina off newest page).
     */
    fun shouldTrustCachedUserContactsBagId(cachedContactCount: Int): Boolean =
        shouldPersistUserContactsBagId(cachedContactCount)

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
     * Authoritative empty online: keep partner-bearing paint (Tina); strip thin planted junk
     * (PREFER_ONLINE_STALE_PLANT). Non-empty uses subset-aware replace; online names win on
     * id match. Callers must not pass timeout-as-empty here.
     */
    fun <T> reconcileContactsAfterOnline(
        painted: List<T>,
        online: List<T>,
        idOf: (T) -> String
    ): List<T> {
        if (online.isEmpty()) {
            return if (shouldKeepPaintedOnAuthoritativeEmptyOnline(painted.size)) painted
            else emptyList()
        }
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

    /**
     * Authoritative empty bag (completed fetch, not timeout): rich partner-bearing paint may
     * outrank a transient empty StartupFast page (Tina). Thin paint (planted stale / self-only
     * junk) must not stick — clear so ensureSelf can paint live login.
     */
    fun shouldKeepPaintedOnAuthoritativeEmptyOnline(paintedContactCount: Int): Boolean =
        paintedContactCount >= UserContactsBagHelpers.PartnerRowThreshold

    /**
     * Interactive budget / GetEntities timeout: thin planted junk must not stay sticky while
     * background continue awaits EnsureBagId under Sync/Notes storm (PREFER_ONLINE_STALE_PLANT).
     * Partner-bearing Tina paint is kept.
     */
    fun shouldStripThinPaintOnInteractiveAbandon(paintedContactCount: Int): Boolean =
        paintedContactCount > 0 && !shouldKeepPaintedOnAuthoritativeEmptyOnline(paintedContactCount)

    /**
     * After authoritative empty / enrich-drop cleared UI: wipe thin sticky disk rows.
     * Do not wipe partner-bearing Tina disk on empty incoming.
     */
    fun shouldClearContactsCacheWhenIncomingEmpty(existingCachedCount: Int): Boolean =
        existingCachedCount > 0 && !shouldPersistUserContactsBagId(existingCachedCount)

    /**
     * After ensureSelf on prefer-online continue: keep self + authoritative online ids.
     * When online was never authoritative (timeout) and pre-ensureSelf paint was not
     * partner-bearing, keep only self — strips planted [STALE] that ensureSelf prepends onto
     * (size becomes 2 but the extra row is junk). Rich Tina paint without online yet is kept.
     *
     * @param authoritativeOnlineIds null = no successful bag fetch yet (timeout / abandon);
     *        non-null (including empty) = completed GetEntities for the bag.
     * @param partnerBearingPaintBeforeEnsureSelf whether contacts were partner-bearing before
     *        ensureSelf ran (not after — ensureSelf+[STALE] must not look like Tina).
     */
    fun <T> retainTrustedContactsAfterEnsureSelf(
        contacts: List<T>,
        selfId: String,
        authoritativeOnlineIds: Collection<String>?,
        partnerBearingPaintBeforeEnsureSelf: Boolean,
        idOf: (T) -> String
    ): List<T> {
        val selfKey = selfId.lowercase()
        if (authoritativeOnlineIds != null) {
            val allowed = HashSet<String>(authoritativeOnlineIds.size + 1)
            for (id in authoritativeOnlineIds) allowed.add(id.lowercase())
            allowed.add(selfKey)
            return distinctById(contacts.filter { allowed.contains(idOf(it).lowercase()) }, idOf)
        }
        if (!partnerBearingPaintBeforeEnsureSelf) {
            return distinctById(contacts.filter { idOf(it).equals(selfId, ignoreCase = true) }, idOf)
        }
        return distinctById(contacts, idOf)
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
