package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Offline coverage for #comm-win-sick-connection prefer-online policy (Win PreferOnlineUiCache parity). */
class PreferOnlineUiCacheTest {
    @Test
    fun preferOnline_alwaysRefreshesAfterCachePaint() {
        assertThat(PreferOnlineUiCache.ShouldRefreshOnlineAfterCachePaint).isTrue()
        assertThat(PreferOnlineUiCache.ShouldForceLoginRefreshAfterCachePaint).isTrue()
    }

    @Test
    fun preferOnline_budgetElapsed_doesNotStickyOfflineBanner() {
        assertThat(PreferOnlineUiCache.ShouldShowOfflineBannerOnInteractiveBudgetElapsed).isFalse()
    }

    @Test
    fun preferOnline_cachedUserContactsBag_onlyWhenPartnerBearing() {
        assertThat(PreferOnlineUiCache.ShouldPreferCachedUserContactsBag).isTrue()
        assertThat(PreferOnlineUiCache.shouldPersistUserContactsBagId(0)).isFalse()
        assertThat(PreferOnlineUiCache.shouldPersistUserContactsBagId(1)).isFalse()
        assertThat(PreferOnlineUiCache.shouldPersistUserContactsBagId(2)).isTrue()
        // Sticky thin priorBag must not skip StartupFast escalate.
        assertThat(PreferOnlineUiCache.shouldTrustCachedUserContactsBagId(0)).isFalse()
        assertThat(PreferOnlineUiCache.shouldTrustCachedUserContactsBagId(1)).isFalse()
        assertThat(PreferOnlineUiCache.shouldTrustCachedUserContactsBagId(2)).isTrue()
    }

    @Test
    fun paintCacheBeforeOnline_whenPayloadExists() {
        assertThat(PreferOnlineUiCache.shouldPaintCacheBeforeOnline(true)).isTrue()
        assertThat(PreferOnlineUiCache.shouldPaintCacheBeforeOnline(false)).isFalse()
    }

    @Test
    fun writeCacheAsap_onlyOnOnlineSuccess() {
        assertThat(PreferOnlineUiCache.shouldWriteCacheAsap(true)).isTrue()
        assertThat(PreferOnlineUiCache.shouldWriteCacheAsap(false)).isFalse()
    }

    @Test
    fun keepCacheOnSoftFail_requiresConnectivityAndPayload() {
        assertThat(PreferOnlineUiCache.shouldKeepCacheOnSoftFail(true, true)).isTrue()
        assertThat(PreferOnlineUiCache.shouldKeepCacheOnSoftFail(true, false)).isFalse()
        assertThat(PreferOnlineUiCache.shouldKeepCacheOnSoftFail(false, true)).isFalse()
    }

    @Test
    fun clearChromeForLoad_onlyWhenNoCache() {
        assertThat(PreferOnlineUiCache.shouldClearChromeForLoad(true)).isFalse()
        assertThat(PreferOnlineUiCache.shouldClearChromeForLoad(false)).isTrue()
    }

    @Test
    fun openLastOpenFromListingDisk_whenPayloadExists() {
        assertThat(PreferOnlineUiCache.shouldOpenLastOpenFromListingDisk(true)).isTrue()
        assertThat(PreferOnlineUiCache.shouldOpenLastOpenFromListingDisk(false)).isFalse()
    }

    @Test
    fun fallbackToHubAfterListingSoftFail_skipsWhenPainted() {
        assertThat(PreferOnlineUiCache.shouldFallbackToHubAfterListingSoftFail(true, false)).isTrue()
        assertThat(PreferOnlineUiCache.shouldFallbackToHubAfterListingSoftFail(true, true)).isFalse()
        assertThat(PreferOnlineUiCache.shouldFallbackToHubAfterListingSoftFail(false, false)).isFalse()
        assertThat(PreferOnlineUiCache.shouldFallbackToHubAfterListingSoftFail(false, true)).isFalse()
    }

