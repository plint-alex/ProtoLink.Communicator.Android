package ru.protolink.communicator.sync.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class ContentHashUtilSkipTest {

    private val t12 = Instant.parse("2026-11-16T12:00:00Z")
    private val t11 = Instant.parse("2026-11-16T11:00:00Z")
    private val t13 = Instant.parse("2026-11-16T13:00:00Z")

    @Test
    fun canSkipRemoteContentDownload_matchesWinContract() {
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "abc", null, null)).isTrue()
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "abc", t12, t12)).isTrue()
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "abc", t11, t12)).isTrue()
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "abc", t13, t12)).isFalse()
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "xyz", t12, t12)).isFalse()
        assertThat(ContentHashUtil.canSkipRemoteContentDownload("abc", "", t12, t12)).isFalse()
    }

    @Test
    fun canSkipCompareWithoutHash_matchesWinContract() {
        assertThat(ContentHashUtil.canSkipCompareWithoutHash(100, 100, "abc", null, null)).isTrue()
        assertThat(ContentHashUtil.canSkipCompareWithoutHash(100, 100, "abc", t12, t12)).isTrue()
        assertThat(ContentHashUtil.canSkipCompareWithoutHash(100, 100, "abc", t13, t12)).isFalse()
        assertThat(ContentHashUtil.canSkipCompareWithoutHash(100, 99, "abc", t12, t12)).isFalse()
        assertThat(ContentHashUtil.canSkipCompareWithoutHash(100, 100, "", t12, t12)).isFalse()
    }

    @Test
    fun trySha256Hex_missingPath_returnsNull() {
        assertThat(
            ContentHashUtil.trySha256Hex { error("Missing note.html") }
        ).isNull()
        assertThat(
            ContentHashUtil.trySha256Hex { throw java.io.FileNotFoundException("gone") }
        ).isNull()
    }

    @Test
    fun trySha256Hex_existingBytes_returnsHex() {
        val hex = ContentHashUtil.trySha256Hex { "hello".toByteArray() }
        assertThat(hex).isEqualTo(ContentHashUtil.sha256Hex("hello".toByteArray()))
    }

    @Test
    fun syncMissingLocalPath_detectsSafMissing() {
        assertThat(SyncMissingLocalPath.isMissing(IllegalStateException("Missing a/b.txt"))).isTrue()
        assertThat(SyncMissingLocalPath.isMissing(IllegalStateException("Missing file a/b.txt"))).isTrue()
        assertThat(SyncMissingLocalPath.isMissing(IllegalStateException("other"))).isFalse()
        assertThat(SyncMissingLocalPath.isMissing(RuntimeException("boom"))).isFalse()
    }
}
