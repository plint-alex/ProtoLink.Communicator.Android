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
    fun emptyLocal_remoteSizeZero_stillReads() {
        assertThat(
            SyncDirectionDecide.decide(0L, 0L, 0L, Instant.now(), Instant.EPOCH)
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
    fun tinyEditorStub_remoteLarger_reads() {
        assertThat(
            SyncDirectionDecide.decide(11L, 158L, 11L, Instant.EPOCH, Instant.EPOCH)
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
    fun sameSizeLocalContentChanged_writes() {
        assertThat(
            SyncDirectionDecide.decide(
                localSize = 4L,
                remoteSize = 4L,
                metaSize = 4L,
                remoteTime = Instant.EPOCH,
                metaTime = Instant.EPOCH,
                localContentChanged = true
            )
        ).isEqualTo(SyncDirectionDecide.Action.Write)
    }

    @Test
    fun decideByHash_equal_skips() {
        val h = ContentHashUtil.sha256Hex("same".toByteArray())
        assertThat(ContentHashUtil.decideByHash(h, h, h))
            .isEqualTo(SyncDirectionDecide.Action.Skip)
    }

    @Test
    fun decideByHash_localDiffers_remoteMatchesMeta_writes() {
        val local = ContentHashUtil.sha256Hex("bbbb".toByteArray())
        val remote = ContentHashUtil.sha256Hex("aaaa".toByteArray())
        assertThat(ContentHashUtil.decideByHash(local, remote, remote))
            .isEqualTo(SyncDirectionDecide.Action.Write)
    }

    @Test
    fun decideByHash_remoteDiffersLocalMatchesMeta_reads() {
        val local = ContentHashUtil.sha256Hex("aaaa".toByteArray())
        val remote = ContentHashUtil.sha256Hex("bbbb".toByteArray())
        assertThat(ContentHashUtil.decideByHash(local, remote, local))
            .isEqualTo(SyncDirectionDecide.Action.Read)
    }

    @Test
    fun decideByHash_emptyMeta_diverged_conflicts() {
        val local = ContentHashUtil.sha256Hex("Test6".toByteArray())
        val remote = ContentHashUtil.sha256Hex("Test5".toByteArray())
        assertThat(ContentHashUtil.decideByHash(local, remote, null))
            .isEqualTo(SyncDirectionDecide.Action.Conflict)
        assertThat(
            ContentHashUtil.decideByHash(
                local, remote, "",
                remoteUpdateTime = Instant.EPOCH,
                metaUpdateTime = Instant.EPOCH
            )
        ).isEqualTo(SyncDirectionDecide.Action.Conflict)
        assertThat(
            ContentHashUtil.decideByHash(
                local, remote, "",
                remoteUpdateTime = Instant.parse("2024-01-02T00:00:00Z"),
                metaUpdateTime = Instant.parse("2024-01-01T00:00:00Z")
            )
        ).isEqualTo(SyncDirectionDecide.Action.Conflict)
    }

    @Test
    fun decideByHash_bothDivergedFromMeta_conflicts() {
        val local = ContentHashUtil.sha256Hex("local".toByteArray())
        val remote = ContentHashUtil.sha256Hex("remote".toByteArray())
        val meta = ContentHashUtil.sha256Hex("baseline".toByteArray())
        assertThat(ContentHashUtil.decideByHash(local, remote, meta))
            .isEqualTo(SyncDirectionDecide.Action.Conflict)
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
