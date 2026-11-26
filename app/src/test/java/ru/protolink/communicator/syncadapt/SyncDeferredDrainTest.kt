package ru.protolink.communicator.syncadapt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tip 1.0.121+: AutoPush must re-drain / refuse idle chrome when Sync claims deferredFull
 * after the first Exchange drain (1.0.120 DOWNLOAD_API_ONLY_NOT_ON_SAF chrome No local changes).
 * Tip 1.0.122 keeps these helpers; Sync stays Win coalesce-only (no waitForLane).
 */
class SyncDeferredDrainTest {
    @Test
    fun shouldDrainAgain_whenDeferredClaimedAfterExchange() {
        assertThat(
            SyncDeferredDrain.shouldDrainAgain(
                deferredFull = true,
                forcePreferred = false,
                extraPassesUsed = 0,
                maxExtraPasses = 2
            )
        ).isTrue()
    }

    @Test
    fun shouldDrainAgain_falseWhenForcePreferredOrBudgetExhausted() {
        assertThat(
            SyncDeferredDrain.shouldDrainAgain(
                deferredFull = true,
                forcePreferred = true,
                extraPassesUsed = 0,
                maxExtraPasses = 2
            )
        ).isFalse()
        assertThat(
            SyncDeferredDrain.shouldDrainAgain(
                deferredFull = true,
                forcePreferred = false,
                extraPassesUsed = 2,
                maxExtraPasses = 2
            )
        ).isFalse()
        assertThat(
            SyncDeferredDrain.shouldDrainAgain(
                deferredFull = false,
                forcePreferred = false,
                extraPassesUsed = 0,
                maxExtraPasses = 2
            )
        ).isFalse()
    }

    @Test
    fun shouldRefuseIdlePaint_whenDeferredPending() {
        assertThat(
            SyncDeferredDrain.shouldRefuseIdlePaint(
                deferredFull = true,
                forcePreferred = false
            )
        ).isTrue()
        assertThat(
            SyncDeferredDrain.shouldRefuseIdlePaint(
                deferredFull = false,
                forcePreferred = false
            )
        ).isFalse()
    }
}
