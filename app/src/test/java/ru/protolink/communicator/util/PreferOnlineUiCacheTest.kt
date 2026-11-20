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
    fun replaceContactsCache_blocksShrink() {
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(2, 2)).isTrue()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(1, 2)).isFalse()
        assertThat(PreferOnlineUiCache.shouldReplaceContactsCache(3, 1)).isTrue()
    }
}
