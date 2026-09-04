package ru.protolink.communicator.sync.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class SyncDirectionDecideTest {
    @Test
    fun missingLocal_reads() {
        assertThat(
            SyncDirectionDecide.decide(null, 10L, 0L, Instant.now(), null)
        ).isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun emptyLocal_unknownRemoteSize_reads() {
        assertThat(
            SyncDirectionDecide.decide(0L, null, 0L, Instant.now(), Instant.EPOCH)
        ).isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun emptyLocal_remoteHasBytes_reads() {
        assertThat(
            SyncDirectionDecide.decide(0L, 42L, 0L, Instant.now(), Instant.EPOCH)
        ).isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun localGrew_writes() {
        assertThat(
            SyncDirectionDecide.decide(10L, 5L, 5L, Instant.EPOCH, Instant.EPOCH)
        ).isEqualTo(SyncDirectionDecide.Action.Write)
    }

    @Test
    fun remoteNewerTime_reads() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")
        assertThat(
            SyncDirectionDecide.decide(5L, 5L, 5L, newer, older)
        ).isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun bothChanged_localWinsUnlessEmpty() {
        val older = Instant.parse("2020-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-01T00:00:00Z")
        assertThat(
            SyncDirectionDecide.decide(9L, 20L, 5L, newer, older)
        ).isEqualTo(SyncDirectionDecide.Action.Write)
        assertThat(
            SyncDirectionDecide.decide(0L, 20L, 5L, newer, older)
        ).isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun unchanged_skips() {
        assertThat(
            SyncDirectionDecide.decide(5L, 5L, 5L, Instant.EPOCH, Instant.EPOCH)
        ).isEqualTo(SyncDirectionDecide.Action.Skip)
    }
}