    @Test
    fun replaceContactsList_blocksThinOnlineOverRichPaint() {
        assertThat(PreferOnlineUiCache.shouldReplaceContactsList(2, 2)).isTrue()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsList(3, 2)).isTrue()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsList(1, 2)).isFalse()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsList(0, 1)).isFalse()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsList(1, 0)).isTrue()
    }

    @Test
    fun replaceContactsList_plantDisjoint_replacesEvenWhenOnlineSmaller() {
        // Planted fake ids (online ⊈ painted): prefer live self even if count shrinks.
        assertThat(
            PreferOnlineUiCache.shouldReplaceContactsList(
                onlineContactCount = 1,
                paintedContactCount = 2,
                onlineIsSubsetOfPainted = false
            )
        ).isTrue()
        // Tina thin bag (online ⊆ painted): keep rich paint.
        assertThat(
            PreferOnlineUiCache.shouldReplaceContactsList(
                onlineContactCount = 1,
                paintedContactCount = 2,
                onlineIsSubsetOfPainted = true
            )
        ).isFalse()
    }

    @Test
    fun onlineSubsetOfPainted_caseInsensitive() {
        assertThat(
            PreferOnlineUiCache.isOnlineSubsetOfPainted(
                listOf("AAA"),
                listOf("aaa", "bbb")
            )
        ).isTrue()
        assertThat(
            PreferOnlineUiCache.isOnlineSubsetOfPainted(
                listOf("ccc"),
                listOf("aaa", "bbb")
            )
        ).isFalse()
    }

    @Test
    fun replaceContactsCache_blocksShrink() {
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(2, 2)).isTrue()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(1, 2)).isFalse()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(3, 1)).isTrue()
        assertThat(
            PreferOnlineUiCache.shouldReplaceContactsCache(
                incomingCount = 1,
                existingCachedCount = 2,
                incomingIsSubsetOfExisting = false
            )
        ).isTrue()
    }

    @Test
    fun reconcile_replaceAfterStalePlant_disjointIds() {
        data class Row(val id: String, val name: String)
        val painted = listOf(Row("00000000-0000-0000-0000-000000000099", "STALE_CACHE_TEST_NAME@test.local"))
        val online = listOf(Row("a52b6095-7952-4535-90a1-ce8bdaf7f9c7", "alex.plint@gmail.com"))
        val out = PreferOnlineUiCache.reconcileContactsAfterOnline(painted, online) { it.id }
        assertThat(out).hasSize(1)
        assertThat(out[0].name).isEqualTo("alex.plint@gmail.com")
        assertThat(out[0].id).doesNotContain("00000000")
    }

    @Test
    fun reconcile_tinaThinBag_keepsRichPaintPartners() {
        data class Row(val id: String, val name: String)
        val painted = listOf(
            Row("self", "alex.plint@gmail.com"),
            Row("tina", "tina@example.com")
        )
        val online = listOf(Row("self", "alex.plint@gmail.com"))
        val out = PreferOnlineUiCache.reconcileContactsAfterOnline(painted, online) { it.id }
        assertThat(out.map { it.id }).containsExactly("self", "tina").inOrder()
    }

    @Test
    fun reconcile_emptyOnline_stripsThinPlant_keepsRichTina() {
        data class Row(val id: String, val name: String)
        val plant = listOf(Row("stale", "STALE_CACHE_TEST_NAME@test.local"))
        assertThat(
            PreferOnlineUiCache.reconcileContactsAfterOnline(plant, emptyList()) { it.id }
        ).isEmpty()
        assertThat(PreferOnlineUiCache.shouldKeepPaintedOnAuthoritativeEmptyOnline(1)).isFalse()
        assertThat(PreferOnlineUiCache.shouldKeepPaintedOnAuthoritativeEmptyOnline(2)).isTrue()
        val rich = listOf(
            Row("self", "alex.plint@gmail.com"),
            Row("tina", "tina@example.com")
        )
        assertThat(
            PreferOnlineUiCache.reconcileContactsAfterOnline(rich, emptyList()) { it.id }
        ).isEqualTo(rich)
    }

    @Test
    fun stripThinPaint_onInteractiveAbandon_keepsTina() {
        assertThat(PreferOnlineUiCache.shouldStripThinPaintOnInteractiveAbandon(0)).isFalse()
        assertThat(PreferOnlineUiCache.shouldStripThinPaintOnInteractiveAbandon(1)).isTrue()
        assertThat(PreferOnlineUiCache.shouldStripThinPaintOnInteractiveAbandon(2)).isFalse()
    }

    @Test
    fun clearContactsCache_whenIncomingEmpty_onlyThinDisk() {
        assertThat(PreferOnlineUiCache.shouldClearContactsCacheWhenIncomingEmpty(0)).isFalse()
        assertThat(PreferOnlineUiCache.shouldClearContactsCacheWhenIncomingEmpty(1)).isTrue()
        assertThat(PreferOnlineUiCache.shouldClearContactsCacheWhenIncomingEmpty(2)).isFalse()
    }

    @Test
    fun replaceAfterStalePlant_timeoutAbandon_thenOnlineSelf() {
        // Plant path: Interactive timeout strips thin → self login; later online reconciles.
        data class Row(val id: String, val name: String)
        val self = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val plant = listOf(Row("00000000-0000-0000-0000-000000000099", "STALE_CACHE_TEST_NAME@test.local"))
        assertThat(PreferOnlineUiCache.shouldStripThinPaintOnInteractiveAbandon(plant.size)).isTrue()
        val afterAbandon = listOf(Row(self, "alex.plint@gmail.com"))
        val online = listOf(Row(self, "alex.plint@gmail.com"))
        val out = PreferOnlineUiCache.reconcileContactsAfterOnline(afterAbandon, online) { it.id }
        assertThat(out).hasSize(1)
        assertThat(out[0].name).isEqualTo("alex.plint@gmail.com")
        assertThat(
            PreferOnlineUiCache.shouldReplaceContactsCache(
                incomingCount = 1,
                existingCachedCount = 1,
                incomingIsSubsetOfExisting = false
            )
        ).isTrue()
        assertThat(PreferOnlineUiCache.shouldClearContactsCacheWhenIncomingEmpty(1)).isTrue()
    }

    @Test
    fun retainTrusted_afterEnsureSelf_stripsPlantedStale() {
        data class Row(val id: String, val name: String)
        val self = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val mixed = listOf(
            Row(self, "alex.plint@gmail.com"),
            Row("00000000-0000-0000-0000-000000000099", "STALE_CACHE_TEST_NAME@test.local")
        )
        // Timeout + thin pre-ensureSelf paint (plant): self only even though list size is 2.
        val afterTimeout = PreferOnlineUiCache.retainTrustedContactsAfterEnsureSelf(
            contacts = mixed,
            selfId = self,
            authoritativeOnlineIds = null,
            partnerBearingPaintBeforeEnsureSelf = false,
            idOf = { it.id }
        )
        assertThat(afterTimeout).hasSize(1)
        assertThat(afterTimeout[0].id).isEqualTo(self)
        // Authoritative online self: drop foreign planted id.
        val afterOnline = PreferOnlineUiCache.retainTrustedContactsAfterEnsureSelf(
            contacts = mixed,
            selfId = self,
            authoritativeOnlineIds = listOf(self),
            partnerBearingPaintBeforeEnsureSelf = false,
            idOf = { it.id }
        )
        assertThat(afterOnline.map { it.id }).containsExactly(self)
        // Rich Tina without online yet: keep partners.
        val rich = listOf(
            Row(self, "alex.plint@gmail.com"),
            Row("tina", "tina@example.com")
        )
        assertThat(
            PreferOnlineUiCache.retainTrustedContactsAfterEnsureSelf(
                contacts = rich,
                selfId = self,
                authoritativeOnlineIds = null,
                partnerBearingPaintBeforeEnsureSelf = true,
                idOf = { it.id }
            )
        ).isEqualTo(rich)
    }

    @Test
    fun dropUnknownContact_afterLoginRefresh_notOnConnectivity() {
        assertThat(
            PreferOnlineUiCache.shouldDropUnknownContactAfterLoginRefresh(
                forceLoginRefresh = true,
                userLookupSucceeded = false,
                isExpectedConnectivityFailure = false
            )
        ).isTrue()
        assertThat(
            PreferOnlineUiCache.shouldDropUnknownContactAfterLoginRefresh(
                forceLoginRefresh = true,
                userLookupSucceeded = false,
                isExpectedConnectivityFailure = true
            )
        ).isFalse()
        assertThat(
            PreferOnlineUiCache.shouldDropUnknownContactAfterLoginRefresh(
                forceLoginRefresh = true,
                userLookupSucceeded = true,
                isExpectedConnectivityFailure = false
            )
        ).isFalse()
    }

    @Test
    fun hardSyncErrorModal_onlyWhenNotExpectedConnectivity() {
        assertThat(PreferOnlineUiCache.shouldShowHardSyncErrorModal(true)).isFalse()
        assertThat(PreferOnlineUiCache.shouldShowHardSyncErrorModal(false)).isTrue()
    }
}
